# NBT API

KSL bundles and relocates Item-NBT-API, so a separate NBTAPI server plugin is not required.

## Custom item data

```kotlin
val item = ItemStack(Material.DIAMOND_SWORD)
item.modifyNBT {
    setString("owner", player.uniqueId.toString())
    setInt("level", 3)
    getOrCreateCompound("stats").setDouble("damage", 12.5)
}
item.readNBT {
    val owner = string("owner")
    val level = int("level") ?: 0
}
```

On Minecraft 1.20.5+, item `modifyNBT/readNBT` access the `custom_data` component, not vanilla item components. Avoid re-applying an `ItemMeta` snapshot captured before the NBT edit: that older snapshot can overwrite the newer item data. Make NBT edits before obtaining/editing ItemMeta, or perform the item-meta work first and then the NBT edit.

## Vanilla item components

```kotlin
item.modifyComponents {
    setString("minecraft:custom_name", """{"text":"Example"}""")
}
item.readComponents {
    val customName = string("minecraft:custom_name")
}
```

Component values must use the format expected by the corresponding Minecraft component. Component editing is a low-level operation: malformed data can make the item invalid or produce unexpected in-game behavior, so validate values against the target Minecraft version.

## Entities and block entities

```kotlin
entity.modifyNBT { setBoolean("Silent", true) }
entity.modifyPersistentNBT { setString("owner", player.uniqueId.toString()) }

val state = block.state
if (state is TileState) {
    state.modifyNBT { setBoolean("Lock", true) }
}
```

Entity `modifyNBT/readNBT` work with vanilla entity NBT. `PersistentNBT` methods work with the persistent custom-data compound. Vanilla block NBT exists only on block entities (chests, furnaces, signs, etc.), not ordinary blocks.

## Compound operations

Supported values: string, byte, short, int, long, float, double, boolean, byte/int/long arrays, nested compounds, key enumeration, existence checks, removal, SNBT parsing, and compound merging.

NBT operations must run on the server primary thread. Use `runSync { ... }` when calling them from asynchronous work.

The legacy `ItemStack.setTag/getTag/hasTag` and typed PDC tag APIs remain unchanged.