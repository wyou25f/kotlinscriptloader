# KSL Packages, Imports, and Local Libraries

This guide explains how to organize reusable Kotlin code for KotlinScriptLoader (KSL), make it available to .kts scripts, and build libraries shared by multiple scripts.

> In short: KSL compiles .kts files as Kotlin Script. Regular Kotlin addon files (.kt) are compiled by Gradle into classes inside the addon JAR. KSL adds the classloader of a loaded addon to the script compilation and execution classpaths. A script can therefore import public addon classes and top-level functions.

## 1. Four concepts that should not be confused

| Concept | Meaning | Declared in |
|---|---|---|
| package | Namespace for Kotlin code | At the top of a .kt file |
| import | Makes a class or declaration from a package available | .kt and .kts |
| defaultImports | Imports KSL adds automatically when compiling each script | Addon's onLoad |
| classpath | Classes and libraries visible to compiler and script | Addon JARs and their available dependencies |

**Important:** addDefaultImports("com.example.tools.*") adds an import; it does not load a library or compile source files. The package must already be on the classpath, for example inside a loaded addon JAR.

## 2. Recommended addon structure

Example: ServerToolkit.

~~~text
ServerToolkit/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    └── main/
        ├── kotlin/
        │   └── com/example/servertoolkit/
        │       ├── ServerToolkitPlugin.kt
        │       ├── service/MessageService.kt
        │       ├── player/PlayerExtensions.kt
        │       └── text/TextExtensions.kt
        └── resources/
            └── plugin.yml
~~~

Directory layout usually mirrors the package name, but the package declaration inside the file is what matters to Kotlin.

PlayerExtensions.kt:

~~~kotlin
package com.example.servertoolkit.player

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

fun Player.sendToolkitMessage(message: String) {
    sendMessage(Component.text("[Toolkit] $message"))
}

fun Player.toolkitLabel(): String = "Player: $name"
~~~

These are ordinary Kotlin extension functions. They do not require a special KSL DSL.

Another source file can call them as regular Kotlin code:

~~~kotlin
package com.example.servertoolkit.service

import com.example.servertoolkit.player.sendToolkitMessage
import org.bukkit.entity.Player

class MessageService {
    fun welcome(player: Player) {
        player.sendToolkitMessage("Welcome!")
    }
}
~~~

## 3. Gradle setup

Minimal build.gradle.kts:

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
    compileOnly(files("libs/KotlinScriptLoader.jar"))
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
~~~

Place the KSL JAR used for compilation in libs/, or add KSL as a Gradle project dependency in a multi-project build. Kotlin, Paper, and JVM versions must match the KSL/server compatibility requirements.

Do not bundle KSL API or Paper API in the final addon JAR; the server provides them. For external runtime libraries, use a delivery method supported by your build, such as shading dependencies into the JAR. Their classes must be available to the addon classloader on the server. Do not include a second copy of another plugin's API when that plugin provides shared types or services.

## 4. plugin.yml

~~~yaml
name: ServerToolkit
version: 1.0.0
main: com.example.servertoolkit.ServerToolkitPlugin
api-version: '1.21'
depend:
  - KotlinScriptLoader
~~~

Use depend when the addon cannot work without KSL. Bukkit will load KSL first. If the integration is optional, choose an appropriate optional dependency model and always check whether KSL is available.

## 5. Register the addon and default imports

Entry point:

~~~kotlin
package com.example.servertoolkit

import com.example.servertoolkit.service.MessageService
import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class ServerToolkitPlugin : JavaPlugin(), KSLAddon {
    override val addonId = "server-toolkit"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Shared utilities for KSL scripts"

    private val messages = MessageService()

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader is unavailable")
            server.pluginManager.disablePlugin(this)
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        api.registerService("toolkit.messages", messages)
        api.addDefaultImports(
            "com.example.servertoolkit.service.MessageService",
            "com.example.servertoolkit.player.*"
        )
    }

    override fun onUnload() {
        // Close your own resources here, if any.
    }

    override fun onDisable() {
        if (KSL.isAvailable) KSL.api.unregisterAddon(addonId)
    }
}
~~~

What these calls do:

- registerService publishes an object that scripts can retrieve through service<T>(key).
- addDefaultImports adds imports to KSL script compilation so scripts do not need to repeat them manually.
- KSL associates these registrations with the addon and removes them when the addon is unloaded.

Register imports from onLoad(api), so KSL can track their owner and clean them up.

## 6. Explicit imports in .kts

After an addon is loaded, its classloader is available to the script compiler and class loader. A .kts file can use normal Kotlin imports:

~~~kotlin
import com.example.servertoolkit.service.MessageService
import com.example.servertoolkit.player.toolkitLabel
import com.example.servertoolkit.player.sendToolkitMessage

val messages = service<MessageService>("toolkit.messages")
    ?: error("ServerToolkit is not loaded")

registerCommand("toolkit") { player, _ ->
    player.sendToolkitMessage(player.toolkitLabel())
}
~~~

