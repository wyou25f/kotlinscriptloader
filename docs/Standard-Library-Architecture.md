# KSL Standard Library — Architecture and Built-in Utilities

**Status:** The built-in text, math, time, player, inventory, item, component, and world packages are shipped. Custom standard-library code can be provided by KSL addons through addon imports/services or versioned modules registered with KSLAPI.

## Built-in packages

These packages are included in the default imports for KSL scripts:

- ru.privateserver.ksl.std.text.*: Unicode-safe truncation, legacy color stripping, UUID parsing, whitespace tokenization/normalization, identifier case conversion, and Minecraft username-shape validation.
- ru.privateserver.ksl.std.math.*: checked clamping, range mapping, and validated percentage chance.
- ru.privateserver.ksl.std.time.*: explicit seconds/minutes/hours-to-ticks conversion, duration rounding, and tick-to-duration conversion.
- ru.privateserver.ksl.std.player.*: player messaging, permissions, and player-inventory helpers.
- ru.privateserver.ksl.std.inventory.*: first matching slot lookup, similar-item counting, and bounded item removal for any Bukkit inventory.
- ru.privateserver.ksl.std.item.*: safe cloned ItemStack amount/name/lore transformations and empty-stack checks.
- ru.privateserver.ksl.std.component.*: literal Adventure text components, MiniMessage parsing, and immutable text appending.
- ru.privateserver.ksl.std.world.*: block, location, and entity-query helpers.
- ru.privateserver.ksl.std.json.*: Kotlin-value conversion, JSON object/array builders, and typed object accessors; file persistence is exposed by the script context.
- ru.privateserver.ksl.std.security.*: SHA-256 checksums, PBKDF2-HMAC-SHA256 password hashing, HMAC-SHA256, constant-time comparisons, secure random tokens, and Base64 helpers.

Inventory and ItemStack helpers that inspect or modify Bukkit objects must be called on the server thread. ItemStack transformation helpers return clones and leave the original stack unchanged. toTextComponent() treats input as literal text; use toMiniMessageComponent() only when the input is intentionally MiniMessage markup.

The security standard library uses Java 21 cryptographic APIs and adds no external runtime dependency. Password hashing and large-file checksums should run asynchronously. SHA-256 is for integrity checks, not password storage; use the PBKDF2 helpers for passwords.

The JSON standard library uses kotlinx.serialization JSON and is bundled with KSL; scripts receive kotlinx.serialization.json.* imports by default. JSON files are restricted to the script scripts/ directory and saved through atomic replacement. File I/O is synchronous.

The script DSL helper chance(percent) follows the same contract as randomChance(percent): the percentage must be finite and within 0..100. Invalid values fail immediately instead of silently producing a result.

The existing ru.privateserver.ksl.dsl.* package remains available. Its .sec, .mins, and .hours extensions use checked arithmetic and throw on overflow instead of silently wrapping.

## Examples

~~~kotlin
val welcome = "<green>Welcome, <bold>player</bold>!</green>".toMiniMessageComponent()
player.tell(welcome)

val renamed = ItemStack(Material.DIAMOND)
    .withDisplayName("<aqua>Reward".toTextComponent())
    .withLore(listOf("A daily reward".toTextComponent()))

player.inventory.addItem(renamed.copyWithAmount(3))
~~~

## Writing a custom standard-library package

A custom standard library is a normal Kotlin KSL addon. Put extension functions and helper classes in a dedicated package, then register that package as a default import from the addon's onLoad callback:

~~~kotlin
package com.example.serverstd

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

fun Player.sendWelcome() {
    sendMessage(Component.text("Welcome to the server!"))
}

fun String.asServerTag(): String = "[Server] $this"
~~~

In the addon:

~~~kotlin
override fun onLoad(api: KSLAPI) {
    api.addDefaultImports("com.example.serverstd.*")
}
~~~

The import is added to the runtime compilation configuration while the addon is registered. KSL also adds the registered addon's classloader to script compilation and evaluation, so imported extension functions and types can be resolved from the addon's JAR. When the addon unloads, its imports and classloader exposure are removed from subsequent compilations.

