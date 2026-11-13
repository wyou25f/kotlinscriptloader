# Writing a custom KSL Standard Library

KSL standard-library extensions are ordinary Kotlin code packaged in a Bukkit plugin that implements `KSLAddon`. This keeps custom APIs out of the KSL core and lets the addon own its imports and lifecycle.

## 1. Create extension functions

For example, create `src/main/kotlin/com/example/serverstd/PlayerExtensions.kt`:

```kotlin
package com.example.serverstd

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

fun Player.sendWelcome() {
    sendMessage(Component.text("Welcome to the server!"))
}

fun Player.sendNotice(message: String) {
    sendMessage(Component.text("[Notice] $message"))
}

fun String.asServerTag(): String = "[Server] $this"
```

These are normal Kotlin top-level extension functions. They do not need a special KSL DSL.

## 2. Register the package from your addon

```kotlin
package com.example.serverstd

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.api.KSLAPI
import ru.privateserver.ksl.api.KSLAddon

class ServerStdAddon : JavaPlugin(), KSLAddon {
    override val addonId = "server-std"
    override val addonVersion = "1.0.0"
    override val minApiVersion = 1

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader is not available")
            server.pluginManager.disablePlugin(this)
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onDisable() {
        if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
    }

    override fun onLoad(api: KSLAPI) {
        api.addDefaultImports("com.example.serverstd.*")
    }

    override fun onUnload() = Unit
}
```

Add `depend: [KotlinScriptLoader]` to the addon's `plugin.yml`, and compile against the KSL JAR using `compileOnly`. Do not bundle another copy of KSL API classes.

## 3. Use the extensions in a KSL script

Once the addon is loaded, its package is imported automatically:

```kotlin
player?.sendWelcome()
player?.sendNotice("The event starts in five minutes")
val label = "Maintenance".asServerTag()
```

No explicit `import` is needed for `com.example.serverstd.*`.

## Module metadata: planner, not automatic loader

KSL exposes `KSLStandardLibraryModule`, `KSLModuleDescriptor` and
`KSLModulePlanner.resolve(...)` as a declarative module contract. The planner
checks duplicate IDs, missing dependencies, dependency cycles and required API
versions, then returns a deterministic dependency-first order.

**The current KSL runtime does not automatically discover, install or uninstall
these modules.** There is no `api.registerStandardLibraryModule(...)` method in
the current `KSLAPI`. For a working custom standard library today, implement
the addon lifecycle shown above and register packages/services in
`onLoad(api)`. This keeps lifecycle ownership explicit and avoids relying on a
planned module host.

## Lifecycle and classpath

KSL adds registered addon classloaders to the script compilation classpath and to the classloader used during script evaluation. KSL's own API and Bukkit classes remain parent-first, so an addon cannot replace those shared types.

When an addon unloads, KSL removes the default imports and stops exposing that addon's classloader to newly compiled scripts. Scripts that already referenced addon classes should be unloaded or reloaded before the addon JAR is disabled.

## Sharing a library between .kts scripts with MesLib2

Use MesLib2 when the library is implemented by one KSL script and another
script needs to consume its object. The exporter owns the value: unloading or
reloading the exporter removes its exports. Consumers should declare
`// @requires: exporter-script-name` so KSL loads the provider first.

Provider script, for example `economy.kts`:

```kotlin
class EconomyLibrary {
    fun balance(playerId: String): Long = 0L
}

meslib2.publish("economy", "service", EconomyLibrary(), version = "1.0.0")
```

Consumer script, for example `shop.kts`:

```kotlin
// @requires: economy

val economy = meslib2.resolve<EconomyLibrary>("economy", "service", "1.0.0")
val balance = economy.balance(player.uniqueId.toString())
```

The shorter overloads use the current script's normalized id as the default
namespace:

```kotlin
// In economy.kts
ml2.provide("service", EconomyLibrary())

// In another script, explicitly address the provider namespace
val economy = libs.require<EconomyLibrary>("economy", "service")
```

Available facade names in scripts are `meslib2`, `ml2`, `lib2` and `libs`.
MesLib2 operations include `export/publish/provide`, `get/find`,
`require/resolve`, `contains` and `info`. Versions are opaque labels matched
exactly; they are not SemVer ranges and MesLib2 does not download external
artifacts or compile loose source files.

Choose the right delivery model:

- **Addon standard library:** reusable compiled `.kt` classes, extension
  functions and services packaged in a Paper addon JAR. Best for code shared
  across many scripts and servers.
- **MesLib2 script library:** a service/object exported by one `.kts` script
  and consumed by other scripts on the same server. Best for server-specific
  script modules and rapid iteration.

## Recommendations

- Keep extensions grouped by domain: `com.example.serverstd.player.*`, `...text.*`, `...inventory.*`.
- Prefer small, predictable functions with KDoc.
- Avoid blocking database and network operations on the server thread.
- Treat Bukkit world, entity and inventory APIs as main-thread operations unless documented otherwise.
- Use unique package names to avoid collisions with built-in std packages.


## Packages and explicit imports

A library may contain as many Kotlin packages as needed. Keep source files under src/main/kotlin, declare each package in the file, and import declarations normally from another addon source file. KSL scripts can then use explicit imports such as:

~~~kotlin
import com.example.serverstd.player.sendWelcome
import com.example.serverstd.text.asServerTag
~~~

Or register package imports once from the addon's onLoad:

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports(
        "com.example.serverstd.player.*",
        "com.example.serverstd.text.*"
    )
}
~~~

Imports are not source compilation: the classes and functions must already be compiled and available through the addon's classloader. KSL does not compile loose .kt files from the scripts directory. See [Packages, imports and local libraries](Packages-and-Imports.md) for the full walkthrough, project layout, Gradle setup and troubleshooting.
