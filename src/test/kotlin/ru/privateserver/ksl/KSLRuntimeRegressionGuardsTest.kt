package ru.privateserver.ksl

import ru.privateserver.ksl.gui.KSLGuiLayout
import ru.privateserver.ksl.storage.KSLPersistentTagKey

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.dsl.isMobDeathEntity
import java.lang.reflect.Proxy

class KSLRuntimeRegressionGuardsTest {
    @Test
    fun `PDC tag paths are script scoped and key normalized`() {
        val alpha = KSLPersistentTagKey.path("alpha.kts", "Join_Count")
        val beta = KSLPersistentTagKey.path("beta.kts", "join_count")
        assertTrue(alpha.endsWith("/join_count"))
        assertFalse(alpha == beta)
        assertFalse(runCatching { KSLPersistentTagKey.path("alpha.kts", "bad key") }.isSuccess)
    }

    @Test
    fun `PDC paths use stable long fingerprints and stay within key bounds`() {
        val scriptName = "folder/" + "a".repeat(180) + ".kts"
        val first = KSLPersistentTagKey.path(scriptName, "custom/value")
        val second = KSLPersistentTagKey.path(scriptName, "custom/value")
        val other = KSLPersistentTagKey.path(scriptName + "2", "custom/value")

        assertTrue(first == second)
        assertFalse(first == other)
        assertTrue(first.length < 256)
    }

    @Test
    fun `mob death alias excludes players`() {
        val player = fakeProxy(Player::class.java)
        val mob = fakeProxy(LivingEntity::class.java)
        assertFalse(isMobDeathEntity(player))
        assertTrue(isMobDeathEntity(mob))
    }

    @Test
    fun `bukkit task cancellation is restricted to the owning script`() {
        val tasks = mapOf("alpha" to setOf(10, 11), "beta" to setOf(20))
        assertTrue(isTaskOwnedBy("alpha", 10, tasks))
        assertFalse(isTaskOwnedBy("alpha", 20, tasks))
        assertFalse(isTaskOwnedBy("missing", 10, tasks))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fakeProxy(type: Class<T>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> '\u0000'
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        } as T
    @Test
    fun `GUI pagination rejects out of range slots`() {
        assertFalse(KSLGuiLayout.validationError(27, listOf(0, 27), -1, -1).isNullOrEmpty())
    }

    @Test
    fun `GUI pagination rejects duplicate content and navigation slots`() {
        assertFalse(KSLGuiLayout.validationError(27, listOf(1, 1), -1, -1).isNullOrEmpty())
        assertFalse(KSLGuiLayout.validationError(27, listOf(1, 2), 2, -1).isNullOrEmpty())
        assertFalse(KSLGuiLayout.validationError(27, listOf(1, 2), 25, 25).isNullOrEmpty())
    }

    @Test
    fun `GUI pagination accepts a valid independent layout`() {
        assertTrue(KSLGuiLayout.validationError(27, listOf(10, 11, 12), 18, 26) == null)
    }

}
