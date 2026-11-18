# Книга IV. KSL Cookbook — практические рецепты

**Шаблоны для игровых механик, команд, данных, баз, инвентаря и интеграций**

Cookbook не заменяет первые три книги. Он показывает типовые решения и объясняет, какие части нужно адаптировать. Перед копированием проверьте импорты и доступность методов в целевой версии KSL.

## Содержание

1. Приветствие при входе
2. Команда с permission
3. Команда с аргументами и валидацией
4. Сообщение через YAML
5. Feature flag
6. Кулдаун игрока
7. Награда за вход
8. Статистика через table
9. Проверка вместимости инвентаря
10. Выдача предметов с остатками
11. Повторяющаяся задача
12. Фоновый расчёт
13. SQL: создание и вставка
14. SQL: асинхронный SELECT
15. SQL-запрос из YAML
16. JSON-профиль
17. TOML-настройки
18. Миграция YAML
19. Кэширование профиля
20. Измерение времени
21. MesLib2: обмен между скриптами
22. Необязательный hook
23. Поиск игроков рядом
24. Валидация никнейма
25. Предмет как награда
26. Диагностика скриптов
27. Cleanup внешнего ресурса
28. Перенос данных в фоновом потоке
29. Проверка конфигурации перед запуском механики
30. Правила адаптации рецептов
31. GUI с пагинацией
32. Типизированные PDC-теги

---

## Рецепт 1. Приветствие при входе

~~~kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage(
        "<green>Добро пожаловать, <white>" + player.name + "</white>!</green>"
    )
}
~~~

KSL регистрирует listener при загрузке и удаляет его при выгрузке скрипта. Не нужно вручную создавать второй listener при каждом входе.

## Рецепт 2. Команда с permission

~~~kotlin
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
    runSync {
        Bukkit.broadcastMessage(message)
    }
}
~~~

Проверка permission обязательна внутри команды. Скрытие команды из подсказок не защищает её.

## Рецепт 3. Аргументы и валидация

~~~kotlin
registerCommand("set-level") { sender, args ->
    val raw = args.getOrNull(0).orEmpty()

    val validator = kslValidator(
        notBlank("Укажите уровень."),
        codePointLength(1, 3, "Слишком длинное значение."),
        matches(Regex("[0-9]+"), "Нужны только цифры.")
    )

    when (val result = raw.validateWith(validator)) {
        is KSLValidationResult.Invalid -> {
            result.errors.forEach { sender.sendMessage(it.message) }
        }
        is KSLValidationResult.Valid -> {
            val level = result.value.toIntOrNull()
            if (level == null || level !in 1..100) {
                sender.sendMessage("Уровень должен быть от 1 до 100.")
            } else {
                sender.sendMessage("Уровень установлен: " + level)
            }
        }
    }
}
~~~

Валидация формата и бизнес-ограничение — разные проверки. Сначала убедитесь, что строка является целым числом, затем проверьте допустимый диапазон.

## Рецепт 4. Сообщение через YAML

shop.yml:

~~~yaml
messages:
  prefix: "<gold>[Магазин]</gold>"
  welcome: "<green>Вы открыли магазин!</green>"
  disabled: "<red>Магазин временно отключён.</red>"
shop:
  enabled: true
~~~

shop.kts:

~~~kotlin
val prefix = config.getString("messages.prefix") ?: "[Магазин]"
val welcome = config.getString("messages.welcome") ?: "Добро пожаловать"
val enabled = config.getBoolean("shop.enabled", true)

registerCommand("shop") { sender, _ ->
    if (!enabled) {
        sender.sendMessage(prefix + " Магазин отключён.")
        return@registerCommand
    }
    sender.sendMessage(prefix + " " + welcome)
}
~~~

Файл shop.yml лежит в корне данных KSL рядом с папкой scripts/, а не внутри scripts/.

## Рецепт 5. Feature flag

~~~kotlin
if (featureEnabled("events.double-xp", default = false)) {
    onEvent<PlayerJoinEvent> {
        player.sendMessage("Сейчас активен двойной опыт!")
    }
}
~~~

Feature flag управляет включением функции. Он не заменяет permission check и не является системой авторизации.

## Рецепт 6. Кулдаун игрока

