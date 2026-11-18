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

KSL компилирует изменённые файлы и применяет результат с учётом жизненного цикла. Ресурсы, зарегистрированные через KSL DSL, принадлежат скрипту и очищаются при его выгрузке.

Каждая успешно запущенная версия получает собственный номер поколения (generation), отдельный `KSLRuntimeScope` и монотонный номер внутри текущего запуска сервера. Контекст скрипта захватывает scope при создании: старый `BukkitScriptContext` не может после reload зарегистрировать новые ресурсы в scope нового поколения.

В generation scope автоматически привязываются основные ресурсы KSL DSL: Bukkit tasks, listeners, commands, GUI, placeholders, event-bus subscriptions, worker handles/futures, particle effects, entity spawns, entity tick tasks и hooks `onDeath/onDamaged/onAttack/drops`. Закрытие scope снимает соответствующую регистрацию или отменяет задачу; завершившиеся одноразовые worker jobs сразу снимают свой cleanup handle. GUI inventory закрывается через основной поток Bukkit, даже если scope был закрыт из worker-потока. Ресурсы, зарегистрированные через `own()` и `onUnload()`, также принадлежат текущему поколению.

При ошибке компиляции предыдущая версия остаётся активной. Если компиляция прошла, но новая версия завершилась ошибкой во время инициализации, KSL очищает частичные ресурсы кандидата и пытается повторно запустить последний успешно скомпилированный артефакт в новом поколении. В этом случае скрипт остаётся рабочим, а его состояние в реестре будет `DEGRADED`: последняя попытка обновления не удалась, но предыдущая версия восстановлена.

**Граница гарантии:** это безопасное восстановление ресурсов, а не полностью бесшовная транзакция Bukkit. Существующие listeners, tasks и commands снимаются по текущему script-owner перед инициализацией кандидата, поэтому в момент переключения возможен короткий промежуток без них. Если кандидат падает, старая версия регистрирует их заново. Произвольные изменения мира, отправленные сообщения, внешние HTTP-запросы и уже выполненные записи в БД автоматически не отменяются. У сущностей с `removeOnReload = false` сам объект мира сохраняется по существующей политике KSL; in-memory ownership, старые tick tasks и hooks очищаются вместе с поколением. При `removeOnReload = true` закрытие поколения также удаляет сущность; если её chunk выгружен, сохраняется существующий механизм очистки при следующей загрузке entity.

Поэтому скриптам рекомендуется делать стартовую регистрацию идемпотентной и не выполнять необратимые игровые действия на верхнем уровне файла.

Номер поколения, состояние и количество ресурсов runtime scope доступны в диагностике `/ksl runtime`. Для подробного состояния самого скрипта используй `/ksl scripts` и `/ksl debug <script-id>`.

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

### Entity

```kotlin
val savedLocation = entity.locationSnapshot()
val closeEnough = entity.isWithin(player.location, 8.0)
val distanceSquared = entity.distanceSquaredTo(player.location)
val inSurvival = entity.isInWorld("survival")

// Teleports using a copy of the destination Location.
val moved = entity.teleportTo(targetLocation)
```

`distanceSquaredTo` требует один и тот же объект World и возвращает квадрат расстояния. `isWithin` возвращает false для разных миров. Радиус должен быть конечным и неотрицательным. `locationSnapshot` не раскрывает изменяемый Location сущности, а `teleportTo` не изменяет переданный Location. Телепортация и чтение живых Entity должны выполняться на серверном потоке.

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


## Script Testing Framework

KSL включает последовательный test runner для проверок логики, используемой в скриптах. Он не подменяет сервер Bukkit/Paper и не создаёт отдельный JVM-процесс: тесты выполняются в том же контексте, где был вызван набор. Не запускай в них долгие операции и не меняй состояние production-мира.

Пример тестового .kts-файла:

~~~kotlin
val report = kslTestSuite("economy") {
    beforeEach {
        put("balance", 100)
    }

    test("starting balance is correct") {
        assertEquals(100, requireValue<Int>("balance"))
    }

    test("balance is not negative") {
        assertTrue(requireValue<Int>("balance") >= 0)
    }
}

println(report)
~~~

Внутри набора доступны:

