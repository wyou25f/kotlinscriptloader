# Script & Addon Authoring Reliability

This guide describes the safe development loop for large KSL scripts and Paper addons. The checks are intentionally conservative: a warning points to a risky pattern, but the absence of warnings is not a proof that arbitrary Kotlin code cannot stall the server.

## Recommended script workflow

1. Keep each independent feature in its own `.kts` file instead of building one giant script.
2. Use KSL-owned events, commands, tasks, worker APIs and lifecycle resources.
3. Run `/ksl validate` before loading a whole project, or `/ksl validate events/join` for one file.
4. Review compiler errors and authoring warnings. Validation compiles the source but does not evaluate its top-level code.
5. Reload only after validation is clean. If compilation fails, KSL keeps the previous working version where possible.

## Large diagnostic sets

`/ksl validate` keeps chat output short: it shows a summary and a small sample of failing files. The complete report is written to:

```text
plugins/KotlinScriptLoader/reports/validation-*.txt
```

Reports include compiler errors, compiler warnings, authoring warnings, script IDs, failure codes and compile durations. Reports are capped at 2 MB and the newest ten are retained. This keeps a script with 50 or more diagnostics from flooding the server console while preserving a readable report.

## Watchdog-aware authoring

Compilation is the expensive part of loading and should happen on KSL's compile workers. Do not use `Thread.sleep`, `runBlocking`, unconditional loops, or manual thread pools in script top-level code or synchronous event callbacks.

Prefer KSL-owned scheduling and worker APIs:

```kotlin
after(20L) {
    // Runs as a scheduled KSL callback.
}

repeat(ticks = 20L) {
    // Repeating work is owned by this script and cleaned up on unload.
}

async {
    // Worker thread: do not access Bukkit world/player/inventory state here.
    val result = calculateOrReadRemoteData()
    sync {
        // Apply Bukkit state on the server thread.
    }
}
```

Direct Bukkit scheduler registrations and raw threads may outlive a script reload unless explicitly cancelled. Prefer KSL APIs or register an explicit cleanup action.

The Paper watchdog must remain enabled. KSL diagnostics can identify a long-running operation and capture thread snapshots, but KSL cannot safely terminate arbitrary Kotlin code already executing inside the JVM.

## Addon reload APIs

Addon code should use asynchronous reload APIs from lifecycle callbacks, command handlers or other server-thread code:

```kotlin
api.reloadScriptAsync("events/join") { result ->
    when (result) {
        true -> logger.info("Script reloaded")
        false -> logger.warning("Script failed to compile or load")
        null -> logger.info("Another reload is already running or KSL is shutting down")
    }
}
```

`reloadScript` is synchronous and blocks the caller until compilation and swap finish. Keep it for controlled tooling where blocking is intentional; prefer `reloadScriptAsync` for normal addon integrations.

The public KSL API contract is version 12. Version 12 includes the structured logger for scripts and addons; see [Structured logging](13-structured-logging.md). Addons should compare `minApiVersion` with the installed `api.apiVersion` rather than hard-code a value.

## IntelliJ preflight

The IntelliJ companion provides local heuristic checks for:
- blocking sleeps and `runBlocking`;
- unconditional `while (true)` loops;
- manually created threads/executors;
- synchronous addon reload calls;
- missing Paper/PlaceholderAPI/LuckPerms dependencies and common API typos.

It analyzes `.kts` scripts and Kotlin `.kt` sources in KSL addon projects. It does not execute code, download dependencies, or replace Gradle compilation and tests.

## Reliability checklist

- Use `val` by default and split large files by responsibility.
- Give every task, listener, subscription and external client a clear owner.
- Do not access Bukkit world/entity/inventory APIs from worker threads.
- Do not block the main thread waiting for futures or network I/O.
- Keep the Paper watchdog enabled.
- Run validation, addon tests and a real server smoke test before deployment.
- Back up scripts and plugin data before replacing production JARs.
