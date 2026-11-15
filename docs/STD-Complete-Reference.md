# KSL Standard Library — Complete Reference

This guide is the practical reference for the built-in Kotlin Standard Library shipped with KotlinScriptLoader. It explains what each package provides, how to use it, what the helpers return, and which runtime constraints still apply.

The reference is written against the repository source. When a code sample and the implementation ever disagree, the source and tests for the exact KSL build are authoritative.

## 1. What the Standard Library is

KSL STD is a collection of ordinary Kotlin functions, extension functions, value helpers, and small utility types under the package prefix:

    ru.privateserver.ksl.std

It is not a second programming language and it does not replace Kotlin. Its purpose is to remove repetitive server-side code and provide consistent, checked behavior for common operations.

The current built-in packages are:

| Package | Purpose | Typical receiver / types |
|---|---|---|
| std.text | Text normalization, UUID parsing, naming helpers | String |
| std.math | Checked numeric operations, interpolation, random values | Int, Long, Double |
| std.time | Tick and Duration conversion | Int, Long, Duration |
| std.player | Player messaging, permissions, inventory and status helpers | Player |
| std.inventory | Inventory lookup, capacity, counting, transfer and removal | Inventory |
| std.item | Immutable-style ItemStack transformations | ItemStack |
| std.component | Adventure Component and MiniMessage conversion | String, Component |
| std.world | Locations, blocks, nearby entities and regions | World, Location, Block |
| std.json | Kotlin-to-JSON conversion and typed JSON access | Any?, JsonObject, JsonArray |
| std.toml | TOML codec and typed map accessors | Map<String, Any?> |
| std.security | Hashes, password hashing, HMAC, tokens and Base64 | String, File, KSLSecurity |
| std.validation | Composable validation rules and result values | Any Kotlin type |

KSL scripts receive these packages through the script definition's default imports. A wildcard import only refers to its named package; it is not recursive. The current script metadata explicitly lists the built-in subpackages.

Addon authors can use the same compiled classes when the KSL JAR is present as a compile-only dependency. Their ordinary Kotlin source files should import the package explicitly.

## 2. General rules

### 2.1 STD does not remove Kotlin or Bukkit rules

STD helpers are convenience functions. They do not make unsafe work safe, and they do not change the thread requirements of the underlying Bukkit API.

Use the server thread for operations that inspect or mutate live Bukkit state, including:

- player inventory and equipment;
- health, food, saturation and player state;
- world blocks and nearby entities;
- location-based world queries;
- inventory contents and ItemStack mutation when the stack is part of a live inventory.

Pure string, math, JSON value conversion, and most validation operations can run on any thread. Password hashing and large-file hashing are CPU or I/O work and should be moved off the server thread.

### 2.2 Return values matter

Several helpers deliberately return information rather than hiding it:

- Player.give(item) returns the stacks that did not fit.
- Player.giveOrDrop(item) returns the number of items dropped.
- Inventory.removeSimilar(...) and Inventory.removeMatching(...) return the number actually removed.
- Player.healBy(...) returns the amount of health actually restored.
- ItemStack transformations return clones, leaving the input object unchanged.
- Validation returns a result containing every failed rule instead of throwing for ordinary invalid input.

Check these return values. Do not assume an inventory accepted an item just because give() completed.

### 2.3 Exceptions indicate programmer or boundary errors

Invalid ranges, negative amounts, non-finite floating point values, unsupported JSON/TOML values, and arithmetic overflow are rejected explicitly. Treat these as useful boundary checks, not as silent coercion.

## 3. Text helpers — std.text

### 3.1 Unicode-safe truncation

**String.truncateCodePoints(maxCodePoints, suffix = "…")**

Limits a string by Unicode code points rather than UTF-16 code units. This prevents a surrogate pair from being split. The suffix counts toward the maximum length when truncation is necessary.

~~~kotlin
val shortTitle = longTitle.truncateCodePoints(32)
val label = playerName.truncateCodePoints(16, "…")
~~~

Behavior:

- A negative maximum is rejected.
- A maximum of zero returns an empty string.
- If the original text already fits, it is returned unchanged.
- If the suffix itself is longer than the available maximum, only the first permitted code points of the suffix are returned.

This helper counts Unicode code points. It does not count grapheme clusters, so a multi-code-point emoji sequence may still be visually composed of more than one code point.

### 3.2 Legacy color codes and UUID parsing

| Function | Behavior |
|---|---|
| String.stripLegacyColors() | Removes legacy Bukkit color and style codes, including the section-sign hex sequence format. |
| String.toUuidOrNull() | Trims input, accepts canonical UUID text, and returns null for invalid input. |
| String.isUuid() | True when toUuidOrNull() succeeds. |

~~~kotlin
val plain = "§aWelcome§r player".stripLegacyColors()
val id = args.firstOrNull()?.toUuidOrNull()

