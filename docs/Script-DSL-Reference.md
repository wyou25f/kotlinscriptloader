# KSL Script DSL — Reference

This document describes the DSL available inside .kts scripts. It is not a reference for the Kotlin/Java Addon API; see [Public API](Public-API.md) for addons.

## 1. Script context

Each script executes with BukkitScriptContext as its implicit receiver. Common properties and methods include:

- plugin — the KSL JavaPlugin instance. Available only to trusted/non-sandboxed scripts; reading it from a sandboxed script throws SecurityException.
- config — YAML configuration for the current script.
- database — the shared raw HikariDataSource. Available only to trusted/non-sandboxed scripts; sandboxed scripts should use KSL SQL methods.
- log(message), logWarn(message), logError(message) — KSL logging.
- onlinePlayers(), onlineCount, maxPlayers.
- findPlayer(nameOrUuid).
- runSync { }, runAsync { }.
- onEvent<T> { }, registerCommand(...).
- registerPlaceholder(...).
- saveConfig(), editConfig { }.

Default imports are controlled by KSL metadata. Do not assume wildcard imports are recursive: subpackages may require separate imports. See [Packages and Imports](Packages-and-Imports.md).

## 2. Events

~~~kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage("<green>Welcome, ${player.name}!</green>")
}
~~~

For cancellable events, configure priority and ignoreCancelled as required:

~~~kotlin
onEvent<PlayerInteractEvent>(
    priority = EventPriority.MONITOR,
    ignoreCancelled = true
) {
    // Observe at MONITOR priority; do not modify the event.
}
~~~

Do not perform expensive SQL, network calls, or long computations in an event callback. Move data to a background task and return Bukkit changes to the main thread.

## 3. Commands

~~~kotlin
registerCommand("profile", aliases = listOf("p")) { sender, args ->
    if (!sender.hasPermission("server.profile")) {
        sender.sendMessage("You do not have permission.")
        return@registerCommand
    }

    sender.sendMessage("Arguments: ${args.size}")
}
~~~

Command names and aliases are validated during registration. KSL reports conflicts and invalid aliases. Do not register commands again inside a repeating event.

## 4. Scheduler

~~~kotlin
runSync {
    // Bukkit API that requires the main thread
}

runAsync {
    // File I/O, SQL, or computation,
    // when the API supports a background call.
}

every(ticks = 20L) {
    // Repeating task
}
~~~

Repeating tasks registered through the KSL context belong to the script and are removed on unload. Do not use Thread.sleep for delays; it blocks a thread. Use KSL/Bukkit scheduler APIs instead.

## 5. Bukkit and threading

| Operation | Thread |
|---|---|
| Modifying blocks/world state | Main |
| Player inventory | Main |
| Teleport, health, player effects | Main |
| SQL through KSL DB API | Background |
| Network requests | Background |
| CPU-heavy computation | Worker/async |
| Background result that changes Bukkit state | Return through runSync |

Do not assume even read access to Bukkit objects is safe on an async thread unless the Paper API explicitly says so.

## 6. Current script YAML configuration

The config file uses the script's base name: shop.kts → shop.yml.

~~~kotlin
val prefix = config.getString("messages.prefix", "<gray>[Shop]</gray>") ?: "<gray>[Shop]</gray>"

editConfig {
    set("settings.enabled", true)
    set("settings.price", 100)
}

saveConfig()
~~~

editConfig(save = true) synchronizes the modification and save. With save = false, changes remain in memory until saveConfig() is called explicitly.

See [Data Formats](Data-Formats.md) for YAML schema validation and migrations.

## 7. Feature flags

Feature flags are stored in the script YAML under features:

~~~kotlin
if (featureEnabled("shop.enabled", default = true)) {
    // The shop is enabled
}

enableFeature("shop.enabled")
disableFeature("shop.enabled")
val stateAfterToggle = toggleFeature("shop.enabled")
~~~

A flag is a dot-separated path such as shop.enabled or events.double-xp. Its value is saved through the script configuration. This controls behavior; it is not a security mechanism. A hidden or disabled feature is not protected from direct calls by other code.

## 8. Persisting state

Use persist(...) and table(...) for persistent data instead of global mutable variables that disappear on reload. See [Database Guide](Database-Guide.md) for storage, serialization, and backend details.

## 9. Data files

The context provides JSON and TOML operations for files in the scripts directory. Paths are restricted to scripts/. Do not use ../ to access the plugin config or other directories. See [Data Formats](Data-Formats.md).

## 10. Messages and errors

~~~kotlin
warn("An optional setting is missing")
error("The current script operation cannot be completed")
fatal("A required part of the script was not initialized")
~~~

- warn logs a warning and continues.
- error interrupts the current operation.
- fatal unloads the current script and its resources.

Do not use fatal for an expected user error such as an invalid command argument. Send a clear message and return from the handler instead.

## 11. PlaceholderAPI and integrations

KSL provides optional integrations when the corresponding plugins are installed. Check hook/API results for null and do not assume a third-party plugin is required. See [KSL Encyclopedia](KSL-Encyclopedia.md) and [Standard Library Guide](Standard-Library-Guide.md) for supported integrations and limitations.

## 12. Comments and disabling code

Kotlin Script supports:

~~~kotlin
// Single-line comment

/*
  Multi-line
  comment
*/

val enabled = true
if (enabled) {
    // This code runs
}
~~~

YAML and TOML comments start with #:

~~~yaml
# This is a comment
shop:
  enabled: true
~~~

~~~toml
# This is a comment
[shop]
enabled = true
~~~

A semicolon does not disable a function. In Kotlin it can separate expressions on one line, but separate lines are clearer. Use an if statement or a persistent feature flag to enable/disable logic. Do not permanently comment out large blocks when a configuration option would express the setting better.

## 13. Resources and hot reload

Events, commands, placeholders, tasks, and other KSL-owned resources should be created through the KSL DSL so KSL can remove them on reload. If you create external resources directly through Bukkit or another plugin, close/cancel them as part of your own lifecycle. Otherwise duplicate handlers and leaks may occur.

## 14. Related guides

- [Script Lifecycle, Hot Reload, and Resource Ownership](Script-Lifecycle-and-Resources.md)
- [Standard Library](Standard-Library-Architecture.md)
- [Runtime API](Runtime-API.md)
- [Database Guide](Database-Guide.md)
- [Security and Sandbox](Security-and-Sandbox.md)
- [Troubleshooting](Troubleshooting.md)
