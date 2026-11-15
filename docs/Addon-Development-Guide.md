# Developing KotlinScriptLoader Addons (KSL API)

This guide explains how to build a Bukkit/Paper plugin that extends KotlinScriptLoader with services available to .kts scripts, default imports, and script lifecycle hooks.

A KSL addon is a regular Paper plugin that implements KSLAddon and registers with KSL through the static KSL object.

## 1. Architecture

The public API consists of four main contracts:

| Type | Purpose |
|---|---|
| KSL | Entry point. A static object that remains available while the KSL plugin is running. |
| KSLAPI | Interface used by addons to register capabilities. |
| KSLAddon | Interface implemented by your plugin. |
| KSLContextExtension | Optional hook for creation/destruction of each script context. |

Registration flow:

~~~text
Your plugin onEnable()
        |
        v
KSL.isAvailable?
        | no -> log an error and disable your plugin
        | yes
        v
KSL.api.registerAddon(this)
        |
        v
KSLAddonManager calls onLoad(api: KSLAPI)
        |
        v
Register services, imports, and context extensions
~~~

A script can then obtain your service:

~~~kotlin
val myService = service<MyService>("service-key")
    ?: error("The addon is not installed")
~~~

## 2. Project requirements

- Kotlin 2.0+ and JVM target 21. This must match KSL; otherwise inline bytecode may fail with a JVM target 21 versus 1.8 error.
- Paper API 1.21.1+.
- The KotlinScriptLoader JAR as a compileOnly dependency. Do not shade KSL classes into your addon JAR; KSL must already be loaded as a separate server plugin.
- depend: [KotlinScriptLoader] in plugin.yml to ensure KSL loads before your addon.

### build.gradle.kts