- beforeAll и afterAll — подготовка и очистка всего набора один раз; afterAll запускается даже при ошибке beforeAll, а несколько cleanup hooks выполняются в обратном порядке.
- beforeEach и afterEach — подготовка и очистка каждого теста; afterEach выполняется даже после ошибки теста.
- test("name") — независимый тест. Выполнение последовательное, чтобы не запускать Bukkit API параллельно.
- put(key, value), value(key) и requireValue<T>(key) — per-test fixtures; у beforeAll/afterAll отдельный контекст набора.
- Проверки: assertTrue, assertFalse, assertEquals, assertNotEquals, assertSame, assertNotSame, assertNull, assertNotNull, assertContains, assertStartsWith, assertEndsWith, assertFails и reified assertThrows<T>.
- skip("reason") — пометить тест как пропущенный.
- report.debugReport() — подробный текстовый отчёт с типом исключения, сообщением и stack trace (до 24 кадров).
- report.toJson() — JSON-отчёт со сводкой, длительностью, причинами и stack trace для CI и внешних логов.
- У результата есть suiteFailure для ошибок beforeAll/afterAll; successful учитывает и ошибки жизненного цикла набора.

Пример:

~~~kotlin
val report = kslTestSuite("economy") {
    beforeAll { put("started", true) }
    beforeEach { put("balance", 100) }

    test("balance is valid") {
        assertTrue(requireValue<Int>("balance") >= 0)
        assertContains("balance:100", "100")
        assertThrows<IllegalArgumentException> {
            throw IllegalArgumentException("invalid amount")
        }
    }

    afterEach { remove("balance") }
    afterAll { remove("started") }
}

println(report.debugReport())
println(report.toJson())
~~~

Отчёт не может гарантировать номер строки исходного .kts: он показывает только те stack frames, которые реально предоставляет JVM. Runner остаётся последовательным и не изолирует тесты от сервера; не запускайте долгие операции и не меняйте production-мир.

Test runner полезен для проверки чистой логики и небольших сценариев скрипта. Он не является песочницей: тестовый код имеет те же права, что и запущенный скрипт.


## Script Engine 2.1

Engine 2.1 дополняет агрегированные метрики успешности:
- `compileSuccesses` и `evaluationSuccesses` — число успешных операций;
- `compileSuccessRate` и `evaluationSuccessRate` — доля успешных операций от 0.0 до 1.0;
- `averageCompileNanos` и `averageEvaluationNanos` — среднее время операции;
- `resetMetrics()` сбрасывает счётчики и временные метрики.

Это метрики фасада движка, а не профилировщик отдельных Kotlin-инструкций. Компиляцию и выполнение по-прежнему обеспечивает Kotlin scripting host.

## Script Testing Framework 2.1

Тесты поддерживают теги, параметризованные кейсы и фильтрацию:

~~~kotlin
val report = kslTestSuite(
    "math",
    KSLScriptTestFilter(includeTags = setOf("unit"), excludeTags = setOf("slow"))
) {
    testEach("addition", listOf(1, 2, 3), tags = setOf("unit")) { value ->
        assertEquals(value + value, value * 2)
    }

    test("database integration", tags = setOf("integration", "slow")) {
        // Будет исключён указанным фильтром.
    }
}
~~~

`testEach` создаёт независимые кейсы с индексными именами. `includeTags` требует наличия всех перечисленных тегов, `excludeTags` исключает кейс при совпадении хотя бы одного тега, а `nameContains` фильтрует имена без учёта регистра. Число исключённых кейсов доступно через `report.filtered` и отражается в текстовом/JSON отчёте.

Добавлены проверки `assertEmpty`, `assertNotEmpty`, `assertGreaterThan`, `assertLessThan`, `assertApproxEquals`.

## Дополнительные Script DSL

### Команды

~~~kotlin
scriptCommand("balance") {
    aliases("money", "bal")
    permission("economy.balance")
    tabComplete { _, args -> if (args.size == 1) listOf("top", "help") else emptyList() }
    player { player, args ->
        player.sendMessage("Баланс: ${args.firstOrNull() ?: "self"}")
    }
}
~~~

Для консоли и command blocks используйте `any { sender, args -> ... }` и при необходимости `tabCompleteAny { sender, args -> ... }`. Нужно объявить ровно один тип исполнителя. Команда принадлежит скрипту и снимается при его выгрузке.

### Группы задач

~~~kotlin
val taskIds = scriptTasks {
    after(20) { broadcast("Прошла секунда") }
    repeat(ticks = 20, initialDelay = 0) { /* sync callback */ }
    afterAsync(100) { /* фоновая операция; Bukkit world/player API не трогать */ }
}
~~~

