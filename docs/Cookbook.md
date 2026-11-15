# KSL Cookbook — Practical Recipes

Templates for common tasks. Check installed integrations and the KSL version before copying an example.

## 1. Welcome message with a feature flag

~~~kotlin
if (featureEnabled("welcome.enabled", default = true)) {
    onEvent<PlayerJoinEvent> {
        player.sendRichMessage("<green>Welcome, ${player.name}!</green>")
    }
}
~~~

## 2. Command with a permission check

~~~kotlin
registerCommand("announce") { sender, args ->
    if (!sender.hasPermission("server.announce")) {
        sender.sendMessage("You do not have permission.")
        return@registerCommand
    }
    if (args.isEmpty()) {
        sender.sendMessage("Usage: /announce <text>")
        return@registerCommand
    }
    val message = args.joinToString(" ")
    runSync { Bukkit.broadcastMessage(message) }
}
~~~

## 3. YAML feature toggle

~~~kotlin
val enabled = featureEnabled("events.double-xp", default = false)
log("Double XP enabled: $enabled")
val newState = toggleFeature("events.double-xp")
~~~

## 4. SQL: parameterized insert

~~~kotlin
execute("""
    CREATE TABLE IF NOT EXISTS player_notes (
        uuid VARCHAR(36) NOT NULL,
        note VARCHAR(255) NOT NULL,
        created_at BIGINT NOT NULL
    )
""".trimIndent())

execute(
    "INSERT INTO player_notes (uuid, note, created_at) VALUES (?, ?, ?)",
    player.uniqueId.toString(),
    "first-join",
    System.currentTimeMillis()
)
~~~

For a real application, add a primary key/index and a schema migration. Never concatenate user input into SQL.

## 5. Async SQL with a result callback

~~~kotlin
queryAsync(
    "SELECT COUNT(*) AS total FROM player_notes WHERE uuid = ?",
    player.uniqueId.toString(),
    mapper = { rs -> if (rs.next()) rs.getInt("total") else 0 }
).whenComplete { total, error ->
    if (error != null) {
        logWarn("Query failed: ${error.message}")
    } else {
        runSync { player.sendMessage("Notes: $total") }
    }
}
~~~

The mapper returns a regular value. Do not return a JDBC ResultSet: the connection is closed after the mapper finishes.

## 6. SQL from YAML

The notes.yml file for notes.kts is located in the KSL plugin directory root, next to scripts/:

~~~yaml
database:
  queries:
    count-notes: "SELECT COUNT(*) AS total FROM player_notes WHERE uuid = ?"
~~~

~~~kotlin
dbQueryYaml("database.queries.count-notes", player.uniqueId.toString()) {
    val total = if (next()) getInt("total") else 0
    runSync { player.sendMessage("Notes: $total") }
}
~~~

## 7. Bounded cache

~~~kotlin
data class ProfileView(val name: String, val level: Int)

val profiles = KSLRT.cache<String, ProfileView>(maxSize = 2_000)
profiles[player.uniqueId.toString()] = ProfileView(player.name, 5)
val cached = profiles[player.uniqueId.toString()]
~~~

Plan invalidation and TTL. A local cache on one server is not synchronized with other Minecraft servers.

## 8. Measure an operation

~~~kotlin
val profile = KSLRT.profiler("shop.price-calculation")
val price = profile.measure { calculatePrice(player.uniqueId) }
val stats = profile.snapshot()
log("calls=${stats.calls}, maxNs=${stats.maxNanos}")
~~~

Do not create a unique profiler name for every call. The registry of named profilers is bounded.

## 9. Background computation

~~~kotlin
val taskId = worker(KSLWorkPriority.NORMAL) {
    val computed = expensiveCalculation()
    runSync { player.sendMessage("Result: $computed") }
}
~~~

Do not access Bukkit objects from background code. Collect an immutable player-data snapshot on the main thread before starting the worker.

## 10. TOML settings

~~~toml
# File: scripts/data/shop.toml
[shop]
enabled = true
max-items = 24
~~~

~~~kotlin
val settings = loadToml("data/shop.toml")
val enabled = settings?.tomlTable("shop")?.tomlBoolean("enabled", true) ?: true
~~~

## 11. JSON payload

~~~kotlin
val payload = jsonObjectOf(
    "uuid" to player.uniqueId.toString(),
    "name" to player.name,
    "online" to true,
    "seenAt" to System.currentTimeMillis()
)
~~~

## 12. YAML migration

~~~kotlin
migrateConfig(
    targetVersion = 2,
    migrations = mapOf(
        1 to { if (!contains("shop.enabled")) set("shop.enabled", true) },
        2 to { if (!contains("shop.max-items")) set("shop.max-items", 24) }
    )
)
~~~

Do not rewrite old migration steps after users have received the configuration.

## 13. Optional integration

~~~kotlin
val auth = hook<KSLAuthMeHook>("authme")
if (auth == null) {
    log("AuthMe is not installed; login verification is disabled")
} else {
    // Use only methods documented for the available hook.
}
~~~

## 14. Troubleshooting

If a script does not load:

1. Check the exact compiler message.
2. Check imports and package declarations.
3. Compare method names with source and documentation.
4. Verify that Bukkit API is not called from async code.
5. Run /ksl doctor and /ksl errors.
6. See Troubleshooting.md.

## 15. What not to do

- Do not use SHA-256 as a password hash.
- Do not store passwords/tokens in plain text.
- Do not use SQL string interpolation for user data.
- Do not call join() for a long-running operation on the main thread.
- Do not call Bukkit inventory/world/player methods from an arbitrary worker thread.
- Do not create unbounded Executors or threads.
- Do not treat a feature flag as an authorization system.
- Do not treat Sandbox as isolation from malicious code.