~~~kotlin
val lastUse = mutableMapOf<UUID, Long>()
val cooldownMillis = 10_000L

registerCommand("daily") { sender, _ ->
    val player = sender as? Player ?: return@registerCommand
    val now = System.currentTimeMillis()
    val previous = lastUse[player.uniqueId] ?: 0L

    if (now - previous < cooldownMillis) {
        val seconds = (cooldownMillis - (now - previous)) / 1000
        player.sendMessage("Подождите ещё " + seconds + " сек.")
        return@registerCommand
    }

    lastUse[player.uniqueId] = now
    player.sendMessage("Ежедневная награда готова!")
}
~~~

Это in-memory cooldown: он сбросится при reload. Для хранения между перезапусками используйте persist/table или БД. В production очищайте старые ключи, если их число может расти без ограничений.

## Рецепт 7. Награда за вход

~~~kotlin
var welcomeCount by persist("welcome-count", persistent = true) { 0L }

onEvent<PlayerJoinEvent> {
    welcomeCount += 1L
    player.sendMessage("Номер входа по общему счётчику: " + welcomeCount)
}
~~~

Этот пример хранит одно persistent значение скрипта, а не персональный баланс каждого игрока. Для данных по игрокам используйте отдельные ключи или SQL-схему.

## Рецепт 8. Статистика через table

~~~kotlin
val stats = table("player_stats")

onEvent<PlayerJoinEvent> {
    val key = player.uniqueId.toString()
    val current = stats.get(key, "joins") ?: 0
    stats.set(key, "joins", current + 1)
}
~~~

Это иллюстрация key/value таблицы. Для конкурентных обновлений с нескольких серверов используйте атомарный SQL UPDATE, а не локальный read-modify-write. Уточните тип возвращаемого значения get в target версии KSL, если используете другой набор значений.

## Рецепт 9. Проверка вместимости инвентаря

~~~kotlin
val reward = ItemStack(Material.DIAMOND, 16)

if (player.canFit(reward)) {
    player.give(reward)
} else {
    player.sendMessage("Освободите место в инвентаре.")
}
~~~

canFit учитывает место в частичных похожих стеках. isInventoryFull проверяет только полное отсутствие пустого storage slot — это не одно и то же.

## Рецепт 10. Выдать предметы с остатками

~~~kotlin
val reward = ItemStack(Material.EMERALD, 64)
val leftovers = player.give(reward)

if (leftovers.isNotEmpty()) {
    player.sendMessage("Часть предметов не поместилась.")
}
~~~

give не выбрасывает остатки на землю. Если именно это нужно:

~~~kotlin
val droppedCount = player.giveOrDrop(reward)
player.sendMessage("На землю выброшено предметов: " + droppedCount)
~~~

Решение о выбрасывании должно быть осознанным: это меняет мир, и предметы могут подобрать другие игроки.

## Рецепт 11. Повторяющаяся задача

~~~kotlin
val taskId = every(ticks = 20L) {
    // Работа примерно раз в секунду при 20 TPS.
}
~~~

Зарегистрируйте repeating task один раз на верхнем уровне скрипта. Не создавайте новую периодическую задачу при каждом event callback.

## Рецепт 12. Фоновый расчёт

~~~kotlin
val playerId = player.uniqueId

worker(KSLWorkPriority.NORMAL) {
    val result = calculateScore(playerId)

    runSync {
        val online = Bukkit.getPlayer(playerId)
        online?.sendMessage("Результат: " + result)
    }
}
~~~

В worker не держите изменяемое состояние Player. Передавайте UUID и другие простые данные, а в главном потоке заново ищите игрока.

## Рецепт 13. SQL: создание и вставка

~~~kotlin
execute("""
    CREATE TABLE IF NOT EXISTS player_notes (
        uuid VARCHAR(36) NOT NULL,
        note VARCHAR(255) NOT NULL,
        created_at BIGINT NOT NULL,
        PRIMARY KEY (uuid, created_at)
    )
""".trimIndent())

execute(
    "INSERT INTO player_notes (uuid, note, created_at) VALUES (?, ?, ?)",
    player.uniqueId.toString(),
    note,
    System.currentTimeMillis()
)
~~~

Для реального проекта продумайте ключи, индексы и миграции. Не конкатенируйте пользовательский ввод в SQL.

