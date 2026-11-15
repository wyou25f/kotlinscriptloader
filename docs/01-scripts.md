# Книга I. Разработка скриптов на KSL

**Путь от первого .kts до полноценных игровых систем**

Целевая среда, указанная в README текущего проекта: Paper 1.21.1 и Java 21. Для других версий проверяйте совместимость JAR и Paper API.

## Как читать эту книгу

KSL исполняет Kotlin Script с расширением .kts. Это не отдельный язык со своим синтаксисом: автор использует Kotlin, а KSL предоставляет контекст Minecraft-сервера, DSL, STD и runtime-инструменты.

В документации важно различать три слоя: API скриптов .kts, Public API для Kotlin/Java аддонов и внутреннюю реализацию KSL. Эта книга посвящена первому слою.

## Содержание

1. Что такое KSL
2. Установка и первый скрипт
3. Папки и структура проекта
4. Kotlin для скриптов
5. BukkitScriptContext и импорты
6. События
7. Команды
8. Планировщик и потоки
9. Жизненный цикл и ресурсы
10. YAML и feature flags
11. Сообщения и плейсхолдеры
12. Стандартная библиотека STD
13. Runtime и MesLib2
14. Интеграции
15. Ошибки и диагностика
16. Безопасность и production
17. Стиль кода и чек-лист
18. GUI и инвентари
19. PersistentDataContainer и теги

---

## Глава 1. Что такое KSL

KotlinScriptLoader — Paper-плагин и runtime для выполнения Kotlin-скриптов. Он обнаруживает .kts файлы, готовит конфигурацию Kotlin Scripting, компилирует исходник и выполняет его с BukkitScriptContext.

    .kts файл
       ↓
    обнаружение и конфигурация компилятора
       ↓
    Kotlin Scripting Compiler
       ↓
    экземпляр скрипта
       ↓
    BukkitScriptContext + DSL + STD
       ↓
    Paper / Bukkit

### Для чего подходят скрипты

- игровые события и реакции на игроков;
- команды и административные инструменты;
- награды, кулдауны, игровые правила;
- сообщения, placeholders и небольшие механики;
- прототипирование без отдельного JAR;
- связывание существующих плагинов через поддерживаемые интеграции.

### Когда нужен аддон

Если код имеет собственные зависимости, сложное состояние, общий сервис для нескольких скриптов, отдельный API, длинный жизненный цикл или должен распространяться как самостоятельная библиотека, вынесите его в Kotlin/Java addon. Этому посвящена книга II.

### Что происходит при reload

KSL компилирует изменённые файлы и применяет результат с учётом жизненного цикла. Ресурсы, зарегистрированные через KSL DSL, принадлежат скрипту и очищаются при его выгрузке. Если новая версия не проходит компиляцию, предыдущая рабочая версия может остаться активной.

---

## Глава 2. Установка и первый скрипт

### Требования

- Paper 1.21.1 — целевая версия из README;
- Java 21;
- JAR KSL, соответствующий серверу;
- доступ к папке plugins и консоли.

### Порядок установки

1. Установите Paper и Java.
2. Поместите KSL JAR в plugins/.
3. Запустите сервер, чтобы KSL создал папку данных.
4. Создайте plugins/KotlinScriptLoader/scripts/hello.kts.
5. Выполните /ksl reload.
6. Проверьте консоль и зайдите с тестовым игроком.

### Первый скрипт

~~~kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage(
        "<green>Добро пожаловать, <white>" + player.name + "</white>!</green>"
    )
}
~~~

### Первая команда

~~~kotlin
registerCommand("hello", aliases = listOf("hi")) { sender, args ->
    sender.sendMessage("Привет! Аргументов: " + args.size)
}
~~~

Команда регистрируется при загрузке скрипта и удаляется вместе с ним.

---

## Глава 3. Папки и структура проекта

### Основная папка

~~~text
plugins/
└── KotlinScriptLoader/
    ├── config.yml
    ├── ksl.db
    ├── locale/
    ├── scripts/
    │   ├── hello.kts
    │   ├── events/
    │   │   └── join.kts
    │   └── libs/
    │       ├── text/TextTools.kt
    │       └── player/PlayerTools.kt
    └── dumps/
~~~

