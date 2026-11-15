# AI Assistant Guide for KotlinScriptLoader

This file explains how to give an AI assistant enough context to write a KSL script or addon, diagnose a problem, or modify KSL itself.

**The assistant must verify the repository version and must not invent APIs based on their names.**

## 1. Start with the four books

The user-facing documentation has been consolidated into four main books:

| Task | Start with |
|---|---|
| Write or debug a .kts script | [Book I — Script Development](01-scripts.md) |
| Create a plugin, addon, hook, service or library | [Book II — Plugins, Addons and Libraries](02-addons.md) |
| Configure storage, SQL, YAML, TOML, JSON or a database | [Book III — Data and Databases](03-data-and-databases.md) |
| Find a complete usage example | [Book IV — Cookbook](04-cookbook.md) |

The [offline reader](site/index.html) presents the same books as a local website. This AI guide is a contributor document, not a fifth user-facing book.

Documentation helps locate the right subsystem, but signatures and behavior must be verified against source code and tests in the current branch.

## 2. Project profile for a new chat

Copy this block into a new conversation and provide the current commit:

    Project: KotlinScriptLoader (KSL)
    Repository: https://github.com/wyou25f/kotlinscriptloader
    Purpose: Kotlin .kts script loader and extension runtime for Paper.
    Target environment in the current project README: Paper 1.21.1, Java 21.
    Scripts use Kotlin Scripting and BukkitScriptContext as an implicit receiver.
    Server script directory: plugins/KotlinScriptLoader/scripts/
    Examples: examples/
    Main documentation: docs/01-scripts.md through docs/04-cookbook.md
    Public API namespace: ru.privateserver.ksl.api
    Script DSL: ru.privateserver.ksl.dsl and BukkitScriptContext
    Standard Library: ru.privateserver.ksl.std.*
    Do not invent methods, properties, imports or signatures.
    Check source and tests for the exact branch/version.
    Distinguish the .kts API, Kotlin/Java addon API and internal core classes.
    Account for Bukkit threading rules and resource cleanup during hot reload.
    Report files, tests, build results and anything not verified.

This profile explains the project but does not replace source code.

## 3. Describe the task precisely

Include desired result, target layer (script/addon/core), expected error and reload behavior, and technical constraints.

Example — a script:

    Write a KSL .kts script that welcomes a player when they join.
    Read the message from YAML and format it with MiniMessage.
    After /ksl reload, the event handler must not be duplicated.
    Use only DSL/API methods that exist in this KSL version.
    Check repository examples and source first.
    Provide the complete .kts file and companion YAML.

Example — a bug:

    Find the cause of this error.
    KSL commit: [hash]
    Paper: [exact version]
    Java: [java -version output]
    Command/action: [...]
    Expected: [...]
    Actual: [...]
    Log: [complete relevant excerpt]
    Script/config: [minimal example]
    Check implementation and tests. Do not suggest nonexistent methods.

## 4. What to attach

- **.kts script:** full script, companion YAML, compiler message, KSL error code, integrations used and expected reload behavior.
- **Addon:** plugin.yml, build.gradle.kts, main class, KSLAddon implementation, API interfaces and loading/linking error.
- **KSL core:** exact commit, related source/tests, reproduction steps, stack trace, actual test/build results and CI link.

## 5. Server diagnostics

Useful information includes:

1. /version — exact server version.
2. /ksl doctor — KSL diagnostics.
3. /ksl errors — recent script errors, if available in this version.
4. The affected script and YAML.
5. Console excerpt around the error.
6. For a freeze, the dump from plugins/KotlinScriptLoader/dumps/.

Do not send the entire plugins/ directory unless necessary. Do not attach a database unless the issue concerns its contents or schema.

## 6. How an assistant should verify code

1. Confirm branch and commit.
2. Find the function/class definition in source.
3. Check default imports and BukkitScriptContext.
4. Compare examples and tests.
5. Verify target layer: .kts, Kotlin addon or Java addon.
6. Check Bukkit threading: world, player, entity and inventory operations usually belong on the main thread.
7. Verify listeners, commands, placeholders, GUI callbacks and tasks are removed during reload/unload.
8. Run tests/build when an environment is available.
9. Separate facts, assumptions and unverified details.

Do not mix the three API layers:

- **Script DSL:** functions/properties available in .kts through its receiver.
- **Addon Public API:** interfaces/extensions for Kotlin and Java plugins.
- **Internal implementation:** KSL internals that are not necessarily stable.

A function available in .kts is not necessarily available to addons under the same name, and vice versa.

### Threading and lifecycle

- Do not run Bukkit world/player/entity/inventory operations from an arbitrary async callback.
- Do not block the main thread with SQL, network calls, long file I/O or Thread.sleep.
- Return Bukkit operations to the main thread through a supported scheduler.
- Do not create repeating tasks that cannot be cancelled.
- Do not treat Sandbox as isolation for untrusted code; scripts run in the same JVM process.

## 7. Handover between chats

Provide the next assistant with the repository link and branch/commit, a short change summary, changed files, test/CI results, remaining tasks and unresolved questions.

Do not write only “continue from last time”: a new chat may not have access to the previous history.

## 8. Confidentiality

Remove Discord webhook URLs, tokens, database passwords, API keys, private keys, environment secrets and personal information before sharing logs or files.

Use placeholders such as [REDACTED_TOKEN], [DB_PASSWORD] and [SERVER_IP]. Preserve structure and value types so the example remains reproducible.

## 9. Final report format

Ask the assistant to finish with a report like this:

    Changes:
    - ...

    Files:
    - ...

    Verification:
    - Tests written: yes/no
    - Tests executed: command and result
    - Build: command and result
    - GitHub Actions: link and status

    Compatibility:
    - ...

    Risks and limitations:
    - ...

    Not verified:
    - ...

“Test written”, “test run” and “test passed” are different statuses. Never claim that a build succeeded unless it was actually run or a successful CI run was observed.
