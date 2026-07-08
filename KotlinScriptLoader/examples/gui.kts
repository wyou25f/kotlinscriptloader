var opens by persist("shop_opens") { 0 }

val shopItems = listOf(
    Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT, Material.IRON_INGOT,
    Material.NETHERITE_INGOT, Material.STICK, Material.APPLE, Material.BREAD,
    Material.BOW, Material.SHIELD, Material.TOTEM_OF_UNDYING, Material.ENDER_PEARL
)

registerCommand("shop") { player, args ->
    opens += 1

    val menu = gui("<gradient:#a29bfe:#6c5ce7>Магазин</gradient>", rows = 4) {
        border(ItemStack(Material.BLACK_STAINED_GLASS_PANE))

        set(31, ItemStack(Material.BARRIER)) { p, _ -> p.closeInventory() }

        paginate(
            items = shopItems,
            slots = (10..16).toList(),
            prevSlot = 28,
            nextSlot = 34,
            render = { material -> ItemStack(material) },
            onClick = { p, material ->
                p.sendRichMessage("<green>Ты выбрал ${material.name}")
                p.closeInventory()
            }
        )

        onClose { p -> p.sendActionBarMessage("<gray>Магазин закрыт") }
    }

    menu.open(player)
    player.sendActionBarMessage("<gray>Магазин открывали <white>$opens<gray> раз(а)")
}
