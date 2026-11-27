package com.lagradost.cloudstream3.linux

import java.io.File

/**
 * The few things that differ between Linux and Windows: where data and downloads go, how a link or folder is
 * opened, where VLC is, and the words shown to the user. Everything else is the same code on both.
 */
object Os {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /** "Linux", "Windows" or "macOS", for texts such as "for Windows". */
    val name: String = when { isWindows -> "Windows"; isMac -> "macOS"; else -> "Linux" }

    private val home: String get() = System.getProperty("user.home")

    /**
     * RoarBox data lives in its own application-support folder. Existing Stream Goat data is moved
     * once on macOS/Windows so profiles, extensions, history and settings survive the rebrand.
     * Linux keeps its established XDG directory to avoid disrupting existing installations.
     */
    fun defaultDataDir(): File = when {
        isMac -> {
            val base = File(home, "Library/Application Support")
            migrateLegacyDataDir(File(base, "RoarBox"), File(base, "Stream Goat"))
        }
        isWindows -> {
            val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
            val current = File(appData, "RoarBox")
            val legacy = File(appData, "Stream Goat")
            val older = File(appData, "CloudStream")
            migrateLegacyDataDir(current, if (legacy.exists()) legacy else older)
        }
        else -> File((System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"), "cloudstream-linux")
    }

    /** Move a legacy profile directory only when the new destination does not already exist. */
    internal fun migrateLegacyDataDir(current: File, legacy: File): File {
        if (current.exists() || !legacy.isDirectory) return current
        current.parentFile?.mkdirs()
        return if (runCatching { legacy.renameTo(current) }.getOrDefault(false)) current else legacy
    }

    /**
     * [file], or the "<file>.tmp" saved next to it when that's newer and holds valid JSON: before 7.2.1 the
     * Windows app couldn't replace a file by renaming onto it, so its latest saves were left in the .tmp file.
     */
    fun freshest(file: File): File {
        val tmp = File(file.path + ".tmp")
        if (!tmp.isFile || tmp.length() == 0L || (file.exists() && tmp.lastModified() < file.lastModified())) return file
        val text = runCatching { tmp.readText().trim() }.getOrNull() ?: return file
        val ok = runCatching { if (text.startsWith("[")) org.json.JSONArray(text) else org.json.JSONObject(text) }.isSuccess
        return if (ok) tmp else file
    }

    /** The user's Downloads folder (Linux: the XDG download folder, which the Flatpak may write to). */
    fun downloadsDir(): File {
        if (!isWindows && !isMac) {
            runCatching {
                File(home, ".config/user-dirs.dirs").readLines()
                    .firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }?.substringAfter('=')?.trim('"')
                    ?.replace("\$HOME", home)
            }.getOrNull()?.let { return File(it) }
        }
        return File(home, "Downloads")
    }

    /** Opens a web address or a file/folder with the system's default app. */
    fun open(target: String): Boolean = runCatching {
        when {
            // explorer opens folders and files; rundll32 opens links in the default browser without a console window.
            isWindows && (target.startsWith("http://") || target.startsWith("https://")) ->
                ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start()
            isWindows -> ProcessBuilder("explorer.exe", target).start()
            isMac -> ProcessBuilder("open", target).start()
            // xdg-open; inside the Flatpak this goes through the desktop portal.
            else -> ProcessBuilder("xdg-open", target).start()
        }
        true
    }.getOrDefault(false)

    /**
     * Opens a web page in a window of its own, without tabs or an address bar (Edge or Chrome's app mode;
     * Edge comes with Windows). Falls back to the default browser. Used for the official players of YouTube,
     * Twitch and Dailymotion channels in Live TV, which the app's own web view can't play.
     */
    fun openAppWindow(url: String): Boolean {
        val env = System.getenv()
        val candidates = if (isWindows) listOfNotNull(
            env["ProgramFiles(x86)"]?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
            env["ProgramFiles"]?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
            env["ProgramFiles"]?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            env["ProgramFiles(x86)"]?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            env["LOCALAPPDATA"]?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
        ).filter { File(it).isFile }
        else if (isMac) listOf("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge").filter { File(it).isFile }
        else (env["PATH"] ?: "").split(File.pathSeparator).flatMap { dir ->
            listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "microsoft-edge-stable", "brave-browser")
                .map { File(dir, it) }
        }.filter { it.canExecute() }.map { it.path }.distinct()
        for (exe in candidates) {
            val started = runCatching {
                ProcessBuilder(exe, "--app=$url", "--window-size=1280,720", "--autoplay-policy=no-user-gesture-required")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.isSuccess
            if (started) return true
        }
        return open(url)
    }

    /** What to do when VLC's libraries are missing. */
    val vlcHint: String get() = when {
        isWindows -> "VLC is included with RoarBox; reinstall RoarBox, or install VLC (64-bit) from videolan.org"
        isMac -> "VLC is included with RoarBox; reinstall RoarBox, or install VLC from videolan.org into Applications"
        else -> "sudo apt install libvlc5 vlc-plugin-base"
    }

    /**
     * VLC shipped inside the app, next to the app's jars: Windows <app>\vlc with libvlc.dll and plugins\;
     * macOS Stream Goat.app/Contents/app/vlc/lib with libvlc.dylib, and vlc/plugins (VLC.app's own layout).
     * -Dcs.vlc.dir overrides it. Null when there's none (Linux uses the system's VLC).
     */
    fun bundledVlcDir(): File? {
        System.getProperty("cs.vlc.dir")?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
        val jarDir = runCatching { File(Os::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        val dirs = listOfNotNull(jarDir?.resolve("vlc"), jarDir?.parentFile?.resolve("vlc"))
        if (isMac) return dirs.map { it.resolve("lib") }.firstOrNull { File(it, "libvlc.dylib").isFile }
        return dirs.firstOrNull { File(it, if (isWindows) "libvlc.dll" else "libvlc.so.5").isFile }
    }

    /** The modifier key shown in shortcut texts: ⌘ on a Mac, Ctrl elsewhere. */
    val shortcutKey: String get() = if (isMac) "⌘" else "Ctrl+"

    /**
     * Windows: makes cloudstreamrepo:// links (repository links on websites) open this app, for the current user
     * (HKCU, no admin rights), like the .desktop file does on Linux. Runs at start; does nothing if already set.
     */
    fun registerLinkHandler() {
        if (!isWindows) return
        val exe = System.getProperty("jpackage.app-path")?.takeIf { it.endsWith(".exe", true) } ?: return
        val key = "HKCU\\Software\\Classes\\cloudstreamrepo"
        val command = "\"$exe\" \"%1\""
        runCatching {
            val current = ProcessBuilder("reg", "query", "$key\\shell\\open\\command", "/ve").redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
            if (current.contains(exe, ignoreCase = true)) return
            fun reg(vararg args: String) = ProcessBuilder(listOf("reg", "add") + args.toList() + "/f").redirectErrorStream(true).start().waitFor()
            reg(key, "/ve", "/d", "URL:RoarBox")
            reg(key, "/v", "URL Protocol", "/d", "")
            reg("$key\\DefaultIcon", "/ve", "/d", "\"$exe\",0")
            reg("$key\\shell\\open\\command", "/ve", "/d", command)
        }.onFailure { System.err.println("W/os: couldn't register cloudstreamrepo:// links: $it") }
    }
}