`scriptTasks` возвращает ID созданных задач. Синхронные callbacks выполняются на основном потоке Bukkit; async callbacks — в фоне и не должны напрямую обращаться к состоянию мира или игроков.


## JSON utilities 2.2

В стандартной JSON-библиотеке доступны:
- `parseJson(text)` — разбор с понятной ошибкой; `parseJsonOrNull(text)` — безопасный вариант.
- `element.toJsonString(pretty = true)` — компактная или форматированная сериализация.
- `element.atPath("profile.roles.0.name")` и `requireAtPath(...)` — доступ к вложенным объектам и индексам массивов.
- `object.deepMerge(override)` — рекурсивное объединение объектов; массивы и примитивы заменяются значением из override.
- `stringAtPath`, `intAtPath`, `longAtPath`, `doubleAtPath`, `booleanAtPath` — типизированное чтение вложенных значений без неявного преобразования типов.

Пример:

~~~kotlin
val defaults = parseJson("""{"profile":{"rank":"member","active":true}}""") as JsonObject
val overrides = parseJson("""{"profile":{"rank":"admin"}}""") as JsonObject
val config = defaults.deepMerge(overrides)

println(config.stringAtPath("profile.rank")) // admin
println(config.toJsonString(pretty = true))
~~~

## Event DSL 2.2

Сгруппируйте обработчики событий в одном блоке:

~~~kotlin
scriptEvents {
    onJoin {
        player.sendMessage("Добро пожаловать!")
    }

    onBlockBreak(ignoreCancelled = true) {
        if (block.type.name == "DIAMOND_ORE") {
            player.sendMessage("Алмазная руда!")
        }
    }

    onChat {
        // AsyncChatEvent выполняется на async-потоке Paper.
        // Для изменений мира или инвентаря используй runSync { ... }.
    }
}
~~~

Доступны `onJoin`, `onQuit`, `onBlockBreak`, `onBlockPlace`, `onInteract`, `onDeath` и `onChat`. Для cancellable-событий можно указать `ignoreCancelled` и `priority`. Все слушатели принадлежат скрипту и снимаются при его выгрузке.


## KSL MCP Server

KSL can optionally expose a local [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) endpoint so a compatible desktop MCP client can inspect the Paper server and KSL scripts.

### Enable

The endpoint is **disabled by default**. Set a strong token in the environment of the process that starts Paper, then enable MCP in `plugins/KotlinScriptLoader/config.yml`:

PowerShell:
```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$env:KSL_MCP_TOKEN = -join ($bytes | ForEach-Object { $_.ToString("x2") })
$rng.Dispose()
java -jar paper.jar
```

Linux:
```bash
export KSL_MCP_TOKEN="$(openssl rand -hex 32)"
java -jar paper.jar
```

Then set:
```yaml
mcp:
  enabled: true
  bind: "127.0.0.1"
  port: 8765
  path: "/mcp"
  token-env: "KSL_MCP_TOKEN"
  allowed-origins: []
  allow-script-reload: false
```

Restart Paper after changing the environment variable or MCP settings. Check the endpoint state with `/ksl mcp`. The endpoint URL is `http://127.0.0.1:8765/mcp`.

### Available tools

- `ksl_server_status` — read-only server version, player count and KSL script totals. It does not expose player names, secrets, console output or file paths.
- `ksl_list_scripts` — up to 50 safe relative script IDs with load state and basic counters. It does not return script source.
- `ksl_reload_script` — optional state-changing tool. It is not advertised unless `allow-script-reload: true`; every call also requires `confirm: true` and a safe relative script ID. Reloading executes the selected script inside the Paper JVM.

### Security boundaries

- The listener accepts only literal loopback addresses (`127.0.0.1` or `::1`), rejects non-loopback clients, validates the `Host` and optional exact `Origin` headers, and requires a constant-time checked Bearer token.
- Request bodies are limited to 64 KiB, requests are rate-limited, and the worker pool and queue are bounded. Responses do not include stack traces or absolute file paths.
- The endpoint is disabled by default. Do not expose it on a public interface or forward the port to the internet. It is intended for a local native MCP client; browser CORS/preflight is not enabled.
- The token is read from the Paper process environment and is not written to KSL config or logs. **This is not a security boundary against code already running inside the same JVM.** KSL scripts and other server plugins may be able to read environment variables or inspect process state. Do not run untrusted scripts or plugins on a server where MCP is enabled.
- KSL's script sandbox is not operating-system process isolation. A bearer token does not make untrusted in-process code safe.
- Keep `allow-script-reload: false` unless the local MCP client and every script it may reload are trusted. Script reload can execute arbitrary behavior permitted to that script within the Paper process.
- KSL uses a local bearer token, not a full OAuth 2.1 authorization server. For remote administration, use a separately secured private tunnel and keep the KSL listener bound to loopback.

