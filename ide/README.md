# IntelliJ IDEA support for KSL scripts

This folder contains a small Gradle companion project for editing KSL `.kts`
scripts with the same Kotlin/JVM and server API classpath used by the plugin.
It is intentionally separate from the runtime plugin and adds no runtime
dependencies to KSL.

## Setup

1. Build or download the KSL plugin JAR (the `KotlinScriptLoader-1.2.0.jar`
   file from the GitHub Actions build artifact).
2. Copy it to `ide/libs/KotlinScriptLoader-1.2.0.jar`.
3. Open the `ide/` directory in IntelliJ IDEA as a Gradle project and use
   JDK 21. Let Gradle sync finish.
4. Point the project at the folder containing your live KSL scripts. Either
   edit `kslScriptsDir` in `gradle.properties` or pass it to Gradle:

   ```shell
   ./gradlew -PkslScriptsDir=/absolute/path/to/plugins/KotlinScriptLoader/scripts
   ```

5. Mark the configured scripts folder as a Sources Root if IntelliJ does not
   do so automatically. Keep the live script directory outside this repository
   if you do not want server-specific scripts committed here.

## What this provides

- Kotlin 2.0.21 and Java 21 project model.
- KSL public API and Bukkit/Paper types on the IDE classpath.
- Compile-time symbols for PlaceholderAPI and LuckPerms when scripts use them.
- A single Gradle project that can be shared with other KSL script authors.

## Current limitation

This companion project provides dependency/classpath resolution. IntelliJ's
full top-level completion for KSL's runtime-provided implicit receiver
(`BukkitScriptContext`) still requires a dedicated Kotlin scripting definition
integrated with the exact script compilation configuration used by KSL. That
is the next IDE-support milestone; this project does not pretend to provide
that integration yet. Do not treat IDE diagnostics as a substitute for
compiling and testing scripts on the target Paper server.
