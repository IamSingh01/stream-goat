package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loopback HTTP proxy for the built-in player.
 *
 * libVLC's HTTP access and its HLS/DASH module only send a User-Agent and a Referer
 * (modules/access/http/access.c, modules/demux/adaptive/http/HTTPConnection.cpp), but
 * extensions often need Origin, cookies or token headers on every request. The player
 * opens `http://127.0.0.1:<port>/s/<id>` instead; the proxy adds the extension's headers,
 * passes Range through for seeking, and rewrites HLS playlists so every variant playlist,
 * segment and key also goes through the proxy with the same headers.
 */
object StreamProxy {
    private data class Target(val url: String, val headers: Map<String, String>, val expiredOk: Boolean = false, val inline: String? = null,
                              val key: Aes? = null)

    /** An AES-128 encrypted segment: the proxy decrypts it, so every player (the Stream Goat player can't) gets plain video. */
    internal class Aes(val keyUrl: String, val iv: ByteArray) {
        override fun toString() = keyUrl + "#" + iv.joinToString("") { "%02x".format(it) }
    }

    private val keys = ConcurrentHashMap<String, ByteArray>()

    private val targets = ConcurrentHashMap<String, Target>()
    @Volatile private var server: HttpServer? = null

    /** Hop-by-hop or recomputed headers that must not be copied between the two sides. */
    private val skipResponse = setOf("transfer-encoding", "connection", "content-encoding", "content-length", "keep-alive")

