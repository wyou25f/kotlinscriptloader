# KSL Public API

## Import

Kotlin scripts and addon projects can use the public namespace:

```kotlin
import ru.privateserver.ksl.api.*

if (isKslAvailable) {
    val loadedScripts = ksl.scripts()
    val apiLevel = KSL_API_VERSION
    val economy = ksl.service<EconomyService>("economy")
}
```

The namespace currently provides Kotlin type aliases for the core addon, hook,
context-extension, script-info, script-event, shape-factory and script-context
contracts, plus typed service/hook helpers. Type aliases are Kotlin source-level
aliases; Java integrations should use the underlying `ru.privateserver.ksl`
interfaces directly.

## Script definition metadata

`KSLScriptMetadata.kt` is the single source of truth for:

- `KSL_SCRIPT_FILE_EXTENSION`;
- `KSL_SCRIPT_JVM_TARGET`;
- `KSL_SCRIPT_CONTEXT_CLASS`;
- `KSL_SCRIPT_DEFAULT_IMPORTS`.

The runtime `KSLScriptCompilationConfiguration` consumes this metadata, and
the annotated `KSLScript` template points IntelliJ's Kotlin scripting support
at that configuration. Addon-registered imports are appended at runtime.

## Compatibility

`KSL_API_VERSION` is the compatibility level checked by addons. Additive
changes should preserve existing signatures. Breaking changes require an
intentional API-version change and corresponding migration notes.

Use the plugin JAR as a `compileOnly` dependency in addon projects; do not
bundle another copy of KSL or its API classes into an addon, because duplicate
classloaders prevent reliable service and hook type checks.

## Standard Library foundation

The future Standard Library module SPI and dependency planner are documented
in [Standard Library architecture](Standard-Library-Architecture.md). They
provide contracts and validation tools only; no standard feature modules are
implemented yet.
