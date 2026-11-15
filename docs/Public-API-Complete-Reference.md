# KSL Public API — Complete Addon Reference

This document is the detailed reference for Kotlin and Java plugins that integrate with KotlinScriptLoader. It describes the addon contract, services, imports, hooks, event bus, script lifecycle, resource scopes, convenience functions, compatibility rules and common failure modes.

The examples are written against the public contracts in the current source. KSL is evolving: confirm the exact plugin JAR and source version used by your addon before adopting a newer helper.

## 1. The API layers

KSL exposes several related namespaces. They are not interchangeable.

| Namespace / layer | Purpose |
|---|---|
| ru.privateserver.ksl | Core runtime contracts and Java-visible API interfaces |
| ru.privateserver.ksl.api | Stable Kotlin-oriented public aliases, extension functions and Java API provider |
| ru.privateserver.ksl.addon | Optional concise Kotlin addon DSL helpers |
| ru.privateserver.ksl.hooks | Built-in hook implementations and hook authoring helpers |
| ru.privateserver.ksl.std | Built-in script Standard Library |
| ru.privateserver.ksl.rt | Caches, profilers, worker scheduling and runtime scopes |
| Internal implementation packages | KSL internals; do not treat as stable addon contracts unless explicitly documented |

A helper visible in a .kts script is not automatically a member of KSLAPI. A Kotlin typealias is not emitted as a separate Java class. An internal class being public in bytecode does not mean it is a supported integration point.

## 2. Addon lifecycle at a glance

A KSL addon is a regular Paper plugin that implements KSLAddon.

The usual lifecycle is:

1. Bukkit loads the addon plugin after KSL because plugin.yml declares a hard dependency.
2. The addon's onEnable() checks that KSL is available.
3. The addon registers itself using KSL.api.registerAddon(this).
4. KSL calls the addon's onLoad(api).
5. The addon registers services, imports, hooks, modules, context extensions and other KSL resources.
6. When the addon plugin is disabled, it calls KSL.api.unregisterAddon(addonId).
7. KSL calls onUnload() and releases KSL-owned registrations for that addon.
8. The addon closes its own external resources.

The addon lifecycle is longer than the lifecycle of a typical .kts script. An addon may remain loaded while many scripts are reloaded.

## 3. Build configuration

### 3.1 Dependencies

Compile against the KSL JAR and Paper API, but do not package a second copy of the KSL API classes inside the addon.

~~~kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(files("libs/KotlinScriptLoader.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}
~~~

The version above is an example aligned with the repository's documented environment. For a real addon, match the KSL release and Paper API that the target server uses. Do not assume the example coordinates are appropriate for a different server generation.

### 3.2 plugin.yml

~~~yaml
name: ExampleAddon
version: 1.0.0
main: com.example.addon.ExampleAddon
api-version: '1.21'
depend:
  - KotlinScriptLoader
~~~

Use depend when the addon cannot function without KSL. A soft dependency is appropriate only when your plugin can operate meaningfully without KSL and handles that state.

### 3.3 Why compileOnly matters

KSL's public types are shared through the plugin classloader. If an addon shades or bundles another copy of KSL API classes, the JVM may load two different Class objects with the same fully-qualified name.

That breaks assignability checks and can make a service appear to have the expected type name while still failing a runtime type check.

Keep KSL as compileOnly. Do not shade KSL itself, Kotlin Script compiler internals, Paper API classes or duplicate API interfaces into the addon.

## 4. Minimal addon implementation

~~~kotlin
package com.example.addon

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class ExampleAddon : JavaPlugin(), KSLAddon {
    override val addonId = "example-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Example KSL integration"

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader is unavailable")
            server.pluginManager.disablePlugin(this)
            return
        }

        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        // Register KSL-facing capabilities here.
    }

    override fun onUnload() {
        // Release resources owned by this addon.
    }

    override fun onDisable() {
        if (KSL.isAvailable) {
            KSL.api.unregisterAddon(addonId)
        }
    }
}
~~~

Do not access KSL.api before checking KSL.isAvailable. KSL may not have completed initialization or may already be shutting down.

The KSLAddon contract includes:

| Property / callback | Purpose |
|---|---|
| addonId | Unique addon identifier. |
| addonVersion | Addon version shown in diagnostics and addon listings. |
| addonDescription | Optional description. |
| dependsOn | Dependency metadata. It is not by itself a Bukkit load-order mechanism. |
| minApiVersion | Minimum KSL API version accepted by the addon. |
| onLoad(api) | Register KSL-facing capabilities. |
| onUnload() | Release addon-owned resources. |

When an addon needs another KSL addon to be ready, use onAddonReady() rather than assuming that Bukkit plugin load order also establishes KSL addon registration order.

## 5. Accessing KSLAPI from Kotlin

Import the public namespace:

~~~kotlin
import ru.privateserver.ksl.api.*
~~~

The API instance aliases are:

| Name | Nullability / behavior |
|---|---|
| ksl | Non-null installed API; throws if KSL is unavailable. |
| kslapi | Same installed API. |
| kslApi | Same installed API. |
| api | Same installed API. |
| kslInstance | Same installed API. |
| kslOrNull | Nullable; suitable for plugin load-order and shutdown edges. |
| isKslAvailable | Availability flag. |

The following Kotlin type names refer to core types through typealiases:

- KSLAPI, KslApi, Api and KSLPublicAPI;
- KSLAddon and KslAddon;
- KSLAddonConfig;
- KSLContextExtension;
- KSLHook and KslHook;
- KSLScriptInfo and KslScriptInfo;
- KSLScriptEvent;
- KSLShapeFactory;
- BukkitScriptContext;
- KSLStandardLibraryModule and KSLModuleDescriptor;
- MesLib2, KSLMesLib2, MesLib and MesLib2Info;
- KSLCache, KSLProfiler, KSLWorkScheduler and KSLWorkPriority;
- KSLRuntimeSnapshot.

Kotlin aliases improve imports; they do not create distinct runtime types.

## 6. Accessing KSLAPI from Java

Kotlin typealias declarations are not emitted as Java classes. Java addons should use the real core interfaces and the Java-friendly provider:

~~~java
import ru.privateserver.ksl.KSLAPI;
import ru.privateserver.ksl.api.KSLApiProvider;

KSLAPI api = KSLApiProvider.getApi();

if (api != null) {
    api.scripts().forEach(script ->
        getLogger().info(script.getId())
    );
}
~~~

KSLApiProvider exposes static Java accessors for availability, nullable API lookup and required API lookup.

For Java development, use real classes in ru.privateserver.ksl where a Kotlin typealias would otherwise appear in an import. Check the compiled signatures in the KSL JAR when interoperability is important.

## 7. KSLAPI member inventory

KSLAPI is the central addon-facing contract.

### 7.1 Core and identity

| Member | Purpose |
|---|---|
| kslPlugin | The KSL JavaPlugin instance. |
| apiVersion | API compatibility level of the installed KSL build. |
| createConfig(owner, fileName = "config.yml", copyDefaults = true) | Creates a YAML config in the owner's data folder using defaults bundled in the addon's JAR. |

KSLAddonConfig is a wrapper around addon-owned YAML configuration. It backs up malformed YAML before loading defaults and writes atomically. For the exact accessors and file path rules, see [Addon Development Guide](Addon-Development-Guide.md).

### 7.2 Addon registration

| Member | Purpose |
|---|---|
| registerAddon(addon) | Registers the addon and starts its KSL lifecycle. |
| unregisterAddon(addonId) | Unregisters an addon and runs its unload lifecycle. |
| registeredAddons() | Snapshot of registered addons. |
| isAddonLoaded(addonId) | Checks whether an addon is registered. |
| onAddonReady(addonId, callback) | Runs a callback when the requested addon is ready; if already ready, the callback runs immediately. |

The dependsOn property is useful metadata but should not be treated as an automatic service dependency resolver. Use onAddonReady() when another addon must be registered before its service is used.

### 7.3 Services

| Member | Purpose |
|---|---|
| registerService(key, instance) | Publishes an object under a service key. |
| unregisterService(key) | Removes a service owned by the current addon lifecycle context. |
| getService(key) | Untyped nullable lookup. |
| getService(key, Class<T>) | Typed nullable lookup. |

Kotlin extensions:

- service<T>(key)
- serviceOrNull<T>(key)
- requireService<T>(key)
- serviceOrThrow<T>(key)
- hasService(key)

~~~kotlin
// Provider addon:
api.registerService("economy", economyService)

// Consumer addon:
val economy = api.service<EconomyService>("economy")
    ?: error("Economy service is not installed")

val requiredEconomy = api.requireService<EconomyService>("economy")
~~~

A missing service returns null for nullable accessors. Required accessors throw an exception with the missing service key and requested type.

Prefer a shared service interface if several addons depend on the same contract. The provider and consumer must share the same interface class loaded by a compatible classloader.

### 7.4 Default imports

| Member | Purpose |
|---|---|
| addDefaultImports(vararg packages) | Adds Kotlin import declarations to KSL script compilation. |
| addImports(vararg packages) | Kotlin convenience alias. |

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports(
        "com.example.economy.*",
        "com.example.economy.model.Currency"
    )
}
~~~

A registered import does not download or compile code. The imported class/function must exist in the addon JAR or an available runtime dependency. Wildcard imports apply to the named package; they are not recursive.

KSL makes a registered addon's classloader available to subsequent script compilation/evaluation. On addon unload, its imports and classloader exposure are removed from later compilations. Scripts that already reference addon classes should be unloaded or reloaded before the addon JAR is disabled.

### 7.5 Standard Library modules

| Member | Purpose |
|---|---|
| registerStandardLibraryModule(module) | Registers and installs a versioned module. |
| unregisterStandardLibraryModule(moduleId) | Uninstalls a module when dependents permit it. |
| standardLibraryModules() | Returns the installed module list. |
| KSLModulePlanner.resolve(modules, apiVersion) | Validates dependencies/API compatibility and returns deterministic dependency-first order. |

A module describes its identity, version, required API level, dependency IDs and default imports. The planner checks duplicate IDs, missing dependencies, dependency cycles and API compatibility.

A module is compiled Kotlin code in an addon JAR. It is not a script source package downloaded from the internet. Install/uninstall should be symmetrical. Module cleanup and dependency ordering are described in [Custom Standard Library](Custom-Standard-Library.md) and [Standard Library Architecture](Standard-Library-Architecture.md).

### 7.6 Context extensions

| Member | Purpose |
|---|---|
| registerContextExtension(extension) | Registers a callback object for script context creation/destruction. |
| unregisterContextExtension(extensionId) | Unregisters an extension owned by the current addon context. |

Kotlin helper in the addon DSL package:

~~~kotlin
api.contextExtension(
    id = "profile-context",
    onCreated = { context ->
        // Create state for this script.
    },
    onDestroyed = { scriptName ->
        // Remove state belonging to this script.
    }
)
~~~

Use a context extension when an addon needs per-script state. Do not retain a destroyed BukkitScriptContext or keep script-specific data in an unbounded global map.

### 7.7 Event bus

Core members:

- subscribe(topic, handler): AutoCloseable
- publish(topic, payload = null): Int

Kotlin aliases:

- on(topic, handler)
- emit(topic, payload = null)
- listenTo(topic, handler)
- sendEvent(topic, payload = null)

~~~kotlin
// Subscriber:
val purchaseSubscription = api.subscribe("shop.purchase") { payload ->
    val purchase = payload as? PurchaseInfo ?: return@subscribe
    api.log("Purchase completed for " + purchase.playerId)
}

// Publisher:
api.publish("shop.purchase", purchaseInfo)

// Explicit cleanup when the subscription is not made in onLoad:
purchaseSubscription.close()
~~~

Handlers run synchronously on the publisher's thread. A handler must be quick and must obey the thread rules of any API it calls. One failing handler does not stop the other subscribers from being invoked. publish returns the number of handlers that completed without throwing.

Subscriptions created during the addon's onLoad lifecycle are automatically removed when that addon unloads. For a subscription created outside onLoad, retain the returned AutoCloseable or register it in KSLAddonScope.

### 7.8 Script inventory and reload

| Member | Purpose |
|---|---|
| scripts() | Returns script metadata snapshots. |
| reloadScript(scriptId) | Recompiles/swaps one script; blocking variant, main-thread only. |
| reloadScripts() | Reloads all scripts; main-thread only. |
| reloadScriptsAsync(done) | Compiles in background and applies the result on the main thread. |
| isSandboxed(scriptId) | Checks whether the script is under KSL sandbox policy. |
| isConsoleCommandBlocked(command) | Checks KSL's script console-command restriction. |