    @Synchronized
    private fun start(): HttpServer = server ?: HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64).also {
        it.createContext("/s/") { exchange -> handle(exchange) }
        it.createContext("/web/") { exchange ->
            exchange.use {
                val page = pages[exchange.requestURI.path.removePrefix("/web/").substringBefore('/')]
                if (page == null) { exchange.sendResponseHeaders(404, -1); return@use }
                val bytes = page.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.responseHeaders.add("Referrer-Policy", "strict-origin-when-cross-origin")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        // Players open several connections at once (playlist, segments, a seek).
        it.executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
        it.start()
        server = it
    }

    private val pages = ConcurrentHashMap<String, String>()

    /**
     * Serves a web page made by [make] (given the page's origin, http://localhost:<port>) and returns its address.
     * Live TV's YouTube/Twitch players: Twitch only plays in a page whose host is its `parent` (localhost is allowed).
     */
    fun page(make: (origin: String) -> String?): String? {
        val s = start()
        val origin = "http://localhost:${s.address.port}"
        val html = make(origin) ?: return null
        val id = MessageDigest.getInstance("SHA-256").digest(html.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        pages[id] = html
        return "$origin/web/$id"
    }

    /** Returns a loopback URL that fetches [url] with [headers]. */
    fun wrap(url: String, headers: Map<String, String>, expiredOk: Boolean = false): String = wrap(url, headers, expiredOk, null)

    private fun wrap(url: String, headers: Map<String, String>, expiredOk: Boolean, key: Aes?): String {
        if (key == null && url.startsWith("data:", true)) wrapData(url, headers)?.let { return it }
        val s = start()
        val id = id(url + (if (expiredOk) "#expired-ok" else "") + (key?.let { "#aes=$it" } ?: ""), headers)
        targets[id] = Target(url, headers, expiredOk, key = key)
        // Keep the original file name/extension: players use it to guess the format.
        val name = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
            .ifBlank { "stream" }.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "http://127.0.0.1:${s.address.port}/s/$id/${Disguise.segmentName(name)}"
    }

    /** Stable ids, so rewriting the same live playlist again doesn't grow the table. */
    private fun id(url: String, headers: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((url + "\n" + headers.toSortedMap()).toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val id = exchange.requestURI.path.removePrefix("/s/").substringBefore('/')
            val target = targets[id] ?: return exchange.sendResponseHeaders(404, -1)
            target.inline?.let { text ->
                val bytes = text.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes); return
            }
            val headers = target.headers.toMutableMap()
            if (target.key == null) exchange.requestHeaders.getFirst("Range")?.let { headers["Range"] = it }
            val request = Request.Builder().url(target.url).headers(headers.toHeaders())
                .apply { if (exchange.requestMethod == "HEAD") head() }
                .build()
            try {
                (if (target.expiredOk) ExpiredCertTrust.client(app.baseClient) else app.baseClient).newCall(request).execute().use { response ->
                    val body = response.body
                    val type = response.header("Content-Type").orEmpty()
                    val stream = java.io.BufferedInputStream(body.byteStream(), 64 * 1024)
                    // A playlist served from a .php address as text/plain is recognised by its first line too.
                    if (exchange.requestMethod != "HEAD" && (isPlaylist(target.url, type) || startsWithPlaylist(stream))) {
                        val text = stream.readBytes().toString(Charsets.UTF_8)
                        if (text.trimStart().startsWith("#EXTM3U")) {
                            logPlaylist(id, text)
                            val bytes = rewrite(text, response.request.url.toString(), target.headers, target.expiredOk).toByteArray()
                            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                            exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                            return
                        }
                        val bytes = text.toByteArray()
                        copyHeaders(response, exchange)
                        exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                        return
                    }
                    copyHeaders(response, exchange)
                    val length = response.header("Content-Length")?.toLongOrNull()
                    if (exchange.requestMethod == "HEAD") {
                        length?.let { exchange.responseHeaders.add("Content-Length", it.toString()) }
                        exchange.sendResponseHeaders(response.code, -1)
                        return
                    }
                    target.key?.let { key ->
                        val plain = decrypt(stream.readBytes(), key, target)
                        val skip = Disguise.junkBefore(java.io.BufferedInputStream(plain.inputStream()))
                        exchange.responseHeaders.set("Content-Type", "video/mp2t")
                        exchange.responseHeaders.remove("Content-Range")
                        exchange.sendResponseHeaders(200, (plain.size - skip).toLong())
                        exchange.responseBody.write(plain, skip, plain.size - skip)
                        return
                    }
                    // Video segments hidden behind a fake picture header (only the sound played): skip to the real
                    // MPEG-TS and say it's video.
                    val input = stream
                    var sendLength = length
                    if (exchange.requestHeaders.getFirst("Range") == null && response.code == 200) {
                        val skip = Disguise.junkBefore(input)
                        if (skip > 0) {
                            input.skip(skip.toLong())
                            sendLength = length?.minus(skip)
                            exchange.responseHeaders.set("Content-Type", "video/mp2t")
                        }
                    }
                    // 0 means chunked when the length is unknown.
                    exchange.sendResponseHeaders(response.code, sendLength ?: 0)
                    input.use { it.copyTo(exchange.responseBody, 64 * 1024) }
                }
            } catch (t: Throwable) {
                // The player closing a connection mid-stream (seek, stop) lands here too.
                runCatching { exchange.sendResponseHeaders(502, -1) }
            }
        }
    }

    private fun copyHeaders(response: okhttp3.Response, exchange: HttpExchange) {
        response.headers.forEach { (name, value) ->
            if (name.lowercase() !in skipResponse) exchange.responseHeaders.add(name, value)
        }
    }

    private fun isPlaylist(url: String, contentType: String): Boolean {
        val type = contentType.lowercase()
        return "mpegurl" in type || runCatching { URI(url).path.lowercase().endsWith(".m3u8") }.getOrDefault(false)
    }

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    /** Points every URI in an HLS playlist (segments, variants, keys, maps) back at the proxy. */
    /** A playlist given as a data: address (NetMirror): served from here, its segments fetched with [headers]. */
    fun wrapData(url: String, headers: Map<String, String>): String? {
        val text = DataUrl.decode(url)?.takeIf { it.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U") } ?: return null
        val s = start()
        val id = id(url, headers)
        val base = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: "https://localhost/"
        targets[id] = Target(url, headers, inline = rewrite(text, base, headers))
        return "http://127.0.0.1:${s.address.port}/s/$id/playlist.m3u8"
    }

    fun rewrite(playlist: String, baseUrl: String, headers: Map<String, String>, expiredOk: Boolean = false): String {
        val base = URI(baseUrl)
        fun resolve(ref: String) = resolveLoose(base, ref) ?: ref
        fun proxied(ref: String, key: Aes? = null) = resolveLoose(base, ref)?.let { wrap(it, headers, expiredOk, key) } ?: ref
        var sequence = mediaSequence.find(playlist)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        var key: Pair<String, ByteArray?>? = null
        val out = StringBuilder()
        for (line in playlist.lineSequence()) {
            when {
                line.isBlank() -> out.append(line)
                line.startsWith("#EXT-X-KEY", true) && "METHOD=AES-128" in line -> {
                    // Decrypted here (see Aes): the line goes, its key is applied to the segments that follow.
                    val uri = uriAttribute.find(line)?.groupValues?.get(1)
                    val iv = ivAttribute.find(line)?.groupValues?.get(1)?.let { hex(it) }
                    key = uri?.let { resolve(it) to iv }
                    if (uri == null) out.append(line) else continue
                }
                line.startsWith("#EXT-X-KEY", true) -> { key = null; out.append(uriAttribute.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }) }
                line.startsWith("#") -> out.append(uriAttribute.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" })
                else -> {
                    val aes = key?.let { (url, iv) -> Aes(url, iv ?: java.nio.ByteBuffer.allocate(16).putLong(8, sequence).array()) }
                    out.append(proxied(line, aes))
                    sequence++
                }
            }
            out.append('\n')
        }
        return vodEnd(out.toString().removeSuffix("\n"))
    }

    private val ivAttribute = Regex("IV=0[xX]([0-9a-fA-F]+)")
    private fun hex(s: String): ByteArray = s.padStart(32, '0').takeLast(32).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun decrypt(data: ByteArray, key: Aes, target: Target): ByteArray {
        val k = keys.getOrPut(key.keyUrl) {
            val request = Request.Builder().url(key.keyUrl).headers(target.headers.toHeaders()).build()
            (if (target.expiredOk) ExpiredCertTrust.client(app.baseClient) else app.baseClient).newCall(request).execute().use { it.body.bytes() }
        }.also { if (keys.size > 64) keys.clear() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(k.copyOf(16), "AES"), javax.crypto.spec.IvParameterSpec(key.iv))
        return runCatching { cipher.doFinal(data) }.getOrElse {
            // Some hosts pad oddly: decrypt without removing padding.
            javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
                init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(k.copyOf(16), "AES"), javax.crypto.spec.IvParameterSpec(key.iv))
                doFinal(data.copyOf(data.size / 16 * 16))
            }
        }
    }

    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** The start of each playlist once (codecs, audio groups, end marker), so a log shows what a source sends. */
    private fun logPlaylist(id: String, text: String) {
        if (!logged.add(id) || logged.size > 200) return
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val head = lines.filter { it.startsWith("#") && !it.startsWith("#EXTINF") }.take(12)
        val firstUri = lines.firstOrNull { !it.startsWith("#") }?.let { u -> u.substringBefore('?').takeLast(60) }
        val seconds = extinf.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }.toLong()
        android.util.Log.i("proxy", "playlist ${lines.size} lines, ${seconds}s, first: $firstUri | " + head.joinToString(" | "))
    }

    private val extinf = Regex("#EXTINF:\\s*([0-9.]+)")
    private val mediaSequence = Regex("#EXT-X-MEDIA-SEQUENCE:\\s*(\\d+)")

    /**
     * A whole film or episode whose playlist doesn't say it has ended (NetMirror): VLC took it for a live stream,
     * showed 27:46:39 (its "live" length) and seeked inside a live window (37:00 landed at 1:37). Such a playlist
     * gets the end marker. Only when it's clearly not live: at least 10 minutes of segments, counted from the first
     * one (live channels list a short, moving window), and not an event that's still growing.
     */
    internal fun vodEnd(text: String): String {
        if ("#EXT-X-ENDLIST" in text || "#EXT-X-PLAYLIST-TYPE:EVENT" in text) return text
        if ((mediaSequence.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L) > 0L) return text
        val seconds = extinf.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        if (seconds < 600) return text
        val typed = if ("#EXT-X-PLAYLIST-TYPE" in text) text else text.replaceFirst("#EXTM3U", "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD")
        return typed.trimEnd() + "\n#EXT-X-ENDLIST\n"
    }
}