if (id == null) {
    sender.sendMessage("A valid UUID is required.")
    return@registerCommand
}
~~~

UUID parsing is intentionally strict. It does not accept abbreviated UUID forms or arbitrary strings that Java's UUID parser may otherwise normalize.

### 3.3 Word splitting and whitespace

| Function | Behavior |
|---|---|
| String.words() | Splits on Unicode whitespace, drops empty pieces, and returns a list. |
| String.normalizeWhitespace() | Joins words with one ordinary space and trims both ends. |

~~~kotlin
val parts = "  one\u00A0two  three ".words()
val normalized = "  one\u00A0two  three ".normalizeWhitespace()
// parts: ["one", "two", "three"]
// normalized: "one two three"
~~~

The implementation recognizes ordinary whitespace and Unicode space characters such as non-breaking space.

### 3.4 Case-insensitive checks

| Function | Meaning |
|---|---|
| containsIgnoreCase(other) | Case-insensitive substring test. |
| startsWithIgnoreCase(prefix) | Case-insensitive prefix test. |
| endsWithIgnoreCase(suffix) | Case-insensitive suffix test. |
| removePrefixIgnoreCase(prefix) | Removes a matching prefix while preserving the remaining original text. |
| removeSuffixIgnoreCase(suffix) | Removes a matching suffix while preserving the remaining original text. |

~~~kotlin
if (commandName.startsWithIgnoreCase("ksl-")) {
    val target = commandName.removePrefixIgnoreCase("ksl-")
}
~~~

These helpers do not create a lowercase copy of the original string.

### 3.5 Identifier naming and Minecraft usernames

| Function | Behavior |
|---|---|
| String.toSnakeCase() | Converts common camelCase, PascalCase, whitespace and hyphen-separated names to snake_case. |
| String.toCamelCase() | Converts underscore-, hyphen- or whitespace-separated words to lower camelCase. |
| String.isMinecraftUsername() | Checks the vanilla username shape: 3–16 ASCII letters, digits or underscores. |

~~~kotlin
val configKey = "HTTPServer-ID".toSnakeCase()
val propertyName = "welcome_message".toCamelCase()
val acceptableName = input.isMinecraftUsername()
~~~

The username helper checks the character and length shape only. It does not check whether a name is registered, available, authenticated, or permitted on the server.

## 4. Math helpers — std.math

### 4.1 Clamping

| Function | Meaning |
|---|---|
| Int.clamped(minimum, maximum) | Clamps an Int to an inclusive range. |
| Long.clamped(minimum, maximum) | Clamps a Long to an inclusive range. |
| Double.clamped(minimum, maximum) | Clamps a Double after checking that the value is not NaN and bounds are ordered and not NaN. |

~~~kotlin
val page = requestedPage.clamped(1, 20)
val volume = requestedVolume.clamped(0.0, 1.0)
~~~

Minimum must not exceed maximum. Double infinities are not rejected by clamped() itself; NaN is rejected. Use doubleRange validation when a finite range is required.

### 4.2 Mapping and interpolation

| Function | Meaning |
|---|---|
| Double.mapToRange(inputMin, inputMax, outputMin, outputMax) | Maps a value linearly from one range to another. Values outside the input range are extrapolated. |
| lerp(start, end, progress) | Linear interpolation; progress outside 0..1 extrapolates. |
| inverseLerp(start, end, value, clamp = false) | Returns the relative position of value in a range; optionally clamps the result to 0..1. |

~~~kotlin
val percent = progress.mapToRange(0.0, 100.0, 0.0, 1.0)
val healthAtHalf = lerp(0.0, maximumHealth, 0.5)
val progressFraction = inverseLerp(minimum, maximum, current, clamp = true)
~~~

The functions validate finite inputs and reject an empty input range. mapToRange also checks the final result for overflow into a non-finite Double. inverseLerp can map a reversed range; its result follows the direction of that range.

### 4.3 Random values

| Function | Range |
|---|---|
| randomChance(percent) | Probability from 0.0 through 100.0 inclusive. |
| randomInt(minimumInclusive, maximumExclusive) | Int in a half-open range. |
| randomLong(minimumInclusive, maximumExclusive) | Long in a half-open range. |
| randomDouble(minimumInclusive, maximumExclusive) | Finite Double in a half-open range. |

~~~kotlin
if (randomChance(15.0)) {
    giveBonusReward(player)
}

val slot = randomInt(0, 9)       // 0 through 8
val seed = randomLong(1L, 100L) // 1 through 99
val multiplier = randomDouble(0.8, 1.2)
~~~

The lower bound is inclusive and the upper bound is exclusive. Every random range requires minimum < maximum. Double bounds must be finite. randomChance rejects NaN, infinities, and percentages outside 0..100. It uses ThreadLocalRandom; it is not a cryptographic random generator.

## 5. Time helpers — std.time

