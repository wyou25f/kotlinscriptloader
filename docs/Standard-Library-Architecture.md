# KSL Standard Library — architecture and built-in utilities

**Status:** built-in text, math, time, player, inventory, item, component and world packages are shipped. Custom standard-library code can be provided by KSL addons; the module planner is currently a validation/planning API, not an automatic module loader.

## Built-in packages

These packages are included in the default imports for KSL scripts:

- `ru.privateserver.ksl.std.text.*`: Unicode-safe truncation, legacy color stripping, UUID parsing and whitespace tokenization.
- `ru.privateserver.ksl.std.math.*`: checked clamping, range mapping and validated percentage chance.
- `ru.privateserver.ksl.std.time.*`: explicit seconds/minutes/hours-to-ticks conversion, duration rounding and tick-to-duration conversion.
- `ru.privateserver.ksl.std.player.*`: player messaging, permissions and player-inventory helpers.
- `ru.privateserver.ksl.std.inventory.*`: first matching slot lookup, similar-item counting and bounded item removal for any Bukkit inventory.
- `ru.privateserver.ksl.std.item.*`: safe cloned ItemStack amount/name/lore transformations and empty-stack checks.
- `ru.privateserver.ksl.std.component.*`: literal Adventure text components, MiniMessage parsing and immutable text appending.
- `ru.privateserver.ksl.std.world.*`: block, location and entity-query helpers.

Inventory and ItemStack helpers that inspect or modify Bukkit objects must be called on the server thread. ItemStack transformation helpers return clones and leave the original stack unchanged. `toTextComponent()` treats input as literal text; use `toMiniMessageComponent()` only when the input is intentionally MiniMessage markup.

The existing `ru.privateserver.ksl.dsl.*` package remains available. Its `.sec`, `.mins` and `.hours` extensions use checked arithmetic and throw on overflow instead of silently wrapping.

## Examples

```kotlin
val welcome = "<green>Welcome, <bold>player</bold>!</green>".toMiniMessageComponent()
player.tell(welcome)

val renamed = ItemStack(Material.DIAMOND)
    .withDisplayName("<aqua>Reward".toTextComponent())
    .withLore(listOf("A daily reward".toTextComponent()))

player.inventory.addItem(renamed.copyWithAmount(3))
```

## Writing a custom std package

A custom standard library is a normal Kotlin KSL addon. Put extension functions and helper classes in a dedicated package, then register that package as a default import from the addon's `onLoad` callback:

```kotlin
package com.example.serverstd

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

fun Player.sendWelcome() {
    sendMessage(Component.text("Welcome to the server!"))
}

fun String.asServerTag(): String = "[Server] $this"
```

In the addon:

```kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports("com.example.serverstd.*")
}
```

The import is added to the runtime compilation configuration while the addon is registered. KSL also adds the registered addon's classloader to script compilation and evaluation, so the imported extension functions and types can be resolved from the addon's JAR. When the addon unloads, its imports and classloader exposure disappear from subsequent compilations.

Use a normal Bukkit `depend: [KotlinScriptLoader]` declaration when the addon requires KSL at startup. Compile against the KSL API with `compileOnly`; do not shade a second copy of KSL API classes into an addon.

Custom std packages can expose:
- Kotlin extension functions for Bukkit, Adventure and your own types;
- top-level helper functions and constants;
- classes, enums and data classes;
- wrappers around third-party libraries bundled by the addon.

Keep Bukkit world, entity and inventory operations on the server thread unless the Paper API explicitly permits asynchronous access.

## Module contracts

KSL exposes declarative module contracts for future independently versioned standard-library modules:

- `KSLModuleDescriptor` declares identity, version, required KSL API level, dependencies and default imports;
- `KSLStandardLibraryModule` declares install/uninstall callbacks;
- `KSLModulePlanner.resolve(...)` validates duplicate IDs, missing dependencies, dependency cycles and API compatibility, then returns a deterministic dependency-first order.

**Important:** the planner currently has no side effects. It does not discover, install or unload modules automatically. For a working custom std today, use the KSL addon lifecycle and `api.addDefaultImports(...)` described above.

## Compatibility policy

- Additive API changes must preserve existing signatures.
- Breaking changes require a deliberate API-version change and migration notes.
- Every addon/module should declare the minimum KSL API version it needs.
- Prefer one documented package namespace per library; avoid wildcard imports that collide.
- Keep custom implementations outside KSL core wherever possible.
