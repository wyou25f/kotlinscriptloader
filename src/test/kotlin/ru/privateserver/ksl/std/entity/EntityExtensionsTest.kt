package ru.privateserver.ksl.std.entity

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class EntityExtensionsTest {
    private fun world(name: String): World =
        Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { proxy, method, args ->
            when (method.name) {
                "getName" -> name
                "toString" -> name
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("Unexpected World method: ${method.name}")
            }
        } as World

    private fun entity(initial: Location): Pair<Entity, () -> Location> {
        var current = initial.clone()
        val instance = Proxy.newProxyInstance(Entity::class.java.classLoader, arrayOf(Entity::class.java)) { proxy, method, args ->
            when (method.name) {
                "getLocation" -> current.clone()
                "getWorld" -> current.world
                "teleport" -> {
                    current = (args!![0] as Location).clone()
                    true
                }
                "toString" -> "KSL test entity"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("Unexpected Entity method: ${method.name}")
            }
        } as Entity
        return instance to { current.clone() }
    }

    @Test
    fun `location snapshot is detached from entity location`() {
        val world = world("world")
        val (entity, current) = entity(Location(world, 1.0, 2.0, 3.0))
        val snapshot = entity.locationSnapshot()
        snapshot.add(10.0, 0.0, 0.0)

        assertEquals(1.0, current().x)
        assertEquals(11.0, snapshot.x)
        assertNotSame(current(), snapshot)
    }

    @Test
    fun `distance and radius require matching world identity`() {
        val world = world("world")
        val otherWorld = world("world")
        val (entity, _) = entity(Location(world, 0.0, 0.0, 0.0))

        assertEquals(25.0, entity.distanceSquaredTo(Location(world, 3.0, 4.0, 0.0)))
        assertTrue(entity.isWithin(Location(world, 3.0, 4.0, 0.0), 5.0))
        assertFalse(entity.isWithin(Location(otherWorld, 0.0, 0.0, 0.0), 5.0))
        assertThrows(IllegalArgumentException::class.java) {
            entity.distanceSquaredTo(Location(otherWorld, 0.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            entity.isWithin(Location(world, 0.0, 0.0, 0.0), Double.NaN)
        }
    }

    @Test
    fun `world name check ignores case and whitespace`() {
        val (entity, _) = entity(Location(world("Survival"), 0.0, 64.0, 0.0))
        assertTrue(entity.isInWorld(" survival "))
        assertFalse(entity.isInWorld("creative"))
        assertFalse(entity.isInWorld(" "))
    }

    @Test
    fun `teleport uses a copy of destination`() {
        val world = world("world")
        val (entity, current) = entity(Location(world, 0.0, 64.0, 0.0))
        val destination = Location(world, 10.0, 70.0, -4.0)

        assertTrue(entity.teleportTo(destination))
        assertEquals(10.0, current().x)
        assertEquals(70.0, current().y)
        assertEquals(-4.0, current().z)
        assertEquals(10.0, destination.x)
        assertThrows(IllegalArgumentException::class.java) {
            entity.teleportTo(Location(null, 0.0, 0.0, 0.0))
        }
    }
}
