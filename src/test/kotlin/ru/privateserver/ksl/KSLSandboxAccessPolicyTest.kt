package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLSandboxAccessPolicyTest {
    @Test
    fun sandboxedScriptsCannotUseDatabaseByDefault() {
        assertThrows(SecurityException::class.java) {
            KSLSandboxAccessPolicy.requireDatabaseAccess("example", sandboxed = true, allowedSandboxScripts = emptySet())
        }
    }

    @Test
    fun databaseCapabilityCanBeGrantedToOneExactScriptId() {
        KSLSandboxAccessPolicy.requireDatabaseAccess(
            "economy/shop",
            sandboxed = true,
            allowedSandboxScripts = setOf("economy/shop")
        )
        assertThrows(SecurityException::class.java) {
            KSLSandboxAccessPolicy.requireDatabaseAccess(
                "economy/shop-v2",
                sandboxed = true,
                allowedSandboxScripts = setOf("economy/shop")
            )
        }
    }

    @Test
    fun trustedNonSandboxScriptsRetainDatabaseAccess() {
        KSLSandboxAccessPolicy.requireDatabaseAccess("trusted", sandboxed = false, allowedSandboxScripts = emptySet())
    }
}
