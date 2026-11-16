# IntelliJ IDEA support for KSL scripts and addons

This Gradle companion project gives IntelliJ a matching Kotlin/JVM project model,
KSL script template, Paper API classpath and live scripts directory. It is
separate from the runtime plugin and adds no runtime dependencies to KSL.

## Setup

1. Use JDK 21 and open ide/ as a Gradle project.
2. Point -PkslJar at the exact KSL development JAR installed on your test server:

       ./gradlew -PkslJar=/absolute/path/to/KotlinScriptLoader-1.2.2.jar verifyKslIdeSetup

3. Optionally set -PkslScriptsDir=/path/to/plugins/KotlinScriptLoader/scripts to index live scripts.
4. Install the separately built IntelliJ plugin from ide/intellij-plugin/.

## KSL-aware completion

The plugin contributes domain-specific completion for KSL .kts scripts and
KSL addon .kt sources. It suggests API access/provider methods, addon lifecycle
scopes, config and services, script lookup, typed lifecycle events, asynchronous
reload, default imports/context extensions, scope-owned listeners and tasks,
server-thread handoff, scheduler SPI and testing DSL entry points.

For .kt files, KSL suggestions activate when the file already references
KSLAddon, imports ru.privateserver.ksl..., or references KSL.*. Unrelated Kotlin
projects are left alone. Kotlin's own plugin still provides semantic completion,
type inference, KDoc and navigation.

## Addon starter kit

Copy templates/addon-starter/ into a new project. It includes a Gradle
configuration, plugin.yml, a minimal KSLAddon, an example scheduler adapter,
an addon-owned Player DSL extension and a KSL .kts sample. See its README.

## Boundaries

- Use the same KSL JAR version for IDE compilation and the server.
- Add optional plugin APIs as compile-only dependencies when your code imports them.
- verifyKslIdeSetup checks the JAR classpath and starter project; it does not
  automate IntelliJ's editor UI.
- Completion entries are curated hints, not a replacement for Kotlin semantic
  analysis or the compiler.
- Validate and test on a staging Paper server before production deployment.
