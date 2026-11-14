package ru.privateserver.ksl

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.dsl.isMobDeathEntity
import java.lang.reflect.Proxy

class KSLRuntimeRegressionGuardsTest {
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
}