## Рецепт 14. SQL: асинхронный SELECT

~~~kotlin
val playerId = player.uniqueId.toString()

queryAsync(
    "SELECT COUNT(*) AS total FROM player_notes WHERE uuid = ?",
    playerId,
    mapper = { rs ->
        if (rs.next()) rs.getInt("total") else 0
    }
).whenComplete { total, error ->
    if (error != null) {
        logWarn("Запрос не выполнен: " + error.message)
    } else {
        runSync {
            Bukkit.getPlayer(UUID.fromString(playerId))
                ?.sendMessage("Записей: " + total)
        }
    }
}
~~~

Mapper возвращает обычное значение. JDBC ResultSet закрывается после mapper. Не возвращайте ResultSet наружу и не вызывайте join() на главном потоке.

## Рецепт 15. SQL-запрос из YAML

Для notes.kts файл notes.yml лежит рядом с scripts/.

~~~yaml
database:
  queries:
    count-notes: "SELECT COUNT(*) AS total FROM player_notes WHERE uuid = ?"
~~~

~~~kotlin
dbQueryYaml("database.queries.count-notes", player.uniqueId.toString()) {
    val total = if (next()) getInt("total") else 0
    runSync {
        player.sendMessage("Записей: " + total)
    }
}
~~~

Здесь SQL и Kotlin-логика разделены. После изменения YAML перезагрузите скрипт.

## Рецепт 16. JSON-профиль

~~~kotlin
val payload = jsonObjectOf(
    "uuid" to player.uniqueId.toString(),
    "name" to player.name,
    "online" to true,
    "seenAt" to System.currentTimeMillis()
)

saveJson("profiles/" + player.uniqueId + ".json", payload)
~~~

Путь должен оставаться внутри разрешённой папки scripts/. JSON не шифрует данные и не поддерживает комментарии.

## Рецепт 17. TOML-настройки

~~~toml
[shop]
enabled = true
max-items = 24
~~~

~~~kotlin
val settings = loadToml("data/shop.toml")
val shop = settings?.tomlTable("shop").orEmpty()
val enabled = shop.tomlBoolean("enabled", true)
val maxItems = shop.tomlInt("max-items", 24)
~~~

Используйте typed accessors вместо небезопасного cast Long → Int.

## Рецепт 18. YAML миграция

~~~kotlin
migrateConfig(
    targetVersion = 2,
    migrations = mapOf(
        1 to {
            if (!contains("shop.enabled")) set("shop.enabled", true)
        },
        2 to {
            if (!contains("shop.max-items")) set("shop.max-items", 24)
        }
    )
)
~~~

Не переписывайте старые миграционные шаги после выпуска конфигурации пользователям. Добавляйте новый номер миграции.

## Рецепт 19. Кэширование профиля

~~~kotlin
data class ProfileView(
    val name: String,
    val level: Int
)

val profiles = runtimeScope.lru<String, ProfileView>(
    maxSize = 2000,
    expireAfterAccess = Duration.ofMinutes(5)
)

profiles[player.uniqueId.toString()] =
    ProfileView(player.name, 5)
~~~

runtimeScope очистит кэш при reload скрипта. Не храните в кэше изменяемый Bukkit Player для доступа из фоновых задач.

## Рецепт 20. Измерение времени

~~~kotlin
val profile = KSLRT.profiler("shop.price")

val price = profile.measure {
    calculatePrice(player.uniqueId)
}

val snapshot = profile.snapshot()
log("calls=" + snapshot.calls + ", maxNs=" + snapshot.maxNanos)
~~~

Не создавайте уникальное имя на каждую операцию. Профайлеры группируют результаты по имени.

## Рецепт 21. MesLib2: обмен между скриптами

Поставщик:

~~~kotlin
data class EconomyLibrary(
    val currencyName: String = "coins"
)

meslib2.exportLocal(
    "service",
    EconomyLibrary(),
    version = "1.0"
)
~~~

Потребитель:

~~~kotlin
// @requires: economy
val economy = meslib2.require<EconomyLibrary>(
    "economy",
    "service",
    "1.0"
)

log("Валюта: " + economy.currencyName)
~~~

Экспорт привязан к script owner. Если поставщик выгружается, его экспорт удаляется.

## Рецепт 22. Необязательный hook

