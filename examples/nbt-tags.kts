import java.util.UUID

// Player PDC is owned by this script and survives ordinary player-data saves.
onEvent<PlayerJoinEvent> {
    val visits = player.getIntTag("visits") ?: 0
    player.setIntTag("visits", visits + 1)
    player.sendMessage("Ваш визит №${visits + 1}")
}

// ItemStack PDC travels with the item when it is moved or stored normally.
registerCommand("daily-token") { player, _ ->
    val token = ItemStack(Material.PAPER)
    token.setStringTag("kind", "daily")
    token.setBooleanTag("claimed", false)
    token.setUuidTag("token_id", UUID.randomUUID())

    player.inventory.addItem(token)
    player.sendMessage("Вы получили ежедневный жетон.")
}
