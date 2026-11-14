# KSL Script DSL — справочник

Этот документ описывает DSL внутри `.kts`. Он не является справочником Kotlin/Java Addon API: для аддонов смотри [Public API](Public-API.md).

## 1. Контекст скрипта

Каждый скрипт выполняется с implicit receiver `BukkitScriptContext`. Типичные свойства:

- `plugin` — экземпляр JavaPlugin KSL;
- `config` — YAML-конфигурация текущего скрипта;
- `database` — общий HikariDataSource;
- `log(message)`, `logWarn(message)`, `logError(message)` — логирование через KSL;
- `onlinePlayers()`, `onlineCount`, `maxPlayers`;
- `findPlayer(nameOrUuid)`;
- `runSync { }`, `runAsync { }`;
- `onEvent<T> { }`, `registerCommand(...)`;
- `registerPlaceholder(...)`;
- `saveConfig()`, `editConfig { }`.

Набор default imports задаётся метаданными KSL. Не предполагай, что wildcard import рекурсивен: подпакеты могут требовать отдельного импорта. Проверяй [Packages and Imports](Packages-and-Imports.md).

## 2. События

```kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage("<green>Привет, ${player.name}!</green>")
}
```

Для событий с отменой используй параметры `priority` и `ignoreCancelled`, если они нужны:

```kotlin
onEvent<PlayerInteractEvent>(
    priority = EventPriority.MONITOR,
    ignoreCancelled = true
) {
    // На MONITOR наблюдай за событием; не меняй его состояние.
}
```

Не выполняй тяжёлые SQL, сетевые запросы или длительные вычисления в event callback. Сначала передай данные в фоновую задачу, а Bukkit-изменения верни в главный поток.

## 3. Команды

```kotlin
registerCommand("profile", aliases = listOf("p")) { sender, args ->
    if (!sender.hasPermission("server.profile")) {
        sender.sendMessage("Недостаточно прав.")
        return@registerCommand
    }

    sender.sendMessage("Аргументов: ${args.size}")
}
```

Имя команды и aliases валидируются при регистрации. Конфликты и некорректные aliases диагностируются KSL. Не регистрируй команды заново в каждом повторяющемся событии.

## 4. Планировщик

```kotlin
runSync {
    // Bukkit API, требующий главный поток
}

runAsync {
    // Файловый ввод-вывод, SQL или вычисления,
    // если используемый API допускает фоновый вызов.
}

every(ticks = 20L) {
    // Повторяющаяся задача
}
```

Периодические задачи, зарегистрированные через KSL-контекст, принадлежат скрипту и снимаются при unload. Не используй `Thread.sleep` для задержек: это блокирует поток. Для задержки используй scheduler API KSL/Bukkit.

## 5. Bukkit и потоки

| Операция | Поток |
|---|---|
| Изменение блоков/мира | Главный |
| Инвентарь игрока | Главный |
| Телепорт, здоровье, эффекты игрока | Главный |
| SQL через KSL DB API | Фоновый |
| Сетевые запросы | Фоновый |
| CPU-heavy вычисление | Worker/async |
| Результат фоновой работы, меняющий Bukkit state | Вернуть через `runSync` |

Даже чтение Bukkit объектов не следует считать безопасным в async-потоке, если Paper API прямо не разрешает его.

## 6. YAML-конфигурация текущего скрипта

Файл называется как скрипт: `shop.kts` → `shop.yml`.

```kotlin
val prefix = config.getString("messages.prefix", "<gray>[Shop]</gray>") ?: "<gray>[Shop]</gray>"

editConfig {
    set("settings.enabled", true)
    set("settings.price", 100)
}

saveConfig()
```

`editConfig(save = true) { ... }` синхронизирует изменение и сохранение. При `save = false` изменения остаются в памяти до явного `saveConfig()`.

Миграции и проверка схемы YAML описаны в [Data Formats](Data-Formats.md).

## 7. Feature flags

Флаги хранятся в YAML скрипта под `features`:

```kotlin
if (featureEnabled("shop.enabled", default = true)) {
    // Магазин доступен
}

enableFeature("shop.enabled")
disableFeature("shop.enabled")
val stateAfterToggle = toggleFeature("shop.enabled")
```

Имя — dot-separated ключ, например `shop.enabled` или `events.double-xp`. Значение сохраняется через конфигурацию скрипта. Это переключатель поведения, а не механизм безопасности: скрытая/выключенная функция не является защищённой от прямого вызова другим кодом.

## 8. Сохранение состояния

Для постоянных данных используй `persist(...)` и `table(...)`, а не глобальные mutable-переменные, которые исчезнут при reload. Подробности хранения, сериализации и backend смотри в [Database Guide](Database-Guide.md).

## 9. Файлы данных

Контекст предоставляет JSON и TOML операции для файлов в каталоге скриптов. Путь ограничивается областью `scripts/`. Не пытайся обращаться через `../` к конфигу плагина или другим каталогам. Подробности: [Data Formats](Data-Formats.md).

## 10. Сообщения и ошибки

```kotlin
warn("Необязательная настройка отсутствует")
error("Операция текущего скрипта не может быть завершена")
fatal("Обязательная часть скрипта не инициализирована")
```

- `warn` пишет предупреждение и продолжает выполнение.
- `error` прерывает текущую операцию.
- `fatal` выгружает ресурсы текущего скрипта.

Не используй `fatal` для ожидаемой ошибки пользователя, например неверного аргумента команды. Для этого отправь понятное сообщение и вернись из обработчика.

## 11. PlaceholderAPI и интеграции

KSL поддерживает мягкие интеграции, если соответствующие плагины установлены. Проверяй результат hook/API на null и не считай сторонний плагин обязательным. Для списка поддерживаемых интеграций и ограничений смотри [KSL Encyclopedia](KSL-Encyclopedia.md) и [Standard Library Guide](Standard-Library-Guide.md).

## 12. Комментарии и отключение кода

В Kotlin Script:

```kotlin
// Однострочный комментарий

/*
  Многострочный
  комментарий
*/

val enabled = true
if (enabled) {
    // Эта часть выполнится
}
```

В YAML и TOML комментарии начинаются с `#`:

```yaml
# Это комментарий
shop:
  enabled: true
```

```toml
# Это комментарий
[shop]
enabled = true
```

Символ `; ` не отключает функцию. В Kotlin он может разделять выражения на одной строке, но лучше писать отдельные строки. Для включения/выключения частей логики используй обычный `if` или сохраняемый feature flag. Не удаляй код комментариями на постоянной основе, если флаг лучше выражает настройку.

## 13. Ресурсы и hot reload

События, команды, placeholders, задачи и другие KSL-owned ресурсы должны быть созданы через DSL KSL. Так KSL сможет снять их при reload. Если создаёшь собственные внешние ресурсы напрямую через Bukkit/другой плагин, обеспечь их закрытие/отмену в жизненном цикле, иначе возможны дубли обработчиков и утечки.

## 14. Соседние руководства

- [Жизненный цикл скриптов, hot-reload и владение ресурсами](Script-Lifecycle-and-Resources.md)

- [Standard Library](Standard-Library-Architecture.md)
- [Runtime API](Runtime-API.md)
- [Database Guide](Database-Guide.md)
- [Security and Sandbox](Security-and-Sandbox.md)
- [Troubleshooting](Troubleshooting.md)
