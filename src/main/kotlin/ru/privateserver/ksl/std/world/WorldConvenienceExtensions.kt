package ru.privateserver.ksl.std.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

/**
 * Returns online players in a spherical radius around [center].
 * The center must belong to this world; Bukkit world access must run on the server thread.
 */
fun World.playersInRadius(center: Location, radius: Double): List<Player> {
    require(radius >= 0.0 && radius.isFinite()) { "radius must be finite and non-negative" }
    require(center.world === this) { "center must belong to this world" }
    return getNearbyPlayers(center, radius).toList()
}

/**
 * Returns entities in a cube around [center]. Use [World.entitiesInBox] when an explicit box is needed.
 * Bukkit world access must run on the server thread.
 */
fun World.entitiesInRadius(center: Location, radius: Double): List<Entity> {
    require(radius >= 0.0 && radius.isFinite()) { "radius must be finite and non-negative" }
    require(center.world === this) { "center must belong to this world" }
    return getNearbyEntities(center, radius, radius, radius).toList()
}

/** Returns integer block coordinates for concise storage or comparisons. */
fun Location.blockPosition(): Triple<Int, Int, Int> =
    Triple(blockX, blockY, blockZ)

/** Checks whether the chunk containing this location is already loaded, without loading it. */
fun Location.isChunkLoaded(): Boolean {
    val targetWorld = world ?: return false
    return targetWorld.isChunkLoaded(blockX shr 4, blockZ shr 4)
}
