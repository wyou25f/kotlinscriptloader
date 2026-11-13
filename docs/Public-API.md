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
- `kslOrNull`: safe nullable accessor for plugin load-order/shutdown edges;
- `isKslAvailable`: availability flag;
- `KSL_API_VERSION`: addon compatibility level;
- `service<T>()`, `requireService<T>()`, `hook<T>()`, `requireHook<T>()`;
- `hasHook()`, `findAddon()`, `requireAddon()`, `findScript()`,
  `requireScript()` and `isScriptLoaded()`;
- `KSLTopics` and typed `onScriptLoaded()`, `onScriptFailed()`,
  `onScriptUnloaded()` subscriptions;
- the `KSLModulePlanner` facade.

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
[Standard Library architecture](Standard-Library-Architecture.md).
