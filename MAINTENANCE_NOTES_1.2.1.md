# KotlinScriptLoader 1.2.1 — maintenance notes

This maintenance line focuses on safer authoring for larger Kotlin scripts and addons, clearer compiler feedback, and reducing avoidable server-thread stalls.

## Highlights

- **Database transport security:** remote MySQL/MariaDB uses `verify-full` by default. Insecure `disable` / `trust` modes require the explicit `database.allow-insecure-transport: true` setting.
- **Bounded SQL:** script queries have JDBC timeouts, result-row caps and SQL-length limits. Transaction sessions provide bounded prepared batches and rollback on exceptions.
- **Sandbox database capability:** sandboxed scripts are denied SQL and `table()` by default. An exact script id can be allowlisted for the bounded KSL SQL API; raw JDBC remains unavailable to sandboxed scripts.
- **Reload-safe work:** script-owned DB futures and table writes are tied to a runtime generation and pending operations are cancelled during unload.
- **Detached NBT engine:** a copy-on-write SNBT document API adds configurable size, nesting and entry limits while keeping live Bukkit NBT operations separate.
- **Database pressure warning:** the queue monitor samples bounded writer/reader queues, flags rapid growth before the high watermark, and suppresses duplicate alerts until both queues recover. It does not run SQL or resize queues automatically.
- **IDE authoring safeguards:** preflight warns about interpolated/concatenated SQL and immediately blocked async DB/reload futures. Oversized files receive an explicit skipped-analysis warning, and Gradle model reads are cached by VFS modification stamps.

- **Authoring preflight:** `/ksl validate [script-id]` now combines Kotlin compiler diagnostics with conservative warnings for blocking sleeps/coroutines, unconditional loops, unmanaged threads and direct Bukkit scheduler tasks.
- **Readable error reports:** validation writes the full diagnostic set to `plugins/KotlinScriptLoader/reports/`. Chat output stays compact; reports are capped at 2 MB and the newest ten are retained. Fifty or more diagnostics no longer need to flood console output.
- **No script execution during validation:** validation compiles source and checks it, but does not evaluate top-level script code.
- **Async addon reload:** public API v11 adds `KSLAPI.reloadScriptAsync`, allowing addons to compile one script off-thread and apply the result on the server thread.
- **IntelliJ authoring support:** the IDE companion provides KSL API/logging completion, local preflight, and a quick fix for unstructured print calls while retaining thread-safety and dependency diagnostics.
- **Structured logging:** API v12 adds admin-aware script/addon loggers, child categories, structured fields and per-owner server log-level overrides.
- **Safer compile saturation:** the bounded compile pool never runs rejected work inline. Background submitters wait for capacity; server-thread submitters fail fast if the queue is unexpectedly saturated.
- **Regression coverage:** added tests for 50 diagnostics, report truncation, authoring warnings, async reload callbacks and compile-queue saturation.

## Compatibility

- Existing remote-DB configs that explicitly use `ssl-mode: disable` or `trust` must either configure verified TLS or explicitly set `database.allow-insecure-transport: true`. This is an intentional fail-closed change; review the setting before upgrading.

- Main build: Paper 1.21.x and Java 21.
- Public KSL API version: **12**.
- Compatibility branches remain separate and are not modified by this maintenance update.

## Important notes

- Static authoring warnings are heuristic, not a complete Kotlin type checker and not proof that arbitrary code is watchdog-safe.
- Keep the Paper watchdog enabled. KSL cannot safely terminate arbitrary Kotlin code that is already executing inside the JVM.
- `reloadScript` remains synchronous for compatibility. Prefer `reloadScriptAsync` in addon callbacks and normal integrations.
- Validation does not execute scripts. Still test changes on a staging server before production deployment.
- Back up scripts and plugin data before upgrading.

