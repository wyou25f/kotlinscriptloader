package ru.privateserver.ksl

/**
 * Guards escape hatches that expose KSL's owning plugin and its raw infrastructure.
 *
 * This is a capability check, not a JVM security boundary: sandboxed scripts still
 * execute in-process and can use the Bukkit/KSL APIs explicitly exposed to them.
 */
internal object KSLSandboxAccessPolicy {

    fun requireTrustedAccess(scriptName: String, sandboxed: Boolean, capability: String) {
        if (sandboxed) {
            throw SecurityException(
                "Sandboxed script '$scriptName' cannot access the raw '$capability' capability. " +
                    "Use the supported KSL script API instead."
            )
        }
    }

    /**
     * SQL access is a separate opt-in capability. It exposes only the bounded KSL
     * query API, never the raw Hikari pool or JDBC connection.
     */
    fun requireDatabaseAccess(
        scriptName: String,
        sandboxed: Boolean,
        allowedSandboxScripts: Set<String>
    ) {
        if (sandboxed && scriptName !in allowedSandboxScripts) {
            throw SecurityException(
                "Sandboxed script '$scriptName' has no database capability. " +
                    "Add its exact script id to security.sandbox-database-allowlist only when required."
            )
        }
    }
}
