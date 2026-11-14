registerCommand("createshop", permission = "ksl.shop.create") { player, _ ->
    spawnShopkeeper(player.location, "<gold><bold>Рыночный торговец</bold></gold>") {
        profession = Villager.Profession.LIBRARIAN
        villagerType = Villager.Type.PLAINS
        level = 5
        experience = 1
        aiEnabled = false
        invulnerable = true
        silent = true

        trade(
            cost = ItemStack(Material.EMERALD, 8),
            result = ItemStack(Material.DIAMOND, 1)
        )
        trade(
            cost = ItemStack(Material.GOLD_INGOT, 16),
            result = ItemStack(Material.GOLDEN_APPLE, 1),
            maxUses = 64
        )
    }
    player.sendRichMessage("<green>Торговец создан. Нажми по нему ПКМ, чтобы открыть сделки.")
}