- config.yml — конфигурация самого KSL.
- scripts/ — .kts и поддерживаемые локальные библиотеки .kt.
- scripts/libs/ — исходники Kotlin, которые компилируются как общий библиотечный набор.
- ksl.db — SQLite-файл при использовании БД по умолчанию.
- locale/ — системные переводы.
- dumps/ — диагностические дампы.

### .kts и .kt

- .kts — отдельная единица скрипта, которую KSL загружает и перезагружает.
- .kt в scripts/libs — обычный Kotlin-исходник, содержащий общие функции и типы.

Не кладите произвольный .kt рядом со скриптом и не ждите, что он автоматически станет доступен. Для локальных библиотек используйте scripts/libs и правила packages/imports.

### Имена и папки

При включённом recursive KSL ищет файлы во вложенных папках. Например:

    scripts/events/join.kts → ID events/join

Скрытые файлы и папки с префиксом подчёркивания обрабатываются правилами обнаружения отдельно. Используйте понятные ID: они нужны при точечной перезагрузке и диагностике.

### Рекомендуемая структура

~~~text
scripts/
├── welcome.kts
├── commands/
│   ├── rules.kts
│   └── staff.kts
├── events/
│   ├── join.kts
│   └── quit.kts
└── libs/
    └── common/Messages.kt
~~~

Не складывайте весь сервер в один файл. Разделяйте независимые механики по ответственности.

---

## Глава 4. Kotlin для скриптов

### Переменные и типы

~~~kotlin
val serverName = "My Server"
var onlineCount = 0
onlineCount += 1

val coins: Long = 1500L
val price: Double = 12.5
val enabled: Boolean = true
~~~

Предпочитайте val. Используйте var только для состояния, которое действительно меняется.

### Nullable значения

~~~kotlin
val displayName: String? = player.displayName
val safeName = displayName ?: player.name

if (displayName != null) {
    println(displayName.length)
}
~~~

String? допускает null, String — нет. Оператор ?: задаёт значение по умолчанию, ?. безопасно вызывает метод. Избегайте !!, если null реален.

### Условия и циклы

~~~kotlin
if (coins >= 100) {
    player.sendMessage("Доступ открыт")
} else {
    player.sendMessage("Недостаточно монет")
}

for (index in 1..5) {
    println(index)
}
~~~

### Функции и data class

~~~kotlin
fun formatCoins(value: Long): String = value.toString() + " coins"

data class Reward(
    val material: Material,
    val amount: Int
)
~~~

Выносите повторную логику в функции. Для небольших структур данных удобен data class.

### Коллекции

~~~kotlin
val names = listOf("Alex", "Sam", "Mia")
val mutableNames = mutableListOf("Alex")
mutableNames += "Sam"

val prices = mapOf("diamond" to 100L, "iron" to 5L)
~~~

Используйте неизменяемые коллекции там, где изменение не требуется.

### Импорты

~~~kotlin
import java.util.UUID
import org.bukkit.Material
import org.bukkit.event.player.PlayerJoinEvent
~~~

KSL предоставляет стандартные импорты. Wildcard import не рекурсивен: package.* не включает все подпакеты автоматически.

---

## Глава 5. BukkitScriptContext и импорты

Каждый .kts выполняется с BukkitScriptContext в роли implicit receiver. Поэтому контекстные функции можно вызывать напрямую:

~~~kotlin
log("Скрипт загружен")
val online = onlineCount
val found = findPlayer("Alex")
~~~

В контексте и стандартных пакетах доступны группы возможностей:

- сведения о скрипте, конфигурация и логирование;
- события, команды, placeholders;
- sync/async задачи и worker pool;
- SQL, persist и table;
- JSON/TOML операции;
- feature flags;
- runtimeScope, own и onUnload;
- MesLib2.

Если символ не распознаётся, проверьте package, явный import, наличие addon и доступность его JAR для script classloader. Import не скачивает и не устанавливает зависимость.

---

## Глава 6. События

### Простая регистрация

~~~kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage("<green>С возвращением!</green>")
}
~~~

### Данные события

~~~kotlin
onEvent<PlayerQuitEvent> {
    val playerName = player.name
    log(playerName + " отключился")
}
~~~

