# KSL Standard Library

KSL scripts receive the standard-library packages through default imports. The helpers below are available without explicit imports in ordinary `.kts` scripts.

## Player helpers

```kotlin
player.tell("Hello")
player.tellRich("<green>Welcome!</green>")
player.hasAnyPermission("vip", "admin")
player.hasAllPermissions("server.play", "server.chat")
player.isInventoryFull()
player.give(item(Material.DIAMOND))
player.feed()
player.healBy(4.0)
player.count(item(Material.DIAMOND))
player.remove(item(Material.DIAMOND), 2)
player.clearStorage()
player.isInWorld("world")
```

- `give(item)` returns the leftover stacks; it does not silently drop them.
- `clearStorage()` clears the 36 storage slots, preserving armor and offhand.
- `healBy(amount)` clamps to the player's maximum health and returns the actual amount restored.

## Inventory helpers

```kotlin
val freeSlots = inventory.emptySlots()
val full = inventory.isFull()
val diamondSlots = inventory.slotsMatching { it.type == Material.DIAMOND }
val removed = inventory.removeMatching(12) { it.type == Material.DIRT }
val taken = inventory.take(0, 16)
val leftovers = inventory.addAll(listOf(itemA, itemB))
```

Inventory reads and mutations must run on the Bukkit server thread. `take` returns a copy of the removed stack or null. `addAll` copies input stacks and returns leftovers that did not fit.

## Item helpers

```kotlin
val reward = ItemStack(Material.DIAMOND_SWORD)
    .withDisplayName("<gold>Explorer's Blade</gold>".mm)
    .withLore("A reward", "Found in the ruins")
    .withUnbreakable()
    .withEnchantment(Enchantment.SHARPNESS, 3)
    .withCustomModelData(1201)
```

Item transformation helpers return cloned stacks and leave the original unchanged. `withCustomModelData` uses Bukkit's legacy custom-model-data API; new resource-pack workflows should prefer modern item-model/data-component support.

## Discord webhook helpers

Configure named webhook URLs under `discord.webhooks` in the plugin config. Keep webhook tokens private.

```kotlin
discordSend("general", "Server started")

val accepted = discordSendAsync("general", "Backup complete").join()

discordEmbedAsync(
    channel = "logs",
    title = "Player joined",
    description = "A player connected",
    fields = listOf(KSLDiscordEmbedField("Player", player.name))
)
```

Asynchronous Discord calls return `CompletableFuture<Boolean>`. The value is true only when Discord returns a successful HTTP status. Avoid calling `.join()` on the server thread; attach a completion handler or use an asynchronous task instead.

By default, Discord mentions are disabled. Explicitly enable them only when a script intentionally needs mention parsing.

## Optional TAB and AuthMe hooks

Hooks are available when the corresponding plugin is installed. They are not hard dependencies.

```kotlin
val tab = hook<KSLTabHook>("tab")
val auth = hook<KSLAuthMeHook>("authme")

if (tab != null) {
    val configuredPrefix = tab.nameTagPrefix(player, original = true)
    tab.setTabPrefix(player, "<green>[Member]</green> ")
    // Pass null to reset a temporary TAB API override.
    tab.setTabPrefix(player, null)
}

if (auth != null) {
    val authenticated = auth.isAuthenticated(player)
    val registered = auth.isRegistered(player.name)
    val lastLogin = auth.lastLoginTime(player.name)
}
```

TAB changes made through its API are temporary and may be reset when TAB reloads or a player reconnects. Use TAB commands/configuration for persistent formatting. AuthMe integration is intentionally read-only: KSL exposes authentication and registration status but does not collect, store, or manipulate passwords.


## Optional Skript integration

KSL provides a built-in, optional bridge to Skript. It uses Skript's public API through Skript's own classloader, so Skript is not a hard dependency and KSL still starts without it.

### Access and diagnostics

```kotlin
val skript = ksl.skriptOrNull()

if (skript?.isAvailable == true) {
    println("Skript version: ${skript.version}")
    val status = skript.diagnostics()
    println("Skript API ready: ${status.isReady}")
}
```

### Execute Skript commands

Only commands registered by Skript are accepted by executeCommand. Calls must run on the server thread; use executeCommandAsync when the caller may be asynchronous.

```kotlin
skript?.executePlayerCommand(player, "warp home")
skript?.executeConsoleCommand("broadcast Server maintenance soon")
skript?.executeCommandAsync(player, "heal")
```

### Read and write Skript variables

Variable names can be passed with or without curly braces. List variables are exposed as defensive map snapshots.

```kotlin
val coins = skript?.getVariable("profile::coins")
skript?.setVariable("profile::coins", 250)
val inventoryData = skript?.getVariableMap("profile::items")
skript?.deleteVariable("profile::temporary")
```

Only global Skript variables are exposed. Values must be supported by Skript's variable serializer. Variable operations are server-thread confined.

### Inspect and reload scripts

```kotlin
val loaded = skript?.loadedScripts().orEmpty()
val isLoaded = skript?.isScriptLoaded("events/join.sk") == true
skript?.requestReloadScript("events/join.sk")
skript?.requestReloadAll()
```