Minecraft server scheduling uses ticks. KSL time conversions use the conventional 20 ticks per second, which means one tick represents 50 milliseconds in this conversion model.

### 5.1 Direct tick values

- Int.ticks() converts an Int to Long.
- Long.ticks() returns the Long unchanged.

These methods do not clamp negative values. They are numeric conversions, not a validation policy for a scheduler call.

### 5.2 Seconds, minutes, hours, days and weeks

Both Int and Long overloads exist:

| Function | Ticks per unit |
|---|---:|
| secondsToTicks() | 20 |
| minutesToTicks() | 1,200 |
| hoursToTicks() | 72,000 |
| daysToTicks() | 1,728,000 |
| weeksToTicks() | 12,096,000 |

~~~kotlin
val timeout = 30.secondsToTicks()
val dailyReset = 1.daysToTicks()
val weeklyReset = 1.weeksToTicks()
~~~

Conversions use exact multiplication. If the result cannot fit in a Long, an ArithmeticException is thrown instead of wrapping around.

### 5.3 Millisecond rounding

Both Int and Long overloads are available:

- millisecondsToTicksFloor() rounds toward negative infinity.
- millisecondsToTicksCeiling() rounds toward positive infinity.

~~~kotlin
val minimumDelay = 51L.millisecondsToTicksCeiling() // 2 ticks
val fullTicks = 51L.millisecondsToTicksFloor()      // 1 tick
~~~

The distinction matters for negative numbers as well. The implementation follows mathematical floor/ceiling rather than truncating toward zero.

### 5.4 java.time.Duration

| Function | Behavior |
|---|---|
| Duration.toTicksCeiling() | Converts a non-negative duration, rounding any partial tick upward. |
| Duration.toTicksFloor() | Converts a non-negative duration, rounding partial ticks downward. |
| Long.ticksToDuration() | Converts ticks to a Duration using 50 milliseconds per tick. |

~~~kotlin
val duration = Duration.ofMillis(1250)
val noEarlyRun = duration.toTicksCeiling()
val wholeTicks = duration.toTicksFloor()
val asDuration = 40L.ticksToDuration()
~~~

Duration conversions reject negative durations and report arithmetic overflow.

## 6. Player helpers — std.player

Player helpers operate on a live Bukkit Player. Call them on the server thread.

### 6.1 Messaging and permissions

| Function | Behavior |
|---|---|
| Player.tell(String) | Sends literal text. MiniMessage markup is not parsed. |
| Player.tell(Component) | Sends an Adventure Component. |
| Player.tellRich(String) | Parses MiniMessage and sends the resulting Component. |
| Player.hasAnyPermission(vararg nodes) | True when at least one non-blank permission node is granted. |
| Player.hasAllPermissions(vararg nodes) | True when the list is non-empty and every node is non-blank and granted. |

~~~kotlin
player.tell("This is literal text.")
player.tellRich("<green>Purchase complete</green>")
if (player.hasAnyPermission("shop.admin", "shop.moderator")) {
    openAdministrationMenu(player)
}
~~~

The distinction between tell and tellRich is deliberate. User-controlled text should usually be sent literally, not interpreted as MiniMessage.

### 6.2 Inventory capacity and item delivery

| Function | Behavior |
|---|---|
| isInventoryFull() | True if there is no completely empty storage slot. Partial stacks may still have room. |
| canFit(item, amount = item.amount) | Checks whether the requested amount fits in player storage, including partial similar stacks. |
| give(item) | Adds a clone to storage; returns leftover stacks that did not fit. |
| giveOrDrop(item) | Adds a clone and naturally drops leftovers at the player's location; returns the dropped item count. |

~~~kotlin
val reward = ItemStack(Material.DIAMOND, 32)

if (player.canFit(reward)) {
    val leftovers = player.give(reward)
    check(leftovers.isEmpty())
} else {
    val droppedCount = player.giveOrDrop(reward)
    player.tell("Dropped $droppedCount items because your inventory was full.")
}
~~~

The input ItemStack is not handed directly to the inventory; the helpers use clones. give() never drops the remainder. giveOrDrop() explicitly does.

### 6.3 Counting and removing items

| Function | Behavior |
|---|---|
| count(item) | Counts stacks similar to the supplied ItemStack in the player's storage inventory. |
| remove(item, amount) | Removes up to the requested amount of similar items and returns the amount actually removed. |
| countItems(predicate) | Counts the amount of all non-empty storage items matching a predicate. |
| removeItems(predicate) | Removes every non-empty storage stack matching a predicate and returns the total amount removed. |
| removeItems(amount, predicate) | Removes at most the requested amount from matching storage stacks and returns the actual amount removed. |
| clearStorage() | Returns cloned non-empty storage stacks, then clears storage slots. Armor and offhand are preserved. |

~~~kotlin
val diamonds = player.count(ItemStack(Material.DIAMOND))
val removed = player.remove(ItemStack(Material.DIAMOND), 4)

