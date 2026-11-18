package ru.privateserver.ksl.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.KSLCommandPolicy

class KSLCommandPolicyTest {
    @Test
    fun commandNamesNormalizeSlashesNamespacesAndCase() {
        assertEquals("op", KSLCommandPolicy.commandName("/Minecraft:OP Steve"))
        assertEquals("stop", KSLCommandPolicy.commandName("//bukkit:STOP"))
        assertEquals("say", KSLCommandPolicy.commandName("  say hello"))
    }

    @Test
    fun blockedCommandsCannotBeRunRegardlessOfSandboxMode() {
        val blocked = setOf("op", "stop")

        assertTrue(KSLCommandPolicy.isBlocked("op Steve", blocked))
        assertTrue(KSLCommandPolicy.isBlocked("//minecraft:STOP", blocked))
        assertFalse(KSLCommandPolicy.isBlocked("say hello", blocked))
        assertFalse(KSLCommandPolicy.isBlocked("opme Steve", blocked))
    }

    @Test
    fun sandboxedScriptsRequireAnExplicitAllowList() {
        val blocked = setOf("op", "stop")
        val allowed = setOf("say", "tell")

        assertTrue(KSLCommandPolicy.isAllowedForScript("say hello", true, blocked, allowed))
        assertFalse(KSLCommandPolicy.isAllowedForScript("give Steve stone", true, blocked, allowed))
        assertFalse(KSLCommandPolicy.isAllowedForScript("op Steve", true, blocked, allowed))
        assertTrue(KSLCommandPolicy.isAllowedForScript("give Steve stone", false, blocked, allowed))
        assertFalse(KSLCommandPolicy.isAllowedForScript("stop", false, blocked, allowed))
    }
}
