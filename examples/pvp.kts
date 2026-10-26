// @requires: kd

val kdLib = library<Map<String, Any?>>("kd")
    ?: error("kd.kts не загружен — проверь порядок загрузки или @requires")

@Suppress("UNCHECKED_CAST")
val damageFor = kdLib["damageFor"] as (Material) -> Double

@Suppress("UNCHECKED_CAST")
val calcKd = kdLib["kd"] as (Int, Int) -> Double

registerCommand("pvpstats") { player, args ->
    val weapon = player.inventory.itemInMainHand.type
    val dmg = damageFor(weapon)
    val ratio = calcKd(kills = 12, deaths = 4)

    player.sendRichMessage("<gray>Урон твоего оружия (${weapon.name}): <white>$dmg")
    player.sendRichMessage("<gray>K/D: <white>${"%.2f".format(ratio)}")
}