val dirtCount = player.countItems { it.type == Material.DIRT }
val consumed = player.removeItems(12) { it.type == Material.DIRT }
~~~

The predicate overloads operate on storage contents, not armor or offhand slots. removeItems(0, predicate) removes nothing. Negative amounts are rejected.

### 6.4 Food, health and world

| Function | Behavior |
|---|---|
| feed(food = 20, saturation = 5f) | Sets food and saturation within supported ranges. |
| healBy(amount) | Heals up to the current maximum health and returns the actual health restored. |
| isInWorld(worldName) | Checks the player's current world name without case sensitivity. |

~~~kotlin
player.feed(food = 20, saturation = 8f)
val restored = player.healBy(4.0)
val isInSpawnWorld = player.isInWorld("world")
~~~

feed rejects non-finite saturation and clamps food to 0..20. healBy rejects negative or non-finite amounts; it does not resurrect a dead player or bypass other game rules.

## 7. Inventory helpers — std.inventory

Inventory helpers operate on Bukkit Inventory objects and must be called on the server thread when the inventory is live.

### 7.1 Similar-item search and capacity

| Function | Behavior |
|---|---|
| firstSimilarSlot(item) | First slot with a non-empty stack similar to item, or -1. |
| spaceFor(item) | Number of additional similar items that can fit, accounting for partial stacks and maximum stack sizes. |
| canFit(item, amount = item.amount) | Whether the requested amount fits. |
| hasRoomFor(item) | Whether at least one more similar item can fit. |
| emptySlots() | Number of completely empty slots. |
| isFull() | True if no completely empty slot exists. |

Important: isFull() and emptySlots() describe completely empty slots. They do not say whether similar partial stacks can accept more items. Use spaceFor() or canFit() for capacity.

### 7.2 Counting and matching

| Function | Behavior |
|---|---|
| countSimilar(item) | Counts the total amount in similar stacks. |
| firstMatchingSlot(predicate) | First non-empty stack matching the predicate, or -1. |
| countMatching(predicate) | Counts matching non-empty stacks (stack count, not item amount). |
| slotsMatching(predicate) | Returns slot indices of matching non-empty stacks. |

countMatching counts matching slots/stacks. countSimilar counts the total number of items across similar stacks. Do not confuse the two.

### 7.3 Removing and transferring items

| Function | Behavior |
|---|---|
| removeSimilar(item, amount) | Removes up to amount similar items and returns the amount actually removed. |
| removeMatching(amount, predicate) | Removes up to amount items from matching stacks and returns the amount removed. |
| take(slot, amount) | Removes up to amount from one slot and returns a new ItemStack copy, or null. |
| addAll(items) | Adds copies of the supplied stacks and returns copies of leftovers. |

~~~kotlin
val token = ItemStack(Material.EMERALD)
val capacity = inventory.spaceFor(token)
val removed = inventory.removeSimilar(token, 3)

val taken = inventory.take(0, 8)
val leftovers = inventory.addAll(rewards)
~~~

The original ItemStack passed to removeSimilar() is not modified. take() returns a new stack and updates only the selected slot. addAll() clones input and leftover stacks. Invalid slots and negative amounts are rejected.

## 8. ItemStack helpers — std.item

The item helpers follow an immutable-style convention: transformations return a clone, leaving the original ItemStack unchanged.

| Function | Behavior |
|---|---|
| copyWithAmount(amount) | Clone with a new non-negative amount. Zero is allowed. |
| isEmptyStack() | True for AIR or amount <= 0. |
| displayNameOrNull() | Returns a custom Adventure display name, or null. |
| withDisplayName(Component) | Clone with the supplied Adventure display name. |
| withDisplayName(String) | Clone with literal text as the display name. |
| withLore(List<Component>) | Clone with the supplied lore lines. |
| withLore(vararg String) | Clone with plain-text lore lines. |
| withUnbreakable(Boolean = true) | Clone with unbreakable flag changed. |
| withCustomModelData(Int?) | Clone with legacy custom model data. |
| withEnchantment(enchantment, level = 1, ignoreLevelRestriction = false) | Clone with an enchantment added. |
| withoutEnchantment(enchantment) | Clone with the enchantment removed. |
| hasEnchantment(enchantment) | Tests whether item metadata contains the enchantment. |

~~~kotlin
val original = ItemStack(Material.DIAMOND_SWORD)
val reward = original
    .withDisplayName("<gold>Explorer's Blade</gold>".toTextComponent())
    .withLore("A rare reward", "Found in the ruins")
    .withUnbreakable()
    .withEnchantment(Enchantment.SHARPNESS, 3)
~~~

Strings passed to withDisplayName(String) and withLore(String) are literal text. Convert intentionally formatted text with toMiniMessageComponent() and pass the Component overload.

