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

`KSLCache<K, V>` is a thread-safe, bounded LRU cache with optional
expire-after-write TTL. A zero TTL disables expiration.

```kotlin
val balances = KSLRT.cache<String, Long>(maxSize = 10_000)

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

`getOrPut` runs its loader outside the cache lock. Concurrent misses for the
same key may therefore execute the loader more than once; the first completed
value stored in the cache wins. Do not use it for a loader with non-idempotent
side effects.

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

## MesLib2 concise aliases

MesLib2 remains script-scoped: exports are owned by the provider script and
removed when that script unloads. The current script's namespace is available
as both `namespace` and `scope`.

```kotlin
// Provider
ml2.putLocal("service", EconomyLibrary(), version = "1.0.0")

// Consumer
val economy = libs.lookup<EconomyLibrary>("economy", "service", "1.0.0")
if (libs.has("economy", "service")) {
    val details = libs.describe("economy", "service")
}
```

The new names are aliases, not replacements:

| Purpose | Existing names | Additional concise names |
|---|---|---|
| Publish | `export`, `publish`, `provide` | `put` |
| Optional lookup | `get`, `find` | `lookup` |
| Required lookup | `require`, `resolve` | `mustGet` |
| Existence | `contains` | `has` |
| Metadata | `info` | `describe` |

The corresponding `*Local` methods address the current script's namespace.
Versions remain opaque exact-match labels, not semantic-version ranges.

## Threading and lifecycle

The cache and profiler are thread-safe utility objects. They do not make Bukkit
objects thread-safe: world, entity, inventory and most player operations must
still run on the server thread unless Paper documents otherwise.

These utilities are independent objects. KSL does not automatically clear a
cache or profiler created by a script; keep references scoped to the script or
addon that owns them and discard them when that owner unloads.
