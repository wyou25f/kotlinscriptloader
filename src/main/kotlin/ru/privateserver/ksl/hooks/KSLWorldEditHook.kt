package ru.privateserver.ksl.hooks

import ru.privateserver.ksl.*
import ru.privateserver.ksl.schem.KSLClipboard
import ru.privateserver.ksl.storage.KSLAtomicFileWriter
import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.session.ClipboardHolder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

open class KSLWorldEditHook : KSLHook {

    override val hookId = KSLHookIds.WORLD_EDIT
    override val pluginName = "WorldEdit"

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

    /** Copies the player's current WorldEdit selection into a detached clipboard. */
    fun captureSelection(player: Player): KSLClipboard? = KSLErrors.hookSafe("WorldEdit", null) {
        val actor = BukkitAdapter.adapt(player)
        val session = WorldEdit.getInstance().sessionManager.get(actor)
        val world = session.selectionWorld ?: return@hookSafe null
        val region = try {
            session.getSelection(world)
        } catch (ex: IncompleteRegionException) {
            return@hookSafe null
        }
        validateRegion(region)
        val clipboard = BlockArrayClipboard(region)
        Operations.complete(ForwardExtentCopy(world, region, clipboard, region.minimumPoint))
        wrapClipboard(clipboard)
    }

    /** Loads a Sponge .schem file. Null means the file does not exist. */
    fun loadSchematic(file: File): KSLClipboard? = KSLErrors.hookSafe("WorldEdit", null) {
        if (!file.exists()) return@hookSafe null
        require(file.isFile) { "Schematic path is not a regular file: $file" }
        require(file.length() in 1..MAX_SCHEMATIC_FILE_BYTES) {
            "Schematic file must be between 1 byte and $MAX_SCHEMATIC_FILE_BYTES bytes"
        }
        val format = ClipboardFormats.findByFile(file)
            ?: throw IllegalArgumentException("WorldEdit не распознал файл '${file.name}'. Используй Sponge .schem")
        val clipboard = FileInputStream(file).use { input ->
            format.getReader(input).use { reader -> reader.read() }
        }
        wrapClipboard(clipboard)
    }

    /** Saves a clipboard as the Sponge .schem format using an atomic file replacement. */
    fun saveSchematic(file: File, clipboard: KSLClipboard): Boolean =
        KSLErrors.hookSafe("WorldEdit", false) {
            val native = requireNativeClipboard(clipboard)
            validateRegion(native.region)
            val format = ClipboardFormats.findByFile(file)
                ?: throw IllegalArgumentException("WorldEdit не распознал расширение '${file.name}'. Используй .schem")
            KSLAtomicFileWriter.writeFile(file) { temporary ->
                FileOutputStream(temporary).use { output ->
                    format.getWriter(output).use { writer -> writer.write(native) }
                }
            }
            true
        }

    /** Pastes the clipboard at the requested block position and records it in WorldEdit's undo history. */
    fun pasteSchematic(
        player: Player,
        clipboard: KSLClipboard,
        location: Location,
        ignoreAirBlocks: Boolean = false
    ): Boolean = KSLErrors.hookSafe("WorldEdit", false) {
        require(location.world != null) { "Schematic paste location must have a world" }
        val native = requireNativeClipboard(clipboard)
        validateRegion(native.region)
        val actor = BukkitAdapter.adapt(player)
        val session = WorldEdit.getInstance().sessionManager.get(actor)
        val editSession = session.createEditSession(actor)
        try {
            val operation = ClipboardHolder(native)
                .createPaste(editSession)
                .to(BlockVector3.at(location.blockX, location.blockY, location.blockZ))
                .ignoreAirBlocks(ignoreAirBlocks)
                .build()
            Operations.complete(operation)
        } finally {
            editSession.close()
        }
        runCatching { session.remember(editSession) }
        true
    }

    private fun requireNativeClipboard(clipboard: KSLClipboard): Clipboard =
        clipboard.nativeClipboard as? Clipboard
            ?: throw IllegalArgumentException("Clipboard не принадлежит совместимому WorldEdit API")

    private fun validateRegion(region: Region) {
        val min = region.minimumPoint
        val max = region.maximumPoint
        val width = max.x.toLong() - min.x.toLong() + 1L
        val height = max.y.toLong() - min.y.toLong() + 1L
        val length = max.z.toLong() - min.z.toLong() + 1L
        require(width in 1..Int.MAX_VALUE.toLong() &&
            height in 1..Int.MAX_VALUE.toLong() &&
            length in 1..Int.MAX_VALUE.toLong()) { "Размеры schematic выходят за допустимый диапазон" }
        val volume = Math.multiplyExact(Math.multiplyExact(width, height), length)
        require(volume <= MAX_SCHEMATIC_BLOCKS) {
            "Schematic содержит $volume блоков; лимит KSL — $MAX_SCHEMATIC_BLOCKS"
        }
    }

    private fun wrapClipboard(clipboard: Clipboard): KSLClipboard {
        val region = clipboard.region
        validateRegion(region)
        val min = region.minimumPoint
        val max = region.maximumPoint
        val width = (max.x.toLong() - min.x.toLong() + 1L).toInt()
        val height = (max.y.toLong() - min.y.toLong() + 1L).toInt()
        val length = (max.z.toLong() - min.z.toLong() + 1L).toInt()
        val volume = width.toLong() * height.toLong() * length.toLong()
        return KSLClipboard(
            nativeClipboard = clipboard,
            width = width,
            height = height,
            length = length,
            blockCount = volume,
            minimumX = min.x,
            minimumY = min.y,
            minimumZ = min.z
        )
    }

    private companion object {
        const val MAX_SCHEMATIC_FILE_BYTES = 32L * 1024L * 1024L
        const val MAX_SCHEMATIC_BLOCKS = 2_000_000L
    }
}