withEnchantment requires a positive level. The optional ignoreLevelRestriction flag is passed to Bukkit's enchantment metadata API. withCustomModelData uses the legacy Bukkit custom-model-data API; new resource-pack and item-model pipelines should prefer current Paper data-component or item-model facilities.

If an ItemStack has no metadata representation for a requested operation, the clone is returned without a metadata change.

## 9. Adventure Components — std.component

| Function | Behavior |
|---|---|
| String.toTextComponent() | Creates a literal Component; MiniMessage-looking tags remain visible text. |
| String.toMiniMessageComponent() | Parses MiniMessage markup into a Component. |
| Component.appendText(text) | Appends literal text and returns a new Component tree. |

~~~kotlin
val safeText = input.toTextComponent()
val formatted = "<green>Welcome, <bold>builder</bold>!</green>".toMiniMessageComponent()
val combined = formatted.appendText(" — enjoy the server")
player.tell(combined)
~~~

Choose the parser intentionally. Do not parse untrusted player input as MiniMessage unless the feature explicitly requires users to author markup and you have designed suitable restrictions.

## 10. World and Location helpers — std.world

World and Location helpers use Paper/Bukkit world state. Use the server thread for world, block, entity and chunk operations.

### 10.1 Blocks and locations

| Function | Behavior |
|---|---|
| World.blockAt(x, y, z) | Gets a block by integer block coordinates. |
| Location.blockOrNull() | Gets the block at the location, or null if it has no world. |
| Location.offset(x, y, z) | Returns a cloned Location shifted by the offsets. |
| Location.blockPosition() | Returns block coordinates as Triple<Int, Int, Int>. |
| Location.isChunkLoaded() | Checks the containing chunk without loading it. |
| Location.isWithin(other, radius) | Checks same-world distance using squared distance. |
| Location.isInside(min, max) | Inclusive axis-aligned region check; other worlds do not match. |
| Block.isTypeAny(vararg materials) | True when the block type matches any supplied material. |

~~~kotlin
val target = player.location.offset(0.0, 2.0, 0.0)
val block = target.blockOrNull()
val position = target.blockPosition()

if (target.isChunkLoaded()) {
    // The containing chunk is already loaded.
}
~~~

isWithin requires a finite, non-negative radius. It returns false when worlds differ or the receiver has no world. isInside is inclusive and accepts min/max locations in either coordinate order, provided all locations share the same world.

### 10.2 Nearby players and entities

| Function | Behavior |
|---|---|
| World.playersInRadius(center, radius) | Returns online players within a spherical radius. |
| World.entitiesInRadius(center, radius) | Returns entities in a cube with radius on each axis. |
| World.entitiesInBox(BoundingBox) | Returns entities inside an axis-aligned bounding box. |
| World.entitiesInBox(center, x, y = x, z = x) | Returns entities in a box around center. |

~~~kotlin
val nearbyPlayers = world.playersInRadius(player.location, 12.0)
val nearbyEntities = world.entitiesInBox(player.location, 8.0, 4.0, 8.0)
~~~

The playersInRadius helper delegates to Paper's nearby-player query and returns a snapshot list. entitiesInRadius uses equal extents in all three axes and is therefore a cube query, despite the method's radius-oriented name. Use entitiesInBox when you need explicit independent dimensions or a BoundingBox.

A center location must belong to the World receiver. Radius and box extents must be finite and non-negative. These helpers do not load chunks on purpose.

## 11. JSON values — std.json

KSL bundles kotlinx.serialization JSON. The STD package provides conversion helpers and typed accessors. File persistence is exposed separately by the script context; see [Data Formats](Data-Formats.md).

### 11.1 Convert Kotlin values

**Any?.toJsonElement()** converts these values recursively:

- null, JsonElement, String, Char and Boolean;
- Byte, Short, Int, Long, finite Float and finite Double;
- other Number values only when their textual representation is a valid JSON number;
- Enum values as their enum name;
- maps with String keys;
- Iterable, Sequence, object arrays and primitive arrays.

Nested maps and collections are converted recursively. A map with a non-string key, non-finite floating-point value, unsupported class, or invalid Number representation is rejected.

~~~kotlin
val payload = mapOf(
    "name" to player.name,
    "online" to true,
    "balance" to 250L,
    "tags" to listOf("builder", "vip"),
    "stats" to mapOf("wins" to 12, "losses" to 3)
).toJsonElement()
~~~

### 11.2 Build JSON objects and arrays

| Function | Result |
|---|---|
| jsonObjectOf(vararg Pair<String, Any?>) | JsonObject |
| jsonArrayOf(vararg Any?) | JsonArray |

~~~kotlin
val profile = jsonObjectOf(
    "name" to player.name,
    "level" to 12,
    "rank" to "builder"
)
val recent = jsonArrayOf("join", "vote", "purchase")
~~~

### 11.3 Read JSON object fields

Default-friendly object functions:

