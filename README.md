# KotlinScriptLoader (KSL)

**KotlinScriptLoader** is a Bukkit/Paper plugin that allows you to write server scripts in **Kotlin (`.kts`)** instead of Java, Skript, or Denizen. It provides a powerful, type-safe DSL with hot-reload capabilities, giving you full access to the Bukkit/Spigot/Paper API without compiling a full plugin.

## 🤔 Why use KSL?

- **🚀 Rapid Prototyping:** Write and test game logic, custom commands, or events in minutes.
- **🔄 Hot-Reload:** Apply changes instantly with `/ksl reload` — no server restart required.
- **💎 Full Kotlin Power:** Use coroutines, collections, extension functions, and the entire JVM ecosystem.
- **🎨 Modern Text:** Native **MiniMessage** support for gradients, hover events, and click actions.
- **🔌 Built-in Integrations:** Out-of-the-box support for **LuckPerms**, **EssentialsX**, **PlaceholderAPI**, and **Discord Webhooks**.
- **🛡️ Safe Execution:** Optional sandbox mode to restrict dangerous operations in untrusted scripts.

## ⚡ Features

- **Event Listeners:** `onEvent<PlayerJoinEvent> { ... }`
- **Scheduler DSL:** `delay`, `every`, `runAsync`, `runSync`.
- **Dynamic Commands:** Register `/mycommand` directly from a script.
- **Database Access:** Built-in SQLite database via HikariCP.
- **Config & YAML:** Easy per-script configuration management.
- **Addon API:** Extend KSL with custom Java/Kotlin plugins.
- **IDE Support:** Auto-generates `.autocomplete.kts` for IntelliJ IDEA code completion.

## 📝 Example Script (`scripts/hello.kts`)

```kotlin
// Listen for player joins
onEvent<PlayerJoinEvent> {
    val p = player
    p.sendRichMessage("<gradient:red:blue>Welcome, ${p.name}!</gradient>")
    
    // Check LuckPerms group
    if (p.group == "vip") {
        p.showRichTitle("<gold>VIP Player", "<gray>Enjoy your perks!")
    }
}

// Register a custom command
registerCommand("balance", aliases = listOf("bal", "money")) { player, args ->
    val bal = player.balance ?: 0.0
    player.sendRichMessage("<green>Your balance: <yellow>$${bal}")
}

// Schedule a repeating task
every(20L * 60) { // Every minute
    broadcastMM("<gray>[KSL] <white>Server is running smoothly!")
}
```

## 🎮 Commands

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/ksl reload` | `ksl.admin` | Reloads all scripts and config. |
| `/ksl addons` | `ksl.admin` | Lists all loaded KSL addons. |
| `/ksl services` | `ksl.admin` | Lists all registered services. |
| `/ksl discord` | `ksl.admin` | Shows configured Discord webhooks. |
| `/ksl sandbox` | `ksl.admin` | Shows sandbox status. |

## 📦 Requirements

- **Paper / Purpur** (1.20+)
- **Java 21**
- *(Optional)* **PlaceholderAPI**, **LuckPerms**, **EssentialsX**