private fun startsWithPlaylist(input: java.io.BufferedInputStream): Boolean {
    input.mark(64)
    val head = ByteArray(64)
    var n = 0
    while (n < head.size) { val r = input.read(head, n, head.size - n); if (r <= 0) break; n += r }
    input.reset()
    return String(head, 0, n, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")
}

/** data: addresses (RFC 2397): some extensions (NetMirror) hand the player its playlist inline. */
object DataUrl {
    fun decode(url: String): String? {
        if (!url.startsWith("data:", true)) return null
        val comma = url.indexOf(',').takeIf { it > 0 } ?: return null
        val meta = url.substring(5, comma)
        val body = url.substring(comma + 1)
        return runCatching {
            if (meta.endsWith(";base64", true)) String(java.util.Base64.getMimeDecoder().decode(body), Charsets.UTF_8)
            else java.net.URLDecoder.decode(body.replace("+", "%2B"), "UTF-8")
        }.getOrNull()
    }
}

/** Video segments disguised as pictures (a fake image header in front of MPEG-TS). Same as the Android app's. */
object Disguise {
    private val imageExt = Regex("\\.(jpe?g|png|gif|webp|bmp|ico|txt|html|css|js)$", RegexOption.IGNORE_CASE)

    fun segmentName(name: String): String = if (imageExt.containsMatchIn(name)) imageExt.replace(name, ".ts") else name

    fun junkBefore(input: java.io.InputStream): Int {
        val peek = 64 * 1024
        input.mark(peek)
        val buf = ByteArray(peek)
        var n = 0
        while (n < peek) { val r = input.read(buf, n, peek - n); if (r <= 0) break; n += r }
        input.reset()
        return junkBefore(buf, n)
    }

    fun junkBefore(buf: ByteArray, n: Int): Int {
        fun ts(at: Int) = (0 until 4).all { at + it * 188 < n && buf[at + it * 188] == 0x47.toByte() }
        if (n < 188 * 4 || ts(0)) return 0
        for (i in 1 until n - 188 * 3) if (ts(i)) return i
        return 0
    }
}

/**
 * [ref] resolved against [base], with what java.net.URI refuses percent-encoded first: spaces, and the square
 * brackets NetMirror puts in subtitle addresses (hi.[CC].m3u8), which made the whole playlist fail (a 502 for the
 * player). Null when it still can't be read; the caller then leaves that line as it was.
 */
internal fun resolveLoose(base: java.net.URI, ref: String): String? = runCatching {
    val safe = buildString {
        for (b in ref.trim().toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 0x21..0x7e && c.toChar() !in "[]{}|\\^`\"<>") append(c.toChar()) else append("%%%02X".format(c))
        }
    }
    base.resolve(safe).toString()
}.getOrNull()
