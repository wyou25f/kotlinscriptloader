# KSL — Getting Started

A practical guide for Paper server administrators and authors of their first Kotlin script.

- Target environment: Paper 1.21.1, Java 21.
- Script directory: `plugins/KotlinScriptLoader/scripts/`.
- File extension: `.kts`.

## 1. Installation

1. Install Java 21 and Paper 1.21.1.
2. Place the KSL plugin JAR in `plugins/`.
3. Start the server once so KSL can create its configuration and script directory.
4. Stop the server and check `plugins/KotlinScriptLoader/config.yml`.
5. Create `plugins/KotlinScriptLoader/scripts/hello.kts`.

Download JAR files only from a trusted release or CI artifact.

## 2. Your first script

~~~kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage("<green>Welcome, <white>${player.name}</white>!</green>")
}
~~~

Save the file and run `/ksl reload`. Check the console for errors. If the script loads, join the server with a test player.

KSL uses `BukkitScriptContext` as an implicit receiver. Functions such as `onEvent`, `runSync`, `runAsync`, `config`, `database`, and `registerCommand` are therefore available inside a `.kts` file without manually creating a context.

## 3. Register a command

~~~kotlin
registerCommand("hello", aliases = listOf("hi")) { sender, args ->
    sender.sendMessage("Hello! Arguments: ${args.size}")
}
~~~

Check permissions inside the handler if the command is administrative. Do not rely only on hiding it from tab completion.

## 4. YAML configuration

A script can have a companion YAML file with the same base name. For `hello.kts`, create `hello.yml` in the KSL plugin data directory:

~~~yaml
message: "<green>Welcome!</green>"
enabled: true
~~~

~~~kotlin
val enabled = config.getBoolean("enabled", true)
val message = config.getString("message") ?: "<green>Welcome!</green>"

if (enabled) {
    onEvent<PlayerJoinEvent> {
        player.sendRichMessage(message)
    }
}
~~~

Use `editConfig { ... }` for grouped changes and saving. Direct calls to `config.set(...)` from multiple threads do not automatically become thread-safe.

## 5. Reload and diagnostics

- `/ksl reload` — reload all scripts.
- `/ksl reload <script-id>` — reload one script.
- `/ksl doctor` — run diagnostics.
- `/ksl errors` — show recent errors.
- `/ksl error <code>` — look up an error code.
- `/ksl language` — show or change the server message language.

During reload, KSL removes resources owned by the old script instance and registers the new ones. Do not create unmanaged threads or external tasks that outlive the script.

## 6. What to read next

| Goal | Guide |
|---|---|
| Full DSL | [Script DSL Reference](Script-DSL-Reference.md) |
| SQL and databases | [Database Guide](Database-Guide.md) |
| Performance | [Performance and Scaling](Performance-and-Scaling.md) |
| YAML/TOML/JSON | [Data Formats](Data-Formats.md) |
| Security | [Security and Sandbox](Security-and-Sandbox.md) |
| Server operations | [Production Operations](Production-Operations.md) |
| Recipes | [Cookbook](Cookbook.md) |
| Addon API | [Public API](Public-API.md) |
| IDE | [IDE README](../ide/README.md) |

## 7. Important limitations

KSL does not isolate scripts in a separate JVM. A script can access capabilities available to the server and its APIs. Only allow trusted people to write to `scripts/`.

The Bukkit API does not become thread-safe because code is written in Kotlin. World, entity, player, and inventory operations usually need to run on the main thread.

Do not store passwords, webhook URLs, or tokens in a public repository. For the database password, KSL supports the `KSL_DB_PASSWORD` environment variable.
