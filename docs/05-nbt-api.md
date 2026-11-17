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

## Detached NBT document engine

Для подготовки и преобразования SNBT без изменения живого предмета, сущности или блока используйте detached-документ. Он хранит собственный снимок NBT и публикует изменения только после успешного завершения edit-блока.

```kotlin
val document = KSLNBTEngine.parse("""{owner:"Alex",stats:{level:3}}""")

document.edit {
    setStringAt("owner", "Riley")
    setIntAt("stats.level", 4)
}

val level = document.read { intAt("stats.level") }
val snbt = document.snbt()
val backup = document.copy()
```

`KSLNBTEngine.empty()` создаёт пустой документ. `parse()` проверяет размер SNBT, глубину контейнеров и число элементов до передачи текста парсеру Item-NBT-API. Стандартные лимиты — 1 MiB текста, 64 уровня вложенности и 65 536 элементов; их можно настроить через `KSLNBTLimits`.

`edit { ... }` работает с временной копией. Если блок выбрасывает исключение или итоговый SNBT не проходит проверку, исходный документ остаётся неизменным. Значения, возвращённые из `read { ... }`, следует считать снимком; callback получает только read-only view.

Detached engine не привязан к Bukkit и не изменяет live-объекты. Применение результата к ItemStack, Entity или TileState по-прежнему выполняйте через соответствующие `modifyNBT` методы на главном потоке сервера. Самостоятельная проверка SNBT не гарантирует, что произвольные поля допустимы для конкретной версии Minecraft.


## Бинарные файлы .nbt

Для бинарных NBT-файлов в папке `scripts/` используйте `loadNBT`, `saveNBT` и `editNBT`. Это не SNBT-текстовый файл: KSL читает и пишет настоящий бинарный NBT через встроенный Item-NBT-API.

```kotlin
val document = loadNBT("data/player.nbt")
    ?: KSLNBTEngine.empty()

document.edit {
    setString("owner", player.uniqueId.toString())
    setIntAt("stats.level", 12)
}

check(saveNBT("data/player.nbt", document))
```

`loadNBT` возвращает `null`, если файла ещё нет. `editNBT` сам создаёт пустой документ, если файла нет, а затем атомарно сохраняет его. Путь остаётся внутри `scripts/`, расширение должно быть `.nbt`. Стандартный лимит входного файла — 16 MiB; лимиты SNBT по умолчанию остаются 1 MiB текста, 64 уровня и 65 536 элементов. Ошибка записи не заменяет ранее существующий файл.