Не сохраняйте объект события для фоновой задачи. Считайте нужные простые значения во время callback.

### Priority и cancelled events

~~~kotlin
onEvent<PlayerInteractEvent>(
    priority = EventPriority.MONITOR,
    ignoreCancelled = true
) {
    // Только наблюдение.
}
~~~

MONITOR предназначен для наблюдения — не изменяйте событие на этом приоритете.

### Не выполняйте тяжёлую работу внутри события

Не вызывайте медленный SQL, сеть, долгую файловую операцию или блокирующий Future.get/join. Передайте UUID и нужные данные в фон, а Bukkit-изменения верните на главный поток.

---

## Глава 7. Команды

### Команда с permission

~~~kotlin
registerCommand("staff-note") { sender, args ->
    if (!sender.hasPermission("server.staff.note")) {
        sender.sendMessage("Недостаточно прав.")
        return@registerCommand
    }

    if (args.isEmpty()) {
        sender.sendMessage("Использование: /staff-note <текст>")
        return@registerCommand
    }

    sender.sendMessage(args.joinToString(" "))
}
~~~

Проверяйте право внутри обработчика. Скрытие команды в tab completion не защищает её.

### Валидация аргумента

~~~kotlin
registerCommand("set-coins") { sender, args ->
    val amount = args.getOrNull(0)?.toLongOrNull()

    if (amount == null || amount < 0L) {
        sender.sendMessage("Укажите неотрицательное число.")
        return@registerCommand
    }

    sender.sendMessage("Новое значение: " + amount)
}
~~~

Не регистрируйте команды повторно внутри события или цикла. Регистрируйте их на верхнем уровне скрипта.

---

## Глава 8. Планировщик и потоки

### Главный поток

Изменение блоков, инвентарей, здоровья, позиции игрока, сущностей и GUI обычно требует главного потока.

~~~kotlin
runSync {
    player.sendMessage("Работа в main thread")
}
~~~

### Фоновые операции

В фоне выполняйте поддерживаемые async SQL-запросы, сетевой I/O, хеширование, обработку файлов и чистые вычисления.

~~~kotlin
runAsync {
    val checksum = File("plugins/data.bin").sha256()
    runSync {
        log("SHA-256 готов: " + checksum)
    }
}
~~~

### Повторяющиеся задачи

~~~kotlin
val taskId = every(ticks = 20L) {
    // Работа примерно раз в секунду при 20 TPS.
}
~~~

Регистрируйте repeating task один раз, а не при каждом событии. Скриптовые задачи принадлежат скрипту и очищаются при unload.

### Worker pool

~~~kotlin
val taskId = worker(KSLWorkPriority.NORMAL) {
    val result = calculateSomethingExpensive()
    runSync {
        log("Расчёт завершён: " + result)
    }
}
~~~

Приоритеты: LOW, NORMAL, HIGH и CRITICAL. Число потоков, общая очередь и квота задач на скрипт ограничены. При перегрузке задача отклоняется, а не выполняется на вызывающем потоке.

### Управление задачами и результатами

~~~kotlin
val task = workerHandle(KSLWorkPriority.HIGH) {
    calculateSomethingExpensive()
}

task.completion.whenComplete { result, error ->
    runSync {
        if (error == null) log("Готово: " + result.state)
        else warn("Фоновая задача завершилась с ошибкой: " + error.message)
    }
}

// Отменить задачу:
task.cancel()
~~~

Для вычисления значения используйте workerFuture { ... }, возвращающий CompletableFuture<T>. Callback может выполниться в рабочем потоке либо в потоке, который его зарегистрировал, если future уже завершился. Bukkit-операции внутри callback обязательно переносите через runSync.

### Отложенные и повторяющиеся worker-задачи

~~~kotlin
val delayed = workerAfter(delayMillis = 1500L) {
    calculateSomethingExpensive()
}

val fixedRate = workerAtFixedRate(periodMillis = 5000L, initialDelayMillis = 1000L) {
    refreshCachedData()
}

val fixedDelay = workerWithFixedDelay(delayMillis = 5000L) {
    pollRemoteService()
}

// Все три задачи отменяются через handle.cancel().
fixedRate.cancel()
~~~

