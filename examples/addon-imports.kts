// Explicit Kotlin imports from the ExampleAddon JAR.
import ru.example.CoinService
import ru.example.format.coinLabel
import ru.example.format.shortServerLabel

val coins = service<CoinService>("coins")
    ?: error("ExampleAddon не загружен")

registerCommand("coinlabel") { player, _ ->
    player.sendMessage("${player.shortServerLabel()}: ${coins.get(player).coinLabel()}")
}
