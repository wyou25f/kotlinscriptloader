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

\`\`\`kotlin
val skript = ksl.skriptOrNull()

if (skript?.isAvailable == true) {
    println("Skript version: \${skript.version}")
    val status = skript.diagnostics()
    println("Skript API ready: \${status.isReady}")
}
\`\`\`

### Execute Skript commands

Only commands registered by Skript are accepted by executeCommand. Calls must run on the server thread; use executeCommandAsync when the caller may be asynchronous.

\`\`\`kotlin
skript?.executePlayerCommand(player, "warp home")
skript?.executeConsoleCommand("broadcast Server maintenance soon")
skript?.executeCommandAsync(player, "heal")
\`\`\`

### Read and write Skript variables

Variable names can be passed with or without curly braces. List variables are exposed as defensive map snapshots.

\`\`\`kotlin
val coins = skript?.getVariable("profile::coins")
skript?.setVariable("profile::coins", 250)
val inventoryData = skript?.getVariableMap("profile::items")
skript?.deleteVariable("profile::temporary")
\`\`\`

Only global Skript variables are exposed. Values must be supported by Skript's variable serializer. Variable operations are server-thread confined.

### Inspect and reload scripts

\`\`\`kotlin
val loaded = skript?.loadedScripts().orEmpty()
val isLoaded = skript?.isScriptLoaded("events/join.sk") == true
skript?.requestReloadScript("events/join.sk")
skript?.requestReloadAll()
\`\`\`

Reload methods report whether Bukkit dispatched the Skript command; they do not guarantee that every line in the target script parsed successfully. Script paths are normalized and traversal paths are rejected.

### Short aliases

The public KSLAPI also exposes \`skriptOrNull()\`, \`requireSkript()\`, \`isSkriptEnabled()\`, \`skriptCommand()\`, \`skriptVariable()\`, \`setSkriptVariable()\`, \`deleteSkriptVariable()\`, \`skriptVariables()\`, \`skriptScripts()\`, \`isSkriptScriptLoaded()\`, \`reloadSkriptScript()\`, and \`reloadAllSkript()\`.

