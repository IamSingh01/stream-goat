package io.github.sherazi5110.cloudstream

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Applies a bsdiff 4 patch (the `bsdiff` tool's BSDIFF40 format, made by the GitHub builds): old APK + patch =
 * new APK. Streams the new file out and reads the old one in pieces, so it needs little memory even on a TV box.
 */
object Bspatch {
    fun apply(old: File, patch: File, out: File) {
        val header = ByteArray(32)
        RandomAccessFile(patch, "r").use { it.readFully(header) }
        require(String(header, 0, 8, Charsets.US_ASCII) == "BSDIFF40") { "not a bsdiff patch" }
        val ctrlLen = offtin(header, 8)
        val diffLen = offtin(header, 16)
        val newSize = offtin(header, 24)
        require(ctrlLen >= 0 && diffLen >= 0 && newSize >= 0) { "damaged patch" }

        fun block(offset: Long): DataInputStream {
            val input = FileInputStream(patch)
            var left = offset
            while (left > 0) { val n = input.skip(left); require(n > 0) { "damaged patch" }; left -= n }
            return DataInputStream(BZip2CompressorInputStream(BufferedInputStream(input, 1 shl 16)))
        }

        block(32).use { ctrl ->
            block(32 + ctrlLen).use { diff ->
                block(32 + ctrlLen + diffLen).use { extra ->
                    RandomAccessFile(old, "r").use { oldFile ->
                        BufferedOutputStream(out.outputStream(), 1 shl 16).use { dst ->
                            write(ctrl, diff, extra, oldFile, dst, newSize)
                        }
                    }
                }
            }
        }
        require(out.length() == newSize) { "the patch didn't make a whole file" }
    }

    private fun write(ctrl: DataInputStream, diff: DataInputStream, extra: InputStream, old: RandomAccessFile,
                      dst: java.io.OutputStream, newSize: Long) {
        val oldSize = old.length()
        val c = ByteArray(24)
        val a = ByteArray(1 shl 16)
        val b = ByteArray(1 shl 16)
        var newPos = 0L
        var oldPos = 0L
        while (newPos < newSize) {
            ctrl.readFully(c)
            val x = offtin(c, 0)
            val y = offtin(c, 8)
            val z = offtin(c, 16)
            require(x >= 0 && y >= 0 && newPos + x + y <= newSize) { "damaged patch" }
            // x bytes: the diff added to the old file's bytes (where the old file has them).
            var left = x
            while (left > 0) {
                val n = minOf(left, a.size.toLong()).toInt()
                diff.readFully(a, 0, n)
                val from = maxOf(oldPos, 0L)
                val to = minOf(oldPos + n, oldSize)
                if (to > from) {
                    old.seek(from)
                    old.readFully(b, 0, (to - from).toInt())
                    val shift = (from - oldPos).toInt()
                    for (i in 0 until (to - from).toInt()) a[shift + i] = (a[shift + i] + b[i]).toByte()
                }
                dst.write(a, 0, n)
                left -= n; oldPos += n; newPos += n
            }
            // y bytes copied as they are.
            left = y
            while (left > 0) {
                val n = minOf(left, a.size.toLong()).toInt()
                var read = 0
                while (read < n) { val r = extra.read(a, read, n - read); require(r > 0) { "damaged patch" }; read += r }
                dst.write(a, 0, n)
                left -= n; newPos += n
            }
            oldPos += z
        }
    }

    /** bsdiff's signed 64-bit number: little-endian magnitude, sign in the top bit. */
    internal fun offtin(buf: ByteArray, off: Int): Long {
        var y = (buf[off + 7].toLong() and 0x7f)
        for (i in 6 downTo 0) y = y * 256 + (buf[off + i].toLong() and 0xff)
        return if (buf[off + 7].toInt() and 0x80 != 0) -y else y
    }
}