Kotlin aliases include findScript, requireScript, isScriptLoaded, script, scriptOrNull, scriptOrFail, reload, reloadAll, reloadOne, reloadEverything and reloadAllAsync.

~~~kotlin
api.scripts().forEach { info ->
    api.log(info.id + " loaded=" + info.loaded)
}

api.reloadScriptsAsync { result ->
    if (result == null) {
        api.warn("A reload is already running.")
    } else {
        api.log("Reload result: " + result)
    }
}
~~~

The async callback may receive null when another reload is already in progress. The synchronous reload operation blocks the caller until work completes and is restricted to the main thread by the API contract.

### 7.9 Hooks and optional plugins

| Member | Purpose |
|---|---|
| registerHook(hook) | Registers an integration hook. |
| unregisterHook(hookId) | Removes a hook owned by the current addon context. |
| getHook(hookId) | Untyped nullable lookup. |
| getHook(hookId, Class<T>) | Typed nullable lookup. |
| hooks() | Current hook snapshot. |
| isPluginEnabled(pluginName) | Checks whether a Bukkit plugin is enabled. |

Kotlin extensions include hook<T>(), requireHook<T>(), hookOrNull<T>(), hookOrFail<T>(), hasHook(), hasHookRegistered(), pluginAvailable() and hasPlugin().

Hook IDs are case-insensitive. Register hooks only when the provider plugin is enabled. Built-in hook implementations and IDs are grouped under ru.privateserver.ksl.hooks.*; existing root-package facades remain for compatibility.

### 7.10 Placeholder registration

| Member | Purpose |
|---|---|
| registerPlaceholder(key, resolver) | Registers a PlaceholderAPI resolver for %ksl_key%. |
| unregisterPlaceholder(key) | Removes an addon-owned resolver. |
| registerTextPlaceholder(key, resolver) | Registers a text token for KSL message formatting. |
| unregisterTextPlaceholder(key) | Removes an addon-owned text resolver. |

PlaceholderAPI integration is optional. A resolver receives an OfflinePlayer and returns nullable text. Text placeholders receive Player? because a formatted message may not be addressed to a player.

Keys are case-insensitive. Built-in text tokens are reserved. Resources registered during onLoad are owned by the addon and released during unload.

Keep resolvers fast. Do not perform blocking SQL/network work every time PlaceholderAPI requests a value. Cache or precompute data with a clear expiration and invalidation strategy.

### 7.11 Effect shapes and entity attributes

| Member | Purpose |
|---|---|
| registerEffectShape(name, factory) | Registers a custom shape factory for script effect APIs. |
| registerEntityAttribute(key, handler) | Registers a KEY:value handler for custom entity templates. |

These extension points allow addons to add behavior to KSL's existing effect/entity features without modifying KSL core. Keep the handler narrow, validate parameters, and handle unsupported entity types cleanly.

## 8. Kotlin convenience extensions

The public package contains additive Kotlin extensions on KSLAPI. They do not add abstract interface members, so adding a new extension does not force existing KSLAPI implementations to implement another method.

### 8.1 Online player lookup

| Function | Behavior |
|---|---|
| onlinePlayers() | Snapshot of online Player objects. |
| onlinePlayerCount() | Current online player count. |
| findOnlinePlayer(name) | Exact-name online lookup. |
| findOnlinePlayer(uuid) | UUID lookup. |
| isPlayerOnline(uuid) | Checks whether a UUID currently belongs to an online player. |

These operations query Bukkit and should be called on the server thread when live player state is involved.

### 8.2 Broadcast helpers

| Function | Behavior |
|---|---|
| broadcast(String) | Sends literal text as Component.text. |
| broadcast(Component) | Broadcasts an Adventure component. |
| broadcastRich(String) | Parses MiniMessage, then broadcasts the component. |

Do not use broadcastRich with arbitrary untrusted user input. It interprets formatting markup.

### 8.3 Thread and scheduler helpers

