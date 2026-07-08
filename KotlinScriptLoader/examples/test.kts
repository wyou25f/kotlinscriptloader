val greeting = config.getString("greeting", "Привет, %player%!")!!

// registerCommand — лямбда получает (player, args) явно
registerCommand("test", aliases = listOf("ksltest")) { player, args ->
    // Новый синтаксис extension-свойств: player.group, player.balance, player.prefix
    val group = player.group ?: "—"
    val coins = player.balance?.let { "%.2f".format(it) } ?: "—"
    val afk   = isAfk(player)

    // MiniMessage — градиент + жирный
    player.sendRichMessage("<bold><gradient:#ff6b6b:#feca57>${greeting.replace("%player%", player.name)}</gradient></bold>")
    player.sendRichMessage("<gray>Группа: <yellow>$group</yellow>  Баланс: <green>$coins</green>  AFK: <red>$afk</red></gray>")

    // Установка баланса через сеттер: player.balance = ...
    if (args.firstOrNull() == "reset") {
        player.balance = 0.0
        player.sendRichMessage("<red>Баланс сброшен.")
    }
}

registerPlaceholder("greeting") { player ->
    greeting.replace("%player%", player.name ?: "?")
}

// onEvent — receiver lambda: this = событие, поля доступны напрямую
onEvent<PlayerInteractEvent> {
    if (action != Action.RIGHT_CLICK_BLOCK) return@onEvent   // this.action

    val blockType = clickedBlock?.type?.name ?: "unknown"    // this.clickedBlock
    this.player.sendActionBarMessage("<yellow>Блок: $blockType")

    // dbExecute — async INSERT в одну строку, без ручного connection.use
    dbExecute(
        "CREATE TABLE IF NOT EXISTS clicks (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT, block TEXT)"
    )
    dbExecute("INSERT INTO clicks (player, block) VALUES (?, ?)", this.player.name, blockType)

    // dbQuery — async SELECT с колбэком
    dbQuery("SELECT COUNT(*) AS total FROM clicks WHERE player = ?", this.player.name) { rs ->
        if (rs.next()) {
            val total = rs.getInt("total")
            val p = this.player
            runSync { p.sendRichMessage("<gray>Всего кликов: <white>${total}") }
        }
    }
}

// Повторяющийся таймер — every автоматически снимается при /ksl reload
every(20L * 60) {
    broadcast("&a[Авто] Сервер работает нормально!")
    discordSend("logs", "✅ Сервер работает нормально")
}

registerCommand("skin") { player, args ->
    val skinName = args.firstOrNull()
    if (skinName == null) {
        player.sendRichMessage("<red>Использование: /skin <ник></red>")
        return@registerCommand
    }
    if (setSkin(player, skinName)) {
        player.sendRichMessage("<green>Скин изменён на $skinName</green>")
    } else {
        player.sendRichMessage("<red>SkinsRestorer не найден или скин недоступен</red>")
    }
}

onEvent<org.bukkit.event.player.PlayerJoinEvent> {
    discordEmbed(
        channel = "general",
        title = "Игрок зашёл",
        description = "**${player.name}** подключился к серверу",
        color = 0x57F287
    )
}