The implementation follows the stateless Streamable HTTP transport in MCP protocol version `2026-07-28`: each POST carries protocol/client metadata, and the server validates the `MCP-Protocol-Version`, `Mcp-Method` and (for tool calls) `Mcp-Name` headers.


## Карта расширенных возможностей

В этой книге собраны основы `.kts`, игровые DSL, STD, цветные сообщения, предметы, звук, частицы, сущности, миксины, логирование и безопасная работа с runtime. Подробные API аддонов описаны в [Книге II](02-addons.md), хранилища и форматы данных — в [Книге III](03-data-and-databases.md), готовые сценарии — в [Книге IV](04-cookbook.md).


---

# Дополнительная глава: Звук, частицы и управляемые сущности

KSL объединяет игровые эффекты в короткие DSL-вызовы. Звуки, повторяющиеся эффекты и массовый спавн используют задачи владельца-скрипта: при перезагрузке или выгрузке KSL удалит связанные задачи.

## Звуковой движок

Создай переиспользуемое описание звука один раз. Громкость, высота тона и категория задаются цепочкой или функциями builder:

```kotlin
val click = sound(Sound.UI_BUTTON_CLICK) {
    volume(0.7)
    pitchRange(0.95, 1.05)
    category(SoundCategory.PLAYERS)
}

click.playTo(player)
click.playAt(player.location)
click.playNearby(player.location, radius = 24.0)
```

Вместо enum можно использовать строковый ключ: `sound("entity.player.levelup")`. Если namespace не указан, автоматически добавляется `minecraft:`. Для пользовательских ресурс-паков указывай свой namespace. Функция `music(...)` создаёт такой же preset, но по умолчанию использует категорию `SoundCategory.MUSIC` (её можно переопределить внутри builder).

- `playAt(location)` — слышат игроки в мире в пределах обычной дистанции Minecraft.
- `playTo(player, at)` — звук слышит только указанный игрок, но позиция по-прежнему влияет на затухание.
- `playNearby(location, radius)` — проигрывает звук игрокам в заданном радиусе.
- `repeatAt`, `repeatTo`, `repeatNearby` — повторяют звук. `times = -1` означает бесконечный повтор; вернувшийся ID можно отменить через `cancelTask(id)`.
- `pitchRange(min, max)` — выбирает случайную высоту тона для каждого воспроизведения.

Все значения проверяются: громкость 0..16, pitch 0..2, радиус неотрицательный. Методы воспроизведения вызывай в главном потоке сервера; для событий из async-потока используй `runSync { ... }`.

### Последовательности

Последовательность задаёт звуки относительно начала таймлайна. `at` использует абсолютный тик, `after` — задержку от предыдущего звука, `then` — следующий тик:

```kotlin
val intro = soundSequence {
    at(0L, Sound.BLOCK_NOTE_BLOCK_HARP) {
        volume(0.8)
        pitch(1.0)
    }
    after(4L, Sound.BLOCK_NOTE_BLOCK_PLING) {
        pitch(1.2)
    }
    then("ui.button.click")
}

intro.playTo(player)
intro.playAt(player.location, repeats = 3, gapTicks = 20L)
```

Вся последовательность работает как одна отменяемая KSL-задача. Количество повторов ограничено 1..1000, чтобы случайная конфигурация не создавала бесконечный цикл.

## Движок частиц

Существующий `playEffect` поддерживает формы и пользовательские shape-фабрики. Новые короткие алиасы — `particle` и `particles`:

```kotlin
val effectId = player.location.particles(Particle.DUST) {
    durationTicks = 100L
    every(2L)
    amount(3)
    ring(radius = 1.4, points = 32)
    color("#55CCFF", size = 1.2f)
    origin(y = 1.0)
    spin(3.0)
    onEnd { player.msg("<gray>Эффект завершён") }
}

// У сущности: follow=true переносит форму вместе с ней.
// entity.particle(Particle.FLAME, follow = true) { helix(0.8, 2.0, 2.5) }
```

