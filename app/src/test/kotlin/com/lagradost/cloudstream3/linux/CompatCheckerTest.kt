package com.lagradost.cloudstream3.linux

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The developer page's compatibility check listed hundreds of "issues" for extensions that work on Windows
 * (Eclipsia, NetMirror, MovieBox): Android screens and guarded "is this a TV?" checks. Only what could stop
 * browsing or playing should count.
 */
class CompatCheckerTest {
    /** A class whose methods use the missing android.app.UiModeManager: guarded, in a try/finally, and plain. */
    private fun extensionJar(): File {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V11, ACC_PUBLIC, "com/example/store/Store", null, "java/lang/Object", null)
        fun method(name: String, body: org.objectweb.asm.MethodVisitor.() -> Unit) =
            cw.visitMethod(ACC_PUBLIC or ACC_STATIC, name, "()V", null, null).apply { visitCode(); body(); visitMaxs(0, 0); visitEnd() }
        fun org.objectweb.asm.MethodVisitor.useTvCheck() {
            visitTypeInsn(NEW, "android/app/UiModeManager"); visitInsn(POP)
        }
        // runCatching { … }: catch Throwable, carry on.
        method("guarded") {
            val (s, e, h, end) = List(4) { Label() }
            visitTryCatchBlock(s, e, h, null)
            visitLabel(s); useTvCheck(); visitLabel(e); visitJumpInsn(GOTO, end)
            visitLabel(h); visitInsn(POP)
            visitLabel(end); visitInsn(RETURN)
        }
        // try { … } finally { … }: catches "any" but rethrows, so it doesn't protect anything.
        method("withFinally") {
            val (s, e, h) = List(3) { Label() }
            visitTryCatchBlock(s, e, h, null)
            visitLabel(s); visitTypeInsn(NEW, "android/app/ActivityManagerX"); visitInsn(POP); visitLabel(e); visitInsn(RETURN)
            visitLabel(h); visitInsn(ATHROW)
        }
        method("plain") { visitTypeInsn(NEW, "android/graphics/Bitmap"); visitInsn(POP); visitInsn(RETURN) }
        cw.visitEnd()
        val ui = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).apply {
            visit(V11, ACC_PUBLIC, "com/example/ui/SettingsScreen", null, "java/lang/Object", null)
            visitMethod(ACC_PUBLIC or ACC_STATIC, "show", "()V", null, null).apply {
                visitCode(); visitTypeInsn(NEW, "android/graphics/Paint"); visitInsn(POP); visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
            }
            visitEnd()
        }
        val jar = File.createTempFile("compat", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("com/example/store/Store.class")); out.write(cw.toByteArray()); out.closeEntry()
            out.putNextEntry(JarEntry("com/example/ui/SettingsScreen.class")); out.write(ui.toByteArray()); out.closeEntry()
        }
        return jar
    }

    @Test
    fun `only references that could stop the extension matter`() {
        val issues = CompatChecker.check(extensionJar())
        val byOwner = issues.associateBy { it.owner }
        assertTrue(byOwner.getValue("android/app/UiModeManager").handled, "inside a catch-all: falls back by itself")
        assertFalse(byOwner.getValue("android/app/ActivityManagerX").handled, "try/finally rethrows: not protected")
        assertTrue(byOwner.getValue("android/graphics/Paint").screenOnly, "in a settings screen")
        assertEquals(listOf("android/app/ActivityManagerX", "android/graphics/Bitmap"), issues.filter { it.matters }.map { it.owner }.sorted())
    }

    @Test
    fun `screen code and Android UI toolkits`() {
        listOf("com/eclipsiaanime/EclipsiaSettingsUi", "com/MovieBox/compose/ComposableSingletons\$CsKitKt", "com/soryn/core/ui/BackButton",
            "com/eclipsia/core/AppRestart", "com/x/R\$string").forEach { assertTrue(CompatChecker.isScreenCode(it), it) }
        listOf("com/eclipsia/core/store/PluginStore", "com/x/ViewAsianProvider", "com/x/NetMirrorProvider").forEach { assertFalse(CompatChecker.isScreenCode(it), it) }
        assertTrue(CompatChecker.isUiApi("androidx/fragment/app/FragmentManager"))
        assertFalse(CompatChecker.isUiApi("android/graphics/Bitmap"))
    }
}