| Function | Behavior |
|---|---|
| isServerThread() | Returns Bukkit.isPrimaryThread(). |
| scheduleSync(block) | Schedules work on the next server tick. |
| scheduleAsync(block) | Schedules on a Bukkit async worker. |
| scheduleSyncLater(delayTicks, block) | Schedules sync work after a delay. |
| scheduleAsyncLater(delayTicks, block) | Schedules async work after a delay. |
| scheduleSyncRepeating(initialDelayTicks, periodTicks, block) | Repeats on the server thread. |
| scheduleAsyncRepeating(initialDelayTicks, periodTicks, block) | Repeats asynchronously. |

These helpers return BukkitTask. They do not automatically cancel arbitrary work when the addon unloads. Keep the task handle and cancel it in onUnload, or use KSLAddonScope to manage the lifecycle as a group.

The async variants must not access Bukkit world, entity, player or inventory state unless the exact Paper API explicitly permits that operation.

## 9. KSLAddonScope — unified resource ownership

KSLAddonScope is the explicit resource owner added to the public API. It groups external resources, Bukkit listeners, typed event callbacks, event-bus subscriptions and Bukkit tasks under one close operation.

Create it with the Kotlin extension:

~~~kotlin
val scope = api.addonScope()
~~~

Java callers cannot invoke Kotlin extension functions as instance methods. Use the Java-visible static factory instead:

~~~java
import ru.privateserver.ksl.api.KSLAddonScope;
import ru.privateserver.ksl.KSLAPI;

KSLAddonScope scope = KSLAddonScope.create(api);
~~~

The factory creates the same scope implementation and accepts the real KSLAPI interface. The Kotlin extension is a convenience wrapper around this factory.

The scope is explicit. KSL does not automatically call close() on it merely because the addon is unregistered. Retain it and close it from onUnload().

### 9.1 Scope lifecycle

| Property / method | Behavior |
|---|---|
| isClosed | True after close has started. |
| resourceCount | Number of resources still held by the scope. |
| own(resource) | Adds an AutoCloseable resource and returns the same resource. |
| release(resource) | Removes and closes a previously owned resource. |
| close() | Closes all owned resources in reverse registration order. |

If own() is called after the scope has closed, the resource is closed immediately and an IllegalStateException is thrown.

release() returns false when the resource was not owned. When it is owned, it is removed before close is invoked. A close exception is propagated to the caller.

close() is idempotent. It tries every resource even when one close fails. Failures are aggregated into an IllegalStateException, with additional failures attached as suppressed exceptions.

### 9.2 Bukkit event listeners

~~~kotlin
scope.registerEvents(myListener)

scope.listen<PlayerJoinEvent> { event ->
    event.player.sendMessage("Welcome!")
}

scope.listen<PlayerQuitEvent>(
    priority = EventPriority.MONITOR,
    ignoreCancelled = true
) { event ->
    // Observe the event; MONITOR handlers should not modify it.
}
~~~

registerEvents(listener) registers a normal Bukkit Listener. listen<T>() registers one typed event handler. The returned AutoCloseable from listen can also be closed early.

Bukkit listener registration should happen on the server thread. Listeners are unregistered when the scope closes.

### 9.3 Event bus subscriptions

~~~kotlin
scope.subscribe("server.maintenance") { payload ->
    // Receive the event-bus payload.
}
~~~

The KSL event bus invokes handlers synchronously on the publisher's thread. The scope owns the returned subscription and closes it during scope cleanup.

When an addon subscribes inside onLoad, the addon lifecycle itself also tracks that subscription. KSLAddonScope is especially useful when a resource group includes subscriptions alongside tasks, Bukkit listeners and external AutoCloseable resources. Avoid creating independent duplicate subscriptions just to obtain a cleanup handle.

### 9.4 Scheduled tasks

~~~kotlin
scope.runSync {
    // Next server tick.
}

scope.runAsync {
    // Background task; no Bukkit world/player/inventory access.
}

scope.runSyncLater(delayTicks = 40L) {
    // Server-thread work after 2 seconds at 20 TPS.
}

scope.runSyncRepeating(
    initialDelayTicks = 0L,
    periodTicks = 20L
) {
    // Repeating server-thread work.
}

scope.runAsyncRepeating(
    initialDelayTicks = 20L,
    periodTicks = 100L
) {
    // Repeating async work; do not use Bukkit live state here.
}
~~~

