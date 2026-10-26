package ru.privateserver.ksl

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags
import org.bukkit.Location
import org.bukkit.entity.Player

class KSLWorldGuardHook {

    fun regionsAt(location: Location): List<String> = KSLErrors.hookSafe("WorldGuard", emptyList<String>()) {
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        val weLoc = BukkitAdapter.adapt(location)
        query.getApplicableRegions(weLoc).map { it.id }
    }

    fun isInRegion(location: Location, regionId: String): Boolean = KSLErrors.hookSafe("WorldGuard", false) {
        regionsAt(location).any { it.equals(regionId, ignoreCase = true) }
    }

    fun canBuild(player: Player, location: Location): Boolean = KSLErrors.hookSafe("WorldGuard", true) {
        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        query.testState(BukkitAdapter.adapt(location), localPlayer, Flags.BUILD)
    }

    fun canPvp(player: Player, location: Location): Boolean = KSLErrors.hookSafe("WorldGuard", true) {
        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        query.testState(BukkitAdapter.adapt(location), localPlayer, Flags.PVP)
    }

    fun flagValue(location: Location, flagName: String, player: Player? = null): String? =
        KSLErrors.hookSafe("WorldGuard", null) {
            val flag = WorldGuard.getInstance().flagRegistry[flagName] ?: return@hookSafe null
            val localPlayer = player?.let { WorldGuardPlugin.inst().wrapPlayer(it) }
            val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
            query.queryValue(BukkitAdapter.adapt(location), localPlayer, flag)?.toString()
        }
}
