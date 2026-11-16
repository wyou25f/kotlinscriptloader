# KSL IntelliJ IDEA plugin

This is the IntelliJ Platform plugin for Kotlin Script Loader. It adds curated
completion entries for the documented KSL public API, addon lifecycle, event
bus, scheduler SPI, testing DSL and common server-side helpers in Kotlin
`.kts` files.

## Build

Requirements: JDK 21 and Gradle.

```shell
./gradlew -p ide/intellij-plugin test buildPlugin
```

The ZIP is written under `ide/intellij-plugin/build/distributions/`. Install it
through **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Diagnostics in 1.1.0

The plugin warns when common Bukkit/Paper player or world API calls are found
inside KSL's `runAsync { }`, `afterAsync { }` or `repeatAsync { }` blocks.
Examples include player lookup/messaging, teleportation, inventory access,
block lookup and block mutation.

These are conservative source-level warnings, not a complete Kotlin data-flow
or Bukkit API thread-safety analysis. They intentionally do not execute scripts
or connect to a live server. Move server API work to the main thread, for
example by using the KSL `runSync { ... }` facility where it is available.
Keep database, file and expensive pure computations off the server thread.

## Recommended server-admin setup

The curated completion and source-level diagnostics work in Kotlin script files.
For real Kotlin type checking, member completion, KDoc and navigation:

1. Open the `ide/` Gradle companion project in IntelliJ IDEA.
2. Point `-PkslJar` at the exact KSL JAR installed on the server.
3. Point `-PkslScriptsDir` at the server's
   `plugins/KotlinScriptLoader/scripts` directory.
4. Add compile-only dependencies for the server API and every optional plugin
   whose classes are used by scripts.
5. Install this plugin and the IntelliJ Kotlin plugin.

The plugin does not connect to a live server, execute scripts, or infer classes
from plugins that are absent from the IDE classpath. It also does not replace
the Kotlin plugin's semantic analysis. Full type-aware completion and navigation
come from the configured Kotlin project model.

## Scope

- Kotlin `.kts` completion for documented KSL API entry points.
- Completion labels that identify the API area and explain each suggestion.
- Basic warnings for common Bukkit/Paper calls inside KSL async scheduler blocks.
- No runtime dependency added to the KSL server plugin.
- No script execution, filesystem scanning, server connection, or remote code loading.

The async checker is intentionally limited and will evolve alongside tests and
the documented KSL API. It should not be treated as a substitute for reviewing
Paper's threading requirements.
