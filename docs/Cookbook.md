# KSL Cookbook — практические рецепты

Шаблоны для типовых задач. Проверь установленные интеграции и версию KSL перед копированием.

## 1. Приветствие с feature flag

```kotlin
if (featureEnabled("welcome.enabled", default = true)) {
    onEvent<PlayerJoinEvent> { event ->
        event.player.sendRichMessage("<green>Добро пожаловать, ${event.player.name}!</green>")
    }
}
```

## 2. Команда с проверкой прав

```kotlin
registerCommand("announce") { sender, args ->
    if (!sender.hasPermission("server.announce")) {
        sender.sendMessage("Недостаточно прав.")
        return@registerCommand
    }
    if (args.isEmpty()) {
        sender.sendMessage("Использование: /announce <текст>")
        return@registerCommand
    }
    val message = args.joinToString(" ")
    runSync { Bukkit.broadcastMessage(message) }
}
```

## 3. YAML feature toggle

```kotlin
val enabled = featureEnabled("events.double-xp", default = false)
logger.info("Double XP enabled: $enabled")
val newState = toggleFeature("events.double-xp")
```

## 4. SQL: параметризованная запись

```kotlin
execute("""
    CREATE TABLE IF NOT EXISTS player_notes (
        uuid VARCHAR(36) NOT NULL,
        note VARCHAR(255) NOT NULL,
        created_at BIGINT NOT NULL
    )
""".trimIndent())

execute(
    "INSERT INTO player_notes (uuid, note, created_at) VALUES (?, ?, ?)",
    player.uniqueId.toString(),
    "first-join",
    System.currentTimeMillis()
)
```

Для реального приложения добавь primary key/index и миграцию схемы. Не склеивай SQL с пользовательским вводом.

## 5. SQL async с возвратом результата

```kotlin
queryAsync(
    "SELECT COUNT(*) AS total FROM player_notes WHERE uuid = ?",
    player.uniqueId.toString(),
    mapper = { rs -> if (rs.next()) rs.getInt("total") else 0 }
).whenComplete { total, error ->
    if (error != null) {
        logger.warning("Query failed: ${error.message}")
    } else {
        runSync { player.sendMessage("Заметок: $total") }
    }
}
```

Mapper возвращает обычное значение. Не возвращай JDBC ResultSet: соединение закрывается после завершения mapper.

## 6. SQL из YAML

Файл notes.yml для notes.kts находится в корне папки KSL, рядом с scripts/:

```yaml
database:
  queries:
    count-notes: "SELECT COUNT(*) AS total FROM player_notes WHERE uuid = ?"
```

```kotlin
dbQueryYaml("database.queries.count-notes", player.uniqueId.toString()) {
    val total = if (next()) getInt("total") else 0
    runSync { player.sendMessage("Заметок: $total") }
}
```

## 7. Кэш с лимитом

```kotlin
data class ProfileView(val name: String, val level: Int)

val profiles = KSLRT.cache<String, ProfileView>(maxSize = 2_000)
profiles[player.uniqueId.toString()] = ProfileView(player.name, 5)
val cached = profiles[player.uniqueId.toString()]
```

Продумай invalidation и TTL. Локальный cache одного сервера не синхронизируется с другими Minecraft-серверами.

## 8. Измерение операции

```kotlin
val profile = KSLRT.profiler("shop.price-calculation")
val price = profile.measure { calculatePrice(player.uniqueId) }
val stats = profile.snapshot()
logger.info("calls=${stats.calls}, maxNs=${stats.maxNanos}")
```

Не создавай уникальное имя профиля на каждый вызов. Реестр именованных профилей ограничен.

## 9. Фоновое вычисление

```kotlin
val taskId = worker(KSLWorkPriority.NORMAL) {
    val computed = expensiveCalculation()
    runSync { player.sendMessage("Результат: $computed") }
}
```

Не обращайся к Bukkit объектам в фоновом коде. Если нужно получить данные игрока, собери immutable snapshot на главном потоке до запуска worker.

## 10. TOML настройки

```toml
# Файл scripts/data/shop.toml
[shop]
enabled = true
max-items = 24
```

```kotlin
val settings = loadToml("data/shop.toml")
val enabled = settings.tomlTable("shop")?.tomlBoolean("enabled", true) ?: true
```

## 11. JSON payload

```kotlin
val payload = jsonObjectOf(
    "uuid" to player.uniqueId.toString(),
    "name" to player.name,
    "online" to true,
    "seenAt" to System.currentTimeMillis()
)
```

## 12. YAML migration

```kotlin
migrateConfig(
    targetVersion = 2,
    migrations = mapOf(
        1 to { if (!contains("shop.enabled")) set("shop.enabled", true) },
        2 to { if (!contains("shop.max-items")) set("shop.max-items", 24) }
    )
)
```

Не переписывай старые migration steps после публикации конфигурации пользователям.

## 13. Необязательная интеграция

```kotlin
val auth = hook<KSLAuthMeHook>("authme")
if (auth == null) {
    logger.info("AuthMe не установлен; функция проверки входа отключена")
} else {
    // Используй только методы, документированные для доступного hook.
}
```

## 14. Отладка

Если скрипт не загружается:
1. Проверь точную строку компилятора.
2. Проверь imports и package.
3. Сверь имя метода с исходником и документацией.
4. Проверь, не вызывается ли Bukkit API из async.
5. Выполни /ksl doctor и /ksl errors.
6. Смотри Troubleshooting.md.

## 15. Чего не делать

- Не использовать SHA-256 как password hash.
- Не сохранять пароли/токены в открытом виде.
- Не использовать SQL string interpolation для пользовательских данных.
- Не вызывать join() для долгой операции на главном потоке.
- Не вызывать Bukkit inventory/world/player методы из произвольного worker thread.
- Не создавать неограниченные Executors и потоки.
- Не считать feature flag системой авторизации.
- Не считать Sandbox изоляцией вредоносного кода.
