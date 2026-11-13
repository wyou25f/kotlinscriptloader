# IntelliJ IDEA support for KSL scripts

This is a Gradle companion project for editing live KSL `.kts` scripts with the
same Kotlin/JVM and server API classpath used by the plugin. It is separate from
the runtime plugin and adds no runtime dependencies to KSL.

## Setup

1. Build or download the matching `KotlinScriptLoader-1.2.0.jar` from the
   [GitHub Actions build](../.github/workflows/build.yml).
2. Either copy it to `ide/libs/KotlinScriptLoader-1.2.0.jar` or pass its path
   through `-PkslJar=/absolute/path/to/KotlinScriptLoader-1.2.0.jar`.
3. Open the `ide/` directory in IntelliJ IDEA as a Gradle project. Use JDK 21
   and allow Gradle sync to finish.
4. Point the project at the live scripts folder:

   ```shell
   ./gradlew -PkslScriptsDir=/absolute/path/to/plugins/KotlinScriptLoader/scripts
   ```

5. Run the optional classpath check:

   ```shell
   ./gradlew -PkslJar=/absolute/path/to/KotlinScriptLoader-1.2.0.jar verifyKslIdeSetup
   ```

6. If IDEA does not automatically index the configured folder, mark it as a
   Sources Root. Keep server-specific scripts outside this repository unless
   you intentionally want to commit them.

## Script definition and editor support

The plugin JAR contains KSL's `@KotlinScript` template and its canonical
`ScriptCompilationConfiguration`. The template declares the same:

- `.kts` script extension;
- `BukkitScriptContext` implicit receiver;
- default imports;
- Kotlin/JVM target and plugin classpath.

The runtime compiler now extends this same base configuration with imports
registered by loaded addons. This removes the previous duplicated definition
that could drift between the server and the IDE.

For IntelliJ to use the custom template, the KSL JAR must remain on this
companion project's Gradle classpath and the installed Kotlin plugin must
support Kotlin scripting definitions. The `KSL_SCRIPT_DEFAULT_IMPORTS` metadata
in the public API is available to other editor integrations as well.

## Dependencies and known boundaries

- Kotlin 2.0.21 and Java 21 project model.
- KSL public API and Paper/Bukkit symbols.
- PlaceholderAPI and LuckPerms symbols; add compile-only dependencies for
  other optional plugins used by your scripts.
- Addon-provided imports and classes are runtime-dependent. Add the matching
  addon JARs to this project's compile-only classpath and mirror any custom
  imports in your editor configuration when needed.
- IntelliJ analysis cannot reproduce the live server state, plugin availability,
  permissions, or runtime sandbox. Always validate scripts with KSL on the
  target Paper server before deploying them.

This is scripting-definition integration, not a standalone Marketplace plugin.
IDE behavior depends on the installed IntelliJ/Kotlin plugin version; the
`verifyKslIdeSetup` task checks the JAR contents, not IntelliJ's live completion
engine.
