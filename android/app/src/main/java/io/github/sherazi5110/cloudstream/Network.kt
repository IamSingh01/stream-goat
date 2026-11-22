package io.github.sherazi5110.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Configures the shared clients extensions use (`app`, `insecureApp`), like upstream's
 * Requests.initClient, plus what home networks need in practice:
 * - HTTP cache and bounded timeouts, so one dead host can't stall a provider
 * - a persistent cookie store;
 *   sites that set session or signed cookies need them on the next request
 * - DNS-over-HTTPS with automatic fallback to system DNS when the resolver is slow/blocked
 * - IPv4-first addresses, avoiding long hangs on networks with broken IPv6
 * - one retry on connection resets
 */
object Network {
    lateinit var client: OkHttpClient
        private set
    val cookies by lazy { PersistentCookieJar(File(App.context.filesDir, "network/cookies.json")) }

    fun init() {
        val cache = Cache(File(App.context.cacheDir, "http_cache"), 100L * 1024 * 1024)
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 16
        }
        val base = OkHttpClient.Builder()
            .cache(cache)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(32, 90, TimeUnit.SECONDS))
            .cookieJar(cookies)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(RetryOnReset)
            .addInterceptor(MovieBoxFamilyMode)
            .build()
        client = base.newBuilder().dns(SmartDns(base, Settings.dns, Settings.dnsFallback)).build()
        app.baseClient = client
        insecureApp.baseClient = client.newBuilder().ignoreAllSSLErrors().build()
    }

    /** DoH with a circuit breaker: if it fails or is slow, use system DNS for a while. */
    private class SmartDns(bootstrap: OkHttpClient, provider: DnsProvider, private val fallback: Boolean) : Dns {
        private val doh: Dns? = provider.url?.let { url ->
            DnsOverHttps.Builder()
                .client(bootstrap.newBuilder().callTimeout(4, TimeUnit.SECONDS).build())
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(provider.ips.map { InetAddress.getByName(it) })
                .build()
        }
        @Volatile private var degradedUntil = 0L

        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = when {
                doh == null -> Dns.SYSTEM.lookup(hostname)
                fallback && System.currentTimeMillis() < degradedUntil -> Dns.SYSTEM.lookup(hostname)
                else -> try {
                    doh.lookup(hostname)
                } catch (e: IOException) {
                    if (!fallback) throw e
                    Log.w("dns", "DNS-over-HTTPS failed for $hostname ($e); using system DNS for 60 s")
                    degradedUntil = System.currentTimeMillis() + 60_000
                    Dns.SYSTEM.lookup(hostname)
                }
            }
            // IPv4 first: try IPv6 only after every IPv4 address.
            return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }

    /**
     * MovieBox (its CloudStream extensions) tells MovieBox's servers which app modes are on, in its x-client-info
     * header; adult titles (MovieBox's "Midnight" content) are left out in its family mode. While adult content is
     * off for this profile (Settings → Hidden content, or a kids profile), family mode is turned on in that header.
     * The request's signature doesn't cover this header, so the request stays valid.
     */
    private object MovieBoxFamilyMode : Interceptor {
        private val hosts = listOf("aoneroom.com", "inmoviebox.com", "moviebox", "movie-box", "mvbox")
        private val familyOff = Regex("\"X-Family-Mode\"\\s*:\\s*\"0\"")

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val host = request.url.host
            if (hosts.none { it in host } || ContentFilter.nsfwAllowed) return chain.proceed(request)
            val info = request.header("x-client-info")?.takeIf { it.trimEnd().endsWith("}") }
            val sent = if (info == null) request else request.newBuilder().header("x-client-info",
                if ("X-Family-Mode" in info) info.replace(familyOff, "\"X-Family-Mode\":\"1\"") else info.trimEnd().removeSuffix("}") + ",\"X-Family-Mode\":\"1\"}").build()
            // MovieBox's servers ignore family mode for some lists, so the titles are also checked here, with the
            // rules Phisher's MovieBox extension uses on the same data (genre, title words, kids flag and rating).
            return MovieBoxAdult.filter(chain.proceed(sent))
        }
    }

    private object RetryOnReset : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            return try {
                chain.proceed(request)
            } catch (e: IOException) {
                val reset = e.message?.contains("reset", ignoreCase = true) == true ||
                    e is java.io.EOFException || e.javaClass.simpleName == "StreamResetException"
                if (!reset || request.method != "GET" || chain.call().isCanceled()) throw e
                chain.proceed(request)
            }
        }
    }
}

/** Cookie store persisted to disk; session cookies live until the app exits. */
class PersistentCookieJar(private val file: File) : CookieJar {
    private val store = ConcurrentHashMap<String, Cookie>()
    private val persistent = ConcurrentHashMap<String, Boolean>()

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val cookie = Cookie.Builder().name(o.getString("name")).value(o.getString("value"))
                    .apply { if (o.optBoolean("hostOnly")) hostOnlyDomain(o.getString("domain")) else domain(o.getString("domain")) }
                    .path(o.getString("path")).expiresAt(o.getLong("expiresAt"))
                    .apply { if (o.optBoolean("secure")) secure(); if (o.optBoolean("httpOnly")) httpOnly() }
                    .build()
                if (cookie.expiresAt > System.currentTimeMillis()) put(cookie, persist = true)
            }
        }.onFailure { Log.w("cookies", "couldn't read cookie store: $it") }
    }

    private fun key(c: Cookie) = "${c.domain}|${c.path}|${c.name}"

    private fun put(cookie: Cookie, persist: Boolean) {
        store[key(cookie)] = cookie
        persistent[key(cookie)] = persist
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { put(it, it.persistent) }
        if (cookies.any { it.persistent }) save()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.entries.removeIf { it.value.expiresAt < now }
        return store.values.filter { it.matches(url) }
    }

    fun clear() {
        store.clear()
        persistent.clear()
        file.delete()
    }

    val size get() = store.size

    @Synchronized
    private fun save() {
        val array = JSONArray()
        store.forEach { (k, c) ->
            if (persistent[k] != true) return@forEach
            array.put(JSONObject().put("name", c.name).put("value", c.value).put("domain", c.domain).put("path", c.path)
                .put("expiresAt", c.expiresAt).put("secure", c.secure).put("httpOnly", c.httpOnly).put("hostOnly", c.hostOnly))
        }
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }
}