- JsonObject.string(key, default = "")
- JsonObject.int(key, default = 0)
- JsonObject.long(key, default = 0L)
- JsonObject.double(key, default = 0.0)
- JsonObject.bool(key, default = false)
- JsonObject.objectValue(key)
- JsonObject.arrayValue(key)
- JsonObject.hasKey(key)

Required accessors:

- requireString(key)
- requireInt(key)
- requireLong(key)
- requireDouble(key)
- requireBoolean(key)

Required accessors fail when the field is missing or has the wrong JSON type. requireDouble also rejects non-finite results. The default-friendly accessors return the supplied default when the field is missing or cannot be read as that exact primitive type.

JSON primitive strings are not silently coerced into numbers or booleans.

### 11.4 Read JSON arrays and primitive values

| Function | Returns |
|---|---|
| JsonElement.stringOrNull() | String?; only JSON string primitives are accepted. |
| JsonElement.intOrNull() | Int? |
| JsonElement.longOrNull() | Long? |
| JsonElement.doubleOrNull() | Double? |
| JsonElement.booleanOrNull() | Boolean? |
| JsonArray.stringAt(index) | String? |
| JsonArray.intAt(index) | Int? |
| JsonArray.longAt(index) | Long? |
| JsonArray.doubleAt(index) | Double? |
| JsonArray.boolAt(index) | Boolean? |

Array accessors return null for an invalid index or an incompatible type. They do not throw merely because the requested element is absent.

### 11.5 Script-local JSON files

The JSON codec above works on values. Script file operations are context methods such as loadJson, loadJsonObject, saveJson and editJsonObject. They use script-relative paths and the data-format path policy. File I/O is synchronous; large files should not be processed from a latency-sensitive server-thread callback.

See [Data Formats](Data-Formats.md) for the exact file operations, atomic writes, schema validation and migration behavior.

## 12. TOML — std.toml

The TOML codec is provided by **KSLTomlCodec** and uses TOML maps rather than a JSON-style element tree.

### 12.1 Parse, encode and copy

| Function | Behavior |
|---|---|
| KSLTomlCodec.parse(source) | Parses TOML text and returns a mutable-friendly nested Map. Parse errors are rejected. |
| KSLTomlCodec.encode(values) | Encodes a nested map into TOML text. |
| KSLTomlCodec.mutableCopy(source) | Recursively copies TOML tables/maps and lists into mutable maps and lists. |

~~~kotlin
val values = KSLTomlCodec.parse(
    """
    [shop]
    enabled = true
    price = 125
    """.trimIndent()
)

val shop = values.tomlTable("shop").orEmpty()
val enabled = shop.tomlBoolean("enabled", default = false)
val price = shop.tomlLong("price", default = 0L)
~~~

The encoder supports strings, characters, booleans, integer types, BigInteger, BigDecimal, finite Float/Double values, TOML date/time types and lists of supported scalar values. It emits nested maps as TOML tables.

The current writer rejects null values, arrays containing null values, inline-table values within arrays, unsupported classes and non-finite floating point values. Do not assume every arbitrary Kotlin object can be encoded.

### 12.2 Typed TOML map accessors

| Function | Behavior |
|---|---|
| tomlHas(key) | Tests whether the key exists. |
| tomlString(key, default = "") | Reads a String or returns default. |
| tomlLong(key, default = 0L) | Reads an exact integral value that fits Long. |
| tomlInt(key, default = 0) | Reads an exact integral value that fits Int. |
| tomlDouble(key, default = 0.0) | Reads a Number as Double. |
| tomlBoolean(key, default = false) | Reads a Boolean. |
| tomlTable(key) | Reads a nested map. |
| tomlList(key) | Reads a list or returns an empty list. |
| putTomlIfAbsent(key, value) | Adds a key only if it does not already exist. |
| removeToml(key) | Removes and returns the previous value. |

~~~kotlin
val settings = KSLTomlCodec.parse(source).toMutableMap()
settings.putTomlIfAbsent("enabled", true)
settings.removeToml("temporary")
val encoded = KSLTomlCodec.encode(settings)
~~~

tomlInt and tomlLong avoid truncating a floating point number into an integer. They accept only supported exact integral Number types and reject overflow by returning the supplied default.

### 12.3 Script-context aliases

KSL exposes convenience extensions on BukkitScriptContext:

- readToml(fileName) delegates to loadToml(fileName).
- writeToml(fileName, values) delegates to saveToml(fileName, values).
- updateToml(fileName) delegates to editToml(fileName).

These are aliases for script-local file operations. Follow the path and thread rules in [Data Formats](Data-Formats.md).

## 13. Security helpers — std.security

Security helpers are grouped under **ru.privateserver.ksl.std.security** and the **KSLSecurity** object. They use Java cryptographic APIs and do not add an external runtime dependency.

### 13.1 Checksums and integrity

