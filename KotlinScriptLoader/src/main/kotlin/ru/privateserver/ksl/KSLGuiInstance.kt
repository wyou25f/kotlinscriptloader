package ru.privateserver.ksl

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class KSLGuiInstance(
    val scriptName: String,
    val rows: Int,
    title: String
) {
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
        inventory = Bukkit.createInventory(holder, size, MiniMessage.miniMessage().deserialize(title))
        holder.bind(inventory)
    }

    fun set(slot: Int, item: ItemStack, onClick: ((Player, InventoryClickEvent) -> Unit)? = null) {
        if (slot !in 0 until size) return
        inventory.setItem(slot, item)
        clickHandlers[slot] = onClick
    }

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
            if (pageIndex > 0) set(prevSlot, ItemStack(Material.ARROW)) { player, _ -> prevPage() }
            else clear(prevSlot)
        }
        if (nextSlot >= 0) {
            if (from + perPage < pageItems.size) set(nextSlot, ItemStack(Material.ARROW)) { player, _ -> nextPage() }
            else clear(nextSlot)
        }
    }

    private fun nextPage() {
        pageIndex++
        renderPage()
    }

    private fun prevPage() {
        if (pageIndex > 0) pageIndex--
        renderPage()
    }

    fun open(player: Player) {
        player.openInventory(inventory)
    }

    internal fun handleClick(event: InventoryClickEvent) {
        val slot = event.rawSlot
        if (slot !in 0 until size) return
        if (!allowedMovement[slot]) event.isCancelled = true
        val handler = clickHandlers[slot] ?: return
        val player = event.whoClicked as? Player ?: return
        handler(player, event)
    }

    internal fun handleClose(player: Player) {
        closeHandler?.invoke(player)
    }
}
