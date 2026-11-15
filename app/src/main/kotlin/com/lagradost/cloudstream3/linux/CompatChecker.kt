package com.lagradost.cloudstream3.linux

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * Static linkage check for an extension: every class, method and field it references
 * outside itself must exist in this runtime (CloudStream library + Android shim + JDK).
 * Nothing from the extension is loaded or executed, so it's safe on untrusted files.
 * Missing members are exactly what would surface as NoClassDefFoundError /
 * NoSuchMethodError at runtime, often swallowed by the extension as "no results".
 */
object CompatChecker {
    /**
     * [handled]: the reference sits inside the extension's own catch-all (runCatching, try/catch Throwable), so a
     * missing piece only makes that step fall back (e.g. "is this a TV?" on Windows), it doesn't stop the extension.
     */
    data class Issue(val kind: String, val owner: String, val member: String, val usedIn: String, val handled: Boolean = false) {
        /** Code of an Android screen of the extension (its settings, dialogs, Compose UI): not used to browse or play. */
        val screenOnly: Boolean get() = isScreenCode(usedIn.substringBeforeLast('.')) || isUiApi(owner)
        /** Could stop the extension from browsing or playing here. */
        val matters: Boolean get() = !handled && !screenOnly
        override fun toString() = "$kind ${owner.replace('/', '.')}${if (member.isNotEmpty()) ".$member" else ""}  (used in ${usedIn.replace('/', '.')})"
    }

    private val screenWords = listOf("Settings", "Dialog", "Fragment", "Composable", "Compose", "Adapter", "Preference",
        "BackButton", "AppRestart", "Activity", "BottomSheet")
    private val screenEndings = listOf("View", "Layout", "Widget", "Screen", "Sheet", "Ui", "UI", "Button", "Holder")

    /** Android's screen toolkits (views, dialogs, fragments, Compose): only used to show an extension's own screens. */
    fun isUiApi(internalName: String): Boolean = listOf("android/view/", "android/widget/", "androidx/fragment/", "androidx/appcompat/",
        "androidx/compose/", "androidx/recyclerview/", "androidx/preference/", "com/google/android/material/", "android/app/Dialog",
        "android/app/AlertDialog", "android/content/DialogInterface", "androidx/activity/").any { internalName.startsWith(it) }

    /** An extension class that belongs to its Android screens, by package (…/ui/, …/compose/, …/settings/) or name. */
    fun isScreenCode(internalName: String): Boolean {
        val pkg = internalName.substringBeforeLast('/', "").lowercase()
        if (listOf("ui", "compose", "settings", "views", "widget", "widgets", "dialogs").any { "/$pkg/".contains("/$it/") }) return true
        val simple = internalName.substringAfterLast('/').substringBefore('$')
        return simple == "R" || internalName.substringAfterLast('/').startsWith("R$") || screenWords.any { simple.contains(it) } ||
            screenEndings.any { simple.endsWith(it) }
    }

    /**
     * Instructions inside a handler that catches everything (catch Throwable, runCatching; translated from dex
     * these show as catch-any). A try/finally also catches "any" but rethrows: those don't count.
     */
    private fun handledRanges(method: org.objectweb.asm.tree.MethodNode): List<IntRange> {
        val insns = method.instructions
        return method.tryCatchBlocks.filter { tc ->
            tc.type == null || tc.type in setOf("java/lang/Throwable", "java/lang/Error", "java/lang/LinkageError", "java/lang/NoClassDefFoundError")
        }.filter { tc ->
            // Follow the handler to its first unconditional exit: a rethrow means "finally".
            var n: org.objectweb.asm.tree.AbstractInsnNode? = tc.handler
            var steps = 0
            while (n != null && steps++ < 400) {
                val op = n.opcode
                if (op == org.objectweb.asm.Opcodes.ATHROW) return@filter false
                if (op == org.objectweb.asm.Opcodes.GOTO || op in org.objectweb.asm.Opcodes.IRETURN..org.objectweb.asm.Opcodes.RETURN) return@filter true
                n = n.next
            }
            true
        }.map { tc -> insns.indexOf(tc.start) until insns.indexOf(tc.end) }
    }

    private val runtime: ClassLoader = CompatChecker::class.java.classLoader

    /** What the references are checked against: this runtime, or another app's classes (the Android app's APK). */
    @Volatile private var against: ClassLoader = runtime

    /**
     * Checks against [loader] instead of this runtime, e.g. the Android app: its APK (translated) and the
     * platform's android.jar, so the same scan tells what would fail on Android.
     */
    fun <T> against(loader: ClassLoader, block: () -> T): T {
        val before = against
        against = loader
        try { return block() } finally { against = before }
    }