If the addon registered the packages through addDefaultImports, the import statements can be omitted.

Import syntax is ordinary Kotlin:

~~~kotlin
import com.example.servertoolkit.service.MessageService
import com.example.servertoolkit.player.*
import com.example.servertoolkit.player.toolkitLabel
import com.example.servertoolkit.text.format as toolkitFormat
~~~

Wildcard imports are not recursive. com.example.servertoolkit.* does not automatically import declarations from player and text subpackages. Add each required package separately.

## 7. Can a .kts file declare a package?

Treat a regular KSL .kts file as a script, not as a Kotlin source file with a package declaration. Put reusable code into ordinary .kt files in an addon:

- .kt files declare packages, classes, objects, and top-level functions.
- Gradle compiles them into addon classes.
- .kts scripts import those declarations through ordinary imports or defaultImports.

KSL does not scan scripts/ and compile arbitrary neighboring .kt files as source automatically. Placing Utils.kt next to my-script.kts does not make its package available.

## 8. Library or service: which should you use?

Use extension functions for small convenience operations:

~~~kotlin
package com.example.servertoolkit.text

fun String.withPrefix(): String = "[Server] $this"
~~~

Use a service class when functionality has state, configuration, database access, a cache, or a lifecycle:

~~~kotlin
package com.example.servertoolkit.service

class MessageService {
    fun prefix(message: String): String = "[Server] $message"
}
~~~

Register a service once in onLoad; scripts retrieve it with service<T>(key). Do not create a separate instance in every script when state should be shared.

### Multiple libraries and addons

Split functionality across addons when useful: server-text for formatting, server-economy for balances, and server-gui for menus. Each addon should publish only its own packages and services.

For a dependency on another KSL addon, use api.onAddonReady("addon-id") { ... } rather than assuming the service has already registered. dependsOn describes addon dependencies and helps diagnose missing dependencies; onAddonReady provides deferred binding.

## 9. Classpath and unloading

During compilation, KSL collects the current default imports and classloaders of registered addons. Their classes become visible to the compiler. Script execution also uses an addon-aware classloader so the same classes remain available at runtime.

Practical consequences:

1. The addon must be enabled before a script that imports its classes is compiled.
2. Reload affected scripts after installing, updating, or unloading an addon.
3. Do not leave scripts loaded if they still reference classes from an unloaded addon.
4. Do not package KSL API into the addon: duplicated types across classloaders can break service compatibility.
5. Use unique package names and service keys to reduce conflicts.

## 10. Threading and security

- Use Bukkit world/player/inventory/entity APIs on the main server thread unless the specific API explicitly permits asynchronous access.
- Do not run blocking SQL, network requests, or heavy work inside main-thread event handlers.
- Keep addon APIs small and predictable.
- Do not treat KSL Sandbox as a security boundary for third-party JAR addons. An addon is a full Bukkit plugin with the permissions of plugin code.
- Avoid exposing mutable collections directly. Design synchronization for services accessed from multiple threads.

## 11. Troubleshooting imports

1. Confirm that the addon is enabled and appears in /ksl addons.
2. Check the exact package declaration in the .kt source file.
3. Confirm the import points to the package that contains the function or class.
4. Ensure that the declaration is public (the Kotlin default), not private or internal.
5. Check that runtime dependencies are available to the addon classloader on the server.
6. If using defaultImports, confirm registration happens in onLoad and the package name has no typos.
7. After replacing a JAR, restart the server or properly reload the addon and affected scripts. Do not assume already-loaded classloaders can hot-swap classes.

## 12. Related guides

- [Addon Development Guide](Addon-Development-Guide.md) — API, lifecycle, services, events, and context extensions.
- [Custom Standard Library](Custom-Standard-Library.md) — designing a reusable extension library.
- [KSL Public API](Public-API.md) — supported integration points.
- [Standard Library Architecture](Standard-Library-Architecture.md) — built-in packages and conventions.

## Local server libraries: scripts/libs/

KSL compiles all .kt files in plugins/KotlinScriptLoader/scripts/libs/ as one Kotlin source set. Nested folders are supported; each file can declare its own package and regular imports.

~~~text
scripts/
  libs/
    text/TextTools.kt
    player/PlayerTools.kt
  welcome.kts
~~~

TextTools.kt:

~~~kotlin
package server.libs.text
fun String.coloredLabel(): String = "§a$this"
~~~

PlayerTools.kt:

~~~kotlin
package server.libs.player
import org.bukkit.entity.Player
import server.libs.text.coloredLabel
fun Player.notifyServer(text: String) { sendMessage(text.coloredLabel()) }
~~~

A .kts script can import these functions normally:

~~~kotlin
import server.libs.player.notifyServer
player?.notifyServer("Welcome!")
~~~

Run /ksl reload after changing .kt files. They are compiled together and can reference one another. If compilation fails, the previous successfully compiled version remains active.
