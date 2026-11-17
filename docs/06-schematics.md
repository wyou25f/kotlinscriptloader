# Sponge schematics (.schem)

KSL 1.2.2 exposes optional WorldEdit clipboard support. WorldEdit must be installed and enabled for schematic operations; the rest of KSL continues to work without it.

Files are stored under `plugins/KotlinScriptLoader/schematics/`. Only `.schem` files are accepted. File paths are canonicalized and cannot leave that directory. Loaded/captured clipboards are limited to 2,000,000 blocks and input files to 32 MiB.

```kotlin
// Copy the current WorldEdit selection and store it as Sponge schematic.
val clipboard = copySchematicSelection(player)
if (clipboard != null) {
    logger.info("Captured ${clipboard.width}x${clipboard.height}x${clipboard.length}")
    saveSchematic("buildings/spawn.schem", clipboard)
}

// Load a saved schematic and paste it at a location.
val saved = loadSchematic("buildings/spawn.schem")
if (saved != null) {
    pasteSchematic(player, saved, player.location, ignoreAirBlocks = true)
}

// One-call convenience function.
saveSchematicSelection(player, "backups/selection.schem")
```

Available operations:

- `copySchematicSelection(player)` — capture a WorldEdit selection into `KSLClipboard`.
- `loadSchematic(fileName)` — read a Sponge `.schem`; missing files return `null`.
- `saveSchematic(fileName, clipboard)` — atomically write the clipboard.
- `saveSchematicSelection(player, fileName)` — capture and save in one call.
- `pasteSchematic(player, clipboard, location, ignoreAirBlocks)` — paste and record the operation in WorldEdit undo history.

The API uses WorldEdit's public clipboard readers/writers and paste operations. Schematic operations must run on the Bukkit primary thread. Clipboard blocks are copied; entity copying is not enabled by this first version.