Формы: `point`, `circle`, `ring`, `arc`, `square`, `box`, `cube`, `sphere`, `cylinder`, `cone`, `heart`, `helix` и `spiral`. Можно задать `origin`, статическое вращение `rotate` и вращение по тикам `spin`. Для частиц с дополнительными данными есть `color`/`dust`, `transition`, `block`, `item` и `particleData`.

KSL сохраняет ограничения на число точек, количество частиц на эффект и общий бюджет эмиссии за тик. Числа координат и смещения проверяются на конечность, а формы, возвращающие слишком много точек, останавливаются с ошибкой скрипта. ID эффекта можно передать в `stopEffect(id)`, `stopParticles(id)` или `cancelTask(id)`.

## Мобы и сущности

Можно передать строковый шаблон, как раньше, или тип Bukkit напрямую:

```kotlin
val boss = mob(location, EntityType.ZOMBIE) {
    name("<dark_red><bold>Страж")
    maxHealth(120.0)
    damage(12.0)
    speed(1.25)
    armor(8.0)
    target(player)
    mainHand(item(Material.NETHERITE_SWORD))
    helmet(item(Material.NETHERITE_HELMET))
    potion(PotionEffectType.FIRE_RESISTANCE, seconds = -1)
    glow()
    persistent()
    drops(item(Material.GOLD_INGOT, amount = 3), exp = 25)
}

boss.behavior {
    onTick(period = 20L) { mob ->
        if (mob.health < 40.0) mob.isGlowing = true
    }
    onDeath { mob, killer ->
        killer?.msg("<gold>Страж повержен")
    }
}
```

Доступные инструменты builder: MiniMessage-имя, текущее/максимальное здоровье, лечение, цель и сброс цели, постоянство, гравитация, коллизия, подбор предметов, огонь, скорость/вектор, телепортация, все слоты экипировки с шансом выпадения, эффекты зелий в секундах или тиках, а также исходные настройки AI и навигации Paper Pathfinder.

- `mob(location, EntityType, ...)` и `spawnMob(...)` создают одну сущность.
- `mobs(location, EntityType, amount, perTick, intervalTicks, ...)` и `spawnMobs(...)` создают группу постепенно, не спавня весь массив за один тик.
- Старые overloads с шаблонами `"TYPE:ZOMBIE;HP:40;AI:true"` остаются доступными.
- `behavior { onTick; onDeath; onDamaged; onAttack }` управляет событиями конкретной сущности.

Мобы и их обработчики привязаны к скрипту. По умолчанию созданные KSL-сущности удаляются при выгрузке скрипта; флаг `removeOnReload = false` сохраняет саму сущность, но зарегистрированные обработчики скрипта всё равно очищаются. Управление сущностями, путь и воспроизведение частиц/звука выполняй на основном потоке сервера.


---

# Дополнительная глава: STD, цветные сообщения и KSL Mixins

Это дополнение к основным книгам KSL: здесь собраны новые цветные методы, сокращения, KSL Mixins, обновление скриптового движка и защитные custom enchantments.

## Цветные сообщения

KSL принимает MiniMessage и legacy-коды Bukkit/Spigot. Поддерживаются `&a`, `§l`, `&#55CCFF` и шестизначная форма `&x&5&5&C&C&F&F`. Обычные амперсанды, которые не образуют код цвета, остаются текстом.

```kotlin
onEvent<PlayerJoinEvent> {
    player.msg("<gradient:#55CCFF:#AA55FF>Добро пожаловать, </gradient><white>" + player.name)
    player.ab("<gray>Приятной игры")
}

registerCommand("notice") { sender, _ ->
    sender.msg("&#55CCFFЦветное сообщение <bold>с MiniMessage</bold>")
}
```

Новые короткие методы:

| Метод | Назначение |
|---|---|
| `sender.msg(text)` | Отправить MiniMessage/legacy текст игроку, командному отправителю или консоли |
| `sender.sendMM(text)` | Альтернативное имя `msg` |
| `player.ab(text)` | ActionBar |
| `player.actionBar(text)` | Полное имя ActionBar |
| `player.t(title, subtitle)` | Короткий Title |
| `player.title(title, subtitle)` | Полное имя Title |
| `mm(text)` / `text.mm()` | Строка в Adventure Component |
| `broadcastRich(text)` | Форматированная рассылка |
| `text.colors()` | Legacy-коды в `§`-формат |
| `text.mmMarkup()` | Legacy-коды в MiniMessage-теги |

