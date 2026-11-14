# KSL — производительность и масштабирование

Руководство о том, как не блокировать Paper, использовать очереди KSL и диагностировать узкие места.

> Асинхронный API не гарантирует заданное число запросов в секунду. Пропускная способность зависит от CPU, диска, latency сети, SQL, индексов, размера пула, числа серверов и характера нагрузки. Измеряй на своём стенде.

## 1. Сначала найди узкое место

Собери исходные данные: версия Paper/Java/KSL, результаты /ksl doctor и /ksl runtime, тип и размер БД, latency до MySQL/MariaDB, частота запросов, очереди чтения/записи, heap/GC и thread dump при зависании.

Не увеличивай все лимиты одновременно. Большие пулы могут усилить конкуренцию за CPU и соединения.

## 2. Правило главного потока

Главный поток Paper отвечает за игровой tick. Не блокируй его SQL, HTTP, Discord, большими файлами, тяжёлым парсингом, большими циклами, Thread.sleep, долгим Future.join() или Future.get().

```kotlin
runAsync {
    val result = calculateOrLoadData()
    runSync {
        // Bukkit state изменяем только на главном потоке
        player.sendMessage("Готово: $result")
    }
}
```

Не обращайся к Player, World, Entity, Inventory и Bukkit объектам из фонового callback, если конкретный Paper API прямо не разрешает это. Передавай в фоновые задачи простые данные: UUID, строки, числа и DTO.

## 3. Пулы базы данных

KSL использует HikariCP. Параметр database.pool-size задаёт число соединений (типичный старт — 4; допустимый диапазон описан в Database-Guide.md).

Для MySQL/MariaDB учитывай общий лимит подключений: сложи пулы всех Minecraft-серверов и другие DB-клиенты. Не выставляй pool-size 32 «на всякий случай». Измеряй latency, длительность запросов, очереди, CPU/IO базы и max_connections.

## 4. Очереди чтения и записи

KSL разделяет пользовательские SQL-операции:

- Запись — одна последовательная фоновая очередь, сохраняющая порядок принятых write-задач.
- Чтение — отдельный пул reader threads, связанный с размером DB pool.
- Каждая очередь ограничена 10 000 ожидающими задачами.

Ограничение обеспечивает backpressure: при перегрузке новые задачи отклоняются, вместо бесконечного роста памяти. 10 000 — предел защиты, а не целевой размер рабочей очереди.

Наблюдай за обеими очередями через /ksl doctor. Если размер постоянно растёт, найди частые запросы, медленный SQL, отсутствие индексов или сетевую задержку. Простое увеличение очереди проблему не решает.

## 5. Асинхронный SQL API

Старые вызовы остаются доступными:

- dbExecute(sql, ...args) / execute(sql, ...args);
- dbQuery(sql, ...args) { ResultSet -> ... } / query(sql, ...args) { ... };
- dbExecuteYaml(path, ...args) / executeYaml(...);
- dbQueryYaml(path, ...args) { ... } / queryYaml(...).

Future-варианты:

- dbExecuteAsync(sql, ...args) / executeAsync(...);
- dbQueryAsync(sql, ...args, mapper = { rs -> detachedValue }) / queryAsync(...);
- dbExecuteYamlAsync(path, ...args) / executeYamlAsync(...);
- dbQueryYamlAsync(path, ...args, mapper = { ... }) / queryYamlAsync(...).

```kotlin
queryAsync(
    "SELECT balance FROM coins WHERE uuid = ?",
    player.uniqueId.toString(),
    mapper = { rs -> if (rs.next()) rs.getLong("balance") else 0L }
).whenComplete { balance, error ->
    if (error != null) {
        logWarn("Не удалось загрузить баланс: ${error.message}")
    } else {
        runSync { player.sendMessage("Баланс: $balance") }
    }
}
```

Mapper должен вернуть отделённое от JDBC значение: число, строку, data class или список. Не возвращай ResultSet, Connection или Statement: JDBC ресурсы закрываются после mapper.

При заполнении очереди Future завершается ошибкой, связанной с RejectedExecutionException. Обрабатывай её через whenComplete/exceptionally. Не делай join() на главном потоке.

## 6. Prepared statements и индексы

Передавай пользовательские значения параметрами:

```kotlin
execute(
    "UPDATE coins SET balance = balance + ? WHERE uuid = ?",
    amount,
    playerId
)
```

Не собирай SQL интерполяцией из ника, чата или аргументов команды. Параметры защищают значения, но не SQL-идентификаторы: имя таблицы/колонки выбирай из фиксированного whitelist.

