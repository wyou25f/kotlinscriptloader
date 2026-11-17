package ru.privateserver.ksl.nbt

import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class KSLNBTCompoundTest {
    @Test
    fun `reads and writes scalar values`() {
        val nbt = KSLNBTCompound(fakeNbt())
        nbt.setString("owner", "Alex")
        nbt.setInt("level", 7)
        nbt.setDouble("bonus", 1.25)
        nbt.setBoolean("enabled", true)
        assertEquals("Alex", nbt.string("owner"))
        assertEquals(7, nbt.int("level"))
        assertEquals(1.25, nbt.double("bonus"))
        assertEquals(true, nbt.boolean("enabled"))
        assertNull(nbt.string("missing"))
        assertEquals(setOf("owner", "level", "bonus", "enabled"), nbt.keys())
    }

    @Test
    fun `creates nested compounds and removes keys`() {
        val nbt = KSLNBTCompound(fakeNbt())
        nbt.getOrCreateCompound("profile").setString("rank", "builder")
        assertEquals("builder", nbt.compound("profile")?.string("rank"))
        assertTrue(nbt.remove("profile"))
        assertFalse(nbt.remove("profile"))
        assertNull(nbt.compound("profile"))
    }


    @Test
    fun nestedPathHelpersReadAndWriteCompoundLeaves() {
        val nbt = KSLNBTCompound(fakeNbt())
        nbt.setStringAt("profile.stats.rank", "builder")
        nbt.setIntAt("profile.stats.level", 7)

        assertEquals("builder", nbt.stringAt("profile.stats.rank"))
        assertEquals(7, nbt.intAt("profile.stats.level"))
        assertEquals("builder", nbt.compoundAt("profile.stats")?.string("rank"))
        assertNull(nbt.stringAt("profile.missing.value"))
    }

    @Test
    fun nestedPathHelpersRejectUnsafeSegments() {
        val nbt = KSLNBTCompound(fakeNbt())
        assertThrows(IllegalArgumentException::class.java) { nbt.setStringAt("../outside", "x") }
        assertThrows(IllegalArgumentException::class.java) { nbt.intAt("profile..level") }
    }

    private fun fakeNbt(values: MutableMap<String, Any?> = linkedMapOf()): ReadWriteNBT {
        val handler = InvocationHandler { proxy, method, arguments ->
            val args = arguments ?: emptyArray()
            val key = args.firstOrNull() as? String
            when (method.name) {
                "getKeys" -> values.keys.toSet()
                "hasTag" -> values.containsKey(key)
                "getString" -> values[key] as? String
                "getInteger" -> values[key] as? Int ?: 0
                "getDouble" -> values[key] as? Double ?: 0.0
                "getBoolean" -> values[key] as? Boolean ?: false
                "setString", "setInteger", "setDouble", "setBoolean" -> { values[key!!] = args[1]; null }
                "getCompound" -> values[key] as? ReadWriteNBT
                "getOrCreateCompound" -> values.getOrPut(key!!) { fakeNbt() } as ReadWriteNBT
                "removeKey" -> { values.remove(key); null }
                "toString" -> values.toString()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args.firstOrNull()
                else -> error("Unexpected NBT test method: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            ReadWriteNBT::class.java.classLoader,
            arrayOf(ReadWriteNBT::class.java),
            handler
        ) as ReadWriteNBT
    }
}