Доступны и расширенные методы: `parseMM`, `sendRichMessage`, `sendActionBarMessage`, `showRichTitle`, `broadcastMM`, `toMiniMessageComponent`, `toMiniMessageMarkup`, `colorizeLegacy` и `stripLegacyColors`. Старые методы сохранены. Для пользовательского текста, в котором markup не нужно интерпретировать, используй `toTextComponent()`.

## KSL Mixins

KSL Mixins — это скриптовая композиция поведения. Они позволяют собрать несколько событий, периодических действий и одноразовых задач в один именованный объект, а затем установить его в текущий скрипт. Все обработчики и задачи принадлежат этому скрипту и снимаются при reload/unload.

```kotlin
val joinBehavior = mixin("join-behavior") {
    description("Общие реакции на вход игрока")

    on<PlayerJoinEvent> {
        player.msg("<green>С возвращением, " + player.name + "!")
    }

    every(periodTicks = 20L) {
        broadcastRich("<gray>Скрипт активен")
    }

    after(delayTicks = 5L) {
        // Инициализация после применения поведения.
    }
}

val installed = useMixin(joinBehavior)
```

Builder поддерживает `description(text)`, `on<Event> { ... }`, `every(periodTicks, initialDelayTicks) { ... }`, `after(delayTicks) { ... }` и `install { ... }`. `applyMixin(mixin)` — полное имя `useMixin`. Повторное применение одного объекта к тому же контексту возвращает `false`. Миксин нельзя установить в контекст другого скрипта.

**Важно:** это KSL Mixins — композиция событий и задач уровня скриптов, а не Sponge Mixin. Это не подмена байткода Minecraft и не `@Inject`/`@Overwrite`; такие bytecode-инъекции нельзя безопасно гарантировать из обычного hot-reload Bukkit/Paper-плагина.

## Обновление движка скриптов

`/ksl reload changed` сравнивает SHA-256 содержимого `.kts` файлов, а не только время изменения и размер. Это помогает не пропустить изменение, когда timestamp и размер остались прежними. Проверка sandbox и компиляция используют один снимок исходника. Успешная сборка запоминает fingerprint именно скомпилированного снимка; если файл меняется во время компиляции, следующий reload заметит новую версию.

## Защитные зачарования брони

Для KSL Custom Enchantments добавлен `onDamageTaken`. Он вызывается для каждой надетой части брони с этим зачарованием, когда игрок получает урон от сущности. Контекст включает `player`, `attacker`, `item`, `slot`, `level` и исходный `event`.

```kotlin
val ward = customEnchant("ward") {
    name("<aqua>Защита стража")
    maxLevel(3)
    targets(Material.IRON_HELMET, Material.IRON_CHESTPLATE)

    onDamageTaken {
        event.damage = (event.damage - level * 0.5).coerceAtLeast(0.0)
    }
}

val helmet = item(Material.IRON_HELMET) {
    customEnchant(ward, 2)
}
```

`slot` указывает `FEET`, `LEGS`, `CHEST` или `HEAD`. Здесь изменяется текущий `EntityDamageByEntityEvent`; обычные `onHit`, `onUse`, `onBreak` и `onKill` работают как раньше.

## Совместимость и версия API

Существующие имена методов не удалялись. Новые сокращения добавлены поверх прежних вызовов. Публичная версия KSL API повышена до 9.


---

# Дополнительная глава: Предметы, NBT-совместимые метаданные и собственные зачарования

KSL предоставляет компактный DSL для сборки предметов и отдельную систему KSL Custom Enchantments.

## Быстрый конструктор предметов

Пример:

    val sword = item(Material.DIAMOND_SWORD) {
        name("<aqua><bold>Ледяной клинок")
        lore("<gray>Оружие древнего мага", "<dark_aqua>Особое зачарование: Мороз")
        enchant(Enchantment.SHARPNESS, 5, unsafe = true)
        unbreakable()
        hideAttributes()
    }

Старый вызов item(material, name, lore, amount) остаётся доступен. Builder также поддерживает plainName, addLore, modelData, amount, flags, hideEnchants, hideUnbreakable, hideAll и customEnchant.

Большинство методов ItemStack возвращают клон предмета, поэтому исходный объект не меняется:

    val upgraded = item
        .withName("<gold>Сокровище")
        .withAddedLore("Найдено в подземелье")
        .enchant(Enchantment.UNBREAKING, 3)

Доступны короткие алиасы amount, enchant, unenchant, isOf, isSimilarTo, withFlags и withoutFlags.

## KSL Custom Enchantments

