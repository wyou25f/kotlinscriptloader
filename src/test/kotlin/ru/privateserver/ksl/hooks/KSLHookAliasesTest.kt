package ru.privateserver.ksl.hooks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.KSLHook

class KSLHookAliasesTest {
    @Test
    fun builtInHookIdsAreStableUniqueIdentifiers() {
        val ids = listOf(
            KSLHookIds.VAULT,
            KSLHookIds.LUCK_PERMS,
            KSLHookIds.ESSENTIALS,
            KSLHookIds.SKINS_RESTORER,
            KSLHookIds.WORLD_EDIT,
            KSLHookIds.WORLD_GUARD,
            KSLHookIds.TAB,
            KSLHookIds.AUTH_ME,
            KSLHookIds.SKRIPT
        )

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[a-z][a-z0-9._-]*")) })
    }

    @Test
    fun pluginHookBaseValidatesItsDescriptor() {
        val hook = object : KSLPluginHook("example", "ExamplePlugin") {}
        assertEquals("example", hook.hookId)
        assertEquals("ExamplePlugin", hook.pluginName)
        assertTrue(hook is KSLHook)

        assertThrows(IllegalArgumentException::class.java) {
            object : KSLPluginHook("Bad ID", "ExamplePlugin") {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            object : KSLPluginHook("example", " ") {}
        }
    }

    @Test
    fun vaultRejectsNegativeAndNonFiniteAmounts() {
        assertFalse(isValidVaultAmount(-0.01))
        assertFalse(isValidVaultAmount(Double.NaN))
        assertFalse(isValidVaultAmount(Double.POSITIVE_INFINITY))
        assertTrue(isValidVaultAmount(0.0))
        assertTrue(isValidVaultAmount(12.5))
    }
}
