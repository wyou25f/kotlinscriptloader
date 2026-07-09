fun damageFor(weapon: Material): Double = when (weapon) {
    Material.NETHERITE_SWORD -> 8.0
    Material.DIAMOND_SWORD -> 7.0
    Material.IRON_SWORD -> 6.0
    Material.STONE_SWORD -> 5.0
    Material.WOODEN_SWORD -> 4.0
    else -> 1.0
}

fun kd(kills: Int, deaths: Int): Double =
    if (deaths == 0) kills.toDouble() else kills.toDouble() / deaths

export("kd", mapOf(
    "damageFor" to ::damageFor,
    "kd" to ::kd
))

logger.info("kd.kts загружен, экспортированы функции 'damageFor' и 'kd' под ключом 'kd'")
