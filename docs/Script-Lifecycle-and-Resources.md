# Script Lifecycle and Resource Ownership

This guide explains the rules of hot reload: who owns a task or handler, who must stop it, and how to prevent old logic from running after a script is unloaded.

For the complete DSL reference, see [Script DSL Reference](Script-DSL-Reference.md). For addon callbacks, see [Addon Development](Addon-Development-Guide.md). For threading and the worker pool, see [Performance and Scaling](Performance-and-Scaling.md).

## 1. Core principle: every resource needs an owner

Every listener, scheduler task, dynamic command, placeholder, subscription, or external callback must have a clear owner and a defined cleanup point.

In scripts, prefer the KSL DSL over registering resources directly through Bukkit or third-party APIs. KSL tracks resources created through its supported context and removes them when the corresponding script is unloaded or reloaded.

Do not assume that every object created inside a script is automatically managed by KSL. The fact that an object was created in a .kts file does not mean KSL knows how to close it.

## 2. What KSL tracks for scripts

Supported context operations include:

- onEvent<T> { ... } — Bukkit event handlers;
- registerCommand(...), registerCommandAny(...), and aliases cmd(...), cmdAny(...);
- registerPlaceholder(...);
- runSync, runAsync, delay, every, delayAsync, everyAsync;
- scheduler aliases sync, async, after, repeat;
- worker, cpu, work, and cancelWorker;
- subscribe(topic) { ... } — subscription to the KSL event bus;
- runtimeScope.cache/lru(...) — a cache scoped to the current script's lifetime;
- runtimeScope.profile(...) — a scoped profiler;
- own(resource) and onUnload { ... } — ownership of external AutoCloseable resources and cleanup callbacks.

Tasks registered through the KSL context are owned by the script. When a script is unloaded, KSL cancels its tracked Bukkit tasks and worker tasks, removes registered commands and related handler/placeholder resources, and automatically unsubscribes listeners created by that script.

Use these entry points instead of calling Bukkit.getScheduler() directly or manually registering global listeners when an equivalent KSL DSL operation is available.

runtimeScope is created lazily and once per script. It is closed automatically on /ksl reload, script removal, and shutdown: callbacks run in reverse registration order, owned caches are cleared, and scoped profilers are discarded. own(resource) is suitable for AutoCloseable objects (HTTP clients, file watchers, custom subscriptions); onUnload { ... } is suitable for APIs that do not provide a separate handle. The handle returned by onUnload can be closed early to unregister the callback and release references captured by it.

## 3. Example: a safe repeating handler

~~~kotlin
val taskId = every(ticks = 20L) {
    // This callback belongs to the current script.
    // Bukkit API work here is scheduled on the main thread.
}

onEvent<PlayerJoinEvent> {
    player.sendMessage("Welcome!")
}
~~~

Do not create a new repeating task inside every event callback: this quickly creates duplicates. Register it once when the script loads.

To stop a task before reload, use the ID returned by the scheduler call:

~~~kotlin
val taskId = every(ticks = 20L) {
    // Repeating work
}
cancelTask(taskId)
~~~

For the worker pool, worker(...) returns a Long; use cancelWorker(taskId) to cancel it.

## 4. Errors and unloading

- warn(message) reports a problem and continues the current operation.
- error(message) interrupts the current script operation but does not stop the server.
- fatal(message) signals a fatal error in the current script and unloads it, releasing its owned resources.

Do not use fatal for an ordinary command argument error or an expected missing optional plugin. In those cases, give the user a clear explanation and end only the handler.

After an error or reload, do not assume that an unfinished external operation will necessarily complete. Worker code must account for interruption and must not perform Bukkit actions after its owner has been unloaded.

## 5. External resources: explicit ownership is required

KSL cannot automatically manage every resource created by a third-party library. If a script or addon creates an ExecutorService/thread, network client, third-party subscription/listener, file watcher, timer or callback registered with another plugin, or a connection/client not owned by KSL's HikariCP, the owner must retain its handle and close/cancel the resource during unload.

Do not create unmanaged long-running threads. Prefer KSL-managed scheduler/worker APIs and APIs that provide an explicit close()/cancel() method.

If an external resource cannot be reliably released during reload, do not create it in a reloadable script. Move ownership to an addon with an explicit onUnload() implementation.

## 6. Addon lifecycle

An addon is a separate Paper plugin that implements KSLAddon. Its lifecycle is different from the lifecycle of a .kts script:

1. KSL registers the addon and calls onLoad(api).
2. The addon registers services, imports, and context extensions.
3. When the addon stops, it must release its own resources in onUnload().
4. Registered addon services/imports/extensions must be removed symmetrically with their registration.

Do not confuse KSLAddon.onUnload() with unloading one .kts file: an addon usually lives longer than individual scripts. For script-specific resources, use KSLContextExtension and context callbacks rather than global addon state without cleanup.

Detailed signatures and examples are available in [Addon Development Guide](Addon-Development-Guide.md) and [Public API](Public-API.md).

## 7. Threading and hot reload

Hot reload does not make the Bukkit API thread-safe and does not turn an asynchronous operation into a synchronous one.

- Run Bukkit world/entity/player/inventory operations on the main thread unless the Paper API explicitly says otherwise.
- Do not run SQL, network calls, or heavy computations on the main thread.
- For background work, pass simple immutable data (UUIDs, strings, numbers, DTOs), not live Bukkit objects.
- Move background results that change Bukkit state back through runSync { ... }.
- Do not call Future.get() or join() on the main thread for a long-running operation.

See [Performance and Scaling](Performance-and-Scaling.md) and [Database Guide](Database-Guide.md).

## 8. Pre-release checklist

- [ ] All KSL-managed listeners/tasks/commands/placeholders are created through the KSL DSL.
- [ ] Nothing is registered repeatedly inside every event callback.
- [ ] Worker task IDs are stored when manual cancellation is required.
- [ ] External subscriptions/clients/watchers have explicit cleanup.
- [ ] There are no unbounded executor/thread pools.
- [ ] Bukkit API is not called from an arbitrary async callback.
- [ ] After /ksl reload, old callbacks no longer execute logic.
- [ ] The addon releases its own resources in onUnload().
- [ ] An error in one script does not clean up resources owned by another script.

## Related documents

- [Script DSL Reference](Script-DSL-Reference.md)
- [Addon Development Guide](Addon-Development-Guide.md)
- [Public API](Public-API.md)
- [Performance and Scaling](Performance-and-Scaling.md)
- [Troubleshooting](Troubleshooting.md)
