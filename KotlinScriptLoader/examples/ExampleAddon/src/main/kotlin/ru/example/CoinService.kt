package ru.example

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

class CoinService {

    private val coins = ConcurrentHashMap<String, Long>()

    fun get(player: Player): Long = coins.getOrDefault(player.name, 0L)

    fun give(player: Player, amount: Long) {
        coins.merge(player.name, amount, Long::plus)
    }

    fun take(player: Player, amount: Long): Boolean {
        val current = get(player)
        if (current < amount) return false
        coins[player.name] = current - amount
        return true
    }

    fun set(player: Player, amount: Long) {
        coins[player.name] = amount
    }
}
