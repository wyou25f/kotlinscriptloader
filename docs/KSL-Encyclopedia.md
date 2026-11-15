# KotlinScriptLoader (KSL) — Complete Documentation

> This is the architectural overview of KSL. Specialized guides are listed in [docs/README.md](README.md). Always verify API methods and signatures against source code and tests in the current branch.

## Contents

1. [What is KSL?](#1-what-is-ksl)
2. [Architecture](#2-architecture)
3. [Installation and build](#3-installation-and-build)
4. [Plugin lifecycle](#4-plugin-lifecycle)
5. [Files and localization](#5-files-and-localization)
6. [Writing scripts](#6-writing-scripts)
7. [GUI DSL](#7-gui-dsl)
8. [Persistence](#8-persistence)
9. [Integrations and hooks](#9-integrations-and-hooks)
10. [Sandbox and security](#10-sandbox-and-security)
11. [Commands and permissions](#11-commands-and-permissions)
12. [Addon API](#12-addon-api)
13. [Internals for contributors](#13-internals-for-contributors)
14. [Technical pitfalls](#14-technical-pitfalls)
15. [Troubleshooting](#15-troubleshooting)
16. [Contributing](#16-contributing)

## 1. What is KSL?

**KotlinScriptLoader (KSL)** is a plugin for Paper 1.21.1 (Java 21) that compiles and executes Kotlin Script (.kts) files on the server without rebuilding the plugin or restarting the server. Unlike custom pseudo-languages, KSL uses real Kotlin and the APIs available on the server classpath, including Bukkit/Paper, JDBC, Adventure, and MiniMessage.

KSL provides script hot reload, a DSL for events and commands, scheduling and configuration helpers, SQL access, optional plugin integrations, a GUI DSL, persistent values, an addon API, standard-library modules, runtime tools, and localized server messages.

KSL is **not** an isolated JVM sandbox. Scripts run in the server process and may have access to capabilities available to the plugin. Sandbox is a guardrail against selected dangerous patterns, not a security boundary against malicious code.

## 2. Architecture

~~~text
┌─────────────────────────────────────────────────────────┐
│ Layer 1: Plugin core                                     │
│ KotlinScriptLoaderPlugin, ScriptRunner, KSLScript,        │
│ migrations, commands, lifecycle and configuration        │
├─────────────────────────────────────────────────────────┤
│ Layer 2: Script DSL                                      │
│ BukkitScriptContext (implicit receiver for each .kts)    │
│ GUI, persistence, scheduler, database and runtime tools   │
├─────────────────────────────────────────────────────────┤
│ Layer 3: Integrations and Addon API                      │
│ hooks, KSL / KSLAPI / KSLAddon / KSLContextExtension      │
└─────────────────────────────────────────────────────────┘
~~~

Important source files:

| File | Responsibility |
|---|---|
| KotlinScriptLoaderPlugin.kt | Main JavaPlugin class, lifecycle, database, integrations, configuration, and resource cleanup. |
| ScriptRunner.kt | Compiles and executes a .kts file through BasicJvmScriptingHost; performs Sandbox checks and formats errors. |
| KSLScript.kt | Script compilation settings: default imports, JVM target, classloader, and @KotlinScript. |
| BukkitScriptContext.kt | Implicit receiver for every script; exposes the KSL DSL. |
| DynamicCommand.kt | Bukkit Command implementation for commands registered by scripts. |
| KSLCommandExecutor.kt | /ksl command handler. |
| Migrations.kt | Versioned SQLite schema migrations. |
| KSLLocale.kt | Loads message bundles from locale YAML files and resolves translated messages. |
| KSL.kt | Static entry point for addon plugins. |
| KSLAPI.kt | API used by addons to register services, imports, and context extensions. |
| KSLAddon.kt | Interface implemented by addon plugins. |
| KSLAddonManager.kt | Stores addon registrations and implements KSLAPI. |
| KSLContextExtension.kt | Lifecycle hook for each script context. |
| KSLGuiHolder.kt / KSLGuiInstance.kt / KSLGuiManager.kt | GUI holder, menu instance, and plugin-wide listener. |
| KSLPersistStore.kt | In-memory and optional SQLite storage for persist(). |
| hooks/ | Built-in plugin integrations and shared reflection/alias support. |

## 3. Installation and build

Requirements: Java 21 and Paper 1.21.1 or a compatible fork.

Use the repository's build entry point:

~~~powershell
./build.ps1 -Task Build
~~~

The resulting plugin JAR is placed in build/libs/. Copy it to plugins/. For build tasks, CI artifacts, and diagnostic reports, see [Build Diagnostics](Build-Diagnostics.md).

Dependency notes:

- Exclude the old org.bukkit:bukkit transitive dependency from VaultAPI to avoid a Gradle capability conflict with Paper API.
- shadowJar relocates only com.zaxxer.hikari. Do not relocate kotlin.* or org.jetbrains.kotlin.* because dynamically compiled scripts require the original Kotlin class names.
- Use the Gradle wrapper version required by the configured Shadow plugin.

## 4. Plugin lifecycle

### onEnable()

The high-level startup sequence is:

1. saveDefaultConfig() creates config.yml if it does not exist.
2. KSLAddonManager and ScriptRunner are initialized; configuration such as Sandbox settings is read.
3. The locale manager copies built-in locale resources and loads the configured language.
4. KSL.init(addonManager) makes the public API available to addons.
5. The /ksl command executor is registered.
6. initDatabase() starts the database pool and applies migrations.
7. KSLPersistStore is created.
8. KSLGuiManager is created and its plugin-wide listener is registered once.
9. Optional integrations and their hooks are initialized.
10. Discord hooks are created when webhook channels are configured.
11. Script autocomplete information is generated.
12. Scripts are compiled and loaded.
13. The startup summary is printed.

### onDisable()

KSL unloads scripts, shuts down addons, closes the database, and shuts down the public API. Cleanup phases should be isolated so a failure in one phase does not prevent the remaining cleanup.

### /ksl reload

Reload refreshes configuration and integrations, unloads previous script instances, updates autocomplete information, and loads scripts again.

unloadAllScripts() is central to hot reload: KSL collects script IDs before clearing ownership maps, cancels tracked Bukkit and worker tasks, unregisters listeners, removes script commands, removes placeholders, notifies addon context extensions, and closes open GUIs owned by the scripts being unloaded.

## 5. Files and localization

The plugin stores configuration and scripts under plugins/KotlinScriptLoader/. Script files use the .kts extension. A script may have a companion YAML configuration file with the same base name. Nested script directories are supported when recursive loading is enabled.

The server language is configured with language in config.yml. KSL ships with locale/ru.yml, locale/en.yml, and locale/template.yml. On first startup, these resources are copied to plugins/KotlinScriptLoader/locale/.

To add a custom translation, copy template.yml to locale/<language-id>.yml and translate the values. Keep message keys and {placeholders} unchanged. Select the language with /ksl language <language-id> or set language in config.yml. Missing custom keys use the built-in English message as a fallback.

The documentation under docs/ is maintained in English; server messages are localized independently.

See [Getting Started](Getting-Started.md), [Data Formats](Data-Formats.md), and [Production Operations](Production-Operations.md).

## 6. Writing scripts

Each .kts script runs with BukkitScriptContext as its implicit receiver. Check [Script DSL Reference](Script-DSL-Reference.md) and the current source for exact signatures.

### Events

~~~kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage("<green>Welcome, <white>${player.name}</white>!</green>")
}
~~~

### Commands

~~~kotlin
registerCommand("hello", aliases = listOf("hi")) { sender, args ->
    sender.sendMessage("Hello! Arguments: ${args.size}")
}
~~~

Use explicit permission checks for administrative commands. Do not rely only on tab completion to hide a command.

### Scheduling and threading

Use KSL-managed operations such as runSync, runAsync, delay, every, delayAsync, and everyAsync. Bukkit world, player, entity, and inventory operations generally belong on the main thread. Run long SQL/network operations and CPU-heavy calculations in the background, then return Bukkit updates through runSync { ... }.

### Configuration and files

Use config, editConfig { ... }, saveConfig(), and supported YAML/TOML/JSON helpers. Avoid unsynchronized writes from multiple threads. Context file paths are restricted to scripts/; do not use ../ to escape the directory.

### Messaging

KSL supports Adventure/MiniMessage messaging and text placeholders. Prefer its message helpers over manually concatenating formatted text. See [Data Formats](Data-Formats.md) and [Standard Library Guide](Standard-Library-Guide.md).

## 7. GUI DSL

The GUI DSL builds inventory menus through KSL-managed resources. Menus support slots, button callbacks, pagination, and lifecycle cleanup.

~~~kotlin
gui(title = "<gold>Menu", rows = 3) {
    button(13, item(Material.DIAMOND, "<aqua>Example")) { player ->
        player.sendMessage("You clicked the item.")
    }
}
~~~

Verify the current Script DSL Reference before relying on a method signature.

## 8. Persistence

persist() stores state owned by one script. In-memory values survive script reloads while the plugin remains enabled. When SQLite persistence is configured, values can also survive a full server restart.

~~~kotlin
val visits = persist("visits", 0)
visits.value += 1
~~~

Do not store arbitrary Bukkit objects. Use supported scalar values or deliberately serialize structured data. See [Database Guide](Database-Guide.md).

## 9. Integrations and hooks

KSL provides optional hooks for supported plugins. Treat a missing integration as an expected condition, not a fatal error.

~~~kotlin
val vault = hook<KSLVaultHook>("vault")
if (vault == null) {
    log("Vault is not installed; economy integration is disabled")
}
~~~

Depending on installed plugins and the current KSL build, integrations may include PlaceholderAPI, LuckPerms, EssentialsX, Vault, SkinsRestorer, TAB, AuthMe, WorldEdit, WorldGuard, Skript, and Discord webhooks.

## 10. Sandbox and security

Sandbox performs configurable checks for potentially dangerous source patterns and class references. It is a guardrail, not a secure isolation boundary.

Scripts execute inside the server JVM and may access Bukkit and other server capabilities. Only trusted people should be allowed to write scripts or install addons. Never expose passwords or webhook tokens in scripts, YAML files, or logs.

See [Security and Sandbox](Security-and-Sandbox.md).

## 11. Commands and permissions

The /ksl command provides script management, runtime diagnostics, error lookup, and addon/integration information. Exact subcommands depend on the current build; use /ksl help and server tab completion.

The administrative permission is ksl.admin. Protect it with your permissions plugin and do not grant it to untrusted players.

Common commands:

- /ksl reload — reload scripts.
- /ksl validate — validate scripts without loading them.
- /ksl doctor — run diagnostics.
- /ksl errors — inspect recent errors.
- /ksl error <code> — explain an error code.
- /ksl runtime — inspect JVM and worker-pool telemetry.
- /ksl language — show or change the locale.

## 12. Addon API

Third-party Paper plugins can implement KSLAddon and register services, default imports, and context extensions through KSLAPI. Addons should declare KSL as a compileOnly dependency and must not bundle a second copy of KSL API classes.

See [Addon Development Guide](Addon-Development-Guide.md) and [Public API](Public-API.md).

## 13. Internals for contributors

Before changing the core, inspect the current implementation and tests. Pay particular attention to ScriptRunner, script/context ownership, command registration and removal, database executor and migrations, hook initialization, locale key coverage, standard-library module registration, CI quality gates, and JAR inspection requirements.

Do not make an internal implementation class public solely for convenience. Prefer a supported extension point and add tests for lifecycle and compatibility behavior.

## 14. Technical pitfalls

- Do not relocate Kotlin runtime packages in the shaded JAR.
- Do not execute Bukkit world/player/entity/inventory operations from arbitrary worker threads.
- Do not create unbounded thread pools or repeating tasks without cleanup.
- Do not treat Sandbox as isolation from malicious code.
- Do not remove ownership tracking required for safe hot reload.
- Do not assume an optional plugin or hook is installed.
- Do not expose database credentials, Discord webhooks, or API tokens in source control.
- Do not rename locale keys or placeholders when translating YAML files.

## 15. Troubleshooting

Start with the exact error code and the complete surrounding console log. Run /ksl doctor and /ksl validate when available. For a server freeze, inspect both Paper's watchdog dump and KSL's multi-snapshot dump.

See [Troubleshooting](Troubleshooting.md), [Build Diagnostics](Build-Diagnostics.md), and [Error Codes](Error-Codes.md).

## 16. Contributing

1. Check the current main branch and exact commit.
2. Read AGENTS.md and the documents for the affected subsystem.
3. Add or update tests with the implementation.
4. Preserve script hot reload and resource ownership.
5. Keep public API changes compatible or document a deliberate API-version change.
6. Run the strict quality gate and inspect the JAR.
7. Report exactly which checks ran and which were not run.

Do not claim real-server compatibility based only on successful compilation; test Paper behavior on a staging server when practical.
