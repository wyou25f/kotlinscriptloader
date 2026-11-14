# KSL Standard Library — architecture and built-in utilities

**Status:** built-in text, math, time, player, inventory, item, component and world packages are shipped. Custom standard-library code can be provided by KSL addons through addon imports/services or versioned modules registered with `KSLAPI`.

## Built-in packages

These packages are included in the default imports for KSL scripts:

- `ru.privateserver.ksl.std.text.*`: Unicode-safe truncation, legacy color stripping, UUID parsing, whitespace tokenization/normalization, identifier case conversion and Minecraft username-shape validation.
- `ru.privateserver.ksl.std.math.*`: checked clamping, range mapping and validated percentage chance.
- `ru.privateserver.ksl.std.time.*`: explicit seconds/minutes/hours-to-ticks conversion, duration rounding and tick-to-duration conversion.
- `ru.privateserver.ksl.std.player.*`: player messaging, permissions and player-inventory helpers.
- `ru.privateserver.ksl.std.inventory.*`: first matching slot lookup, similar-item counting and bounded item removal for any Bukkit inventory.
- `ru.privateserver.ksl.std.item.*`: safe cloned ItemStack amount/name/lore transformations and empty-stack checks.
- `ru.privateserver.ksl.std.component.*`: literal Adventure text components, MiniMessage parsing and immutable text appending.
- `ru.privateserver.ksl.std.world.*`: block, location and entity-query helpers.\n- `ru.privateserver.ksl.std.json.*`: Kotlin-value conversion, JSON object/array builders and typed object accessors; file persistence is exposed by the script context.

Inventory and ItemStack helpers that inspect or modify Bukkit objects must be called on the server thread. ItemStack transformation helpers return clones and leave the original stack unchanged. `toTextComponent()` treats input as literal text; use `toMiniMessageComponent()` only when the input is intentionally MiniMessage markup.

The security standard library uses Java 21 cryptographic APIs and adds no external runtime dependency. Password hashing and large-file checksums should run asynchronously. SHA-256 is for integrity checks, not password storage; use the PBKDF2 helpers for passwords.\n\nThe JSON standard library uses kotlinx.serialization JSON and is bundled with KSL; scripts receive `kotlinx.serialization.json.*` imports by default. JSON files are restricted to the script `scripts/` directory and saved through atomic replacement. File I/O is synchronous.\n\nThe existing `ru.privateserver.ksl.dsl.*` package remains available. Its `.sec`, `.mins` and `.hours` extensions use checked arithmetic and throw on overflow instead of silently wrapping.

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

The planner itself has no side effects; it only validates and orders modules. The runtime module manager uses it when an addon calls `api.registerStandardLibraryModule(module)`. Register declared dependencies first, then the dependent module. Modules registered during `onLoad` are owned by that addon and are uninstalled in reverse registration order when it unloads. A module can also be removed explicitly with `api.unregisterStandardLibraryModule(id)`, provided no installed module depends on it. See [Custom Standard Library](Custom-Standard-Library.md) for a complete example.

## MesLib2 — межскриптовые библиотеки

В каждом скрипте доступно свойство `meslib2`. Оно использует существующее
owner-aware хранилище экспортов KSL, добавляя namespace, проверку типа и метку
версии. Экспорты принадлежат скрипту-владельцу и автоматически удаляются при unload.

```kotlin
// economy.kts
meslib2.publish("economy", "service", EconomyService(), version = "2.0")

// shop.kts
// @requires: economy
val economy = ml2.resolve<EconomyService>("economy", "service", "2.0")
```

Идентификаторы namespace/name должны соответствовать `[a-z][a-z0-9._-]*`.
Версия — непрозрачная метка с точным сравнением; KSL пока не интерпретирует
SemVer и не загружает внешние артефакты автоматически. Короткие `publish(name, value)` и `provide(name, value)` используют namespace
текущего скрипта. Для получения объекта из собственного namespace используй
`findLocal<T>(name)` / `requireLocal<T>(name)`; версии можно задавать через
`publishLocal` / `provideLocal`. Алиасы фасада: `meslib2`, `ml2`, `lib2`,
`libs`; методы-алиасы: `publish/provide`, `find/resolve`. Старые `export()`, `library<T>()` и
`requireLibrary<T>()` остаются совместимыми.

## kslrt — runtime-инструменты

Пакет `ru.privateserver.ksl.rt.*` добавлен в default imports:

- `KSLCache<K, V>` — потокобезопасный LRU-кэш с лимитом размера и optional TTL;
- `KSLProfiler` — агрегирует количество вызовов, ошибки, общее и максимальное время;
- `kslrt.cache<K, V>(maxSize, expireAfter)` и `kslrt.profiler(name)` — фабрики;
- `ksl`, `kslapi`, `kslApi` и `api` — алиасы установленного `KSLAPI` (старые имена сохранены).

```kotlin
val playerCache = kslrt.cache<String, PlayerData>(maxSize = 2_000)
val profileTimer = kslrt.profiler("profile-load")

val profile = profileTimer.measure {
    loadProfile(playerId)
}
logger.info("Profile load average: ${profileTimer.snapshot().averageNanos} ns")
```

Кэш предназначен для данных приложения, а не для обхода потоковых ограничений
Paper. Не помещай в него изменяемые Bukkit Player/World/Inventory объекты для
доступа из async-задач. TTL по умолчанию выключен; при конкурентном cache miss
loader может выполниться более одного раза. Профилировщик измеряет elapsed
wall-clock time, а не CPU time. Сначала измеряй узкие места, затем оптимизируй.

## Compatibility policy

- Additive API changes must preserve existing signatures.
- Breaking changes require a deliberate API-version change and migration notes.
- Every addon/module should declare the minimum KSL API version it needs.
- Prefer one documented package namespace per library; avoid wildcard imports that collide.
- Keep custom implementations outside KSL core wherever possible.
