package ru.privateserver.ksl.std.world

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity

/** Gets a block using integer block coordinates. */
fun World.blockAt(x: Int, y: Int, z: Int): Block = getBlockAt(x, y, z)

/** Gets a block at this location, or null when the location has no world. */
fun Location.blockOrNull(): Block? = world?.getBlockAt(blockX, blockY, blockZ)

/** Returns a copy of this location shifted by the supplied offsets. */
fun Location.offset(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0): Location =
    clone().add(x, y, z)

/** True when both locations belong to the same world and are within the radius. */
fun Location.isWithin(other: Location, radius: Double): Boolean {
    require(radius >= 0.0 && radius.isFinite()) { "radius must be finite and non-negative" }
    return world != null && world === other.world && distanceSquared(other) <= radius * radius
}

/** Checks whether a block is one of the requested materials. */
fun Block.isTypeAny(vararg materials: Material): Boolean = materials.any { type == it }

/** Returns entities in an axis-aligned box around the center. Must be called on the server thread. */
fun World.entitiesInBox(box: BoundingBox): List<Entity> =
    getNearbyEntities(box).toList()

/** Returns entities within an axis-aligned cube around a location. Must be called on the server thread. */
fun World.entitiesInBox(center: Location, x: Double, y: Double = x, z: Double = x): List<Entity> {
    require(x >= 0.0 && y >= 0.0 && z >= 0.0 && x.isFinite() && y.isFinite() && z.isFinite()) {
        "box radii must be finite and non-negative"
    }
    require(center.world === this) { "center must belong to this world" }
    return getNearbyEntities(center, x, y, z).toList()
}

/** Inclusive axis-aligned region test; locations in another world never match. */
fun Location.isInside(min: Location, max: Location): Boolean {
    if (world == null || world !== min.world || world !== max.world) return false
    val minX = min.x().coerceAtMost(max.x())
    val minY = min.y().coerceAtMost(max.y())
    val minZ = min.z().coerceAtMost(max.z())
    val maxX = min.x().coerceAtLeast(max.x())
    val maxY = min.y().coerceAtLeast(max.y())
    val maxZ = min.z().coerceAtLeast(max.z())
    return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}
