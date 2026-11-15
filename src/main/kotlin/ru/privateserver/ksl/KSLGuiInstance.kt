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
    @Volatile private var disposed = false

    init {
        if (rows != requestedRows) {
            plugin.logger.warning("[$scriptName] ${KSLError.GUI_BAD_ROWS.tag} gui: rows=$requestedRows вне 1..6 — использую $rows")
        }
        if (!Bukkit.isPrimaryThread()) {
            throw IllegalStateException(
                "${KSLError.GUI_WRONG_THREAD.tag} gui() вызван не из основного потока сервера — Bukkit Inventory можно создавать только на main thread. " +
                    "Если ты вызываешь gui() внутри runAsync/delayAsync, оберни это в runSync { gui(...) ... }."
            )
        }
        val component = try {
            MiniMessage.miniMessage().deserialize(title)
        } catch (ex: Throwable) {
            plugin.logger.warning("[$scriptName] ${KSLError.GUI_BAD_TITLE.tag} Не удалось разобрать MiniMessage-заголовок GUI ('$title'): ${ex.message} — использую как обычный текст")
            Component.text(title)
        }
        inventory = Bukkit.createInventory(holder, size, component)
        holder.bind(inventory)
    }

    private fun canMutate(operation: String): Boolean {
        if (disposed) {
            plugin.logger.warning("[$scriptName] GUI уже выгружен — вызов $operation проигнорирован")
            return false
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.logger.warning("[$scriptName] ${KSLError.GUI_WRONG_THREAD.tag} gui.$operation должен вызываться из основного потока; используйте runSync")
            return false
        }
        return true
    }

    fun set(slot: Int, item: ItemStack, onClick: ((Player, InventoryClickEvent) -> Unit)? = null) {
        if (!canMutate("set")) return
        if (slot !in 0 until size) {
            plugin.logger.warning("[$scriptName] ${KSLError.GUI_BAD_SLOT.tag} gui: слот $slot вне 0..${size - 1} — предмет не добавлен")
            return
        }
        inventory.setItem(slot, item.clone())
        clickHandlers[slot] = onClick
        allowedMovement[slot] = false
    }

    fun button(slot: Int, item: ItemStack, onClick: (Player) -> Unit) =
        set(slot, item) { player, _ -> onClick(player) }

    fun clear(slot: Int) {
        if (!canMutate("clear") || slot !in 0 until size) return
        inventory.setItem(slot, null)
        clickHandlers[slot] = null
        allowedMovement[slot] = false
    }

    fun allowItemMovement(slot: Int) {
        if (!canMutate("allowItemMovement")) return
        if (slot in 0 until size) allowedMovement[slot] = true
    }

    fun fill(item: ItemStack) {
        if (!canMutate("fill")) return
        for (i in 0 until size) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item.clone())
                clickHandlers[i] = null
                allowedMovement[i] = false
            }
        }
    }

    fun border(item: ItemStack) {
        if (!canMutate("border")) return
        for (i in 0 until size) {
            val col = i % 9
            val row = i / 9
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                inventory.setItem(i, item.clone())
                clickHandlers[i] = null
                allowedMovement[i] = false
            }
        }
    }

    fun onClose(handler: (Player) -> Unit) {
        if (canMutate("onClose")) closeHandler = handler
    }

    fun <T : Any> paginate(
        items: List<T>,
        slots: List<Int>,
        prevSlot: Int = -1,
        nextSlot: Int = -1,
        render: (T) -> ItemStack,
        onClick: (Player, T) -> Unit
    ) {
        if (!canMutate("paginate")) return
        val slotSnapshot = slots.toList()
        val validationError = KSLGuiLayout.validationError(size, slotSnapshot, prevSlot, nextSlot)
        if (validationError != null) {
            plugin.logger.warning("[$scriptName] ${KSLError.GUI_BAD_SLOT.tag} gui paginate: $validationError — пагинация не изменена")
            return
        }

        // Remove the previous page's items and handlers only after the new layout is known to be valid.
        (pageSlots.toList() + listOf(this.prevSlot, this.nextSlot))
            .filter { it in 0 until size }
            .distinct()
            .forEach(::clear)

        // Snapshot the caller's list: later external mutations must not silently change menu pages.
        pageItems = items.toList()
        pageSlots = slotSnapshot.toIntArray()
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
        if (disposed) return
        val render = pageRender ?: return
        try {
            val perPage = pageSlots.size
            if (perPage == 0) return
            val from = pageIndex * perPage
            pageSlots.forEach(::clear)
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
            KSLErrors.log(plugin, scriptName, "gui paginate render", ex, KSLError.GUI_CLICK_FAILED)
        }
    }

    val pageCount: Int
        get() = if (pageSlots.isEmpty()) 0 else maxOf(1, (pageItems.size + pageSlots.size - 1) / pageSlots.size)

    fun showPage(index: Int) {
        if (!canMutate("showPage") || pageSlots.isEmpty()) return
        if (index !in 0 until pageCount) {
            plugin.logger.warning("[$scriptName] ${KSLError.GUI_BAD_SLOT.tag} gui.showPage: индекс $index вне 0..${pageCount - 1} — текущая страница сохранена")
            return
        }
        pageIndex = index
        renderPage()
    }

    fun refresh() {
        if (canMutate("refresh")) renderPage()
    }

    private fun nextPage() {
        if (disposed || pageIndex + 1 >= pageCount) return
        pageIndex++
        renderPage()
    }

    private fun prevPage() {
        if (disposed || pageIndex <= 0) return
        pageIndex--
        renderPage()
    }

    fun open(player: Player) {
        if (disposed) {
            plugin.logger.warning("[$scriptName] ${KSLError.GUI_CLICK_FAILED.tag} Нельзя открыть GUI после выгрузки скрипта")
            return
        }
        if (Bukkit.isPrimaryThread() && clickDepth == 0) {
            openNow(player)
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, Runnable { openNow(player) })
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "gui open", ex, KSLError.GUI_CLICK_FAILED)
            }
        }
    }

    private fun openNow(player: Player) {
        if (disposed || !player.isOnline) return
        try {
            player.openInventory(inventory)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "gui open", ex, KSLError.GUI_CLICK_FAILED)
        }
    }

    internal fun dispose() {
        disposed = true
        clickHandlers.fill(null)
        closeHandler = null
        pageRender = null
        pageClick = null
        pageItems = emptyList()
    }

    internal fun handleClick(event: InventoryClickEvent) {
        if (disposed) {
            event.isCancelled = true
            return
        }
        if (event.action == InventoryAction.COLLECT_TO_CURSOR && !allowedMovement.all { it }) event.isCancelled = true
        val slot = event.rawSlot
        if (slot !in 0 until size) {
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
            KSLErrors.log(plugin, scriptName, "gui click slot $slot", ex, KSLError.GUI_CLICK_FAILED)
        } finally {
            clickDepth--
        }
    }

    internal fun handleDrag(event: InventoryDragEvent) {
        if (disposed || event.rawSlots.any { it in 0 until size && !allowedMovement[it] }) {
            event.isCancelled = true
        }
    }

    internal fun handleClose(player: Player) {
        if (disposed) return
        try {
            closeHandler?.invoke(player)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "gui onClose", ex, KSLError.GUI_CLICK_FAILED)
        }
    }

    private companion object {
        var clickDepth = 0
    }
}