Для частых запросов добавляй индексы по WHERE/JOIN/ORDER BY, выбирай только нужные столбцы, ограничивай выборку LIMIT и проверяй план запроса средствами своей СУБД.

## 7. YAML SQL query cache

Запросы из database.queries кэшируются. После изменения файла перечитай конфиг:

```kotlin
val count = reloadScriptConfig()
log("Перечитано запросов: $count")
```

Если YAML изменён программно через editConfig, обнови только query cache:

```kotlin
editConfig {
    set("database.queries.find-coins", "SELECT balance FROM coins WHERE uuid = ?")
}
reloadYamlSqlQueries()
```

Обновление кэша атомарное: выполняющиеся DB-задачи используют уже полученную SQL-строку, новые — актуальную.

## 8. Worker pool KSLRT

runAsync и worker решают разные задачи. runAsync отправляет задачу в async scheduler Bukkit и возвращает Bukkit task ID (Int). worker использует ограниченный KSL worker pool, поддерживает приоритеты и возвращает worker task ID (Long). Для CPU-heavy задач и контролируемой очереди предпочитай worker; для простой фоновой операции, совместимой с Bukkit async scheduler, используй runAsync. Ни один вариант не разрешает обращаться к Bukkit world/player/inventory state из фонового потока.


Для CPU-heavy и blocking задач без Bukkit state доступен управляемый worker pool:

```kotlin
val taskId = worker(KSLWorkPriority.NORMAL) {
    val result = calculateSomethingExpensive()
    runSync { /* применить результат через Bukkit API */ }
}
```

Приоритеты: HIGH, NORMAL, LOW. Очередь ограничена, есть лимит задач на скрипт. При перегрузке работа отклоняется, а не выполняется неожиданно на вызывающем потоке. Задачи принадлежат скрипту и отменяются при unload. Worker code должен корректно реагировать на interruption.

Настройки config.yml:

```yaml
runtime:
  cpu-threads: 2
  queue-capacity: 256
  max-tasks-per-script: 64
```

Число worker threads меняется через /ksl runtime workers <1-64>. Queue capacity и per-script limit читаются при старте.

## 9. Кэширование

KSLCache<K, V> — потокобезопасный bounded LRU cache с optional TTL:

```kotlin
val cache = KSLRT.cache<String, Long>(maxSize = 10_000)
cache[playerId] = balance
val cached = cache[playerId]
val stats = cache.stats()
```

Кэш не заменяет источник истины. Продумай TTL, invalidation после записи, поведение нескольких серверов и ограничение размера. Локальный cache одного сервера не синхронизируется автоматически с другими JVM. При конкурентном cache miss loader может выполниться больше одного раза.

## 10. Измерение

```kotlin
val profiler = KSLRT.profiler("economy.load")
val value = profiler.measure { loadEconomyData() }
val snapshot = profiler.snapshot()
log("calls=${snapshot.calls}, avgNs=${snapshot.averageNanos}")
```

Реестр именованных профилировщиков ограничен 1024 именами. Для динамических профилей вызывай KSLRT.removeProfiler(name), когда профиль больше не нужен. Не создавай уникальное имя на каждый вызов.

## 11. Индекс скриптов и автодополнение

KSL keeps an immutable in-memory snapshot of script IDs and reload completion
targets, not File objects. File instances are created only for explicit reload,
validation and status operations. Tab completion reuses the compact metadata
instead of walking the scripts directory and rebuilding folder candidates on every
keystroke. The snapshot is refreshed by explicit discovery/reload operations and
invalidated by filesystem watcher events.

The per-script command registry uses compact owner lists rather than allocating
a separate concurrent hash set for every script. Bukkit still owns the live command
objects and their callbacks: this bookkeeping optimization reduces duplicate
registry overhead, but it does not make the memory used by loaded script code or
registered command handlers disappear.

For servers with many scripts, keep auto-reload enabled when live filesystem
changes should appear immediately in suggestions. Without the watcher, an explicit
reload/validation scan refreshes the index.

## 12. Чек-лист

- [ ] Нет SQL и сети на главном потоке.
- [ ] Пользовательские значения идут через prepared statement.
- [ ] Есть индексы под частые запросы.
- [ ] Future errors обрабатываются.
- [ ] Bukkit операции возвращаются в sync.
- [ ] Worker задачи конечны и корректно отменяются.
- [ ] Очереди не растут постоянно.
- [ ] Пулы всех серверов укладываются в лимит DB.
- [ ] Есть проверенный backup/restore.
- [ ] Нагрузка измерена на стенде.

См. Database-Guide.md, Runtime-API.md, Production-Operations.md и Troubleshooting.md.
