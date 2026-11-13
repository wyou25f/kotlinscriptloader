package ru.privateserver.ksl.api.library

import ru.privateserver.ksl.api.KSLAPI
import ru.privateserver.ksl.api.KSL_API_VERSION

/**
 * Declarative metadata for a future KSL Standard Library module.
 *
 * A module is deliberately separate from the KSL core: it can be versioned,
 * planned and tested without adding its implementation to the plugin.
 */
data class KSLModuleDescriptor(
    val id: String,
    val version: String,
    val description: String,
    val requiredApiVersion: Int = KSL_API_VERSION,
    val dependencies: Set<String> = emptySet(),
    val defaultImports: List<String> = emptyList()
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9._-]*"))) {
            "Module id must match [a-z][a-z0-9._-]*: '$id'"
        }
        require(version.isNotBlank()) { "Module '$id' must declare a version" }
        require(description.isNotBlank()) { "Module '$id' must declare a description" }
        require(requiredApiVersion > 0) { "Module '$id' has an invalid required API version" }
        require(id !in dependencies) { "Module '$id' cannot depend on itself" }
        require(dependencies.all { it.matches(Regex("[a-z][a-z0-9._-]*")) }) {
            "Module '$id' contains an invalid dependency id"
        }
        require(defaultImports.all { it.isNotBlank() }) {
            "Module '$id' contains a blank default import"
        }
        require(defaultImports.distinct().size == defaultImports.size) {
            "Module '$id' contains duplicate default imports"
        }
    }
}

/**
 * Contract implemented by future Standard Library modules.
 *
 * Implementations should register resources through [KSLAPI] so KSL's addon
 * ownership and unload cleanup can be reused by the eventual module host.
 */
interface KSLStandardLibraryModule {
    val descriptor: KSLModuleDescriptor

    /** Called once after all declared dependencies have been installed. */
    fun install(api: KSLAPI)

    /** Called in reverse dependency order. Must tolerate partial installation. */
    fun uninstall(api: KSLAPI) = Unit
}

/**
 * Validates a module set and returns a deterministic dependency-first order.
 *
 * This planner has no side effects: it does not install modules or mutate KSL.
 * The future Standard Library host can call it before changing server state.
 */
object KSLModulePlanner {
    @JvmStatic
    @JvmOverloads
    fun resolve(
        modules: Collection<KSLStandardLibraryModule>,
        apiVersion: Int = KSL_API_VERSION
    ): List<KSLStandardLibraryModule> {
        require(apiVersion > 0) { "KSL API version must be positive" }

        val byId = linkedMapOf<String, KSLStandardLibraryModule>()
        modules.sortedBy { it.descriptor.id }.forEach { module ->
            val id = module.descriptor.id
            require(byId.putIfAbsent(id, module) == null) {
                "Duplicate KSL module id '$id'"
            }
        }

        byId.values.forEach { module ->
            val descriptor = module.descriptor
            require(descriptor.requiredApiVersion <= apiVersion) {
                "Module '${descriptor.id}' requires KSL API v${descriptor.requiredApiVersion}, available v$apiVersion"
            }
            val missing = descriptor.dependencies - byId.keys
            require(missing.isEmpty()) {
                "Module '${descriptor.id}' has missing dependencies: ${missing.sorted().joinToString()}"
            }
        }

        val ordered = mutableListOf<KSLStandardLibraryModule>()
        val visited = mutableSetOf<String>()
        val visiting = linkedSetOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            if (!visiting.add(id)) {
                val cycle = (visiting.dropWhile { it != id } + id).joinToString(" -> ")
                throw IllegalArgumentException("KSL module dependency cycle: $cycle")
            }

            val module = byId.getValue(id)
            module.descriptor.dependencies.sorted().forEach(::visit)
            visiting.remove(id)
            visited += id
            ordered += module
        }

        byId.keys.sorted().forEach(::visit)
        return ordered
    }
}
