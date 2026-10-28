package ru.privateserver.ksl

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class KSLGuiInstance(
    val scriptName: String,
    private val plugin: KotlinScriptLoaderPlugin,
    requestedRows: Int,
    title: String
) {
    // createInventory accepts 9..54 slots only; anything else would throw after the script already ran half-way.
    val rows: Int = requestedRows.coerceIn(1, 6)
    val size = rows * 9
    val inventory: Inventory
    private val holder = KSLGuiHolder(this)

    private val clickHandlers = arrayOfNulls<(Player, InventoryClickEvent) -> Unit>(size)
    private val allowedMovement = BooleanArray(size)
    private var closeHandler: ((Player) -> Unit)? = null

    private var pageItems: List<Any> = emptyList()
    private var pageSlots: IntArray = IntArray(0)
    private var pageIndex = 0
    private var pageRender: ((Any) -> ItemStack)? = null
    private var pageClick: ((Player, Any) -> Unit)? = null
    private var prevSlot = -1
    private var nextSlot = -1

    init {
        if (rows != requestedRows) {
            plugin.logger.warning("[$scriptName] gui: rows=$requestedRows вне 1..6 — использую $rows")
        }
        if (!Bukkit.isPrimaryThread()) {
            throw IllegalStateException(
                "gui() вызван не из основного потока сервера — Bukkit Inventory можно создавать только на main thread. " +
                    "Если ты вызываешь gui() внутри runAsync/delayAsync, оберни это в runSync { gui(...) ... }."
            )
        }
        val component = try {
            MiniMessage.miniMessage().deserialize(title)
        } catch (ex: Throwable) {
            plugin.logger.warning("[$scriptName] Не удалось разобрать MiniMessage-заголовок GUI ('$title'): ${ex.message} — использую как обычный текст")
            Component.text(title)
        }
        inventory = Bukkit.createInventory(holder, size, component)
        holder.bind(inventory)
    }

    fun set(slot: Int, item: ItemStack, onClick: ((Player, InventoryClickEvent) -> Unit)? = null) {
        if (slot !in 0 until size) {
            plugin.logger.warning("[$scriptName] gui: слот $slot вне 0..${size - 1} — предмет не добавлен")
            return
        }
        inventory.setItem(slot, item)
        clickHandlers[slot] = onClick
    }

    // Same as set(), minus the event parameter most click handlers ignore.
    fun button(slot: Int, item: ItemStack, onClick: (Player) -> Unit) = set(slot, item) { player, _ -> onClick(player) }

    fun clear(slot: Int) {
        if (slot !in 0 until size) return
        inventory.setItem(slot, null)
        clickHandlers[slot] = null
    }

    fun allowItemMovement(slot: Int) {
        if (slot in 0 until size) allowedMovement[slot] = true
    }

    fun fill(item: ItemStack) {
        for (i in 0 until size) if (inventory.getItem(i) == null) inventory.setItem(i, item)
    }

    fun border(item: ItemStack) {
        for (i in 0 until size) {
            val col = i % 9
            val row = i / 9
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) inventory.setItem(i, item)
        }
    }

    fun onClose(handler: (Player) -> Unit) {
        closeHandler = handler
    }

    fun <T : Any> paginate(
        items: List<T>,
        slots: List<Int>,
        prevSlot: Int = -1,
        nextSlot: Int = -1,
        render: (T) -> ItemStack,
        onClick: (Player, T) -> Unit
    ) {
        if (slots.isEmpty()) {
            plugin.logger.warning("[$scriptName] gui paginate: список slots пуст — страницы не будет")
            return
        }
        @Suppress("UNCHECKED_CAST")
        pageItems = items as List<Any>
        pageSlots = slots.toIntArray()
        this.prevSlot = prevSlot
        this.nextSlot = nextSlot
        @Suppress("UNCHECKED_CAST")
        pageRender = render as (Any) -> ItemStack
        @Suppress("UNCHECKED_CAST")
        pageClick = onClick as (Player, Any) -> Unit
        pageIndex = 0
        renderPage()
    }

    private fun renderPage() {
        val render = pageRender ?: return
        try {
            val perPage = pageSlots.size
            val from = pageIndex * perPage
            pageSlots.forEach { slot -> clear(slot) }
            for (i in pageSlots.indices) {
                val itemIndex = from + i
                if (itemIndex >= pageItems.size) break
                val entry = pageItems[itemIndex]
                set(pageSlots[i], render(entry)) { player, _ -> pageClick?.invoke(player, entry) }
            }
            if (prevSlot >= 0) {
                if (pageIndex > 0) set(prevSlot, ItemStack(Material.ARROW)) { _, _ -> prevPage() }
                else clear(prevSlot)
            }
            if (nextSlot >= 0) {
                if (from + perPage < pageItems.size) set(nextSlot, ItemStack(Material.ARROW)) { _, _ -> nextPage() }
                else clear(nextSlot)
            }
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "gui paginate render", ex)
        }
    }

    val pageCount: Int
        get() = if (pageSlots.isEmpty()) 0 else maxOf(1, (pageItems.size + pageSlots.size - 1) / pageSlots.size)

    fun showPage(index: Int) {
        if (pageSlots.isEmpty()) return
        pageIndex = index.coerceIn(0, pageCount - 1)
        renderPage()
    }

    private fun nextPage() {
        pageIndex++
        renderPage()
    }

    private fun prevPage() {
        if (pageIndex > 0) pageIndex--
        renderPage()
    }

    // Opening an inventory from an async thread throws, and opening one inside a click event can lose the cursor item,
    // so both cases are deferred by a tick.
    fun open(player: Player) {
        if (Bukkit.isPrimaryThread() && clickDepth == 0) {
            player.openInventory(inventory)
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) player.openInventory(inventory) })
        }
    }

    // Called when the owning script unloads: drops every lambda so a viewer's open inventory cannot keep the old classloader alive.
    internal fun dispose() {
        clickHandlers.fill(null)
        closeHandler = null
        pageRender = null
        pageClick = null
        pageItems = emptyList()
    }

    internal fun handleClick(event: InventoryClickEvent) {
        // A double-click on an allowed slot gathers matching items from every other slot, locked ones included.
        if (event.action == InventoryAction.COLLECT_TO_CURSOR && !allowedMovement.all { it }) event.isCancelled = true
        val slot = event.rawSlot
        if (slot !in 0 until size) {
            // Shift-click and double-click in the player's own inventory move items into or out of the GUI.
            if (event.isShiftClick || event.action == InventoryAction.COLLECT_TO_CURSOR) event.isCancelled = true
            return
        }
        if (!allowedMovement[slot]) event.isCancelled = true
        val handler = clickHandlers[slot] ?: return
        val player = event.whoClicked as? Player ?: return
        clickDepth++
        try {
            handler(player, event)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "gui click slot $slot", ex)
        } finally {
            clickDepth--
        }
    }

    internal fun handleDrag(event: InventoryDragEvent) {
        if (event.rawSlots.any { it in 0 until size && !allowedMovement[it] }) event.isCancelled = true
    }

    internal fun handleClose(player: Player) {
        try {
            closeHandler?.invoke(player)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "gui onClose", ex)
        }
    }

    private companion object {
        // Click handlers run on the main thread only, so a plain counter is enough.
        var clickDepth = 0
    }
}