| Function | Behavior |
|---|---|
| KSLSecurity.sha256(text) | SHA-256 of UTF-8 text, lower-case hexadecimal. |
| KSLSecurity.sha256File(file) | Streaming SHA-256 file digest. |
| KSLSecurity.verifySha256(text, expectedHex) | Verifies the digest with constant-time byte comparison. |
| String.sha256() | Text checksum alias. |
| File.sha256() | File checksum alias. |
| String.sha256Matches(expectedHex) | Checksum verification alias. |

SHA-256 is suitable for integrity checks and fingerprints. It is deliberately fast and must not be used as a password hashing algorithm.

### 13.2 Password hashing

The password format uses PBKDF2-HMAC-SHA256, a random salt and a derived 32-byte hash.

- Default work factor: 210,000 iterations.
- Accepted work factor: 100,000 through 2,000,000 iterations.
- Salt generated by KSL: 16 random bytes.
- Stored representation contains the algorithm label, iteration count, salt and derived hash.

Functions:

- KSLSecurity.hashPassword(CharArray, iterations = default)
- KSLSecurity.hashPassword(String, iterations = default)
- KSLSecurity.verifyPassword(CharArray, encodedHash)
- KSLSecurity.verifyPassword(String, encodedHash)
- top-level hashPassword(password)
- top-level verifyPassword(password, encodedHash)

~~~kotlin
val encodedHash = hashPassword(passwordInput)
val accepted = verifyPassword(passwordInput, encodedHash)
~~~

Prefer the CharArray overload when the caller can supply one. The String overload creates and clears an intermediate character array, but an immutable String cannot be erased from memory by this API.

Password hashing and verification consume CPU. Run them asynchronously, never inside a synchronous event callback on the main server thread. Never log or store the original password.

### 13.3 HMAC, secure comparison, tokens and Base64

| Function | Behavior |
|---|---|
| KSLSecurity.hmacSha256(message, secret) | HMAC-SHA256 authentication code. |
| KSLSecurity.constantTimeEquals(first, second) | Constant-time byte comparison for the UTF-8 representations. |
| KSLSecurity.randomToken(byteCount = 32) | Cryptographically random URL-safe Base64 token; byteCount must be 16..256. |
| KSLSecurity.base64Encode(value) | Standard Base64 encoding of UTF-8 text. |
| KSLSecurity.base64Decode(value) | Decodes Base64 as valid UTF-8 or returns null. |

Top-level aliases include hmacSha256, secureToken, base64Encode and base64Decode. Root-package aliases also include token, secureToken, passwordHash, passwordCheck, passwordVerify and String.passwordMatches.

Base64 is encoding, not encryption. HMAC requires a secret key that must remain private. Do not use SHA-256, Base64 or a home-made string transformation as a replacement for authenticated encryption or password hashing.

## 14. Validation helpers — std.validation

The validation package provides reusable, composable rules. Ordinary invalid input is represented as data rather than immediately throwing an exception.

### 14.1 Core types

- **KSLValidationIssue** stores a stable rule name and a developer-facing message.
- **KSLValidationRule<T>** stores a rule name, message and predicate.
- **KSLValidator<T>** evaluates a list of rules and accumulates failures.
- **KSLValidationResult<T>** is either Valid(value) or Invalid(issues).

Result helpers:

| Member | Behavior |
|---|---|
| isValid | True for Valid. |
| errors | Empty for Valid; all issues for Invalid. |
| getOrNull() | Value for Valid, otherwise null. |
| getOrThrow() | Value for Valid; throws IllegalArgumentException with all rule messages for Invalid. |
| map(transform) | Transforms a Valid value; preserves an Invalid result. |

### 14.2 Built-in rules

| Function | Type | Behavior |
|---|---|---|
| notBlank(message) | String | Rejects blank input. |
| codePointLength(minimum, maximum, message) | String | Checks Unicode code-point length, not UTF-16 code-unit length. |
| matches(pattern, message) | String | Requires the entire string to match the Regex. |
| intRange(range, message) | Int | Checks an inclusive IntRange. |
| longRange(range, message) | Long | Checks an inclusive LongRange. |
| doubleRange(minimum, maximum, message) | Double | Requires a finite value within inclusive finite bounds. |
| validationRule(name, message, predicate) | T | Creates a custom rule. |
| kslValidator(vararg rules) | T | Creates a validator. |
| value.validateWith(validator) | T | Validates the receiver. |

Invalid rule definitions are rejected when the rule is created: blank rule names/messages, negative string minimum, reversed ranges, and non-finite Double bounds are not allowed.

### 14.3 Compose rules and collect all errors

~~~kotlin
val usernameRules = kslValidator(
    notBlank("Enter a username."),
    codePointLength(3, 16, "Username must be 3 to 16 code points."),
    matches(Regex("[A-Za-z0-9_]+"), "Use letters, digits and underscores only.")
)

val result = rawUsername.validateWith(usernameRules)

