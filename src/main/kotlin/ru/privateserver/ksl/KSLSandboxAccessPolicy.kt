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
}
