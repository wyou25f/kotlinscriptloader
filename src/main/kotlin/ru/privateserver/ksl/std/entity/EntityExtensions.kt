package ru.privateserver.ksl.std.entity

import org.bukkit.Location
import org.bukkit.entity.Entity

/** Returns a detached copy of the entity's current location. */
fun Entity.locationSnapshot(): Location = location.clone()

/**
 * Returns squared distance to [target]. Both locations must belong to the same non-null world.
 * Squared distance avoids the square-root cost when comparing distances.
 */
fun Entity.distanceSquaredTo(target: Location): Double {
    val current = location
    require(current.world != null && current.world === target.world) {
        "entity and target must belong to the same non-null world"
    }
    return current.distanceSquared(target)
}

/**
 * Checks whether this entity is within [radius] of [target].
 * Returns false for different worlds; invalid radii are rejected.
 */
fun Entity.isWithin(target: Location, radius: Double): Boolean {
    require(radius >= 0.0 && radius.isFinite()) { "radius must be finite and non-negative" }
    val current = location
    return current.world != null &&
        current.world === target.world &&
        current.distanceSquared(target) <= radius * radius
}

/** Checks the entity's current world name, ignoring case and surrounding whitespace. */
fun Entity.isInWorld(worldName: String): Boolean =
    worldName.isNotBlank() && world.name.equals(worldName.trim(), ignoreCase = true)

/**
 * Teleports this entity to a copy of [destination].
 * The destination must belong to a loaded World object; Bukkit teleportation must run on the server thread.
 */
fun Entity.teleportTo(destination: Location): Boolean {
    require(destination.world != null) { "destination must belong to a world" }
    return teleport(destination.clone())
}
