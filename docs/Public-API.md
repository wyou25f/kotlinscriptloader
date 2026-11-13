# KSL Public API

## Kotlin import

Kotlin scripts and addon projects can use one stable namespace:

```kotlin
import ru.privateserver.ksl.api.*

val api = kslOrNull
if (api != null) {
    val loadedScripts = api.scripts()
    val apiLevel = KSL_API_VERSION
    val economy = api.service<EconomyService>("economy")
    val luckPerms = api.hook<SomeLuckPermsHook>("luckperms")
    val isLoaded = api.isScriptLoaded("welcome")
}
```

The package exports Kotlin aliases for the core addon, hook, context-extension,
script-info, script-event, shape-factory, script-context and Standard Library
module contracts. It also exposes:

- `ksl`: non-null API accessor that throws if KSL is not initialized;
- `kslapi`, `kslApi` and `api`: concise aliases for the installed non-null API;
- `kslOrNull`: safe nullable accessor for plugin load-order/shutdown edges;
- `isKslAvailable`: availability flag;
- `KSL_API_VERSION`: addon compatibility level;
- `service<T>()`, `requireService<T>()`, `hook<T>()`, `requireHook<T>()`;
- `hasHook()`, `findAddon()`, `requireAddon()`, `findScript()`,
  `requireScript()` and `isScriptLoaded()`;
- `KslApi` and `KSLPublicAPI`: alternate type names for `KSLAPI`;
- `KSLMesLib2` and `MesLib`: alternate type names for the MesLib2 facade;
- `KSLTopics` and typed `onScriptLoaded()`, `onScriptFailed()`,
  `onScriptUnloaded()` subscriptions;
- the `KSLModulePlanner` facade;
- `registerStandardLibraryModule(module)`, `unregisterStandardLibraryModule(id)` and `standardLibraryModules()` for addon-owned module lifecycle.

Script lifecycle subscriptions use KSL's event bus. Handlers execute
synchronously on the publisher's thread; move Bukkit world/entity/inventory
work to the server thread when necessary. Close the returned `AutoCloseable`
when a manually managed subscription is no longer needed. Subscriptions made
inside an addon's `onLoad` are automatically removed when that addon unloads.

### IntelliJ completion

Add the matching KSL plugin JAR as a compile-only dependency in an IDE Gradle
project. The JAR includes the `@KotlinScript` template and shared compilation
configuration, including the `BukkitScriptContext` implicit receiver. See
[IDE setup](../ide/README.md) and its completion smoke-test script.

## Java integrations

Kotlin `typealias` declarations are not emitted as Java classes. Java addons
should use the actual interfaces in `ru.privateserver.ksl` and obtain the API
through the Java-friendly facade:

```java
import ru.privateserver.ksl.KSLAPI;
import ru.privateserver.ksl.api.KSLApiProvider;

KSLAPI api = KSLApiProvider.getApi();
if (api != null) {
    api.scripts().forEach(script -> getLogger().info(script.getId()));
}
```

Use the plugin JAR as a `compileOnly` dependency; do not bundle a second copy
of KSL or its API classes into an addon. Duplicate classloaders break service
and hook type checks.

## Script definition metadata

`KSLScriptMetadata.kt` is the single source of truth for:

- `KSL_SCRIPT_FILE_EXTENSION`;
- `KSL_SCRIPT_JVM_TARGET`;
- `KSL_SCRIPT_CONTEXT_CLASS`;
- `KSL_SCRIPT_DEFAULT_IMPORTS`.

The runtime compiler extends the same base configuration with imports
registered by loaded addons. Editor projects must add those addon JARs to their
classpath to resolve addon-owned symbols.

## Compatibility

Additive changes must preserve existing signatures. Breaking changes require
an intentional API-version change and migration notes. Keep KSL's API classes
provided by the plugin classloader and declare them as `compileOnly` in addons.

The Standard Library utilities and module contracts are documented in
[Standard Library architecture](Standard-Library-Architecture.md) and the
[custom library guide](Custom-Standard-Library.md).


## Convenience extensions

The public API package also provides small, additive helpers for addon authors:

- `onlinePlayers()` and `findOnlinePlayer(name)` for online-player lookup;
- `broadcast(String)` for literal text and `broadcast(Component)` for Adventure components;
- `isServerThread()` for an explicit thread check;
- `scheduleSync`, `scheduleAsync`, `scheduleSyncLater`,
  `scheduleAsyncLater`, `scheduleSyncRepeating` and
  `scheduleAsyncRepeating` for Bukkit tasks.

These are extension functions, not new abstract `KSLAPI` members, so they do not
require an API-version bump and do not break existing addon implementations.
Tasks created by an addon are owned by that addon: cancel repeating or long-lived
tasks during `onUnload`. Async callbacks must not access Bukkit world, entity or
inventory state.


## Runtime utilities

KSL exposes `KSLCache` and `KSLProfiler` from
`ru.privateserver.ksl.rt.*`; the script-friendly factory object is `kslrt`.
The namespaced script library facade is available through the
`BukkitScriptContext` receiver as `meslib2`, `ml2`, `lib2` or `libs`.
Its typed operations include `publish/provide`, `find/get`, `resolve/require`,
`contains` and `info`. These additions do not change existing
`KSLAPI` interface signatures.


## Short public API names

For Kotlin callers, all of the following are aliases to the same installed
`KSLAPI` instance: `ksl`, `kslapi`, `kslApi` and `api`. The type names
`KSLAPI`, `KslApi` and `KSLPublicAPI` refer to the same interface.
`addImports(...)` is a short extension alias for `addDefaultImports(...)`,
and `serviceOrThrow<T>(key)` aliases `requireService<T>(key)`.

These are Kotlin conveniences. Kotlin typealiases are not Java classes; Java
addons should keep using the real `ru.privateserver.ksl.KSLAPI` interface.
