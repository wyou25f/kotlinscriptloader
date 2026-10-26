package ru.privateserver.ksl

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class KSLWorldEditHook {

    fun selection(player: Player): Pair<Location, Location>? = KSLErrors.hookSafe("WorldEdit", null) {
        val actor = BukkitAdapter.adapt(player)
        val session = WorldEdit.getInstance().sessionManager.get(actor)
        val world = session.selectionWorld ?: return@hookSafe null
        val region = try {
            session.getSelection(world)
        } catch (ex: IncompleteRegionException) {
            return@hookSafe null
        }
        val bukkitWorld = BukkitAdapter.adapt(world)
        val min = region.minimumPoint
        val max = region.maximumPoint
        Location(bukkitWorld, min.x.toDouble(), min.y.toDouble(), min.z.toDouble()) to
            Location(bukkitWorld, max.x.toDouble(), max.y.toDouble(), max.z.toDouble())
    }

    fun selectionVolume(player: Player): Long? = KSLErrors.hookSafe("WorldEdit", null) {
        val (min, max) = selection(player) ?: return@hookSafe null
        val dx = (max.blockX - min.blockX + 1).toLong()
        val dy = (max.blockY - min.blockY + 1).toLong()
        val dz = (max.blockZ - min.blockZ + 1).toLong()
        dx * dy * dz
    }

    fun fillSelection(player: Player, material: Material): Int? = KSLErrors.hookSafe("WorldEdit", null) {
        val actor = BukkitAdapter.adapt(player)
        val session = WorldEdit.getInstance().sessionManager.get(actor)
        val world = session.selectionWorld ?: return@hookSafe null
        val region = try {
            session.getSelection(world)
        } catch (ex: IncompleteRegionException) {
            return@hookSafe null
        }
        val blockState = BukkitAdapter.adapt(material.createBlockData())
        val editSession = session.createEditSession(actor)
        val changed = try {
            editSession.setBlocks(region, blockState)
        } finally {
            editSession.close()
        }
        runCatching { session.remember(editSession) }
        changed
    }

    fun hasClipboard(player: Player): Boolean = KSLErrors.hookSafe("WorldEdit", false) {
        val actor = BukkitAdapter.adapt(player)
        val session = WorldEdit.getInstance().sessionManager.get(actor)
        try {
            session.clipboard
            true
        } catch (ex: EmptyClipboardException) {
            false
        }
    }
}