when (result) {
    is KSLValidationResult.Valid -> createProfile(result.value)
    is KSLValidationResult.Invalid -> {
        result.errors.forEach { issue ->
            player.tell(issue.message)
        }
    }
}
~~~

Every rule is evaluated, so a user can receive more than one actionable message in one validation pass. Rule order determines the issue order.

Validators are immutable from the caller's perspective. **and(rule)** and **and(otherValidator)** return new validators; the original validator is not modified.

### 14.4 Custom rules

~~~kotlin
val priceRules = kslValidator(
    doubleRange(0.0, 1_000_000.0),
    validationRule("currency-scale", "Use at most two decimal places.") {
        value -> value * 100.0 == kotlin.math.round(value * 100.0)
    }
)
~~~

A custom predicate should be deterministic, quick and side-effect-free. Do not put database lookups, network requests or Bukkit world access inside a validation predicate. Validate structural input first, then perform business checks in an explicit service layer.

## 15. Short root-package aliases

The root **ru.privateserver.ksl.std** package contains concise aliases for common JSON and security work:

| Alias | Equivalent |
|---|---|
| String.sha256sum() | KSLSecurity.sha256(text) |
| File.sha256sum() | KSLSecurity.sha256File(file) |
| String.passwordMatches(hash) | KSLSecurity.verifyPassword(text, hash) |
| passwordHash(password) | KSLSecurity.hashPassword(password) |
| passwordCheck(password, hash) | KSLSecurity.verifyPassword(password, hash) |
| passwordVerify(password, hash) | KSLSecurity.verifyPassword(password, hash) |
| token(bytes = 32) | KSLSecurity.randomToken(bytes) |
| secureToken(byteCount = 32) | KSLSecurity.randomToken(byteCount) |
| jsonObj(entries...) / jsonObject(entries...) | jsonObjectOf(...) |
| jsonArr(values...) / jsonArray(values...) | jsonArrayOf(...) |
| Any?.json() / Any?.toJsonValue() | toJsonElement() |
| String.checksum() | KSLSecurity.sha256(text) |
| File.checksumFile() | KSLSecurity.sha256File(file) |

Use descriptive package-specific names in reusable libraries when they improve readability. The short aliases are conveniences, not a requirement.

## 16. Choosing the right helper

| Need | Prefer |
|---|---|
| Send player-authored or user-provided text literally | tell(String) or toTextComponent() |
| Send trusted MiniMessage markup | tellRich() or toMiniMessageComponent() |
| Truncate text without splitting surrogate pairs | truncateCodePoints() |
| Validate a UUID provided by a command | toUuidOrNull() |
| Check user input and show all failed constraints | KSLValidator / validateWith() |
| Convert server time to scheduler ticks | secondsToTicks(), Duration.toTicksCeiling() |
| Check true item capacity, including partial stacks | Inventory.spaceFor() / canFit() |
| Deliver an item without silently losing leftovers | Player.give() and inspect the result |
| Build a cloned reward ItemStack | withDisplayName(), withLore(), withEnchantment() |
| Query a region or nearby entities | std.world helpers on the server thread |
| Convert nested Kotlin data into JSON | toJsonElement(), jsonObjectOf(), jsonArrayOf() |
| Parse or produce TOML strings | KSLTomlCodec |
| Store passwords | hashPassword() / verifyPassword(), asynchronously |
| Compare a file or payload fingerprint | sha256() / verifySha256() |
| Share complex script logic across files | MesLib2 or an addon service, not a utility extension |

## 17. What STD does not do

STD is intentionally a utility layer. It does not:

- make Bukkit APIs thread-safe;
- make script code run outside the server JVM;
- isolate untrusted scripts from the host process;
- provide a general ORM or replace the database API;
- automatically persist arbitrary Kotlin objects;
- turn Base64 or SHA-256 into encryption/password storage;
- guarantee that an item fits without checking the returned capacity/leftover result;
- replace Paper's own API documentation for version-specific behavior.

For persistent storage, configuration and migrations, read [Data Formats](Data-Formats.md) and [Database Guide](Database-Guide.md). For script-only functions available through BukkitScriptContext, read [Script DSL Reference](Script-DSL-Reference.md). For addon-owned extension functions, read [Custom Standard Library](Custom-Standard-Library.md).

## 18. Maintenance policy for this reference

When STD changes:

1. Update the package table and method inventory in this document.
2. Add at least one example for a new public helper.
3. Document validation, overflow, nullability, cloning and thread behavior where relevant.
4. Add or update tests for boundary cases.
5. Keep default imports in KSL script metadata aligned with the actual package.
6. Keep [Standard Library Architecture](Standard-Library-Architecture.md) and [Standard Library Guide](Standard-Library-Guide.md) aligned with this complete reference.

This page describes the built-in utilities present in the current source; it is not a promise that every future Paper API helper will be added to KSL STD.
