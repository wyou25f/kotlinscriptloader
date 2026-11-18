package ru.privateserver.ksl.hooks.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLCompatibilityHooksTest {
    @Test
    fun rootHookFacadesRemainAssignableToTheirCurrentImplementations() {
        assertTrue(ru.privateserver.ksl.hooks.KSLAuthMeHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLAuthMeHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLEssentialsHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLEssentialsHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLLuckPermsHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLLuckPermsHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLSkinHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLSkinHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLSkriptHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLSkriptHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLTabHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLTabHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLVaultHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLVaultHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLWorldEditHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLWorldEditHook::class.java))
        assertTrue(ru.privateserver.ksl.hooks.KSLWorldGuardHook::class.java.isAssignableFrom(ru.privateserver.ksl.KSLWorldGuardHook::class.java))
    }

    @Test
    fun reflectionCompatibilityFacadeKeepsItsRootJvmName() {
        assertEquals("ru.privateserver.ksl.KSLHookReflection", ru.privateserver.ksl.KSLHookReflection::class.java.name)
    }
}
