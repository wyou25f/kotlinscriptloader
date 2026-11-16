# KotlinScriptLoader 1.3.0

KSL 1.3.0 focuses on safer authoring for larger Kotlin scripts and addons, clearer compiler feedback, and reducing avoidable server-thread stalls.

## Highlights

- **Authoring preflight:** `/ksl validate [script-id]` now combines Kotlin compiler diagnostics with conservative warnings for blocking sleeps/coroutines, unconditional loops, unmanaged threads and direct Bukkit scheduler tasks.
- **Readable error reports:** validation writes the full diagnostic set to `plugins/KotlinScriptLoader/reports/`. Chat output stays compact; reports are capped at 2 MB and the newest ten are retained. Fifty or more diagnostics no longer need to flood console output.
- **No script execution during validation:** validation compiles source and checks it, but does not evaluate top-level script code.
- **Async addon reload:** public API v11 adds `KSLAPI.reloadScriptAsync`, allowing addons to compile one script off-thread and apply the result on the server thread.
- **IntelliJ addon diagnostics:** the IDE companion analyzes KSL `.kt` addon sources as well as server `.kts` scripts, with warnings for common blocking patterns and synchronous reload calls.
- **Safer compile saturation:** the bounded compile pool never runs rejected work inline. Background submitters wait for capacity; server-thread submitters fail fast if the queue is unexpectedly saturated.
- **Regression coverage:** added tests for 50 diagnostics, report truncation, authoring warnings, async reload callbacks and compile-queue saturation.

## Compatibility

- Main build: Paper 1.21.x and Java 21.
- Public KSL API version: **11**.
- Compatibility branches remain separate and are not modified by this release.

## Important notes

- Static authoring warnings are heuristic, not a complete Kotlin type checker and not proof that arbitrary code is watchdog-safe.
- Keep the Paper watchdog enabled. KSL cannot safely terminate arbitrary Kotlin code that is already executing inside the JVM.
- `reloadScript` remains synchronous for compatibility. Prefer `reloadScriptAsync` in addon callbacks and normal integrations.
- Validation does not execute scripts. Still test changes on a staging server before production deployment.
- Back up scripts and plugin data before upgrading.