workerAtFixedRate придерживается фиксированного интервала и пропускает новый тик, если предыдущее выполнение ещё идёт. workerWithFixedDelay отсчитывает паузу от завершения предыдущего выполнения. Повторяющиеся задачи не накладываются друг на друга.

Планировщик хранит состояние и короткую историю задач, время выполнения, число пропущенных тиков и статистику по скриптам. Задачи автоматически отменяются при выгрузке владельца и остановке планировщика. workerAfter, workerAtFixedRate и workerWithFixedDelay возвращают KSLWorkHandle с cancel(), info() и completion.


### Передача данных

Передавайте UUID, строки, числа и immutable DTO. Не изменяйте Bukkit Player, World, Entity или Inventory из фонового callback.

Не используйте Thread.sleep и не блокируйте главный поток через join()/get().

---

## Глава 9. Жизненный цикл и ресурсы

Ресурс — это listener, задача, подписка, команда, placeholder, GUI callback, соединение, file watcher или внешний клиент.

KSL отслеживает ресурсы, созданные через поддерживаемые context API: onEvent, registerCommand, placeholders, scheduler, worker, subscribe, runtimeScope и cleanup callbacks.

### Внешние ресурсы

~~~kotlin
val client = own(createMyClient())

val cleanup = onUnload {
    thirdPartySubscription.close()
}

// Если cleanup больше не нужен:
cleanup.close()
~~~

runtimeScope принадлежит текущему скрипту и закрывается при reload, удалении и shutdown. Cleanup вызывается в обратном порядке регистрации. Ошибка одного cleanup не должна мешать очистке остальных ресурсов.

Не думайте, что любой объект, созданный в .kts, автоматически отслеживается KSL. Внешние клиенты и потоки требуют явной очистки.

### warn, error и fatal

- warn — сообщить и продолжить.
- error — прервать текущую операцию.
- fatal — фатальная ошибка текущего скрипта с его выгрузкой и очисткой.

Не используйте fatal для обычного неверного аргумента команды.

---

## Глава 10. YAML и feature flags

Для scripts/shop.kts companion YAML называется shop.yml и располагается в папке данных KSL рядом с scripts/, а не внутри scripts/.

~~~yaml
messages:
  prefix: "<gold>[Магазин]</gold>"
shop:
  enabled: true
  max-items: 24
~~~

~~~kotlin
val enabled = config.getBoolean("shop.enabled", true)
val maxItems = config.getInt("shop.max-items", 24)
val prefix = config.getString("messages.prefix") ?: "[Магазин]"

editConfig {
    set("shop.max-items", 32)
}
~~~

Для группового редактирования используйте editConfig. Вызов saveConfig нужен, если выбран режим без автоматического сохранения.

### Feature flags

~~~kotlin
if (featureEnabled("shop.enabled", default = true)) {
    // Логика магазина.
}

enableFeature("shop.enabled")
disableFeature("shop.enabled")
val current = toggleFeature("shop.enabled")
~~~

Feature flag управляет поведением, но не является системой авторизации. Защищённую команду всё равно проверяйте по permission.

Для сложной конфигурации используйте schema validation с типами, defaults и диапазонами. Не скрывайте неверные настройки молчаливым clamp.

---

## Глава 11. Сообщения и плейсхолдеры

Literal text и MiniMessage — разные режимы:

- Component.text(text) создаёт буквальный текст.
- MiniMessage.deserialize(text) интерпретирует markup.

STD предлагает toTextComponent() и toMiniMessageComponent(). Не разбирайте произвольный пользовательский ввод как MiniMessage без необходимости.

~~~kotlin
player.tell("Обычный текст")
player.tellRich("<green>Форматированный текст</green>")
~~~

PlaceholderAPI hooks и текстовые placeholders могут быть необязательными. Проверяйте наличие интеграции. Не выполняйте тяжёлый SQL при каждом запросе placeholder — используйте кэш и понятную стратегию обновления.

---

## Глава 12. Стандартная библиотека STD

STD — это обычные Kotlin-функции и extension-функции. Она не заменяет Kotlin и не отменяет правила Bukkit.

