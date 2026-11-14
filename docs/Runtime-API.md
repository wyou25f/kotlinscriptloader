# KSL Runtime API

KSL exposes a small low-level utility layer from
`ru.privateserver.ksl.rt.*`. It is intended for addon and script authors who
need reusable runtime primitives without depending on KSL's internal compiler,
classloader or script-loader implementation.

## Entry points and aliases

All names below resolve to the same factory:

```kotlin
val cache = KSLRT.cache<String, Int>(512)
val lru = kslrt.lru<String, Int>(128)
val profiler = rt.profiler("economy.lookup")
val sameFactory = runtime
```

- `KSLRT` is the canonical factory object.
- `kslrt` is the compatibility name.
- `rt` and `runtime` are short aliases.
- `cache()` and `lru()` create the same bounded LRU cache.
- `profiler()` and `profile()` create the same profiler.
- `measure(name) { ... }` measures one block and returns its result.

KSL scripts already import the `ru.privateserver.ksl.rt.*` package by default.
Standalone addon projects should add the KSL plugin JAR as a `compileOnly`
dependency and import the package explicitly.

## Cache

```kotlin
import java.time.Duration
```

`KSLCache<K, V>` is a thread-safe, bounded LRU cache with optional
expire-after-write and expire-after-access TTLs. Both default to zero, which
disables that expiration policy. The third constructor/factory argument is
`expireAfterAccess`; existing two-argument calls remain source-compatible.

```kotlin
val balances = KSLRT.cache<String, Long>(maxSize = 10_000, expireAfterAccess = Duration.ofMinutes(5))

balances["player-uuid"] = 250L
val balance = balances["player-uuid"] ?: 0L

val loaded = balances.getOrCompute("another-player") {
    loadBalanceFromDatabase("another-player")
}

if (balances.has("player-uuid")) {
    balances.remove("player-uuid")
}

val keys = balances.keysSnapshot()
val values = balances.valuesSnapshot()
val stats = balances.stats()
println("hit rate: ${stats.hitRate}")
```

Available operations:

- `get(key)` / operator `cache[key]`;
- `put(key, value)` / operator `cache[key] = value`;
- `getOrPut(key) { ... }` and alias `getOrCompute(key) { ... }`;
- `invalidate(key)` and alias `remove(key)`;
- `containsKey(key)` and alias `has(key)`;
- `clear()` / `invalidateAll()`;
- `size()` / `currentSize`;
- `keysSnapshot()`, `valuesSnapshot()`;
- `stats()` and `resetStats()`.

New convenience methods: `computeIfAbsent`, `getOrDefault`, `putIfAbsent`, `putAll` and `cleanUp` (returns how many expired entries it removed).

`getOrPut` runs its loader outside the cache lock. Concurrent misses for the
same key may therefore execute the loader more than once; the first completed
value stored in the cache wins. Do not use it for a loader with non-idempotent
side effects.

A successful `get` refreshes access TTL and LRU recency. `containsKey`/`has`
checks expiration without refreshing either. `cleanUp()` can proactively remove expired entries.

Cache statistics expose `hits`, `misses`, `evictions`, current `size`,
total `requests` and `hitRate`. Clearing entries preserves counters;
`resetStats()` resets counters without clearing entries.

## Profiler

`KSLProfiler` aggregates wall-clock timings and failure counts:

```kotlin
val databaseProfile = KSLRT.profile("database.balance")

val balance = databaseProfile.time {
    loadBalanceFromDatabase(playerId)
}

val snapshot = databaseProfile.stats()
println("calls=${snapshot.calls}, average=${snapshot.averageMillis} ms")
```

- `measure { ... }` and `time { ... }` measure a block, return its result,
  and rethrow exceptions after recording the failed call.
- `record(elapsedNanos, failed)` adds an externally measured sample.
- `snapshot()` and `stats()` return an immutable snapshot.
- `reset()` and `clear()` reset accumulated counters.

Snapshots include call count, failure count, successful calls, total/average/max
nanoseconds and corresponding millisecond values.

## MesLib2 — межскриптовые библиотеки

MesLib2 хранит типизированные экспорты в пространстве имён. Запись принадлежит
скрипту, который её опубликовал, и автоматически удаляется при unload этого
скрипта. Namespace по умолчанию строится из имени текущего скрипта.

