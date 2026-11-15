# KotlinScriptLoader (KSL)

KotlinScriptLoader is a Kotlin scripting plugin for **Paper 1.21.1** and **Java 21**. It lets server owners build server-side features in Kotlin `.kts` files, reload scripts without restarting the server, and extend KSL through a public API and addons.

## Highlights

- Kotlin-based `.kts` scripts with a Bukkit/Paper-oriented DSL.
- Background script compilation and reload workflows designed to keep expensive compiler work off the server's main thread.
- Managed script lifecycle: KSL tracks and cleans up listeners, tasks, commands, placeholders, GUI sessions, and other owned resources.
- Built-in APIs for configuration, databases, scheduling, text, inventory, entities, effects, and more.
- Addon and library extension points, plus optional integrations with other server plugins.
- Structured error codes, diagnostics, watchdog reports, and runtime tools.
- Server messages in Russian and English, with support for custom YAML locales.

## Requirements

- Paper 1.21.1
- Java 21
- PowerShell 7+ for the repository's build scripts

## Installation

1. Install Java 21 and Paper 1.21.1.
2. Place the KSL plugin JAR in the server's `plugins/` directory.
3. Start the server once. KSL creates its data directory, configuration, locale files, and script directory.
4. Add `.kts` files to `plugins/KotlinScriptLoader/scripts/`.
5. Run `/ksl reload` to load the scripts.

Download JAR files only from a trusted release or the repository's GitHub Actions artifacts.

## First script

Create `plugins/KotlinScriptLoader/scripts/hello.kts`:

```kotlin
onEvent<PlayerJoinEvent> {
    player.sendRichMessage("<green>Welcome, <white>${player.name}</white>!</green>")
}
```

Save the file and run `/ksl reload`. See the [Getting Started guide](docs/Getting-Started.md) for a full walkthrough.

## Common commands

| Command | Purpose |
|---|---|
| `/ksl reload` | Reload all scripts |
| `/ksl reload <script-id>` | Reload one script |
| `/ksl reload changed` | Reload changed scripts |
| `/ksl validate` | Validate scripts without loading them |
| `/ksl doctor` | Run system diagnostics |
| `/ksl errors` | Show recent errors |
| `/ksl error <code>` | Explain an error code |
| `/ksl language` | Show or change the server message language |
| `/ksl runtime` | Inspect runtime resources and worker settings |

Most administrative commands require the `ksl.admin` permission.

## Localization

KSL includes Russian (`ru`) and English (`en`) messages. Set `language: ru` or `language: en` in `config.yml`, or change it at runtime with `/ksl language <ru|en|custom>`.

To add another language, copy `locale/template.yml` to a file such as `locale/de.yml`, translate the values, then run `/ksl language de`. See [Localization](docs/Localization.md) for the complete format and fallback rules.

## Documentation

The full English documentation is in [docs/README.md](docs/README.md).

| Topic | Guide |
|---|---|
| Installation and first script | [Getting Started](docs/Getting-Started.md) |
| Script DSL | [Script DSL Reference](docs/Script-DSL-Reference.md) |
| Practical examples | [Cookbook](docs/Cookbook.md) |
| Architecture and features | [KSL Encyclopedia](docs/KSL-Encyclopedia.md) |
| Public API and addon development | [Public API](docs/Public-API.md) · [Addon Development](docs/Addon-Development-Guide.md) |
| Standard library | [Standard Library Architecture](docs/Standard-Library-Architecture.md) |
| Databases and data formats | [Database Guide](docs/Database-Guide.md) · [Data Formats](docs/Data-Formats.md) |
| Runtime, performance, and production | [Runtime API](docs/Runtime-API.md) · [Performance and Scaling](docs/Performance-and-Scaling.md) · [Production Operations](docs/Production-Operations.md) |
| Security | [Security and Sandbox](docs/Security-and-Sandbox.md) |
| Errors and troubleshooting | [Error Codes](docs/Error-Codes.md) · [Troubleshooting](docs/Troubleshooting.md) |
| Localization | [Localization](docs/Localization.md) |
| Build and CI | [Build Diagnostics](docs/Build-Diagnostics.md) |

## Build and test

The repository uses JDK 21 and PowerShell 7+:

```powershell
./build.ps1 -Task Build
./build.ps1 -Task Test
```

The plugin JAR is written to `build/libs/`. Build logs and diagnostic reports are written to `build-reports/`.

## Security model

KSL scripts execute inside the Minecraft server process. The sandbox can block selected JVM capabilities and catch dangerous mistakes, but it is **not** process isolation and is not a complete security boundary. Only trusted people should be allowed to write to the `scripts/` directory or use `ksl.admin`.

Bukkit/Paper APIs are not automatically thread-safe. Follow the API's thread requirements; most world, player, entity, and inventory changes must run on the server's main thread.

## Contributing

Before changing public APIs or runtime behavior, read [AGENTS.md](AGENTS.md) and the [AI Assistant Guide](docs/AI-ASSISTANT-GUIDE.md). Verify behavior against the current source and tests.