| Пакет | Для чего |
|---|---|
| std.text | Unicode, UUID, нормализация пробелов, snake_case/camelCase |
| std.math | clamp, mapToRange, lerp, inverseLerp, random |
| std.time | секунды/минуты/часы/дни/недели в ticks, Duration |
| std.player | сообщения, permissions, инвентарь, здоровье |
| std.inventory | поиск слотов, вместимость, подсчёт и удаление предметов |
| std.item | clone-based ItemStack transformations |
| std.component | Adventure Component и MiniMessage |
| std.world | блоки, локации, регионы и entity queries |
| std.json | Kotlin values и typed JSON accessors |
| std.toml | TOML codec и typed map helpers |
| std.security | SHA-256, PBKDF2, HMAC, токены, Base64 |
| std.validation | валидаторы и накопление ошибок |

### Text

~~~kotlin
val words = "  один\u00A0два три ".words()
val normalized = "  один\u00A0два три ".normalizeWhitespace()
val shortName = player.name.truncateCodePoints(16)
val id = input.toUuidOrNull()
val validName = input.isMinecraftUsername()
~~~

Дополнительно: containsIgnoreCase, startsWithIgnoreCase, endsWithIgnoreCase, removePrefixIgnoreCase, removeSuffixIgnoreCase, toSnakeCase и toCamelCase.

truncateCodePoints считает Unicode code points, но не grapheme clusters. isMinecraftUsername проверяет только форму имени: 3–16 ASCII букв, цифр и подчёркиваний. Это не проверка существующего аккаунта.

### Math

~~~kotlin
val percent = value.clamped(0.0, 100.0)
val normalized = value.mapToRange(0.0, 100.0, 0.0, 1.0)
val middle = lerp(0.0, 100.0, 0.5)
val fraction = inverseLerp(0.0, 100.0, 75.0, clamp = true)
val slot = randomInt(0, 9)
val chance = randomChance(20.0)
~~~

Диапазоны проверяются. Верхняя граница randomInt/randomLong/randomDouble не включается. randomChance принимает конечное значение 0..100. Это обычный игровой RNG, не криптографический генератор.

### Time

~~~kotlin
val cooldown = 30.secondsToTicks()
val daily = 1.daysToTicks()
val delay = Duration.ofMillis(1250).toTicksCeiling()
val duration = 40L.ticksToDuration()
~~~

KSL использует модель 20 ticks per second. Точное умножение проверяет переполнение Long. Для миллисекунд есть округление вниз и вверх.

### Player и Inventory

~~~kotlin
player.tell("Обычный текст")
player.tellRich("<green>Форматированный текст</green>")

val canFit = player.canFit(reward, 16)
val leftovers = player.give(reward)
val dropped = player.giveOrDrop(reward)
val amount = player.count(reward)
val removed = player.remove(reward, 3)
~~~

give возвращает остатки, которые не поместились. giveOrDrop выбрасывает остатки в мир. Inventory.spaceFor учитывает частичные похожие стеки, а emptySlots считает только полностью пустые слоты.

### ItemStack

~~~kotlin
val reward = ItemStack(Material.DIAMOND_SWORD)
    .withDisplayName("Клинок исследователя")
    .withLore("Редкая награда", "Найдена в руинах")
    .withUnbreakable()
    .withEnchantment(Enchantment.SHARPNESS, 3)
~~~

Преобразования возвращают clone и не меняют исходный ItemStack. Строковые имена/lore — literal text; MiniMessage нужно явно превратить в Component.

### Component

~~~kotlin
val literal = userInput.toTextComponent()
val rich = "<green>Успех!</green>".toMiniMessageComponent()
val combined = rich.appendText(" Готово.")
~~~

### World

~~~kotlin
val target = player.location.offset(0.0, 2.0, 0.0)
val block = target.blockOrNull()
val nearby = player.world.playersInRadius(player.location, 12.0)
val position = target.blockPosition()
~~~

World, Entity, Player, Inventory и Block — живые Bukkit объекты. Вызывайте такие операции на серверном потоке.

### JSON

~~~kotlin
val profile = jsonObjectOf(
    "uuid" to player.uniqueId.toString(),
    "name" to player.name,
    "online" to true,
    "coins" to 150L
)
val element = profile.toJsonElement()
~~~

Map-ключи должны быть String. Double/Float должны быть конечными. Произвольный объект сериализуйте через явное представление.

