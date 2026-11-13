import ru.privateserver.ksl.api.*

/*
 * IntelliJ smoke test for the KSL script definition.
 * Hover over public functions/extensions to inspect KDoc.
 * Ctrl/Cmd-click API and extension names to navigate to declarations.
 * Try completion after "api.", "player?.", "world." and "location?."
 */

val api = kslOrNull
if (api != null) {
    api.scripts().forEach { script ->
        println("${script.id}: ${script.loaded}")
    }
}

val shortLabel = "KotlinScriptLoader".truncateCodePoints(12)
val ticks = 5.secondsToTicks()

val player = Bukkit.getOnlinePlayers().firstOrNull()
player?.tellRich("<green>KSL completion is available")
val canManage = player?.hasAnyPermission("ksl.admin", "ksl.manage") ?: false
val inventoryFull = player?.isInventoryFull() ?: false

Bukkit.getWorld("world")?.let { world ->
    val block = world.blockAt(0, 64, 0)
    val nearby = world.entitiesInBox(Location(world, 0.0, 64.0, 0.0), 8.0)
    val shifted = Location(world, 0.0, 64.0, 0.0).offset(y = 1.0)
    val inside = shifted.isInside(
        Location(world, -8.0, 0.0, -8.0),
        Location(world, 8.0, 128.0, 8.0)
    )
}
