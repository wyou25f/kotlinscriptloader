# IntelliJ IDEA support for KSL scripts

This Gradle companion project gives IntelliJ a matching Kotlin/JVM project model,
KSL script template, Paper API classpath and live scripts directory. It is
separate from the runtime plugin and adds no runtime dependencies to KSL.

## Setup

1. Download the artifact for the exact build you installed from
   [GitHub Actions](../.github/workflows/build.yml). Artifacts are named
   `KSL-build-<run-number>-<commit-sha>` so consecutive builds are easy to tell apart.
2. Copy the JAR to `ide/libs/KotlinScriptLoader-1.2.0.jar`, or pass its full path
   through `-PkslJar=/absolute/path/to/KotlinScriptLoader-1.2.0.jar`.
3. Open `ide/` in IntelliJ IDEA as a Gradle project. Use JDK 21 and let Gradle sync.
4. Point the project at the server's scripts folder:

   ```shell
   ./gradlew -PkslScriptsDir=/absolute/path/to/plugins/KotlinScriptLoader/scripts
   ```

5. Run the classpath/template check:

   ```shell
   ./gradlew -PkslJar=/absolute/path/to/KotlinScriptLoader-1.2.0.jar verifyKslIdeSetup
   ```

6. If the live scripts folder is not indexed, mark it as a Sources Root. Do not
   commit a live server scripts folder unless that is intentional.

## What should be available

The plugin JAR contains the `@KotlinScript` template and canonical
`ScriptCompilationConfiguration`. Runtime and editor metadata share:

- `.kts` extension;
- `BukkitScriptContext` implicit receiver;
- default imports, including the KSL Standard Library;
- Kotlin/JVM target and the plugin's classloader dependencies.

For example, a KSL script can import the public namespace and use typed
completion on the API:

```kotlin
import ru.privateserver.ksl.api.*

val api = kslOrNull
if (api != null) {
    api.scripts().forEach { script ->
        println("${script.id}: ${script.loaded}")
    }
    val economy = api.service<EconomyService>("economy")
}
```

The receiver also exposes the KSL context methods and the default-imported
DSL/Standard Library functions. IntelliJ should offer member completion,
parameter information and navigation when its Kotlin scripting support has
successfully discovered the template.

## Dependencies and boundaries

- Kotlin 2.0.21 and Java 21.
- KSL JAR, Paper API, PlaceholderAPI and LuckPerms.
- Add compile-only dependencies for any optional plugins/addons whose types
  your own scripts use.
- Addon-provided imports/classes only appear when their JARs are added to this
  IDE project's classpath. Runtime-only plugin state cannot be inferred by an IDE.
- The Gradle check verifies the classpath and template classes in the JAR. It
  does **not** prove that a particular IntelliJ/Kotlin plugin version has
  enabled live completion; test the sample script in the IDE itself.

## IntelliJ editor smoke checks

Open `samples/ide-completion-smoke-test.kts` after Gradle sync:

1. Type after `api.`, `player?.`, `world.` and `location?.` to inspect member completion.
2. Hover over `tellRich`, `entitiesInBox`, `isInside` and public API methods to inspect KDoc.
3. Ctrl/Cmd-click these declarations to test navigation.
4. Open `samples/ide-error-smoke-test.kts`. Its deliberately unknown method must be
   reported as an unresolved reference. This file is a diagnostics fixture and
   must not be copied into a live server scripts folder.

The Gradle verification checks that the template, API and Standard Library classes
are present in the exact JAR and that the smoke fixtures exist. It cannot automate
IntelliJ's editor UI; completion, hover, navigation and highlighting still require
the above in-IDE check on the target IntelliJ/Kotlin plugin version.

## VS Code

KSL publishes a Kotlin scripting definition and a Gradle classpath, not a
VS Code extension. VS Code completion depends on the Kotlin language extension
and language server in use; many Kotlin language servers do not fully consume
custom `@KotlinScript` templates or implicit receivers. IntelliJ IDEA is the
supported first target for full KSL script-aware completion. In VS Code, the
public API classes may still resolve when the project classpath is configured,
but implicit-receiver completion is not guaranteed.