~~~kotlin
val auth = hook<KSLAuthMeHook>("authme")

if (auth == null) {
    log("AuthMe отсутствует — используем базовый режим")
} else {
    val registered = auth.isRegistered(player.name)
    player.sendMessage("Регистрация: " + registered)
}
~~~

Optional integration должна оставаться optional. Не делайте сервер зависимым от плагина, если функция может быть отключена с понятным сообщением.

## Рецепт 23. Поиск игроков рядом

~~~kotlin
val nearby = player.world.playersInRadius(
    player.location,
    12.0
)

nearby.forEach { target ->
    target.sendMessage("Рядом находится игрок: " + player.name)
}
~~~

Операция взаимодействует с live world state и должна выполняться на серверном потоке.

## Рецепт 24. Валидация никнейма

~~~kotlin
registerCommand("profile") { sender, args ->
    val rawName = args.getOrNull(0).orEmpty()

    val validator = kslValidator(
        notBlank("Введите имя."),
        codePointLength(3, 16, "Длина должна быть от 3 до 16 символов."),
        matches(Regex("[A-Za-z0-9_]+"), "Допустимы буквы, цифры и _.")
    )

    when (val result = rawName.validateWith(validator)) {
        is KSLValidationResult.Valid -> {
            sender.sendMessage("Имя прошло проверку формата.")
        }
        is KSLValidationResult.Invalid -> {
            result.errors.forEach { sender.sendMessage(it.message) }
        }
    }
}
~~~

Проверка формата не означает, что аккаунт существует или принадлежит пользователю.

## Рецепт 25. Предмет как награда

~~~kotlin
val reward = ItemStack(Material.DIAMOND_SWORD)
    .withDisplayName("<gold>Клинок исследователя</gold>".toMiniMessageComponent())
    .withLore(
        listOf(
            "Редкая награда".toTextComponent(),
            "Получена за достижение".toTextComponent()
        )
    )
    .withUnbreakable()
    .withEnchantment(Enchantment.SHARPNESS, 3)

val leftovers = player.give(reward)
if (leftovers.isNotEmpty()) {
    player.sendMessage("Освободите место для полной награды.")
}
~~~

ItemStack helpers возвращают clone. Строку, которую нужно форматировать, преобразуйте в Component явно. Для пользовательских текстов обычно безопаснее literal text.

## Рецепт 26. Диагностика скриптов

~~~kotlin
registerCommand("script-check") { sender, _ ->
    if (!sender.hasPermission("ksl.admin")) {
        sender.sendMessage("Недостаточно прав.")
        return@registerCommand
    }

    val scripts = ksl.scripts()
    sender.sendMessage("Известных скриптов: " + scripts.size)

    scripts.forEach { info ->
        sender.sendMessage(
            info.id + ": state=" + info.state +
                ", loaded=" + info.loaded +
                ", errors=" + info.errors +
                ", sourceExists=" + info.sourceExists
        )
        info.lastError?.let { sender.sendMessage("  Last error: " + it) }
    }
}
~~~

Если доступность API или alias отличается в вашей версии, используйте Public API из книги II.

## Рецепт 27. Cleanup внешнего ресурса

~~~kotlin
val client = own(createHttpClient())

val cleanup = onUnload {
    thirdPartyEvents.unsubscribe(listener)
}

// Если cleanup больше не нужен:
cleanup.close()
~~~

Не создавайте внешний client без определённого места закрытия. При reload его нельзя оставлять работающим в старом classloader.

## Рецепт 28. Перенос данных в фоновом потоке

~~~kotlin
val playerId = player.uniqueId
val playerName = player.name

worker(KSLWorkPriority.NORMAL) {
    val profile = loadProfileFromService(playerId)

    runSync {
        val online = Bukkit.getPlayer(playerId)
        online?.sendMessage("Данные загружены для " + playerName)
    }
}
~~~

Передавайте стабильные значения. После фоновой операции заново проверяйте, что игрок онлайн, и только затем меняйте Bukkit state.

## Рецепт 29. Проверка конфигурации перед запуском механики

~~~kotlin
val issues = validateConfig {
    bool("shop.enabled", default = true)
    int("shop.max-items", default = 24, min = 1, max = 54)
    string("messages.prefix", default = "[Shop]")
}

