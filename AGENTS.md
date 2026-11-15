# Instructions for AI coding agents

This repository is KotlinScriptLoader (KSL), a Kotlin .kts script loader for Paper 1.21.1 / Java 21.

## Documentation

The four primary user/developer books are:

1. `docs/01-scripts.md` — script authoring and the .kts DSL.
2. `docs/02-addons.md` — Kotlin/Java plugins, addons, libraries and Public API.
3. `docs/03-data-and-databases.md` — persistence, SQL, SQLite, MySQL/MariaDB, phpMyAdmin and file formats.
4. `docs/04-cookbook.md` — practical examples and patterns.

For coding-agent workflow and handover rules, read `docs/AI-ASSISTANT-GUIDE.md`. The offline reader lives in `docs/site/index.html`.

## Before making changes

1. Read this file and the relevant KSL book.
2. Inspect the current branch, commit and actual source files. Do not assume earlier conversation context matches the checkout.
3. Distinguish script DSL (.kts + BukkitScriptContext), addon Public API (Kotlin/Java integrations), and KSL internal implementation.
4. Verify every referenced function, property, import and signature in current source/tests. Never invent API.
5. Inspect related tests and examples before changing behavior.

## Compatibility and lifecycle

- Preserve existing public signatures and behavior unless a breaking change is explicitly requested.
- Treat `ru.privateserver.ksl.api` as the public integration namespace. Do not expose internal classes casually.
- Keep KSL script metadata, default imports and IDE metadata aligned with the actual script template.
- Release every listener, command, placeholder, task, GUI callback and other script-owned resource on reload/unload.
- Consider failed-load rollback and partial registration.
- Do not assume a function available in .kts is automatically available to addons, or vice versa.

## Bukkit and concurrency

- Bukkit world/entity/player/inventory operations belong on the server thread unless the exact API explicitly documents otherwise.
- Do not block the server thread with SQL, network, long file I/O, sleeps or expensive compilation.
- Async callbacks must hand Bukkit work back to the server thread.
- Avoid unmanaged repeating tasks and unbounded executors.
- Do not claim sandboxing isolates hostile code: KSL runs inside the server JVM.

## Verification and reporting

- Add or update focused tests for behavior changes.
- Run relevant tests and the build when possible.
- Report separately: tests added, tests executed, tests passed, build result and CI result.
- Never claim a check passed if it was not actually run or observed passing.
- Summarize changed files, compatibility implications, limitations and anything not verified.

For user-facing context-sharing instructions, see `docs/AI-ASSISTANT-GUIDE.md`.