Use a normal Bukkit depend: [KotlinScriptLoader] declaration when the addon requires KSL at startup. Compile against KSL API with compileOnly; do not shade a second copy of KSL API classes into an addon.

Custom standard-library packages can expose Kotlin extension functions for Bukkit, Adventure, and your own types; top-level helper functions/constants; classes/enums/data classes; and wrappers around third-party libraries bundled by the addon.

Keep Bukkit world, entity, and inventory operations on the server thread unless the Paper API explicitly permits asynchronous access.

## Module contracts

KSL exposes declarative module contracts for independently versioned standard-library modules:

- KSLModuleDescriptor declares identity, version, required KSL API level, dependencies, and default imports.
- KSLStandardLibraryModule declares install/uninstall callbacks.
- KSLModulePlanner.resolve(...) validates duplicate IDs, missing dependencies, dependency cycles, and API compatibility, then returns a deterministic dependency-first order.

The planner has no side effects; it only validates and orders modules. The runtime module manager uses it when an addon calls api.registerStandardLibraryModule(module). Register declared dependencies first, then the dependent module. Modules registered during onLoad are owned by that addon and are uninstalled in reverse registration order when it unloads. A module can also be removed explicitly with api.unregisterStandardLibraryModule(id), provided no installed module depends on it. See [Custom Standard Library](Custom-Standard-Library.md) for a complete example.

## MesLib2 — Cross-Script Libraries

MesLib2 is a script-scoped registry for sharing objects and services between scripts. Each export has a namespace, name, type, and version label. A script owns its exports; they are removed when that script is unloaded.

~~~kotlin
// economy.kts
meslib2.exportLocal("service", EconomyService(), version = "2.0")

// shop.kts
// @requires: economy
val economy = ml2.require<EconomyService>("economy", "service", "2.0")
~~~

New scripts should prefer the canonical operations: export/get/require/contains/info, along with exportIfAbsent, remove, list, and clear. Removal is restricted to the current owner, and exportIfAbsent atomically prevents accidental replacement. list(namespace) returns sorted entry metadata. Legacy names publish/provide/put/find/lookup/resolve/mustGet/has/describe and their local variants remain available for compatibility.

Namespace and name must match [a][a-z0-9._-]*. A version is an opaque label compared exactly; KSL does not interpret SemVer and does not automatically load external artifacts. Type checks use JVM classes, so prefer shared API types when provider and consumer are loaded by different classloaders.

## kslrt — Runtime Utilities

The package ru.privateserver.ksl.rt.* is included in default imports:

- KSLCache<K, V> is a thread-safe LRU cache with a size bound and optional TTL.
- KSLProfiler aggregates call counts, errors, total time, and maximum time.
- kslrt.cache<K, V>(maxSize, expireAfter) and kslrt.profiler(name) are factories.
- ksl, kslapi, kslApi, and api are aliases for the installed KSLAPI; legacy names remain available.

~~~kotlin
val playerCache = kslrt.cache<String, PlayerData>(maxSize = 2_000)
val profileTimer = kslrt.profiler("profile-load")

val profile = profileTimer.measure {
    loadProfile(playerId)
}
logger.info("Profile load average: ${profileTimer.snapshot().averageNanos} ns")
~~~

The cache is for application data, not a way around Paper's threading rules. Do not store mutable Bukkit Player/World/Inventory objects in it for access from asynchronous tasks. TTL is disabled by default. Under concurrent cache misses, the loader may run more than once. The profiler measures elapsed wall-clock time, not CPU time. Measure bottlenecks before optimizing.

## Compatibility policy

- Additive API changes must preserve existing signatures.
- Breaking changes require a deliberate API-version change and migration notes.
- Every addon/module should declare the minimum KSL API version it needs.
- Prefer one documented package namespace per library; avoid wildcard imports that collide.
- Keep custom implementations outside KSL core wherever possible.

## Runtime profiler limits

Named KSLRT profilers are shared by name and use thread-safe counters. The registry is capped at 1,024 names to prevent unbounded memory use from dynamically generated profiler names; call KSLRT.removeProfiler(name) when a dynamic profiler is no longer needed. Runtime snapshots are available through kslRuntimeStats, runtimeStats(), runtimeMetrics, and kslRuntimeSnapshot().
