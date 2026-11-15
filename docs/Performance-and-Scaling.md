# KSL — Performance and Scaling

This guide explains how to keep the Paper main thread responsive, use KSL queues, and diagnose bottlenecks.

> An asynchronous API does not guarantee a fixed number of requests per second. Throughput depends on CPU, storage, network latency, SQL, indexes, pool size, server count, and workload. Measure on your own staging environment.

## 1. Find the bottleneck first

Collect the Paper/Java/KSL versions, /ksl doctor and /ksl runtime output, database type and size, MySQL/MariaDB latency, query frequency, read/write queue sizes, heap/GC data, and a thread dump when the server stalls.

Do not increase every limit at once. Larger pools can increase contention for CPU and database connections.

## 2. Keep the main thread responsive

Paper's main thread is responsible for game ticks. Do not block it with SQL, HTTP, Discord, large files, expensive parsing, large loops, Thread.sleep, or long Future.join()/get() calls.

~~~kotlin
runAsync {
    val result = calculateOrLoadData()
    runSync {
        // Change Bukkit state on the main thread only.
        player.sendMessage("Complete: $result")
    }
}
~~~

Do not access Player, World, Entity, Inventory, or other Bukkit objects from a background callback unless the specific Paper API explicitly permits it. Pass simple values such as UUIDs, strings, numbers, and DTOs to background tasks.

## 3. Database connection pools

KSL uses HikariCP. database.pool-size controls the number of connections; start with a conservative value such as 4 and measure the actual workload.

For MySQL/MariaDB, consider the combined connection limit: add the pools from all Minecraft servers and other database clients. Do not set pool-size to 32 “just in case”. Measure latency, query duration, queue depth, database CPU/I/O, and max_connections.

## 4. Read and write queues

KSL separates user SQL operations:

- Writes use one sequential background queue that preserves the order of accepted write tasks.
- Reads use a separate reader pool sized with the database connection pool.
- Both queues are bounded at 10,000 pending tasks.

The bound provides backpressure: when a queue is saturated, new tasks are rejected instead of consuming unbounded memory. 10,000 is a safety ceiling, not a target queue size.

Monitor both queues with /ksl doctor. If a queue grows continuously, look for excessive query frequency, slow SQL, missing indexes, or network latency. Increasing the queue size does not solve the underlying problem.

## 5. Asynchronous SQL API

Existing calls remain available:

- dbExecute(sql, ...args) / execute(sql, ...args);
- dbQuery(sql, ...args) { ResultSet -> ... } / query(sql, ...args) { ... };
- dbExecuteYaml(path, ...args) / executeYaml(...);
- dbQueryYaml(path, ...args) { ... } / queryYaml(...).

Future-based variants:

- dbExecuteAsync(sql, ...args) / executeAsync(...);
- dbQueryAsync(sql, ...args, mapper = { rs -> detachedValue }) / queryAsync(...);
- dbExecuteYamlAsync(path, ...args) / executeYamlAsync(...);
- dbQueryYamlAsync(path, ...args, mapper = { ... }) / queryYamlAsync(...).

~~~kotlin
queryAsync(
    "SELECT balance FROM coins WHERE uuid = ?",
    player.uniqueId.toString(),
    mapper = { rs -> if (rs.next()) rs.getLong("balance") else 0L }
).whenComplete { balance, error ->
    if (error != null) {
        logWarn("Could not load balance: ${error.message}")
    } else {
        runSync { player.sendMessage("Balance: $balance") }
    }
}
~~~

The mapper must return a value detached from JDBC: a number, string, data class, or collection. Do not return ResultSet, Connection, or Statement; JDBC resources are closed after the mapper finishes.

When a queue is full, the future completes exceptionally with an error related to RejectedExecutionException. Handle it with whenComplete/exceptionally. Never call join() on the main thread.

## 6. Prepared statements and indexes

Pass user-provided values as parameters:

~~~kotlin
execute(
    "UPDATE coins SET balance = balance + ? WHERE uuid = ?",
    amount,
    playerId
)
~~~

Do not build SQL by interpolating player names, chat messages, or command arguments. Parameters protect values, not SQL identifiers: select table and column names from a fixed allowlist.