### TOML

~~~kotlin
val settings = KSLTomlCodec.parse(
    """
    [shop]
    enabled = true
    price = 25
    """.trimIndent()
)
val shop = settings.tomlTable("shop").orEmpty()
val enabled = shop.tomlBoolean("enabled", false)
~~~

TOML encoder не поддерживает null и произвольные inline tables внутри arrays. Комментарии могут потеряться при повторной записи.

### Security и Validation

~~~kotlin
val digest = "payload".sha256()
val token = secureToken(32)
val passwordHash = hashPassword(passwordInput)
val accepted = verifyPassword(passwordInput, passwordHash)
~~~

SHA-256 предназначен для контрольной суммы, не для хранения пароля. Для пароля есть PBKDF2-HMAC-SHA256. Хеширование выполняйте асинхронно. Base64 — кодировка, не шифрование.

~~~kotlin
val validator = kslValidator(
    notBlank(),
    codePointLength(3, 16),
    matches(Regex("[A-Za-z0-9_]+"))
)

when (val result = username.validateWith(validator)) {
    is KSLValidationResult.Valid -> createProfile(result.value)
    is KSLValidationResult.Invalid -> {
        result.errors.forEach { issue -> player.tell(issue.message) }
    }
}
~~~

Валидатор запускает все правила и собирает ошибки. and(...) возвращает новый валидатор. Пользовательские validationRule должны быть быстрыми и не выполнять SQL/сеть.

---

## Глава 13. Runtime и MesLib2

### KSLRT

Runtime API содержит кэш, профайлер, worker scheduler, runtime scopes и telemetry. Каноническая фабрика — KSLRT; короткие aliases: kslrt, rt, runtime.

### Кэш

~~~kotlin
val cache = KSLRT.cache<String, Long>(
    maxSize = 2000,
    expireAfterAccess = Duration.ofMinutes(5)
)
cache["player-id"] = 250L
val balance = cache["player-id"]
~~~

Кэш ограничен по размеру и thread-safe. TTL по умолчанию выключен. getOrPut может запустить loader больше одного раза при конкурентном miss. Для атомарного loader используйте соответствующий helper, но не вызывайте SQL/сеть под lock.

### Профайлер

~~~kotlin
val profiler = KSLRT.profiler("shop.price")
val price = profiler.measure { calculatePrice(playerId) }
val snapshot = profiler.snapshot()

log("calls=" + snapshot.calls + ", avg=" + snapshot.averageMillis + " ms")
~~~

Профайлер измеряет прошедшее время, а не загрузку CPU. Не создавайте новое имя на каждый вызов: registry ограничен.

### runtimeScope

~~~kotlin
val profileCache = runtimeScope.lru<String, PlayerProfile>(maxSize = 1000)
val client = own(createClient())

onUnload {
    // Дополнительная очистка.
}
~~~

runtimeScope очищается при reload текущего скрипта.

### MesLib2

MesLib2 — типизированная система экспорта между скриптами:

~~~kotlin
// economy.kts
meslib2.exportLocal("service", EconomyLibrary(), version = "2.0")

// shop.kts
val economy = meslib2.require<EconomyLibrary>("economy", "service", "2.0")
~~~

Экспорт принадлежит скрипту-поставщику и удаляется при его выгрузке. Версия — точная метка; MesLib2 не интерпретирует её как SemVer и не загружает внешние JAR.

---

## Глава 14. Интеграции

Интеграция может быть необязательной. Проверяйте hook и не делайте внешний плагин hard dependency, если без него KSL может работать.

~~~kotlin
val auth = hook<KSLAuthMeHook>("authme")

if (auth == null) {
    log("AuthMe отсутствует — базовый режим")
} else {
    val registered = auth.isRegistered(player.name)
}
~~~

Конкретные методы hooks сверяйте с установленной версией KSL и документацией addon.

---

## Глава 15. Ошибки и диагностика

Сначала определите фазу: обнаружение файла, компиляция, top-level evaluation, поздний callback или unload/cleanup.