All scheduled tasks are wrapped in a cancellation handle owned by the scope. The task methods return BukkitTask so an addon can also cancel a task early.

Delays and initial delays must be non-negative. Repeating periods must be positive.

### 9.5 External resources

~~~kotlin
scope.own(createHttpClient())
scope.own(thirdPartySubscription)
scope.own(fileWatcher)
~~~

Any external object that implements AutoCloseable can be owned. The addon remains responsible for choosing resources whose close operation is safe to invoke during addon unload.

### 9.6 Complete addon example

~~~kotlin
class MetricsAddon : KSLAddon {
    override val addonId = "metrics-addon"
    override val addonVersion = "1.0.0"

    private var scope: KSLAddonScope? = null

    override fun onLoad(api: KSLAPI) {
        val resources = api.addonScope()
        scope = resources

        resources.listen<PlayerJoinEvent> { event ->
            recordJoin(event.player.uniqueId)
        }

        resources.subscribe("server.backup.completed") { payload ->
            recordBackup(payload)
        }

        resources.runSyncRepeating(periodTicks = 20L) {
            publishCurrentMetrics()
        }

        resources.own(createMetricsClient())
    }

    override fun onUnload() {
        scope?.close()
        scope = null
    }
}
~~~

### 9.7 Scope boundaries

KSLAddonScope is a resource-lifecycle helper, not a thread-safety wrapper, sandbox, or process isolation mechanism.

- Register Bukkit listeners on the server thread.
- Keep Bukkit world/entity/player/inventory access on the server thread.
- Do not block the main thread while closing slow network or database clients; design close operations to be bounded and predictable.
- Do not use the scope as permission to create unmanaged threads.
- Do not assume an async task can safely use a Player reference after the player disconnects or the addon unloads.

## 10. Concise addon DSL — ru.privateserver.ksl.addon

The package ru.privateserver.ksl.addon.* contains convenience wrappers around the longer KSLAPI methods. These are Kotlin helpers; core API names remain available.

| Helper | Purpose |
|---|---|
| simpleAddon(id, version, ...) { api -> } | Creates a small addon object without a dedicated class. Supports description, dependency metadata, API requirement, load and unload callbacks. |
| KSLBaseHook(hookId, pluginName) | Base class for a custom hook. |
| api.provide(key, instance) / api.revoke(key) | Service registration/removal aliases. |
| api.hasService(key) / api.serviceOr<T>(key, default) | Service lookup helpers. |
| api.imports(vararg packages) | Alias for default imports. |
| api.whenReady(id) { addon -> } | Alias for onAddonReady. |
| api.isLoaded(id), api.findAddon(id), api.addonIds() | Addon lookup helpers. |
| api.requireApi(version) | Fails fast when the installed API is too old. |
| api.kslVersion | Installed KSL plugin version. |
| api.reload() | Reloads all scripts. |
| api.contextExtension(id, ...) | Creates a KSLContextExtension from callbacks. |
| api.hookIds(), api.hasHook(id), api.hookIf(pluginName) { ... } | Hook lookup and optional registration. |
| api.placeholder(key) { ... } | PlaceholderAPI registration alias. |
| api.textPlaceholder(key) { ... } | KSL text-token registration alias. |
| api.shape(name, factory) | Custom effect shape alias. |
| api.entityAttribute(key) { ... } | Custom entity-template attribute alias. |
| shapeOf { tick, center -> points } | Creates a shape from a lambda. |
| ringPoints(radius, count, y) | Generates a horizontal ring of vectors. |
| parseTemplate(text) | Parses KEY:value template fields. |
| entity.attr(name, value), entity.attrBase(name) | Reads/sets a named base attribute through KSL's helper. |
| api.on(topic) { ... }, api.emit(topic, payload) | Event-bus aliases. |
| api.table(name) | Accesses the KSL SQLite table facade for addon storage. |
| api.log(...), api.warn(...), api.severe(...) | KSL-prefixed logging helpers. |
| api.sync { ... }, api.async { ... }, api.later(ticks) { ... }, api.every(ticks) { ... } | Raw Bukkit scheduler shortcuts. |
| api.listen<Event> { ... } | Registers a Bukkit event listener and returns a close handle. |