Add indexes for frequent WHERE/JOIN/ORDER BY patterns, select only required columns, limit result sets, and inspect query plans using your database tools.

## 7. YAML SQL query cache

Queries from database.queries are cached. After editing the file, reload the script configuration:

~~~kotlin
val count = reloadScriptConfig()
log("Reloaded queries: $count")
~~~

If YAML is updated programmatically through editConfig, refresh the query cache:

~~~kotlin
editConfig {
    set("database.queries.find-coins", "SELECT balance FROM coins WHERE uuid = ?")
}
reloadYamlSqlQueries()
~~~

Cache refresh is atomic: running DB tasks keep the SQL string they already received, while new tasks use the latest version.

## 8. KSLRT worker pool

runAsync and worker serve different purposes. runAsync submits work to the Bukkit async scheduler and returns a Bukkit task ID (Int). worker uses the bounded KSL worker pool, supports priorities, and returns a worker task ID (Long). Prefer worker for CPU-heavy tasks and controlled queueing; use runAsync for simple background work that fits the Bukkit async scheduler. Neither API makes Bukkit world/player/inventory state safe to access asynchronously.

~~~kotlin
val taskId = worker(KSLWorkPriority.NORMAL) {
    val result = calculateSomethingExpensive()
    runSync {
        // Apply the result through Bukkit API.
    }
}
~~~

Priorities: HIGH, NORMAL, LOW. The queue is bounded and there is a per-script task limit. Under overload, work is rejected instead of unexpectedly running on the caller's thread. Tasks belong to their script and are cancelled on unload. Worker code must handle interruption correctly.

config.yml example:

~~~yaml
runtime:
  cpu-threads: 2
  queue-capacity: 256
  max-tasks-per-script: 64
~~~

The worker count can be changed through /ksl runtime workers <1-64>. Queue capacity and the per-script limit are read at startup.

## 9. Caching

KSLCache<K, V> is a thread-safe bounded LRU cache with optional TTL:

~~~kotlin
val cache = KSLRT.cache<String, Long>(maxSize = 10_000)
cache[playerId] = balance
val cached = cache[playerId]
val stats = cache.stats()
~~~

A cache is not the source of truth. Plan TTL, invalidation after writes, multi-server behavior, and maximum size. A local cache is not automatically synchronized with other JVMs. Under concurrent cache misses, the loader may run more than once.

## 10. Profiling

~~~kotlin
val profiler = KSLRT.profiler("economy.load")
val value = profiler.measure { loadEconomyData() }
val snapshot = profiler.snapshot()
log("calls=${snapshot.calls}, avgNs=${snapshot.averageNanos}")
~~~

The named profiler registry is limited to 1,024 names. Call KSLRT.removeProfiler(name) when a dynamic profiler is no longer needed. Do not create a unique name for every invocation.

## 11. Script index and tab completion

KSL keeps an immutable in-memory snapshot of script IDs and reload-completion targets, rather than retaining File objects. File instances are created only for explicit reload, validation, and status operations. Tab completion reuses compact metadata instead of walking the scripts directory and rebuilding folder candidates on every keystroke. The snapshot is refreshed by discovery/reload operations and invalidated by filesystem watcher events.

The per-script command registry uses compact owner lists rather than allocating a separate concurrent hash set for every script. Bukkit still owns the live command objects and callbacks: this bookkeeping optimization reduces duplicate registry overhead, but it does not remove the memory used by loaded script code or command handlers.

For servers with many scripts, enable auto-reload when filesystem changes should immediately appear in suggestions. Without the watcher, an explicit reload/validation scan refreshes the index.

## 12. Checklist

- [ ] No SQL or network calls on the main thread.
- [ ] User values are passed through prepared statements.
- [ ] Frequent queries have appropriate indexes.
- [ ] Future errors are handled.
- [ ] Bukkit operations are returned to the main thread.
- [ ] Worker tasks are bounded and cancellable.
- [ ] Queues do not grow continuously.
- [ ] All server pools fit within the database connection limit.
- [ ] Backup and restore have been tested.
- [ ] Load has been measured on a staging environment.

See [Database Guide](Database-Guide.md), [Runtime API](Runtime-API.md), [Production Operations](Production-Operations.md), and [Troubleshooting](Troubleshooting.md).
