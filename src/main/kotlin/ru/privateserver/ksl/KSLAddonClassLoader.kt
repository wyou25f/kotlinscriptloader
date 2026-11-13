package ru.privateserver.ksl

import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * Parent-first classloader that exposes classes and resources from registered KSL addons.
 *
 * The parent remains authoritative for KSL, Kotlin, Bukkit and shared API classes. Addon
 * classloaders are consulted only when the parent cannot resolve a class, preventing an
 * addon from shadowing KSL's own API types.
 */
internal class KSLAddonClassLoader(
    parent: ClassLoader,
    addonClassLoaders: Collection<ClassLoader>
) : ClassLoader(parent) {

    private val delegates = addonClassLoaders
        .filter { it !== parent && it !== this }
        .distinct()

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return@synchronized it }

            val loaded = try {
                parent.loadClass(name)
            } catch (_: ClassNotFoundException) {
                delegates.firstNotNullOfOrNull { delegate ->
                    runCatching { delegate.loadClass(name) }.getOrNull()
                } ?: throw ClassNotFoundException(name)
            }

            if (resolve && loaded.classLoader === this) resolveClass(loaded)
            loaded
        }

    override fun getResource(name: String): URL? =
        parent.getResource(name)
            ?: delegates.firstNotNullOfOrNull { it.getResource(name) }

    override fun getResources(name: String): Enumeration<URL> {
        val resources = linkedSetOf<URL>()
        resources += Collections.list(parent.getResources(name))
        delegates.forEach { delegate -> resources += Collections.list(delegate.getResources(name)) }
        return Collections.enumeration(resources)
    }
}
