# Структурированное логирование KSL

Руководство относится к линии KotlinScriptLoader 1.2.1. Оно описывает работу структурированного логирования и не объявляет выпуск новой версии.

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

- KSL development build version: 1.2.2-SNAPSHOT.
- Public KSL API contract: version 12.
- IDEA plugin version: 1.2.1.
- Последний публичный стабильный релиз KSL: 1.2.1.
- Этот документ не создаёт GitHub Release или release tag.