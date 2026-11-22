package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Os
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * libmpv (mpv's player library, FFmpeg inside) through JNA: the main player on Windows, Mac and Linux.
 *
 * Like ExoPlayer on Android it reads HLS itself: separate audio tracks (NetMirror: English, Hindi…), subtitle
 * tracks, the real length and exact seeking. Windows and Mac builds carry libmpv in the app folder (app/mpv);
 * Linux uses the system's (the .deb depends on it). Without it the app plays everything with VLC, as before.
 */
object Mpv {
    @Suppress("FunctionName")
    interface Lib : Library {
        fun mpv_create(): Pointer?
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_terminate_destroy(ctx: Pointer)
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
        fun mpv_free(data: Pointer)
        fun mpv_command(ctx: Pointer, args: StringArray): Int
        fun mpv_observe_property(ctx: Pointer, id: Long, name: String, format: Int): Int
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
        fun mpv_wakeup(ctx: Pointer)
        fun mpv_error_string(error: Int): String
        fun mpv_render_context_create(res: PointerByReference, ctx: Pointer, params: Pointer): Int
        fun mpv_render_context_set_update_callback(r: Pointer, cb: UpdateCallback?, data: Pointer?)
        fun mpv_render_context_update(r: Pointer): Long
        fun mpv_render_context_render(r: Pointer, params: Pointer): Int
        fun mpv_render_context_free(r: Pointer)
    }

    /** mpv says a new frame is ready (on its own thread). */
    fun interface UpdateCallback : Callback { fun invoke(data: Pointer?) }

    private interface LibC : Library { fun setlocale(category: Int, locale: String): Pointer? }

    /** The folder with the bundled libmpv (Windows, Mac), like Os.bundledVlcDir. */
    fun bundledDir(): File? {
        System.getProperty("cs.mpv.dir")?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
        val jarDir = runCatching { File(Mpv::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        return listOfNotNull(jarDir?.resolve("mpv"), jarDir?.parentFile?.resolve("mpv")).firstOrNull { it.isDirectory }
    }

    val lib: Lib? by lazy {
        val dir = bundledDir()
        val candidates = buildList {
            if (dir != null) when {
                Os.isWindows -> addAll(listOf("libmpv-2.dll", "mpv-2.dll", "mpv-1.dll").map { File(dir, it) }.filter { it.isFile }.map { it.absolutePath })
                Os.isMac -> addAll(listOf("libmpv.2.dylib", "libmpv.dylib").map { File(dir, it) }.filter { it.isFile }.map { it.absolutePath })
                else -> addAll(listOf("libmpv.so.2", "libmpv.so.1").map { File(dir, it) }.filter { it.isFile }.map { it.absolutePath })
            }
            when {
                Os.isWindows -> addAll(listOf("libmpv-2", "mpv-2"))
                Os.isMac -> addAll(listOf("/opt/homebrew/lib/libmpv.2.dylib", "/usr/local/lib/libmpv.2.dylib", "mpv"))
                else -> addAll(listOf("libmpv.so.2", "libmpv.so.1", "mpv"))
            }
        }
        for (name in candidates) {
            runCatching { Native.load(name, Lib::class.java) }
                .onSuccess { android.util.Log.i("mpv", "using $name"); return@lazy it }
                .onFailure { if (File(name).isAbsolute) android.util.Log.w("mpv", "can't load $name: ${it.message}") }
        }
        android.util.Log.i("mpv", "libmpv not found: VLC plays everything")
        null
    }

    val available: Boolean get() = lib != null

    /** A new mpv instance (not yet initialized); mpv refuses to start under a locale with a decimal comma. */
    fun create(): Pointer? {
        val lib = lib ?: return null
        if (!Os.isWindows) runCatching {
            Native.load("c", LibC::class.java).setlocale(if (Os.isMac) 4 else 1 /* LC_NUMERIC */, "C")
        }
        return lib.mpv_create()
    }

    // ---------- mpv_render_param arrays: { int type; void *data; } ----------

    const val PARAM_INVALID = 0
    const val PARAM_API_TYPE = 1
    const val PARAM_SW_SIZE = 17
    const val PARAM_SW_FORMAT = 18
    const val PARAM_SW_STRIDE = 19
    const val PARAM_SW_POINTER = 20

    /** A zero-terminated parameter list; [keep] holds the memory the entries point to until it's used. */
    class Params(entries: List<Pair<Int, Pointer?>>) {
        val memory = Memory(16L * (entries.size + 1)).apply { clear() }
        init {
            entries.forEachIndexed { i, (type, data) ->
                memory.setInt(16L * i, type)
                memory.setPointer(16L * i + 8, data)
            }
        }
    }

    fun cString(s: String): Memory = Memory(s.length + 1L).apply { setString(0, s, "US-ASCII") }
}
