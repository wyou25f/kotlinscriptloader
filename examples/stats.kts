val kills = table("player_stats")

val minKillsForVip = config.getOrSetDefault("min-kills-for-vip", 10)

onEvent<org.bukkit.event.player.PlayerJoinEvent> {
    val rank = if (kills.getInt(player.uniqueId.toString(), "kills") >= minKillsForVip) "VIP" else "игрок"
    broadcast(config.message("welcome", player, "rank" to rank))
}

onEvent<org.bukkit.event.player.PlayerQuitEvent> {
    config.messageList("farewell-list", player)
        .forEach { line -> broadcast(line) }
}

registerCommand("kills") { player, args ->
    val target = args.firstOrNull() ?: player.name
    val count = kills.getInt(target, "kills")
    player.sendRichMessage("<gray>Убийства <white>$target<gray>: <yellow>$count")
}

onEvent<org.bukkit.event.entity.PlayerDeathEvent> {
    val killer = entity.killer ?: return@onEvent
    val total = kills.increment(killer.uniqueId.toString(), "kills")
    if (total.toInt() == minKillsForVip) {
        broadcastMM("<gold>${killer.name} <yellow>достиг $minKillsForVip убийств и получил VIP!")
    }
}