```kotlin
// economy.kts — provider
meslib2.exportLocal("service", EconomyLibrary(), version = "2.1")

// shop.kts — consumer
val economy = meslib2.require<EconomyLibrary>("economy", "service", "2.1")
```

### Канонические методы

| Задача | Методы |
|---|---|
| Публикация | `export(namespace, name, value, version)`, `exportLocal(name, value, version)` |
| Безопасная публикация без перезаписи | `exportIfAbsent(...)`, `exportIfAbsentLocal(...)` |
| Необязательное получение | `get<T>(...)`, `getLocal<T>(...)` |
| Обязательное получение | `require<T>(...)`, `requireLocal<T>(...)` |
| Проверка наличия | `contains(...)`, `containsLocal(...)` |
| Метаданные | `info(...)`, `infoLocal(...)` |
| Удаление своего экспорта | `remove(...)`, `removeLocal(...)` |
| Перечень и очистка namespace | `list(...)`, `listLocal()`, `clear(...)`, `clearLocal()` |

`exportIfAbsent` выполняет проверку и публикацию атомарно: конкурентный
поставщик не сможет незаметно заменить уже опубликованное значение.
Удалить экспорт может только его текущий владелец. `list` возвращает
отсортированный снимок `MesLib2Info`, включая `namespace`, `name`,
`version`, `typeName`, `owner` и составной `key`.

### Совместимость

Старые имена не удалены: `publish/provide/put`, `find/lookup`,
`resolve/mustGet`, `has`, `describe`, а также прежние локальные варианты
по-прежнему работают. Для нового кода рекомендуется использовать канонические
методы выше, чтобы не плодить разные названия одной операции.

Версия остаётся непрозрачной меткой с точным сравнением. MesLib2 не пытается
интерпретировать SemVer и не загружает внешние JAR автоматически. Тип объекта
проверяется через JVM-класс; используйте общие API-типы, если провайдер и
потребитель собраны разными classloader-ами.

## Threading and lifecycle

The cache and profiler are thread-safe utility objects. They do not make Bukkit
objects thread-safe: world, entity, inventory and most player operations must
still run on the server thread unless Paper documents otherwise.

These utilities are independent objects. KSL does not automatically clear a
cache or profiler created by a script; keep references scoped to the script or
addon that owns them and discard them when that owner unloads.


## Managed worker scheduler and runtime telemetry

Use the bounded, priority-aware worker pool for CPU-heavy or blocking work
that does not access Bukkit world/player state:

```kotlin
val taskId = worker(KSLWorkPriority.NORMAL) {
    val result = calculateSomethingExpensive()
    runSync { /* apply result through Bukkit APIs */ }
}
val otherTask = cpu { calculateSomethingExpensive() }
val thirdTask = work { calculateSomethingExpensive() }
cancelWorker(taskId)
```

Priorities are `HIGH`, `NORMAL` and `LOW`. Queue saturation and per-script
limits reject new work rather than running it on the caller (possibly main) thread.
Queued and running worker tasks owned by a script are cancelled on unload.
Running tasks are interrupted; worker code should cooperate with interruption.

```yaml
runtime:
  cpu-threads: 2
  queue-capacity: 256
  max-tasks-per-script: 64
```

Change the worker count live with `/ksl runtime workers <1-64>`; it is saved
to `config.yml`. Queue capacity and per-script limit are read at startup.
Worker count limits KSL's background concurrency but does not pin threads to
physical cores or reserve CPU time from the OS.

`/ksl runtime` (aliases: `/ksl perf`, `/ksl memory`, `/ksl cpu`) displays
JVM heap/non-heap usage, processor count, process/system CPU load when available,
thread counts, GC totals and worker queue activity. Scripts and addons can read
the same snapshot via `KSLRuntimeMonitor.snapshot()`, `stats()`, `memory()`,
`kslRuntimeStats` or `runtimeStats()`.

The JVM controls heap size through startup flags such as `-Xms` and `-Xmx`.
KSL does not forcibly invoke garbage collection, resize the JVM heap, or claim
to measure exact memory retained by individual scripts.