if (issues.isEmpty()) {
    registerCommand("shop") { sender, _ ->
        sender.sendMessage("Магазин готов.")
    }
} else {
    issues.forEach { issue ->
        logWarn("Config " + issue.path + ": " + issue.message)
    }
}
~~~

Валидация до регистрации позволяет не запускать механику с неполной или заведомо неверной конфигурацией.

---


---

## Рецепт 31. GUI с пагинацией

~~~kotlin
val products = listOf(
    Material.DIAMOND,
    Material.EMERALD,
    Material.GOLD_INGOT,
    Material.IRON_INGOT
)

val menu = gui("<gold>Магазин</gold>", rows = 3) {
    button(22, ItemStack(Material.BARRIER)) { player ->
        player.closeInventory()
    }

    paginate(
        items = products,
        slots = (10..16).toList(),
        prevSlot = 18,
        nextSlot = 26,
        render = { material -> ItemStack(material) },
        onClick = { player, material ->
            player.sendMessage("Вы выбрали: ${material.name}")
        }
    )
}

menu.open(player)
~~~

Слоты контента и навигации должны быть уникальными и находиться внутри меню. Для ручной перерисовки текущей страницы вызовите `menu.refresh()`, а для переключения — `menu.showPage(index)`. Не вызывайте методы изменения инвентаря из async-кода.

## Рецепт 32. Типизированные PDC-теги

~~~kotlin
import java.util.UUID

onEvent<PlayerJoinEvent> {
    val previous = player.getIntTag("join_count") ?: 0
    player.setIntTag("join_count", previous + 1)
}

val reward = ItemStack(Material.PAPER)
reward.setStringTag("kind", "daily")
reward.setBooleanTag("claimed", false)
reward.setUuidTag("id", UUID.randomUUID())

val rewardId = reward.getUuidTag("id")
~~~

Эти методы работают с `ItemStack` и с `PersistentDataHolder` (например, Player/Entity). Ключи привязаны к скрипту и не конфликтуют с внутренними метками KSL. Старые `ItemStack.setTag/getTag/hasTag` остаются для обратной совместимости. PDC не заменяет SQL, если данным нужны общие транзакции или централизованный поиск.

## Глава 30. Правила адаптации рецептов

Перед использованием любого примера:

1. Проверьте имена функций и сигнатуры в целевой версии KSL.
2. Проверьте imports и наличие optional plugins.
3. Определите поток выполнения callback.
4. Добавьте permissions в административные команды.
5. Проверьте cleanup после /ksl reload.
6. Не оставляйте секреты в коде.
7. Обработайте ошибки SQL и внешних интеграций.
8. Проверьте реальный сервер после автоматической сборки.

### Рецепт — не готовый production-плагин

Пример показывает идею и базовый API. Для production обычно нужны schema migrations, обработка гонок, права, конфигурация, логирование, тестирование и диагностика.


## Рецепт 33. Передача функции между скриптами через MesLib2

Файл-поставщик `scripts/economy/service.kts`:

~~~kotlin
val taxCalculator: (Double) -> Double = { amount -> amount * 1.1 }
meslib2.export("economy", "tax-calculator", taxCalculator, "1.0.0")
~~~

Файл-потребитель `scripts/shop/checkout.kts`:

~~~kotlin
// @requires: economy/service
val taxCalculator = meslib2.require<(Double) -> Double>(
    "economy",
    "tax-calculator",
    "1.0.0"
)

val total = taxCalculator(100.0)
~~~

`@requires` задаёт порядок загрузки скриптов, а MesLib2 проверяет тип и точную метку версии экспорта. При выгрузке поставщика его экспорт удаляется; потребитель должен обрабатывать отсутствие сервиса и не сохранять полученную функцию дольше жизненного цикла поставщика.

**Конец книги IV.** Четыре книги вместе образуют основную документацию KSL.


---

# Рецепт 33. Declarative Automation на YAML

Automation вызывает только заранее зарегистрированные обработчики. Конфигурация не исполняет Kotlin-код и не может объявить произвольный класс или метод.

