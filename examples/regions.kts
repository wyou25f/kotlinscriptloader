registerCommand("region") { player, args ->
    val loc = player.location
    val regions = wgRegionsAt(loc)

    if (regions.isEmpty()) {
        player.sendRichMessage("<gray>Ты не в регионе WorldGuard.")
    } else {
        player.sendRichMessage("<gray>Регионы здесь: <yellow>${regions.joinToString(", ")}")
        player.sendRichMessage("<gray>PvP разрешён: <white>${wgCanPvp(player, loc)}")

        val greeting = wgFlag(loc, "greeting")
        if (greeting != null) player.sendRichMessage("<green>$greeting")
    }
}

registerCommand("fillselection") { player, args ->
    if (!wgCanBuild(player, player.location)) {
        player.sendRichMessage("<red>Тебе запрещено строить в этом регионе WorldGuard.")
        return@registerCommand
    }

    val material = args.firstOrNull()?.let { Material.matchMaterial(it) }
    if (material == null) {
        player.sendRichMessage("<red>Использование: /fillselection <материал></red>")
        return@registerCommand
    }

    val volume = weSelectionVolume(player)
    if (volume == null) {
        player.sendRichMessage("<red>Сначала выдели регион деревянным топором WorldEdit (//pos1, //pos2).")
        return@registerCommand
    }

    val changed = weFillSelection(player, material)
    player.sendRichMessage("<green>Заполнено блоков: <white>${changed ?: 0} <gray>из <white>$volume")
}
