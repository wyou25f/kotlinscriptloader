package ru.privateserver.ksl

// Classes a sandboxed script must not be able to load. This is enforced at class-loading time,
// so aliases and dynamically assembled class names receive the same decision. Entries ending
// in "." are package prefixes; other entries are exact binary class names.
class KSLClassDenyList(extra: Collection<String> = emptyList(), allowed: Collection<String> = emptyList()) {

    companion object {
        val defaultNames = listOf(
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process", "java.lang.ProcessHandle",
            "java.lang.Thread", "java.util.Timer",
            "java.util.concurrent.Executors", "java.util.concurrent.ScheduledExecutorService",
            "java.util.concurrent.ThreadPoolExecutor", "java.util.concurrent.ForkJoinPool",
            "java.lang.ClassLoader", "java.net.URLClassLoader",
            "java.lang.reflect.Method", "java.lang.reflect.Field", "java.lang.reflect.Constructor",
            "java.lang.reflect.Proxy", "java.lang.reflect.AccessibleObject",
            // MethodHandles stays denied; Lookup itself must remain loadable for JVM lambda linkage.
            "java.lang.invoke.MethodHandle", "java.lang.invoke.MethodHandles",
            "java.lang.invoke.VarHandle"
        )

        val defaultPrefixes = listOf(
            "java.lang.reflect.",
            "java.io.",
            "java.nio.file.",
            "java.nio.channels.",
            "java.net.",
            "sun.misc.", "sun.reflect.", "jdk.internal.", "com.sun.",
            "javax.script.", "javax.tools.", "java.lang.instrument.", "java.rmi.",
            "kotlin.reflect.full.", "kotlin.reflect.jvm.", "org.jetbrains.kotlin.",
            "org.objectweb.asm.", "javassist.", "net.bytebuddy."
        )
    }

    private val names: Set<String>
    private val prefixes: List<String>
    private val allowedNames: Set<String>
    private val allowedPrefixes: List<String>

    init {
        // Modifier is descriptive metadata, not an invocation/reflection entry point.
        // Keep the broad reflection package blocked while allowing this harmless type.
        val normalizedAllowed = (listOf("java.lang.reflect.Modifier") + allowed)
            .map { it.trim() }.filter { it.isNotEmpty() }
        allowedNames = normalizedAllowed.filterNot { it.endsWith(".") }.toSet()
        allowedPrefixes = normalizedAllowed.filter { it.endsWith(".") }.distinct()

        val all = (defaultNames + defaultPrefixes + extra.map { it.trim() })
            .filter { it.isNotEmpty() }
        prefixes = all.filter { it.endsWith(".") }.distinct()
        names = all.filter { !it.endsWith(".") }.toSet()
    }

    fun denies(binaryName: String): Boolean {
        if (binaryName in allowedNames || allowedPrefixes.any { binaryName.startsWith(it) }) return false
        return binaryName in names || prefixes.any { binaryName.startsWith(it) }
    }
}

// Parent of a sandboxed script's own class loader: every class the script touches is resolved through here first.
class KSLSandboxClassLoader(
    parent: ClassLoader,
    private val denyList: KSLClassDenyList,
    private val scriptName: String,
    private val onDenied: (String) -> Unit
) : ClassLoader(parent) {

    private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (denyList.denies(name)) {
            // Once per class: a script that retries in a loop must not flood the console.
            if (reported.add(name)) onDenied(name)
            throw ClassNotFoundException("[sandbox] скрипту $scriptName запрещён доступ к классу $name")
        }
        return super.loadClass(name, resolve)
    }
}