```yaml
version: 1
automations:
  - id: cache-refresh
    enabled: true
    schedule:
      type: every
      interval-ms: 60000
      initial-delay-ms: 5000
      mode: fixed-delay
    action:
      handler: cache.refresh
      args:
        region: global
        max-items: 500
  - id: daily-report
    schedule:
      type: once
      delay-ms: 3600000
    action:
      handler: report.daily
```

TOML использует ту же модель version 1. Корневые ключи: `version` и `automations`. У записи допустимы `id`, `enabled`, `schedule`, `action`; у action — `handler` и необязательный `args`. Для расписания `once` требуется `delay-ms`; для `every` — `interval-ms`, необязательные `initial-delay-ms` и `mode` (`fixed-delay` или `fixed-rate`).

Обработчики регистрируются кодом аддона в `KSLAutomationActionRegistry`, затем engine создаётся через addon scope. При reload сначала полностью разбирается и проверяется конфигурация, затем проверяется наличие обработчиков. Новые callbacks не выполняются до фиксации замены; если проверка не проходит, прежний набор остаётся активным. Engine закрывается вместе с addon scope.

Ограничения версии 1: не более 256 автоматизаций, 1 Mi символов исходника, задержка до 30 дней, вложенность аргументов до 8 уровней. Для игровых Bukkit API переходи на главный поток через addon scope; worker callback не делает Bukkit API потокобезопасным.

# Рецепт 34. JDBC Accounts через KSLAPI

```kotlin
val accounts = api.createAccountStore(this, dataSource)
accounts.initialize()

val account = accounts.register("ExampleUser", "a-long-password".toCharArray())

val session = accounts.authenticate(
    "exampleuser",
    "a-long-password".toCharArray()
)

if (session != null) {
    // Показывается один раз: сохрани токен на стороне клиента безопасным способом.
    val rawToken = session.token
    val profile = accounts.findSession(rawToken)
}
```

`createAccountStore(owner, dataSource)` назначает аддону отдельный безопасный SQL table prefix. Напрямую созданный `KSLAccountStore` по умолчанию использует совместимые таблицы `ksl_accounts` и `ksl_account_sessions`; для изоляции передай `tablePrefix`. DataSource принадлежит вызывающему коду и не закрывается store автоматически.

Пароль хранится как salted PBKDF2-HMAC-SHA256 hash; bearer token выдаётся один раз, а в БД хранится только SHA-256 digest. Сессия ограничена TTL от одной минуты до 30 дней. Вызывай register/authenticate в worker-потоке: вычисление password hash CPU-интенсивно. Это базовый account/session store, а не готовая система ролей, MFA, password reset или защиты от brute force.

# Рецепт 35. Экспериментальный PostEmberDB client

```kotlin
KSLPostemberClient(
    host = "127.0.0.1",
    port = 7432,
    connectTimeoutMillis = 2_000,
    readTimeoutMillis = 5_000
).use { db ->
    db.execute("CREATE TABLE notes (id INTEGER NOT NULL, body TEXT)")
    db.execute("INSERT INTO notes VALUES (1, 'hello')")
    val result = db.query("SELECT * FROM notes")
}
```

На текущем upstream PostEmberDB сервер in-memory, без аутентификации, транзакций и долговременного хранения; протокол поддерживает ограниченный набор SQL. Клиент KSL поэтому разрешает только loopback, ограничивает размер ответа и количество строк и задаёт connect/read timeout. Это экспериментальная интеграция для разработки, не production database и не замена SQLite/PostgreSQL/MySQL. Сам репозиторий PostEmberDB остаётся независимым.

# Рецепт 36. Production checklist перед включением функции

1. Проверить входные данные и ограничить размер строк, списков и файлов.
2. Не конкатенировать пользовательские данные в SQL — использовать bind parameters.
3. Выполнять файловый, сетевой и password-hash I/O вне Bukkit main thread.
4. Возвращаться на главный поток перед изменением мира, игрока, инвентаря или сущности.
5. Назначить владельца каждой задаче, listener, connection и внешнему клиенту.
6. Закрывать scope и ресурсы в addon/script unload.
7. Проверить rollback, timeout, повторный запуск и ошибку частичной конфигурации.
8. Прогнать `/ksl validate`, unit tests и Paper smoke test на staging.
9. Сделать резервную копию БД и plugin data перед заменой JAR.
10. Не считать sandbox или отсутствие предупреждений гарантией безопасности произвольного кода.