    /** Accepts a .cs3 (translated statically first) or a .jar. */
    fun check(file: File): List<Issue> {
        val jar = if (file.extension == "jar") file else DexTranslator.translate(file)
        val own = ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.associate { entry ->
                val node = ClassNode()
                ClassReader(zip.getInputStream(entry).readBytes()).accept(node, ClassReader.SKIP_FRAMES)
                node.name to node
            }
        }
        val issues = linkedSetOf<Issue>()
        for (cls in own.values) {
            (listOfNotNull(cls.superName) + cls.interfaces).forEach { checkClass(it, own, cls.name, issues) }
            for (method in cls.methods) {
                val where = "${cls.name}.${method.name}"
                val caught = handledRanges(method)
                for ((index, insn) in method.instructions.withIndex()) {
                    val found = linkedSetOf<Issue>()
                    when (insn) {
                        is MethodInsnNode -> if (insn.owner.startsWith("[")) Unit
                        else if (checkClass(insn.owner, own, where, found) && resolveMethod(insn.owner, insn.name, insn.desc, own) == false) {
                            found += Issue("missing method", insn.owner, insn.name + insn.desc, where)
                        }
                        // Compose's $stable flags from host classes are handled when the extension loads (ComposeStable).
                        is FieldInsnNode -> if (insn.name == "\$stable" && insn.desc == "I" && insn.owner !in own) Unit
                        else if (checkClass(insn.owner, own, where, found) && resolveField(insn.owner, insn.name, own) == false) {
                            found += Issue("missing field", insn.owner, insn.name, where)
                        }
                        is TypeInsnNode -> Type.getObjectType(insn.desc).let { t ->
                            val name = if (t.sort == Type.ARRAY) t.elementType.takeIf { it.sort == Type.OBJECT }?.internalName else t.internalName
                            if (name != null) checkClass(name, own, where, found)
                        }
                    }
                    if (found.isEmpty()) continue
                    val inCatch = caught.any { index in it }
                    found.forEach { issues += if (inCatch) it.copy(handled = true) else it }
                }
            }
        }
        // A reference that is also used outside a catch-all counts as not handled.
        issues.removeAll { it.handled && it.copy(handled = false) in issues }
        return issues.toList()
    }

    private fun load(name: String): Class<*>? =
        runCatching { Class.forName(name.replace('/', '.'), false, against) }.getOrNull()

    /** True if the class exists (in the extension or the runtime). */
    private fun checkClass(name: String, own: Map<String, ClassNode>, where: String, issues: MutableSet<Issue>): Boolean {
        if (name in own || load(name) != null) return true
        issues += Issue("missing class", name, "", where)
        return false
    }

    /** null = can't tell (e.g. unresolvable superclass, already reported). */
    private fun resolveMethod(owner: String, name: String, desc: String, own: Map<String, ClassNode>): Boolean? {
        own[owner]?.let { node ->
            if (node.methods.any { it.name == name && it.desc == desc }) return true
            val supers = listOfNotNull(node.superName) + node.interfaces
            return supers.map { resolveMethod(it, name, desc, own) }.let { r -> if (r.any { it == true }) true else if (r.any { it == null }) null else false }
        }
        val cls = load(owner) ?: return null
        if (name == "<init>") return cls.declaredConstructors.any { Type.getConstructorDescriptor(it) == desc }
        return hierarchy(cls).any { c ->
            c.declaredMethods.any { it.name == name && Type.getMethodDescriptor(it) == desc } ||
                // Signature-polymorphic MethodHandle calls, and Object methods on interfaces.
                (c == java.lang.invoke.MethodHandle::class.java && c.declaredMethods.any { it.name == name && Modifier.isNative(it.modifiers) })
        } || (cls.isInterface && Any::class.java.declaredMethods.any { it.name == name && Type.getMethodDescriptor(it) == desc })
    }

    private fun resolveField(owner: String, name: String, own: Map<String, ClassNode>): Boolean? {
        own[owner]?.let { node ->
            if (node.fields.any { it.name == name }) return true
            val supers = listOfNotNull(node.superName) + node.interfaces
            return supers.map { resolveField(it, name, own) }.let { r -> if (r.any { it == true }) true else if (r.any { it == null }) null else false }
        }
        val cls = load(owner) ?: return null
        return hierarchy(cls).any { c -> c.declaredFields.any { it.name == name } }
    }

    private fun hierarchy(cls: Class<*>): Sequence<Class<*>> = sequence {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque(listOf(cls))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            yield(c)
            c.superclass?.let { queue += it }
            queue += c.interfaces
        }
    }
}
