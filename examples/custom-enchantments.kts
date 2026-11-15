// KSL Custom Enchantments: script-owned enchantments with persistent item levels.
val thunder = customEnchant("thunder") {
    name("<yellow>Гром")
    maxLevel(3)
    targets(Material.DIAMOND_AXE, Material.NETHERITE_AXE)
    conflicts("frost")

    onHit {
        attacker.world.strikeLightningEffect(target.location)
        if (target is LivingEntity) {
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOW, 20 * level, 0))
        }
    }

    onKill {
        player.giveItem(item(Material.GOLDEN_APPLE) {
            name("<gold>Громовой трофей")
            lore("<gray>Выдано за убийство с Громом")
        })
    }
}

val axe = item(Material.DIAMOND_AXE) {
    name("<yellow><bold>Топор Громовержца")
    lore("<gray>Каждый удар вызывает грозу")
    customEnchant(thunder, 2)
    enchant(Enchantment.EFFICIENCY, 5, unsafe = true)
    unbreakable()
    hideAttributes()
}

onJoin {
    player.inventory.addItem(axe)
}
