# Данные и конфигурации KSL: YAML, TOML и JSON

Руководство по выбору формата, чтению/записи, комментариям, миграциям и безопасному хранению файлов.

## 1. Какой формат выбрать

| Формат | Лучше подходит для | Комментарии | KSL |
|---|---|---|---|
| YAML | Настройки сервера и скрипта, вложенные конфиги | `#` | Bukkit YAML API и helpers |
| TOML | Структурированные настройки с таблицами и типами | `#` | Tomlj codec + helpers |
| JSON | Обмен данными, API payloads, машинные структуры | Стандартный JSON комментарии не поддерживает | kotlinx.serialization JSON |

Не выбирай формат только по скорости записи: размер файла, частота обновления, сериализация и место выполнения часто важнее.

## 2. Где находятся файлы

- Конфиг плагина: `plugins/KotlinScriptLoader/config.yml`.
- Конфиг скрипта `shop.kts`: файл `shop.yml` в каталоге плагина.
- JSON/TOML данные через контекст: внутри каталога `plugins/KotlinScriptLoader/scripts/`, с ограничением пути на эту директорию.

Для дополнительных YAML файлов используй контекстные методы `editYaml(fileName) { ... }` и `saveYaml(fileName, yaml)`, если нужен отдельный файл внутри разрешённой области. Не используй относительные пути с `../`.

## 3. YAML: базовый доступ

```yaml
# Настройки магазина
shop:
  enabled: true
  max-items: 24
  title: "<gold>Магазин</gold>"
  worlds:
    - world
    - world_nether
```

```kotlin
val enabled = config.getBoolean("shop.enabled", true)
val maxItems = config.getInt("shop.max-items", 24)
val title = config.getString("shop.title") ?: "<gold>Магазин</gold>"
val worlds = config.getStringList("shop.worlds")
```

Удобные accessors в Standard Library/context:

- `hasPath(path)`
- `stringOr(path, default)`
- `intOr(path, default)`
- `longOr(path, default)`
- `boolOr(path, default)`
- `listOfStrings(path)`
- `removePath(path)`

Существующие `string/int/long/double/bool/strings` и Bukkit методы сохраняются. При использовании receiver-style extension убедись, что receiver — `YamlConfiguration`.

## 4. Безопасное редактирование YAML

```kotlin
editConfig {
    set("shop.enabled", true)
    set("shop.max-items", 32)
}
```

`editConfig` синхронизирует блок изменений и сохранение. Не разделяй конкурентное изменение и сохранение на несколько несогласованных вызовов. Для файловых YAML-конфигов используй `editYaml`/`saveYaml` с разрешёнными путями.

### Проверка схемы

```kotlin
val issues = validateConfig {
    string("messages.prefix", default = "<gray>[Server]</gray>")
    int("shop.max-items", default = 24, min = 1, max = 54)
    bool("shop.enabled", default = true)
    strings("worlds.allowed", default = listOf("world"))
}
issues.forEach { issue ->
    logger.warning("Config '${issue.path}': ${issue.message}")
}
```

По умолчанию отсутствующие/некорректные значения могут быть заменены defaults из схемы. Передавай `applyDefaults = false`, если нужна только диагностика без изменения файла. Диапазоны проверяются; KSL не должен незаметно подрезать ошибочные значения до границы.

### Миграция YAML

```kotlin
val configVersion = migrateConfig(
    targetVersion = 2,
    migrations = mapOf(
        1 to {
            if (!contains("shop.max-items")) set("shop.max-items", 24)
        },
        2 to {
            if (!contains("shop.enabled")) set("shop.enabled", true)
        }
    )
)
```

Шаги выполняются по порядку. Пропущенные версии и конфиг с версией выше target отклоняются. Изменения применяются к временной копии; исходный файл обновляется только после успешного выполнения миграций. После публикации скрипта не переписывай старые миграции — добавляй следующий номер.

## 5. TOML: файлы

```toml
# Основные настройки
port = 25565
enabled = true
motd = "Welcome"

[database]
host = "localhost"
pool-size = 4

[features]
shop = true
```

Чтение:

```kotlin
val settings = loadToml("data/settings.toml")
val port = settings?.tomlInt("port", 25565) ?: 25565
val database = settings?.tomlTable("database")
val host = database?.tomlString("host", "localhost") ?: "localhost"
```

Запись и редактирование:

```kotlin
editToml("data/settings.toml") {
    this["port"] = 25566
    this["motd"] = "New server"
    this["database"] = mapOf("host" to "127.0.0.1", "pool-size" to 6)
}

val parsed = readToml("data/settings.toml")
writeToml("data/backup.toml", mapOf("enabled" to true))
updateToml("data/settings.toml") {
    this["maintenance"] = false
}
```

Доступные операции:
- контекст: `loadToml`, `saveToml`, `editToml`;
- короткие helpers: `readToml`, `writeToml`, `updateToml`;
- accessors Map: `tomlHas`, `tomlString`, `tomlLong`, `tomlInt`, `tomlDouble`, `tomlBoolean`, `tomlTable`, `tomlList`;
- mutable map: `putTomlIfAbsent`, `removeToml`.

Числа TOML после чтения могут быть представлены как `Long` или другой Number. Для целых значений используй числовые accessors, а не небезопасное приведение к Int.

### Поддерживаемая запись

Codec поддерживает scalar values, arrays и nested tables. Null и arrays of tables не поддерживаются и должны приводить к явной ошибке, а не к потере данных. Запись выполняется атомарной заменой. Повреждённый исходный файл сохраняется/резервируется перед сообщением об ошибке, если это возможно.

## 6. JSON: объекты и файлы

```kotlin
val profile = loadJsonObject("data/profile.json")
val coins = profile?.long("coins", 0L) ?: 0L

editJsonObject("data/profile.json") {
    put("coins", (coins + 10).toJsonElement())
    put("active", true.toJsonElement())
}

val payload = jsonObjectOf(
    "player" to player.name,
    "online" to true,
    "tags" to listOf("builder", "vip")
)
saveJson("cache/player.json", payload)
```

Преобразователи `jsonObjectOf`, `jsonArrayOf` и `toJsonElement` поддерживают обычные Kotlin значения/коллекции. Для типизированного доступа к `JsonObject` смотри методы `string/int/long/double/bool`, `objectValue`, `arrayValue`.

JSON по стандарту не поддерживает комментарии. Не вставляй `//` или `#` в JSON-файл: используй отдельное поле `_comment` только если формат твоего приложения это допускает.

## 7. Комментарии и выключение настроек

- Kotlin `.kts`: `//` и `/* ... */`.
- YAML: `#`.
- TOML: `#`.
- JSON: комментарии стандартом не предусмотрены.

Точка с запятой в Kotlin — разделитель инструкций, не синтаксис отключения функции. В YAML/TOML точка с запятой не превращает настройку в комментарий. Чтобы выключить возможность, используй boolean:

```yaml
features:
  shop:
    enabled: false
```

Или KSL feature flags:

```kotlin
if (featureEnabled("shop.enabled", default = true)) {
    // Выполняется только если feature flag включён
}
```

Feature flags — конфигурация, а не контроль доступа. Для защищённых команд отдельно проверяй permission.

## 8. Производительность и потоки

Операции с файлами синхронные. Большие JSON/TOML/YAML файлы не читай и не записывай часто на главном потоке. Переноси файловые операции в фоновую задачу, а Bukkit state меняй через `runSync`.

Не сохраняй конфиг каждую игровую tick. Группируй изменения и записывай пакетно. Для высокочастотных значений используй подходящее хранилище/батчинг вместо переписывания большого текстового файла.

## 9. Безопасность

- Не храни пароли, API keys, Discord webhook URL в примерах и публичных YAML/TOML/JSON.
- Проверяй значения из файла: конфиг может быть испорчен или отредактирован вручную.
- Пути контекстных файлов ограничены каталогом скриптов.
- Не считай сериализацию шифрованием.
- Секреты базы задавай через переменную окружения `KSL_DB_PASSWORD` и ограничивай права DB пользователя.

См. [Security and Sandbox](Security-and-Sandbox.md) и [Database Guide](Database-Guide.md).