The concise addon DSL's raw sync/async/later/every task helpers are not automatically cancelled when the addon unloads. Retain BukkitTask or use KSLAddonScope for grouped ownership.

### 10.1 simpleAddon example

~~~kotlin
val addon = simpleAddon(
    id = "greeter",
    version = "1.0.0",
    description = "Greeter service"
) { api ->
    api.provide("greeter", Greeter())
    api.imports("com.example.greeter.dsl.*")
}
~~~

The returned object still follows the KSLAddon lifecycle. It does not bypass addon registration, classloader requirements or cleanup rules.

## 11. Script lifecycle event topics

KSL publishes script lifecycle events through the event bus:

| Topic | Payload |
|---|---|
| ksl.script.loaded | KSLScriptEvent |
| ksl.script.failed | KSLScriptEvent |
| ksl.script.unloaded | KSLScriptEvent |

Typed Kotlin helpers:

- onScriptLoaded { event -> ... }
- onScriptFailed { event -> ... }
- onScriptUnloaded { event -> ... }

~~~kotlin
val loadedSubscription = api.onScriptLoaded { event ->
    api.log("Script loaded: " + event.scriptId)
}
~~~

Handlers run synchronously on the publisher thread. A lifecycle event is diagnostic/application notification; do not perform slow operations in the callback. Close manual subscription handles when no longer required.

## 12. Runtime telemetry and utilities

The public package re-exports selected runtime classes and exposes telemetry snapshots.

| Type / member | Purpose |
|---|---|
| KSLCache<K, V> | Bounded thread-safe cache. |
| KSLProfiler | Timing statistics collector. |
| KSLWorkScheduler / KSLWorkPriority | Managed background work API types. |
| KSLRuntimeSnapshot | Immutable runtime metrics snapshot. |
| kslRuntimeStats | Current read-only runtime snapshot property. |
| runtimeStats() | Function-form snapshot alias. |
| runtimeMetrics | Alternative property alias. |
| kslRuntimeSnapshot() | Explicitly prefixed function alias. |

KSLRT is the canonical runtime utility factory; Kotlin aliases include kslrt, rt and runtime. For cache semantics, TTL behavior, profiler counters, worker-pool limits and script runtime scopes, read [Runtime API](Runtime-API.md).

## 13. Optional Skript integration

KSL can expose an optional bridge to Skript through its own hook when Skript is installed. Skript is not a hard dependency of KSL.

Kotlin API aliases include:

- skriptOrNull()
- requireSkript()
- isSkriptEnabled()
- skriptCommand(...)
- skriptVariable(...)
- setSkriptVariable(...)
- deleteSkriptVariable(...)
- skriptVariables(...)
- skriptScripts()
- isSkriptScriptLoaded(...)
- reloadSkriptScript(...)
- reloadAllSkript()

The bridge does not make Skript part of KSL's compiler or turn Skript variables into Kotlin properties. Check availability and follow the bridge's thread and value-serialization constraints. See [Standard Library Guide](Standard-Library-Guide.md).

## 14. Ownership and automatic cleanup

KSL tracks registrations made during the addon's onLoad callback. Depending on the registration type, this includes services, default imports, context extensions, hooks, modules and event-bus subscriptions. During addon unload, KSL removes the resources owned by that addon.

An addon cannot unregister another addon's owned service, hook, placeholder, context extension or Standard Library module by key while running in its own lifecycle context. This prevents one addon from accidentally tearing down another addon's capability.

This automatic registry cleanup is not a substitute for closing external resources:

- KSL does not know how to close arbitrary third-party clients unless the addon owns the handle;
- a thread or executor created by the addon must be stopped;
- Bukkit listeners registered outside KSL ownership must be unregistered;
- repeating tasks created with raw Bukkit scheduler helpers must be cancelled;
- watchers, subscriptions and network resources need explicit close/cancel operations.

Use KSLAddonScope or explicit onUnload cleanup for those objects.

## 15. Threading rules

The event bus invokes subscribers synchronously on the publisher's thread. Bukkit's threading restrictions remain in effect.