| Команда | Назначение |
|---|---|
| /ksl reload | Перезагрузить скрипты |
| /ksl reload <id> | Перезагрузить один |
| /ksl reload changed | Перезагрузить изменённые |
| /ksl validate | Проверить скрипты |
| /ksl doctor | Проверить окружение |
| /ksl errors | Последние ошибки |
| /ksl error <code> | Объяснить код |
| /ksl runtime | Runtime и worker telemetry |
| /ksl language | Выбрать язык сообщений |

Большинство административных команд требует ksl.admin.

Порядок отладки:

1. Сохраните полный текст ошибки.
2. Определите ID скрипта.
3. Проверьте import, имя класса и тип.
4. Проверьте вызовы Bukkit из async.
5. Проверьте companion YAML.
6. Запустите /ksl doctor.
7. Проверьте необязательные плагины и addons.
8. Создайте минимальный пример.

---

## Глава 16. Безопасность и production

Скрипты KSL выполняются в той же JVM, что сервер и плагины. Sandbox может ограничивать отдельные возможности, но не является полноценной изоляцией от вредоносного кода.

- Разрешайте изменять scripts/ только доверенным людям.
- Ограничьте ksl.admin.
- Проверяйте permissions внутри административных команд.
- Не храните пароли, токены и webhook URL в публичном Git.
- Не размещайте config.yml с секретами в скриншотах.
- Не создавайте бесконечные потоки, таймеры и executors.
- У каждого listener/task должен быть владелец.
- Внешние клиенты и подписки закрывайте при unload.

Не измеряйте производительность по ощущению. Сравнивайте startup, reload, длительность обработчиков, SQL latency и tick time.

---

## Глава 17. Стиль и проверка скриптов

Перед публикацией:

- [ ] Скрипт загружается на target версии KSL.
- [ ] Команды проверяют permissions.
- [ ] Неверные аргументы обрабатываются корректно.
- [ ] Bukkit API вызывается на подходящем потоке.
- [ ] Нет необслуживаемых repeating tasks.
- [ ] Внешние ресурсы закрываются.
- [ ] Секреты вынесены из исходников.
- [ ] Конфигурация имеет defaults и валидацию.
- [ ] После reload старые обработчики не дублируются.
- [ ] Диагностика не содержит секретов.


---

## Глава 18. GUI и инвентари

KSL предоставляет DSL для собственных инвентарь-меню через `gui(title, rows) { ... }`. Размер меню — от 1 до 6 строк по 9 слотов. Меню нужно создавать и изменять в основном потоке Bukkit; `open(player)` сам переносит открытие на следующий тик, если вызван из async-контекста или обработчика клика.

~~~kotlin
val menu = gui("<gold>Профиль</gold>", rows = 3) {
    border(ItemStack(Material.BLACK_STAINED_GLASS_PANE))

    button(13, ItemStack(Material.BOOK)) { player ->
        player.sendMessage("Это кнопка меню")
    }

    onClose { player ->
        player.sendActionBarMessage("<gray>Меню закрыто")
    }
}

menu.open(player)
~~~

Основные методы:

| Метод | Назначение |
|---|---|
| `set(slot, item) { player, event -> ... }` | Установить предмет и необязательный обработчик клика |
| `button(slot, item) { player -> ... }` | Кнопка без параметра события |
| `clear(slot)` | Очистить слот вместе с обработчиком |
| `fill(item)` / `border(item)` | Заполнить пустые слоты или рамку |
| `allowItemMovement(slot)` | Явно разрешить перемещение предметов в конкретном слоте |
| `paginate(...)` | Отрисовать список на нескольких страницах с кнопками навигации |
| `showPage(index)` / `refresh()` | Перейти на существующую страницу или перерисовать текущую |
| `onClose { player -> ... }` | Обработать закрытие меню |

### Пагинация

`slots` — список слотов для содержимого, `prevSlot` и `nextSlot` — отдельные слоты навигации. Значение `-1` отключает соответствующую кнопку. KSL проверяет диапазоны и пересечения: список содержимого не должен содержать дубликаты, а слоты навигации не могут совпадать друг с другом или с содержимым.

~~~kotlin
val products = listOf(Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT)

val menu = gui("<green>Магазин</green>", rows = 4) {
    paginate(
        items = products,
        slots = (10..16).toList(),
        prevSlot = 28,
        nextSlot = 34,
        render = { material -> ItemStack(material) },
        onClick = { player, material ->
            player.sendMessage("Выбрано: ${material.name}")
        }
    )
}

