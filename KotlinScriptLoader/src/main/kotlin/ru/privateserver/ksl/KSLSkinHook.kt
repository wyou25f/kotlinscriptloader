package ru.privateserver.ksl

import net.skinsrestorer.api.SkinsRestorer
import net.skinsrestorer.api.SkinsRestorerProvider
import net.skinsrestorer.api.property.SkinProperty
import org.bukkit.entity.Player

class KSLSkinHook {

    private val api: SkinsRestorer = SkinsRestorerProvider.get()

    fun setSkin(player: Player, skinName: String): Boolean {
        val result = api.skinStorage.findOrCreateSkinData(skinName).orElse(null) ?: return false
        api.playerStorage.setSkinIdOfPlayer(player.uniqueId, result.identifier)
        api.getSkinApplier(Player::class.java).applySkin(player)
        return true
    }

    fun setSkinRaw(player: Player, value: String, signature: String): Boolean {
        val property = SkinProperty.of(value, signature)
        api.getSkinApplier(Player::class.java).applySkin(player, property)
        return true
    }

    fun currentSkin(player: Player): SkinProperty? =
        api.playerStorage.getSkinForPlayer(player.uniqueId, player.name).orElse(null)
}