Это зачарования KSL, а не записи реестра ванильных org.bukkit.enchantments.Enchantment. Они хранят уровень в PersistentDataContainer предмета и запускают обработчики событий KSL.

Пример:

    import org.bukkit.Sound

    val frost = customEnchant("frost") {
        name("<aqua>Мороз")
        maxLevel(3)
        targets(Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)
        conflicts("flame")

        onHit {
            if (target is LivingEntity) {
                target.addPotionEffect(PotionEffect(PotionEffectType.SLOW, 40 * level, 0))
            }
            attacker.world.playSound(target.location, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.4f)
        }

        onKill {
            player.health = (player.health + 1.0 * level).coerceAtMost(player.maxHealth)
        }
    }

    val sword = item(Material.DIAMOND_SWORD) {
        name("<aqua><bold>Ледяной клинок")
        lore("<gray>Клинок, сковывающий врагов")
        customEnchant(frost, 2)
    }

### Триггеры

| Обработчик | Контекст |
|---|---|
| onHit | attacker, target, victim, item, level, event |
| onUse | player, item, level, action, clickedBlock, event |
| onBreak | player, block, item, level, event |
| onKill | player, target, item, level, event |

onHit работает для прямого удара игрока и снаряда, у которого игрок является владельцем. В onBreak и onKill проверяется предмет в главной руке. Обработчики выполняются на основном потоке Bukkit; тяжёлые вычисления не следует выполнять прямо в событии.

Зачарования скрипта автоматически снимаются из реестра при reload/unload. Уже созданные предметы сохраняют ключ и уровень; если скрипт снова зарегистрирует зачарование с тем же id, оно продолжит распознаваться. Если скрипт выгружен и не загружен обратно, данные сохраняются, но обработчики не вызываются.

Уровень ограничен диапазоном 1–255, предметы проверяются по targets, несовместимые ключи задаются через conflicts. По умолчанию зачарование разрешено на любом материале. Id локален для скрипта; для зачарования другого скрипта указывай полное имя script-name:id.

### Операции с предметом

    val stronger = sword
        .withCustomEnchant(frost, 3)
        .withEnchantment(Enchantment.UNBREAKING, 3)

    val level = stronger.customEnchantLevel(frost)
    val hasFrost = stronger.hasCustomEnchant(frost)
    val plain = stronger.withoutCustomEnchant(frost)

Методы withCustomEnchant, withoutCustomEnchant, withEnchantment и другие with... возвращают копию. Для изменения текущего предмета присвой результат обратно переменной.

Собственные зачарования отображаются в lore. Если требуется нестандартный текст уровня или визуальный формат, lore можно настроить после вызова builder.

Для зачарований брони доступен дополнительный обработчик `onDamageTaken`. Он вызывается при получении игроком урона от сущности для каждой подходящей надетой части брони; контекст содержит `player`, `attacker`, `item`, `slot`, `level` и `event`. Через `event.damage` можно изменить текущий урон. Пример с `onDamageTaken` приведён в [справочнике STD и миксинов](01-scripts.md).

## Границы совместимости

KSL Custom Enchantments не появятся в списке ванильных зачарований на столе зачарования и не будут автоматически поддерживаться сторонними плагинами, ожидающими Bukkit Enchantment. Для стандартных зачарований используй enchant(Enchantment, level).

Система не подменяет NBT/PDC API: customEnchant предназначен для игровых эффектов, а modifyNBT, modifyPersistentNBT и modifyComponents — для низкоуровневых данных и компонентов предметов. См. также NBT API (05-nbt-api.md).


---

# Дополнительная глава: Структурированное логирование

Руководство относится к development-линии KotlinScriptLoader 1.2.2. Оно описывает работу структурированного логирования и не объявляет выпуск новой версии.

## Что даёт KSL logger

Встроенный logger связывает сообщения с владельцем (скрипт или аддон), именем категории и уровнем важности. Это помогает фильтровать шумный debug-вывод и оставлять полезные события в серверной консоли.

| Уровень | Назначение |
|---|---|
| `TRACE` | Очень подробные внутренние детали |
| `DEBUG` | Отладочные сообщения |
| `INFO` | Обычные события и состояния |
| `WARN` | Неожиданные, но обрабатываемые ситуации |
| `ERROR` | Ошибки, требующие внимания |
| `OFF` | Запретить вывод через этот экземпляр logger |

Видимость всегда ограничена настройкой администратора. Уровень, указанный в коде, не может ослабить серверный минимум.

