package ru.privateserver.ksl

// Classes a sandboxed script must not be able to load. This is enforced where it cannot be disguised: the script's
// class loader refuses to hand the class out, so it does not matter how the name was built (concatenation, aliases,
// Class.forName, reflection). Entries ending in "." are package prefixes, everything else is an exact class name.
class KSLClassDenyList(extra: Collection<String> = emptyList(), allowed: Collection<String> = emptyList()) {

    companion object {
        val defaultNames = listOf(
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process", "java.lang.ProcessHandle",
            "java.lang.ClassLoader", "java.net.URLClassLoader", "java.net.Socket", "java.net.ServerSocket",
            // Reflection that can reach anything: calling a private method, setting a final field, proxies.
            "java.lang.reflect.Method", "java.lang.reflect.Field", "java.lang.reflect.Constructor",
            "java.lang.reflect.Proxy", "java.lang.reflect.AccessibleObject",
            // MethodHandles is the entry point; its Lookup, MethodType and LambdaMetafactory stay available
            // because the compiler generates them for every lambda.
            "java.lang.invoke.MethodHandles", "java.lang.invoke.VarHandle"
        )

        val defaultPrefixes = listOf(
            "sun.misc.", "jdk.internal.", "com.sun.", "javax.script.", "javax.tools.", "java.lang.instrument.", "java.rmi.",
            "kotlin.reflect.full.", "kotlin.reflect.jvm.", "org.jetbrains.kotlin.",
            "org.objectweb.asm.", "javassist.", "net.bytebuddy."
        )
    }

    private val names: Set<String>
    private val prefixes: List<String>

    init {
        val skip = allowed.map { it.trim() }.toSet()
        val all = (defaultNames + defaultPrefixes + extra.map { it.trim() }).filter { it.isNotEmpty() && it !in skip }
        prefixes = all.filter { it.endsWith(".") }.distinct()
        names = all.filter { !it.endsWith(".") }.toSet()
    }

    fun denies(binaryName: String): Boolean = binaryName in names || prefixes.any { binaryName.startsWith(it) }
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