| Operation | Default guidance |
|---|---|
| Read online players / player properties | Server thread |
| Change player inventory, health, location or effects | Server thread |
| Query or change world/block/entity state | Server thread |
| Register Bukkit listeners | Server thread |
| SQL or network operations | Background thread / async service |
| CPU-heavy work | Bounded worker pool |
| Apply background result to Bukkit | Return to server thread |

Use immutable values to cross thread boundaries. UUIDs, strings, numbers and data-transfer objects are preferable to live Player, Entity, World or Inventory objects.

Do not block the main thread with Future.get(), join(), network requests, SQL, file I/O or long hashing operations.

## 16. Compatibility policy

KSL public API changes should follow these rules:

1. Additive Kotlin extensions are preferred when a new convenience operation does not need new implementation state.
2. Additive concrete helper classes can be introduced without forcing every KSLAPI implementation to add an abstract method.
3. New abstract KSLAPI members require careful compatibility planning and an intentional API version decision.
4. Breaking changes need migration notes and an API version update.
5. Existing aliases should remain until a documented deprecation/migration process exists.
6. Kotlin typealiases must not be presented as Java-visible classes.
7. KSL API dependencies should remain compileOnly in addons.
8. Public signatures and behavior should have focused tests.

KSL_API_VERSION is the public compatibility level exported by the installed build. Read it at runtime rather than hardcoding an assumed version. Addon minApiVersion declares the minimum supported API level.

## 17. Common integration failures

### KSL is unavailable during onEnable

Check KSL.isAvailable before reading KSL.api. Ensure plugin.yml declares depend when the addon requires KSL.

### A service is found by key but fails a type check

The addon may have bundled a duplicate copy of the service interface or class. Move KSL API and shared contracts to compileOnly / a shared parent-loaded API dependency.

### Script cannot resolve an addon function

Check all of these:

1. The addon is registered and loaded.
2. The class/function is compiled into the addon JAR.
3. The package import matches the actual source package exactly.
4. The addon classloader is made available to KSL.
5. The script was recompiled after the import was registered.
6. The addon JAR and script use compatible Kotlin/JVM targets.

An import does not compile loose .kt files stored beside .kts scripts.

### Another addon is not ready

dependsOn is metadata, not a load-order guarantee. Use onAddonReady() and resolve the service in its callback.

### A listener or repeating task remains after disable

Use KSLAddonScope and close it in onUnload(), or retain and explicitly close/cancel every handle.

### A callback throws on an async thread

Move Bukkit operations to the main thread. Keep network, SQL and CPU work off the server thread.

### Java cannot import KslApi or KSLPublicAPI

Those are Kotlin typealiases. Use ru.privateserver.ksl.KSLAPI and KSLApiProvider.

## 18. Addon release checklist

- [ ] KSL JAR is compileOnly; KSL classes are not shaded into the addon.
- [ ] Paper and JVM targets match the deployment environment.
- [ ] plugin.yml declares the correct dependency.
- [ ] onEnable checks KSL availability before accessing KSL.api.
- [ ] addonId is stable and unique.
- [ ] minApiVersion reflects the actual features used.
- [ ] onLoad registers only capabilities the addon owns.
- [ ] Imports point to compiled classes in the addon JAR.
- [ ] Services shared across classloaders use shared interface classes.
- [ ] Bukkit listeners are closed during unload.
- [ ] Raw Bukkit tasks are cancelled or owned by KSLAddonScope.
- [ ] External clients, executors, watchers and subscriptions are closed.
- [ ] onDisable unregisters the addon.
- [ ] Async callbacks cannot apply Bukkit changes after unload.
- [ ] Documentation includes an example .kts consumer script.
- [ ] Addon behavior is tested against the exact KSL/Paper/JDK versions.

## 19. Related documentation

- [Public API overview](Public-API.md)
- [Addon Development Guide](Addon-Development-Guide.md)
- [KSL Developer Handbook](KSL-Developer-Handbook.md)
- [Custom Standard Library](Custom-Standard-Library.md)
- [Standard Library Architecture](Standard-Library-Architecture.md)
- [STD Complete Reference](STD-Complete-Reference.md)
- [Runtime API](Runtime-API.md)
- [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md)
- [Packages and Imports](Packages-and-Imports.md)
- [Security and Sandbox](Security-and-Sandbox.md)
