val coins = service<ru.example.CoinService>("coins")
    ?: error("ExampleAddon не установлен")

registerCommand("coins") { player, args ->
    when (args.getOrNull(0)) {
        "give" -> {
            val amount = args.getOrNull(1)?.toLongOrNull() ?: 0L
            coins.give(player, amount)
            player.sendRichMessage("<green>+$amount монет. Итого: ${coins.get(player)}")
        }
        "take" -> {
            val amount = args.getOrNull(1)?.toLongOrNull() ?: 0L
            if (coins.take(player, amount)) {
                player.sendRichMessage("<red>-$amount монет. Итого: ${coins.get(player)}")
            } else {
                player.sendRichMessage("<red>Недостаточно монет!")
            }
        }
        else -> player.sendRichMessage("<yellow>Монеты: <white>${coins.get(player)}")
    }
}

onEvent<org.bukkit.event.player.PlayerJoinEvent> {
    player.sendRichMessage("<gray>Твои монеты: <gold>${coins.get(player)}")
}
