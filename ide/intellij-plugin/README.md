# KSL IntelliJ IDEA plugin

The IntelliJ Platform plugin for Kotlin Script Loader. Version **1.2.2** adds a
local static preflight pass, dependency diagnostics, API typo checks, version
warnings, blocking-call warnings and addon-source checks. It does not add a
runtime dependency to the server.

## Build and install

Requirements: JDK 21 and Gradle.

```shell
./gradlew -p ide/intellij-plugin test buildPlugin
```

The ZIP is written under `ide/intellij-plugin/build/distributions/`. Install it
through **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Diagnostics

- **Missing dependencies:** checks imports for Paper/Spigot API, PlaceholderAPI
  and LuckPerms against root `build.gradle(.kts)` and
  `gradle/libs.versions.toml`. A quick action opens the Gradle build file so
  the developer can add the correct artifact and version.
- **Unknown KSL API calls:** checks calls through the explicit `KSL.api`
  receiver (or a variable explicitly typed as `KSLAPI`) against the public API
  catalog. This is deliberately conservative and is not full Kotlin resolution.
- **Deprecated and version-sensitive APIs:** flags known deprecated
  `PlayerChatEvent` usage and legacy Material constants when a modern
  Paper/Spigot target is declared.
- **Async thread-safety:** warns about common Bukkit/Paper player and world
  calls inside KSL `runAsync { }`, `afterAsync { }` and `repeatAsync { }`
  blocks. The quick fix wraps a standalone statement in `runSync { ... }` (declaration lines are excluded to preserve variable scope);
  review the resulting code and keep only Bukkit API work on the server thread.
- **Authoring safety:** warns about `Thread.sleep`, `runBlocking`, unconditional loops,
  manually created threads/executors and synchronous `KSLAPI.reloadScript` calls.
- **Addon projects:** local preflight also analyzes Kotlin `.kt` files that reference
  KSL, in addition to server `.kts` scripts.
- **Preflight:** right-click a Kotlin `.kts` file and choose
  **KSL: Validate Current Script**. The action runs local static checks without
  compiling or executing the script and without connecting to a server.

## Limits and project setup

These checks are source-level heuristics, not a complete Kotlin type checker,
Gradle dependency resolver, Paper compatibility database or security sandbox.
They inspect only the current script and the root Gradle build files. A
dependency declared transitively or through a custom convention plugin may be
reported as missing. The plugin does not download artifacts, scan the server,
execute scripts, or connect to a live Minecraft server.

For full Kotlin type checking, member completion, KDoc and navigation, open
your addon as a Gradle project and declare the exact KSL JAR, Paper API and
optional plugin APIs used by the addon. The plugin's diagnostics complement
the Kotlin plugin; they do not replace a correctly configured classpath.