Reload methods report whether Bukkit dispatched the Skript command; they do not guarantee that every line in the target script parsed successfully. Script paths are normalized and traversal paths are rejected.

### Short aliases

The public KSLAPI also exposes `skriptOrNull()`, `requireSkript()`, `isSkriptEnabled()`, `skriptCommand()`, `skriptVariable()`, `setSkriptVariable()`, `deleteSkriptVariable()`, `skriptVariables()`, `skriptScripts()`, `isSkriptScriptLoaded()`, `reloadSkriptScript()`, and `reloadAllSkript()`.



## JSON files and values

KSL scripts can read and write JSON files in their own `scripts/` directory. Paths are checked against traversal and symbolic-link escapes, writes use an atomic replace, and malformed files are preserved (a backup is attempted before an error is raised).

```kotlin
val data = loadJsonObject("data/player.json")
val coins = data?.int("coins", 0) ?: 0

editJsonObject("data/player.json") {
    put("coins", (coins + 10).toJsonElement())
    put("lastSeen", System.currentTimeMillis().toJsonElement())
}

val payload = jsonObjectOf(
    "player" to player.name,
    "online" to true,
    "stats" to mapOf("wins" to 12, "losses" to 3),
    "tags" to listOf("builder", "vip")
)
saveJson("cache/player.json", payload)
```

- `loadJson(file)` returns any JSON root value, or `null` when the file does not exist.
- `loadJsonObject(file)` expects an object root.
- `saveJson(file, value)` writes a `JsonElement` and returns whether the write succeeded.
- `editJsonObject(file) { ... }` preserves untouched keys and creates a missing object file.
- `jsonObjectOf(...)`, `jsonArrayOf(...)`, and `toJsonElement()` convert common Kotlin primitives, maps, arrays and collections.
- `JsonObject.string/int/long/double/bool` provide typed access with defaults; `objectValue` and `arrayValue` read nested structures.
- File operations are synchronous. Do not use them for large files on the Bukkit server thread; use KSL async scheduling when appropriate.


## Security helpers

KSL exposes built-in security utilities through `ru.privateserver.ksl.std.security.*`. They use Java 21 cryptographic APIs and require no additional runtime library.

```kotlin
// SHA-256 is for checksums and integrity checks, not password storage.
val checksum = "important data".sha256()
val valid = "important data".sha256Matches(checksum)

// Passwords use salted PBKDF2-HMAC-SHA256.
// Store only the encoded result; never store the original password.
val passwordHash = hashPassword("correct horse battery staple")
val accepted = verifyPassword("correct horse battery staple", passwordHash)

// File checksum, HMAC, random token and Base64 helpers.
val fileChecksum = File("plugins/KotlinScriptLoader/config.yml").sha256()
val signature = hmacSha256("payload", secretKey)
val token = secureToken(32)
val encoded = base64Encode("hello")
val decoded = base64Decode(encoded)
```

Available methods include `KSLSecurity.sha256(text)`, `sha256File(file)`, `verifySha256(text, hex)`, `hashPassword(password)`, `verifyPassword(password, encodedHash)`, `hmacSha256(message, secret)`, `constantTimeEquals(a, b)`, `randomToken(byteCount)`, `base64Encode(text)` and `base64Decode(text)`. Top-level aliases are available for the common operations.

**Important:** SHA-256 is intentionally fast and must not be used to store passwords. Passwords are hashed with a unique random salt and PBKDF2-HMAC-SHA256; the encoded value contains the algorithm, work factor, salt and derived hash. Password verification uses constant-time comparison. The accepted work-factor range is bounded to prevent untrusted encoded values from requesting excessive CPU time.

Run password hashing/verification and large-file checksums asynchronously; these operations can consume CPU or perform disk I/O. Base64 is encoding, not encryption. HMAC requires a secret key that must be stored outside public configs and never logged. Do not use XOR as password hashing or as a substitute for authenticated encryption. Never put plaintext passwords, API keys or webhook tokens in YAML/JSON files committed to a repository.


## TOML files

TOML support is available through the same script-local `scripts/` directory as JSON.

```kotlin
val settings = loadToml("data/settings.toml")
val port = (settings?.get("port") as? Long)?.toInt() ?: 25565

editToml("data/settings.toml") {
    this["port"] = 25566
    this["motd"] = "Welcome"
    this["database"] = mapOf("host" to "localhost", "enabled" to true)
}

val config = readToml("data/settings.toml")
writeToml("data/other.toml", mapOf("enabled" to true))
updateToml("data/settings.toml") { this["maintenance"] = false }
```

TOML writes are atomic and paths are confined to the script's `scripts/` directory. Malformed input is preserved and backed up before an error is reported. The writer supports scalar values, arrays and nested tables; arrays of tables and null values are rejected explicitly. File operations are synchronous, so large files should be handled asynchronously.

## Short API and Standard Library aliases

The public API exposes `Api`, `KslApi`, `KslAddon`, `KslHook`, `KslScriptInfo` and `kslInstance` as concise alternatives to the full KSL names. The standard import set also includes compact helpers: `sha256sum()`, `passwordHash()`, `passwordCheck()`, `token()`, `jsonObj()`, `jsonArr()` and `json()`. Existing descriptive names remain available and are preferred in larger codebases.
