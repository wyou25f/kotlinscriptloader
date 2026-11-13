// CoinService and ru.example.format.* are default-imported by ExampleAddon.
val coins = service<CoinService>("coins")
    ?: error("ExampleAddon не загружен")

registerCommand("coinlabel-default") { player, _ ->
    player.sendMessage("${player.shortServerLabel()}: ${coins.get(player).coinLabel()}")
}