## В `.kts` скриптах

Создай отдельный logger и переиспользуй его:

```kotlin
val shopLog = scriptLogger("economy.shop") {
    level = KSLLogLevel.DEBUG
    prefix = "SHOP"
}

shopLog.info("Shop script loaded", "items" to 24)
shopLog.debug("Price cache refreshed", "entries" to 140)
shopLog.warn("Price fallback used", "material" to "DIAMOND")
shopLog.error("Could not load shop data", cause)

val sqlLog = shopLog.child("database")
sqlLog.info("Connection pool ready")
```

`scriptLogger()` использует ID текущего скрипта как имя по умолчанию. `kslLogger()` — короткий alias. Старые методы `log(message)`, `logWarn(message)` и `logError(message)` сохранены и отправляют сообщения через новый logger.

Поля передаются как пары `"key" to value`. Они ограничены по количеству и длине, а переводы строк нормализуются, чтобы одна запись не разрасталась в много строк консоли.

## В Kotlin/Java аддонах

Kotlin DSL:

```kotlin
val storageLog = api.logger("example-addon", "storage") {
    level = KSLLogLevel.DEBUG
    prefix = "STORAGE"
    includeOwner = true
}

storageLog.info("Storage initialized", "backend" to "sqlite")
storageLog.child("cache").debug("Cache warmed", "entries" to 320)
```

Низкоуровневый API:

```kotlin
val logger = api.createLogger(
    ownerId = "example-addon",
    name = "storage",
    config = KSLLoggerConfig().apply {
        level = KSLLogLevel.INFO
        prefix = "STORAGE"
    }
)

logger.info("Addon ready")
```

Для Java доступны обычные методы `createLogger`, `info`, `warn`, `error`, а также `KSLLogLevel` и `KSLLoggerConfig`. Точные сигнатуры смотри в public API установленного KSL JAR.

## Настройки сервера

Минимальный уровень по умолчанию — INFO. Администратор может отдельно разрешить более подробные сообщения отдельному скрипту или аддону:

```yaml
logging:
  default-level: INFO
  owner-levels:
    "script:economy/shop": DEBUG
    "addon:example-addon": TRACE
  max-message-length: 4096
  script-errors-as-warnings: true
```

Ключи владельцев имеют префикс `script:` или `addon:`. Для обычного script ID используй путь без расширения `.kts`. Например, `script:economy/shop`.

Примеры:
- Глобальный INFO подавляет TRACE и DEBUG для всех владельцев.
- Override DEBUG для одного владельца позволяет его DEBUG и уровни выше, но не TRACE.
- Override TRACE включает все уровни этому владельцу.
- Если logger в коде установлен на WARN, даже администраторский TRACE не заставит его писать INFO/DEBUG: более строгий из двух уровней побеждает.

Изменения применяются при перечитывании конфигурации, например через `/ksl reload`. Администраторская политика хранится на стороне плагина; скрипт не может изменить её из logger config.

## IntelliJ IDEA

KSL IntelliJ plugin дополняет completion для `scriptLogger`, `kslLogger`, `KSLLoggerConfig`, `KSLLogLevel`, методов `trace/debug/info/warn/error`, дочерних категорий, `api.createLogger` и `api.logger`.

Локальный preflight также предупреждает о голых `print/println` в KSL исходниках: такие сообщения не получают owner, категорию и управляемый уровень. Для `.kts` есть quick fix, заменяющий имя функции на `scriptLogger().info`; аргументы вызова остаются на месте. Это эвристика IDE, а не Kotlin formatter или полноценный PSI refactoring.

## Ограничения и рекомендации

- Логирование направляется в logger самого плагина KSL и использует серверную консольную конфигурацию; отдельный rolling-file appender не создаётся.
- Не записывай пароли, ключи, токены, webhook URL и персональные данные в сообщения.
- Не включай TRACE/DEBUG глобально на production надолго: подробные записи могут увеличить объём логов.
- Не выполняй тяжёлые вычисления внутри выражений формирования сообщения до вызова logger; проверка уровня не отменяет вычисления аргументов Kotlin.
- Logger не заменяет обработку исключений, метрики или аудит действий администратора.

## Совместимость

- KSL development build version: 1.2.2.
- Public KSL API contract: version 15.
- IDEA plugin version: 1.2.1.
- Последний публичный стабильный релиз KSL: 1.2.1.
- Этот документ не создаёт GitHub Release или release tag.