~~~kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(files("libs/KotlinScriptLoader-1.0.0.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
~~~

Place the built KSL JAR in your project's libs/ directory, or use compileOnly project(":KotlinScriptLoader") when both plugins are modules in one Gradle multi-project build.

### plugin.yml

~~~yaml
name: MyKSLAddon
version: ${version}
main: com.example.MyAddonPlugin
api-version: '1.21'
depend: [KotlinScriptLoader]
~~~

Use depend rather than softdepend: an addon that requires KSL cannot operate without it, and the hard dependency establishes load order.

## 3. KSLAddon

~~~kotlin
interface KSLAddon {
    val addonId: String
    val addonVersion: String
    val addonDescription: String get() = ""
    val dependsOn: List<String> get() = emptyList()
    fun onLoad(api: KSLAPI)
    fun onUnload()
}
~~~

- addonId is the unique key shown by /ksl addons. Use kebab-case, such as my-addon.
- addonVersion and addonDescription are displayed in logs and /ksl addons.
- dependsOn lists the addon IDs whose services you depend on. It is diagnostic metadata; it does not block loading or control load order. Use onAddonReady (section 4) for an actual readiness dependency.
- onLoad(api) runs once when the addon is registered. Register services, imports, and context extensions here.
- onUnload() runs when the addon is unregistered. It is usually called from your plugin's onDisable().

## 4. KSLAPI

The API available inside onLoad includes:

~~~kotlin
interface KSLAPI {
    val kslPlugin: JavaPlugin
    fun registerAddon(addon: KSLAddon)
    fun unregisterAddon(addonId: String)
    fun registerService(key: String, instance: Any)
    fun unregisterService(key: String)
    fun getService(key: String): Any?
    fun addDefaultImports(vararg packages: String)
    fun registerContextExtension(extension: KSLContextExtension)
    fun unregisterContextExtension(extensionId: String)
    fun registeredAddons(): List<KSLAddon>
    fun isAddonLoaded(addonId: String): Boolean
    fun onAddonReady(addonId: String, callback: (KSLAddon) -> Unit)
}
~~~

### Hooks and the ru.privateserver.ksl.hooks package

Built-in hook implementations live in ru.privateserver.ksl.hooks. The shared KSLHook contract remains in ru.privateserver.ksl, and old root-package class names remain as compatibility facades.

For a new hook, use the base class and aliases:

~~~kotlin
import ru.privateserver.ksl.hooks.*

class EconomyHook : KSLPluginHook("economy", "Vault") {
    fun providerName(): String = "My economy bridge"
}

override fun onLoad(api: KSLAPI) {
    api.registerHookIfEnabled("Vault") { EconomyHook() }
}
~~~

KSLPluginHook validates the hook ID and plugin name. registerHookIfEnabled skips creation when the provider plugin is disabled. findHook<T>() and requireHookAlias<T>() provide typed access; hookOrNull<T>() remains available through ru.privateserver.ksl.api.*. Use KSLHookIds constants for built-in integrations rather than repeating string IDs. A hook registered during onLoad is removed automatically when the addon unloads.

### registerService(key, instance)

Registers an object under a string key. Scripts retrieve it with service<T>(key). Choose a descriptive key such as coins, gui, or warps.

### addDefaultImports(vararg packages)

Adds packages to the default imports used to compile scripts, so users do not need to repeat imports in every .kts file. Values use regular Kotlin import syntax, such as com.example.CoinService or com.example.*.

### registerContextExtension(extension)

Registers a hook that receives notifications when each script context is created and destroyed. Use it when your service needs per-script state.

### onAddonReady(addonId, callback)

This is the safest way to depend on another KSL addon. Bukkit plugin load order may differ from what you expect, especially when plugins use soft dependencies. onAddonReady handles both cases:

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.onAddonReady("example-addon") { addon ->
        coins = api.getService("coins") as? CoinService
    }
}
~~~

If the target addon is already loaded, the callback runs immediately and synchronously. Otherwise, KSL stores the callback and invokes it when that addon registers, regardless of Bukkit plugin order.

### isAddonLoaded(addonId)

A synchronous one-time check when a callback is not needed:

~~~kotlin
if (api.isAddonLoaded("example-addon")) {
    // The addon is already registered.
}
~~~

### getService, unregisterService, unregisterAddon, registeredAddons

These are symmetric management operations, usually needed by meta-addons that manage other addons.

### Automatic cleanup

Resources registered from onLoad (services, imports, and context extensions) are automatically removed by unregisterAddon, even if onUnload does not explicitly unregister each item. This prevents scripts and other addons from retaining services owned by an unloaded addon.

Use onUnload() to close the addon's own resources, such as connections, threads, or files. Automatic cleanup only covers resources registered through KSLAPI.

For a grouped lifecycle, create an explicit `KSLAddonScope` with `api.addonScope()` inside `onLoad`. Register Bukkit listeners, typed event handlers, event-bus subscriptions, scheduled tasks, and external `AutoCloseable` resources through that scope. Call `scope.close()` in `onUnload`; it releases resources in reverse registration order. The scope does not change KSLAPI's binary contract or API version. See [Public API](Public-API.md#addon-resource-scopes) for the complete example.

## 5. Minimal working example

The following example exposes an in-memory currency service.

### CoinService.kt

~~~kotlin
package com.example

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

class CoinService {
    private val coins = ConcurrentHashMap<String, Long>()

    fun get(player: Player): Long = coins.getOrDefault(player.name, 0L)

    fun give(player: Player, amount: Long) {
        coins.merge(player.name, amount, Long::plus)
    }

    fun take(player: Player, amount: Long): Boolean {
        val current = get(player)
        if (current < amount) return false
        coins[player.name] = current - amount
        return true
    }

    fun set(player: Player, amount: Long) {
        coins[player.name] = amount
    }
}
~~~

There is nothing KSL-specific in this class. It is ordinary Kotlin. Use ConcurrentHashMap instead of HashMap if scripts may access the service concurrently from different threads.

### MyAddonPlugin.kt

~~~kotlin
package com.example

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class MyAddonPlugin : JavaPlugin(), KSLAddon {
    override val addonId = "coins-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "In-game currency service for scripts"

    private val coinService = CoinService()

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader was not found")
            isEnabled = false
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onDisable() {
        if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
    }

    override fun onLoad(api: KSLAPI) {
        api.registerService("coins", coinService)
        api.addDefaultImports("com.example.CoinService")
    }

    override fun onUnload() = Unit
}
~~~

Important points:

- Check KSL.isAvailable before accessing KSL.api in onEnable(). If KSL failed to initialize, accessing the API can throw. Disable your plugin gracefully instead.
- onUnload() can be empty when the service owns no external resources. Close connections, threads, and files here when applicable.

### Use the service from a script

~~~kotlin
val coins = service<com.example.CoinService>("coins")
    ?: error("CoinsAddon is not installed")

registerCommand("coins") { player, args ->
    when (args.getOrNull(0)) {
        "give" -> {
            val amount = args.getOrNull(1)?.toLongOrNull() ?: 0L
            coins.give(player, amount)
            player.sendRichMessage("<green>+$amount coins. Total: ${coins.get(player)}")
        }
        "take" -> {
            val amount = args.getOrNull(1)?.toLongOrNull() ?: 0L
            if (coins.take(player, amount)) {
                player.sendRichMessage("<red>-$amount coins. Total: ${coins.get(player)}")
            } else {
                player.sendRichMessage("<red>Not enough coins!")
            }
        }
        else -> player.sendRichMessage("<yellow>Coins: <white>${coins.get(player)}")
    }
}
~~~

If you registered com.example.CoinService through addDefaultImports(), the script can use CoinService without the fully qualified class name.

## 6. KSLContextExtension — per-script state

Use KSLContextExtension when a service needs data isolated for each .kts file rather than global state:

~~~kotlin
interface KSLContextExtension {
    val extensionId: String
    fun onContextCreated(context: BukkitScriptContext)
    fun onContextDestroyed(scriptName: String)
}
~~~

onContextCreated(context) runs when each script loads, including during /ksl reload. context.scriptName identifies the script. onContextDestroyed(scriptName) runs when the context is unloaded. Release per-script state here.

## 7. Lifecycle and thread safety

An addon should register with KSL during onEnable() and unregister during onDisable(). Do not hold onto script contexts after they are destroyed. If callbacks are asynchronous, account for the addon being unloaded before the callback completes.

Bukkit world, entity, player, and inventory operations usually need the main server thread. Use the supported scheduler for Bukkit operations and background workers for SQL, network calls, or CPU-heavy work. Do not block the main thread with Future.get(), join(), or Thread.sleep().

Use thread-safe collections for services that may be accessed concurrently. A ConcurrentHashMap does not make the objects stored in it thread-safe.

## 8. Compatibility and API versions

Use the API version required by the features your addon calls. When a minimum API version is required, declare minApiVersion so an incompatible KSL version produces a clear load error rather than a later linkage failure.

Do not bundle KSL API classes into your addon. Keep the KSL JAR as compileOnly. Duplicate copies loaded through different classloaders can break type checks and service lookup.

## 9. Troubleshooting

**JVM target mismatch:** Ensure both Java and Kotlin toolchains target JVM 21. The addon target must be compatible with the server and KSL runtime.

**ClassNotFoundException for your service:** The addon JAR may not be on the classpath used to compile scripts. Confirm that the addon is loaded and that addDefaultImports() references a real class/package.

**A dependency warning during startup:** dependsOn is diagnostic metadata and does not delay loading. If you need another addon to be ready before using its service, call api.onAddonReady(id) { ... } instead of reading api.getService(...) immediately inside onLoad.

## 10. Addon release checklist

- [ ] addonId is unique and uses kebab-case.
- [ ] plugin.yml declares depend: [KotlinScriptLoader].
- [ ] onEnable checks KSL.isAvailable before accessing KSL.api.
- [ ] onDisable calls KSL.api.unregisterAddon(addonId).
- [ ] Services accessed concurrently use thread-safe collections.
- [ ] The addon is built for JVM target 21.
- [ ] The addon README includes at least one .kts usage example.

---

## API v2: Addon author features

KSL.API_VERSION = 2. The additions below are backward-compatible; addons built against v1 continue to work.

### Minimum API version

~~~kotlin
override val minApiVersion = 2
~~~

An addon requiring a newer API will not load on an older KSL version; the log reports the reason.

### Typed services

~~~kotlin
// Provider
api.registerService("economy", MyEconomy())

// Consumer
val eco = api.service<MyEconomy>("economy")             // null if missing or of another type
val required = api.requireService<MyEconomy>("economy") // throws a descriptive error
~~~

If the log says the type names match but the classes differ, the consumer addon may have bundled the service interface into its JAR. Add the provider API as compileOnly, not implementation.

### Event bus

The event bus lets addons and scripts communicate without a hard dependency on each other.

~~~kotlin
// In addon.onLoad(api)
api.subscribe("shop.purchase") { payload -> /* payload: Any? */ }

// From an addon or script
api.publish("shop.purchase", PurchaseInfo(player.uniqueId, 100))
~~~

Handlers run synchronously on the publisher's thread. An exception in one handler does not prevent the others from running. In scripts, use subscribe(topic) { ... } and publish(topic, payload); script subscriptions are removed on unload. Subscriptions created outside onLoad are not automatically removed: retain the returned AutoCloseable and call close().

### Resource ownership

Services, context extensions, default imports, subscriptions, and onAddonReady callbacks registered inside onLoad are owned by the addon and removed by unregisterAddon. Registrations made outside onLoad have no automatic owner and must be cleaned up manually.

If onLoad throws, the addon is considered not loaded and its partial registrations are rolled back. Registering an already registered addonId unloads the old instance first.

### Adding functions to the script DSL

The script uses BukkitScriptContext as an implicit receiver, so extension functions on it behave like built-in DSL functions:

~~~kotlin
// addon.onLoad(api)
api.addDefaultImports("com.example.myaddon.dsl.*")

// com/example/myaddon/dsl/Dsl.kt
fun BukkitScriptContext.shopOpen(player: Player) { /* ... */ }
~~~

For script-owned resources, implement KSLContextExtension (both callbacks may be optional in the current API) and clear state in onContextDestroyed(scriptName).

---

## API v3: Hooks, particle shapes, and mob attributes

KSL.API_VERSION = 3. Set minApiVersion = 3 when using these features.

### Custom hook (integration with another plugin)

~~~kotlin
class PointsHook : KSLHook {
    override val hookId = "playerpoints"
    override val pluginName = "PlayerPoints"
    private val api = PlayerPoints.getInstance().api

    fun balance(player: Player): Int = api.look(player.uniqueId)
    fun give(player: Player, amount: Int) = api.give(player.uniqueId, amount)
}

// addon.onLoad(api)
if (api.isPluginEnabled("PlayerPoints")) api.registerHook(PointsHook())
~~~

A script can use requireHook<PointsHook>("playerpoints"). Register the hook's package through addDefaultImports() so scripts can resolve the class. A hook registered during onLoad is removed with the addon.

### Custom particle shape

~~~kotlin
api.registerEffectShape("spiral") { params, durationTicks ->
    val radius = params.num("radius", 1.0)
    val height = params.num("height", 2.0)
    KSLEffectShape { tick, _ ->
        val angle = Math.toRadians(tick * 20.0)
        listOf(Vector(cos(angle) * radius, (tick % 20) / 20.0 * height, sin(angle) * radius))
    }
}
~~~

In a script: location.playEffect(Particle.FLAME) { shape("spiral", "radius" to 1.5, "height" to 3.0) }. Built-in shapes include circle, helix, sphere, square, and point.

### Custom mob-template key

~~~kotlin
api.registerEntityAttribute("SIZE") { entity, value ->
    (entity as? Slime)?.size = value.toInt()
}
~~~

A script can then use spawnCustomEntity(loc, "TYPE:SLIME HP:40 SIZE:4"). An exception in an attribute handler is logged as a warning and does not abort the spawn; other keys continue to be applied.

---

## Addon aliases (ru.privateserver.ksl.addon.*)

Import ru.privateserver.ksl.addon.* for concise wrappers around KSLAPI. The longer API names remain available.

| Alias | Purpose |
|---|---|
| simpleAddon(id, version) { api -> } | Define an addon without a dedicated class; supports unload, requires, and needsApi |
| KSLBaseHook(hookId, pluginName) | Base class for a hook |
| api.provide(key, obj) / api.revoke(key) | registerService / unregisterService |
| api.hasService(key) | Check whether a service exists |
| api.serviceOr<T>(key, default) | Return a service or a default value |
| api.imports("pkg.*") | addDefaultImports |
| api.whenReady(id) { addon -> } | onAddonReady |
| api.isLoaded(id), api.findAddon(id), api.addonIds() | Inspect loaded addons |
| api.requireApi(3) | Fail with a clear message when KSL is too old |
| api.kslVersion | KSL plugin version |
| api.reload() | reloadScripts() |
| api.contextExtension("id", onCreated, onDestroyed) | Context extension without a custom class |
| api.hasHook(id), api.hookIds() | Inspect hooks |
| api.hookIf("Plugin") { MyHook() } | Register a hook only when the plugin is enabled |
| api.shape(name) { params, ticks -> } | registerEffectShape |
| api.entityAttribute(KEY) { entity, value -> } | registerEntityAttribute |
| shapeOf { tick, center -> points } | Shape from a lambda |
| ringPoints(radius, count, y) | Ring points for custom shapes |
| parseTemplate("A:1 B:2") | Parse KEY:value templates |
| entity.attr(name, v), entity.attrBase(name) | Base attribute value by vanilla name |
| params.num/int/long/bool/str(key, default) | Read shape parameters |
| api.on(topic) { }, api.emit(topic, payload) | subscribe / publish |
| api.table("myaddon_data") | KSL SQLite table (use an addon prefix) |
| api.log(...), api.warn(...), api.severe(...) | Log with a [KSL] prefix |
| api.sync { }, api.async { }, api.later(ticks) { }, api.every(ticks) { } | Bukkit tasks; cancel them yourself when the addon unloads |
| api.listen<Event>(priority, ignoreCancelled) { } | Bukkit listener; close() unregisters it |

Example:

~~~kotlin
val addon = simpleAddon("greeter", "1.0", needsApi = 3) { api ->
    api.imports("com.example.greeter.dsl.*")
    api.provide("greeter", Greeter())
    api.on("player.welcome") { name -> api.log("welcome $name") }
}
~~~

---

## API v4: Placeholders

~~~kotlin
// addon.onLoad(api)
api.placeholder("online") { player -> Bukkit.getOnlinePlayers().size.toString() } // %ksl_online%
~~~

The equivalent API is registerPlaceholder(key) { player -> ... } and unregisterPlaceholder(key). PlaceholderAPI must be installed. Keys are case-insensitive. A placeholder registered inside onLoad is removed with the addon.

An exception in a resolver is logged and returns an empty string; it does not affect PlaceholderAPI. If a key is already used by a script or another addon, KSL logs a warning and the new resolver replaces the old one.

## API v5: Text placeholders

~~~kotlin
api.textPlaceholder("rank") { player -> player?.let { lookupRank(it) } ?: "Guest" }
~~~

The equivalent methods are registerTextPlaceholder(key) { player -> ... } and unregisterTextPlaceholder(key). The player may be null for text that is not addressed to a specific player. Built-in tokens are reserved and names are case-insensitive. A placeholder registered during onLoad is removed with the addon.

## API v6: Scripts, reload, and security

~~~kotlin
// Script list: ID, loaded state, load time, error count
api.scripts().forEach { println("${it.id} loaded=${it.loaded} ${it.loadMillis}ms errors=${it.errors}") }

// Lifecycle events
api.onScriptLoaded { event -> api.log("loaded ${event.scriptId} in ${event.millis}ms") }
api.onScriptFailed { event -> api.warn(KSLError.SCRIPT_COMPILE, "${event.scriptId}: ${event.error}") }

// Compile asynchronously; the previous script version remains active if compilation fails
api.reloadScriptsAsync { result ->
    if (result == null) api.log("reload already running")
    else api.log("loaded ${result.first}")
}
api.reloadScript("economy/shop") // blocking variant; call only from the main thread
~~~

api.isSandboxed(id) and api.isConsoleCommandBlocked(cmd) allow an addon to apply the same rules as KSL. api.warn(code, text) and api.severe(code, text) log a catalogued KSLError code so /ksl error can explain it.

## API v8: Addon configuration files

Starting with API v8, KSLAPI provides createConfig, a convenience wrapper around Bukkit YAML configuration. The file is stored in the addon's data folder, and defaults are read from a resource bundled in the addon JAR.

### Creating and reading a config

Place config.yml in src/main/resources/ and create the config:

~~~kotlin
class MyAddonPlugin : JavaPlugin(), KSLAddon {
    private lateinit var settings: KSLAddonConfig
    override val minApiVersion = 8

    override fun onEnable() {
        if (!KSL.isAvailable) { isEnabled = false; return }
        settings = KSL.api.createConfig(this)
        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        val enabled = settings.getBoolean("settings.enabled", true)
        val prefix = settings.getString("settings.prefix", "&6[MyAddon]")
        if (enabled) logger.info("Addon enabled, prefix=$prefix")
    }

    override fun onDisable() {
        if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
    }
    override fun onUnload() = Unit
}
~~~

createConfig(this) creates plugins/MyAddon/config.yml on first startup by copying the matching resource from the JAR. If the resource does not exist, an empty YAML file is created. By default, copyDefaults = true: getters can read resource defaults even when those values are not yet present in the user config.

Supported methods include getString, getInt, getLong, getDouble, getBoolean, getStringList, contains, getKeys, getSection, set, save, and reload. The config property exposes the underlying Bukkit FileConfiguration for advanced use cases.

A different relative path can be selected, and copying defaults can be disabled:

~~~kotlin
val messages = api.createConfig(this, "messages.yml", copyDefaults = false)
messages.set("welcome", "Hello!")
messages.save()
messages.reload() // reload from disk
~~~

Absolute paths and paths escaping the addon's data folder are rejected. As with ordinary Bukkit config, read and modify configuration on the server thread unless your own code provides synchronization.

### Regular Bukkit configuration remains supported

KSLAddonConfig is optional. Addons can continue using JavaPlugin methods such as saveDefaultConfig(), config, saveConfig(), and reloadConfig(). The new API does not change or intercept Bukkit's standard config behavior.

The examples/ExampleAddon project contains a complete example with a packaged config.yml, KSLAPI.createConfig(), and startup settings.

## Packages, local imports, and custom libraries

KSL includes each registered addon's classloader in .kts compilation and execution. An addon can therefore expose regular Kotlin packages, classes, and top-level extension functions. Scripts import them normally:

~~~kotlin
import com.example.myaddon.player.*
import com.example.myaddon.service.MyService
~~~

To avoid repeating imports in every script, register them in onLoad:

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports(
        "com.example.myaddon.player.*",
        "com.example.myaddon.service.MyService"
    )
}
~~~

An import does not add a class to the classpath. Classes must be compiled into the addon JAR or one of its available runtime dependencies. A wildcard import applies only to the specified package, not its subpackages.

Declare packages in ordinary .kt source files in the addon. A .kts file is a script that imports compiled declarations; KSL does not compile arbitrary .kt files placed next to scripts/ automatically.

For project layout, Gradle setup, explicit/default imports, multiple libraries, and diagnostics, see [Packages and Imports](Packages-and-Imports.md).
