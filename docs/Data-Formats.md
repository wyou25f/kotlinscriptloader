# KSL Data and Configuration: YAML, TOML, and JSON

A guide to choosing a format, reading and writing data, comments, migrations, and safe file storage.

## 1. Choosing a format

| Format | Best suited for | Comments | KSL |
|---|---|---|---|
| YAML | Server and script settings, nested configuration | # | Bukkit YAML API and helpers |
| TOML | Structured settings with tables and types | # | Tomlj codec and helpers |
| JSON | Data exchange and API payloads | Standard JSON has no comments | kotlinx.serialization JSON |

Do not choose a format based on write speed alone. File size, update frequency, serialization, and execution context are often more important.

## 2. File locations

- Plugin config: plugins/KotlinScriptLoader/config.yml.
- Config for shop.kts: shop.yml in the plugin data directory.
- JSON/TOML data accessed through a script context: inside plugins/KotlinScriptLoader/scripts/, with paths restricted to that directory.

For additional YAML files, use editYaml(fileName) { ... } and saveYaml(fileName, yaml) within the allowed area. Do not use relative paths containing ../.

## 3. YAML: basic access

~~~yaml
# Shop settings
shop:
  enabled: true
  max-items: 24
  title: "<gold>Shop</gold>"
  worlds:
    - world
    - world_nether
~~~

~~~kotlin
val enabled = config.getBoolean("shop.enabled", true)
val maxItems = config.getInt("shop.max-items", 24)
val title = config.getString("shop.title") ?: "<gold>Shop</gold>"
val worlds = config.getStringList("shop.worlds")
~~~

Useful accessors in the Standard Library/context include hasPath, stringOr, intOr, longOr, boolOr, listOfStrings, and removePath. Existing string/int/long/double/bool/strings and Bukkit methods remain available. For receiver-style extensions, make sure the receiver is YamlConfiguration.

## 4. Safe YAML editing

~~~kotlin
editConfig {
    set("shop.enabled", true)
    set("shop.max-items", 32)
}
~~~

editConfig synchronizes the change block and save operation. Do not split concurrent modification and saving across several unsynchronized calls. For file-based YAML, use editYaml/saveYaml with allowed paths.

### Schema validation

~~~kotlin
val issues = validateConfig {
    string("messages.prefix", default = "<gray>[Server]</gray>")
    int("shop.max-items", default = 24, min = 1, max = 54)
    bool("shop.enabled", default = true)
    strings("worlds.allowed", default = listOf("world"))
}
issues.forEach { issue ->
    logWarn("Config '${issue.path}': ${issue.message}")
}
~~~

By default, missing/invalid values may be replaced with schema defaults. Pass applyDefaults = false for diagnostics without changing the file. Ranges are validated; KSL must not silently clamp invalid values to a boundary.

### YAML migrations

~~~kotlin
val configVersion = migrateConfig(
    targetVersion = 2,
    migrations = mapOf(
        1 to { if (!contains("shop.max-items")) set("shop.max-items", 24) },
        2 to { if (!contains("shop.enabled")) set("shop.enabled", true) }
    )
)
~~~

Migration steps run in order. Missing versions and configs with a version higher than the target are rejected. Changes are applied to a temporary copy; the original file is updated only after every step succeeds. Once a script has been published, do not rewrite old migrations — add the next version instead.

## 5. TOML files

~~~toml
# Main settings
port = 25565
enabled = true
motd = "Welcome"

[database]
host = "localhost"
pool-size = 4

[features]
shop = true
~~~

Reading:

~~~kotlin
val settings = loadToml("data/settings.toml")
val port = settings?.tomlInt("port", 25565) ?: 25565
val database = settings?.tomlTable("database")
val host = database?.tomlString("host", "localhost") ?: "localhost"
~~~

Writing and editing:

~~~kotlin
editToml("data/settings.toml") {
    this["port"] = 25566
    this["motd"] = "New server"
    this["database"] = mapOf("host" to "127.0.0.1", "pool-size" to 6)
}

val parsed = readToml("data/settings.toml")
writeToml("data/backup.toml", mapOf("enabled" to true))
updateToml("data/settings.toml") {
    this["maintenance"] = false
}
~~~

Available operations include loadToml/saveToml/editToml, readToml/writeToml/updateToml, tomlHas/tomlString/tomlLong/tomlInt/tomlDouble/tomlBoolean/tomlTable/tomlList, putTomlIfAbsent, and removeToml.

TOML numbers may be represented as Long or another Number. Use numeric accessors rather than unsafe casts to Int.

### Writing behavior and comments

The TOML parser converts a document into a regular Map; saveToml/editToml serialize values again. Comments, original formatting, and whitespace are not part of the data model and may be lost after writing. Keep important explanatory comments separately or add them after automatic generation.

The codec supports scalar values, arrays, and nested tables. Null and arrays of tables are not supported and must produce an explicit error rather than silently losing data. Writing uses an atomic replacement. When possible, a malformed source file is preserved/backed up before an error is reported.

## 6. JSON objects and files

~~~kotlin
val profile = loadJsonObject("data/profile.json")
val coins = profile?.long("coins", 0L) ?: 0L

editJsonObject("data/profile.json") {
    put("coins", (coins + 10).toJsonElement())
    put("active", true.toJsonElement())
}

val payload = jsonObjectOf(
    "player" to player.name,
    "online" to true,
    "tags" to listOf("builder", "vip")
)
saveJson("cache/player.json", payload)
~~~

jsonObjectOf, jsonArrayOf, and toJsonElement support ordinary Kotlin values and collections. For typed JsonObject access, see string/int/long/double/bool, objectValue, and arrayValue.

JSON does not support comments in the standard. Do not put // or # in JSON files. Use a separate _comment field only if your application's format allows it.

## 7. Comments and disabling settings

- Kotlin .kts: // and /* ... */.
- YAML: #.
- TOML: #.
- JSON: comments are not part of the standard.

A semicolon in Kotlin separates statements; it does not disable a function. A semicolon in YAML/TOML does not start a comment. To disable a feature, use a boolean:

~~~yaml
features:
  shop:
    enabled: false
~~~

Or use KSL feature flags:

~~~kotlin
if (featureEnabled("shop.enabled", default = true)) {
    // Runs only while the feature flag is enabled
}
~~~

Feature flags are configuration, not access control. Check permissions separately for protected commands.

## 8. Performance and threading

File operations are synchronous. Do not frequently read or write large JSON/TOML/YAML files on the main thread. Move file operations to a background task and update Bukkit state through runSync.

Do not save a config every game tick. Group changes and write them in batches. For high-frequency values, use a suitable storage/batching strategy instead of repeatedly rewriting a large text file.

## 9. Security

- Do not store passwords, API keys, or Discord webhook URLs in examples or public YAML/TOML/JSON.
- Validate values loaded from files: config may be corrupted or edited manually.
- Context file paths are restricted to the scripts directory.
- Serialization is not encryption.
- Set database secrets through KSL_DB_PASSWORD and restrict database user permissions.

See [Security and Sandbox](Security-and-Sandbox.md) and [Database Guide](Database-Guide.md).