menu.showPage(0)
menu.refresh()
menu.open(player)
~~~

Список элементов копируется при вызове `paginate`: последующее добавление или удаление элементов во внешнем MutableList не меняет уже созданную пагинацию. Для обновления содержимого вызовите `paginate` повторно либо `refresh()`, если изменились данные, используемые функцией `render`.

`showPage(index)` принимает только существующий индекс. Неверное значение не исправляется автоматически: KSL оставляет текущую страницу и пишет предупреждение. Перемещение предметов запрещено по умолчанию; вызывайте `allowItemMovement` только для действительно интерактивных слотов и после установки предмета в слот. При `fill`, `border`, `set` и `clear` обработчики/права движения обновляются вместе с содержимым.

Открытые меню и их callbacks очищаются при выгрузке скрипта. Старая ссылка на GUI после reload не сможет снова открыть меню или выполнить старый обработчик.

## Глава 19. PersistentDataContainer и пользовательские теги

В Bukkit/Paper для пользовательских данных предметов и сущностей предназначен `PersistentDataContainer` (PDC). В KSL доступны типизированные методы для `ItemStack`, `Player` и других объектов `PersistentDataHolder`:

- строки: `setStringTag / getStringTag / hasStringTag`;
- целые числа: `setIntTag / getIntTag / hasIntTag`;
- длинные числа и дробные значения: `setLongTag`, `setDoubleTag` и соответствующие методы чтения/проверки;
- Boolean: `setBooleanTag / getBooleanTag / hasBooleanTag`;
- UUID: `setUuidTag / getUuidTag / hasUuidTag`;
- удаление: `removePersistentTag(key)`.

~~~kotlin
import java.util.UUID

onEvent<PlayerJoinEvent> {
    val visits = player.getIntTag("visits") ?: 0
    player.setIntTag("visits", visits + 1)
}

val reward = ItemStack(Material.PAPER)
reward.setStringTag("reward_kind", "daily")
reward.setBooleanTag("claimed", false)
reward.setUuidTag("reward_id", UUID.randomUUID())

val kind = reward.getStringTag("reward_kind")
~~~

Новые методы хранят значения в ключевом пути, отдельном для текущего скрипта: скрипты не перезаписывают пользовательские данные друг друга. Ключ должен содержать от 1 до 128 символов из латинских букв, цифр, `.`, `_`, `-` и `/`; регистр нормализуется в нижний.

**Совместимость:** старые строковые методы `ItemStack.setTag/getTag/hasTag` оставлены без изменения и продолжают читать/писать прежний ключ в namespace плагина. Новые типизированные методы используют отдельный путь `scriptdata` и не затрагивают старые значения или внутренние метки KSL (`ksl_owner`, `ksl_ephemeral`).

PDC — это штатное хранилище пользовательских метаданных Bukkit/Paper, а не API произвольного редактирования внутренних NBT-тегов Minecraft. Для данных, которые должны быть общей серверной базой с транзакциями и SQL-запросами, используйте `table` или SQL API; сохранение PDC зависит от жизненного цикла соответствующего предмета/сущности.

## Словарь основных понятий

| Термин | Объяснение |
|---|---|
| .kts | Отдельный Kotlin-скрипт |
| BukkitScriptContext | Контекстный receiver KSL |
| STD | Встроенная стандартная библиотека |
| Event | Событие Paper/Bukkit |
| Listener | Подписчик на Bukkit-события |
| Main thread | Основной поток сервера |
| Worker | Фоновая задача |
| Reload | Замена активной версии скрипта |
| Resource ownership | Кто отвечает за закрытие ресурса |
| runtimeScope | Ресурсный scope скрипта |
| PDC | PersistentDataContainer — типизированное хранилище пользовательских данных Bukkit/Paper |
| MesLib2 | Обмен типизированными объектами между скриптами |
| Hook | Необязательная интеграция |
| Addon | Отдельный Paper-плагин, расширяющий KSL |
| Sandbox | Набор ограничений, но не изоляция JVM |

**Конец книги I.** Далее — книга о создании полноценных аддонов и библиотек на Kotlin и Java.
