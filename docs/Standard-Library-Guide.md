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
