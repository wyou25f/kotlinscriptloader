# KSL IntelliJ IDEA plugin

This is the first actual IntelliJ Platform plugin module for Kotlin Script Loader.
It adds completion entries for the documented KSL public API, addon lifecycle,
event bus, scheduler SPI, testing DSL and common server-side helpers in Kotlin
`.kts` files.

## Build

Requirements: JDK 21 and Gradle.

```shell
./gradlew -p ide/intellij-plugin buildPlugin
```

The ZIP is written under `ide/intellij-plugin/build/distributions/`. Install it
through **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Recommended server-admin setup

The plugin's curated completion works immediately in Kotlin script files. For
real Kotlin type checking, member completion, KDoc and navigation:

1. Open the `ide/` Gradle companion project in IntelliJ IDEA.
2. Point `-PkslJar` at the exact KSL JAR installed on the server.
3. Point `-PkslScriptsDir` at the server's
   `plugins/KotlinScriptLoader/scripts` directory.
4. Add compile-only dependencies for the server API and every optional plugin
   whose classes are used by scripts.
5. Install this plugin and the IntelliJ Kotlin plugin.

The plugin does not connect to a live server, execute scripts, or infer classes
from plugins that are absent from the IDE classpath. It also does not replace
the Kotlin plugin's semantic analysis. Its completion list is a curated
convenience layer; full type-aware completion and navigation come from the
configured Kotlin project model.

## Scope of this first release

- Kotlin `.kts` completion for documented KSL API entry points.
- Completion labels that identify the API area and explain each suggestion.
- No runtime dependency added to the KSL server plugin.
- No script execution, filesystem scanning, server connection, or remote code loading.

Future releases can add configurable classpath discovery, inspections backed by
KSL metadata, and context-aware templates without coupling the editor plugin to
a running Minecraft server.
