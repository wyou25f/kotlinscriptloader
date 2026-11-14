# KSL — начало работы

Практическое руководство для администратора Paper и автора первого Kotlin-скрипта.

- Целевая среда: Paper 1.21.1, Java 21.
- Файлы скриптов: `plugins/KotlinScriptLoader/scripts/`.
- Расширение: `.kts`.
- Версия проекта в текущей документации: 1.2.1. Проверяй фактический релиз/commit перед обновлением сервера.

## 1. Установка

1. Установи Java 21 и Paper 1.21.1.
2. Помести собранный `KotlinScriptLoader-1.2.0.jar` в `plugins/`.
3. Запусти сервер один раз, чтобы KSL создал конфигурацию и каталог скриптов.
4. Останови сервер перед первой настройкой.
5. Проверь `plugins/KotlinScriptLoader/config.yml`.
6. Создай файл `plugins/KotlinScriptLoader/scripts/hello.kts`.

Не скачивай случайные JAR из сторонних источников. Используй релиз/артефакт из доверенного CI проекта.

## 2. Первый скрипт

```kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage("<green>Добро пожаловать, <white>${player.name}</white>!</green>")
}
```

Сохрани файл и выполни `/ksl reload`. Проверь консоль на ошибки. Если скрипт загружен, зайди на сервер тестовым игроком.

KSL использует `BukkitScriptContext` как implicit receiver. Поэтому функции вроде `onEvent`, `runSync`, `runAsync`, `config`, `database` и `registerCommand` доступны в .kts без создания контекста вручную.

## 3. Команда

```kotlin
registerCommand("hello", aliases = listOf("hi")) { sender, args ->
    sender.sendMessage("Привет! Аргументов: ${args.size}")
}
```

Проверяй permissions внутри обработчика, если команда должна быть административной. Не полагайся только на скрытие команды из tab-completion.

## 4. Конфигурация YAML

У скрипта `hello.kts` есть собственный `hello.yml`. Пример:

```yaml
# Настройки приветствия
message: "<green>Добро пожаловать!</green>"
enabled: true
```

```kotlin
val enabled = config.getBoolean("enabled", true)
val message = config.getString("message") ?: "<green>Welcome!</green>"

if (enabled) {
    onEvent<PlayerJoinEvent> {
        player.sendRichMessage(message)
    }
}
```

Для атомарного группового изменения и сохранения используй `editConfig { ... }`. Прямые вызовы `config.set(...)` из нескольких потоков не становятся автоматически потокобезопасными.

## 5. Перезагрузка и выгрузка

- `/ksl reload` — перезагрузка всех скриптов.
- `/ksl reload <script-id>` — отдельный скрипт.
- `/ksl doctor` — диагностика.
- `/ksl errors` — последние ошибки.
- `/ksl error <code>` — расшифровка кода ошибки.

При reload KSL снимает ресурсы старого экземпляра скрипта и регистрирует новые. Не создавай неуправляемые потоки и внешние задачи, которые переживут выгрузку.

## 6. Где читать дальше

| Цель | Руководство |
|---|---|
| Полный DSL | [Script DSL Reference](Script-DSL-Reference.md) |
| SQL, SQLite, MySQL/MariaDB | [Database Guide](Database-Guide.md) |
| Производительность | [Performance and Scaling](Performance-and-Scaling.md) |
| YAML/TOML/JSON | [Data Formats](Data-Formats.md) |
| Безопасность | [Security and Sandbox](Security-and-Sandbox.md) |
| Эксплуатация сервера | [Production Operations](Production-Operations.md) |
| Практические рецепты | [Cookbook](Cookbook.md) |
| API аддонов | [Public API](Public-API.md) |
| IDE | [IDE README](../ide/README.md) |

## 7. Важные ограничения

KSL не изолирует скрипты в отдельной JVM. Скрипт имеет доступ к возможностям, которые доступны самому серверу и подключённым API. Разрешай запись в `scripts/` только доверенным людям.

Bukkit API не становится потокобезопасным из-за того, что код написан на Kotlin. Мир, сущности, игроки и инвентари обычно нужно читать/изменять на главном потоке.

Не храни пароли, webhook URL и токены в публичном репозитории. Для пароля базы KSL поддерживает переменную окружения `KSL_DB_PASSWORD`.
