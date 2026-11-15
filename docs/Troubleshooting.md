# Troubleshooting

Look up an error code printed to the console with `/ksl error <code>`. The catalog is available through `/ksl errors list`; see [Error Codes](Error-Codes.md) for the full reference.

## “The server has not responded for 10 seconds” / “DO NOT REPORT THIS TO PAPER”

This warning comes from Paper's watchdog: the server main thread was busy for more than 10 seconds and could not keep up with ticks. The thread dump may identify the plugin in the `Current Thread: Server thread` section. Look for `KotlinScriptLoader` in the stack.

Kotlin script compilation initializes a large compiler runtime. In older KSL versions, compilation could block the main thread during `/ksl reload`. Current reload/validation flows compile scripts in the background; applying the script still runs its top-level code on the main thread because it may access Bukkit, worlds, players, and entities.

- `/ksl reload` (all scripts, one script, a folder, or `changed`) compiles in the background.
- `/ksl validate` and `/ksl doctor` run expensive checks outside the main thread.
- Large top-level loops, mass entity spawning, synchronous SQL/network calls, or `Thread.sleep` can still block ticks.
- For mass spawning, use `spawnCustomEntities(..., perTick = 1..10)`.
- Return Bukkit operations from background code with `runSync { ... }`.

KSL can write an early diagnostic dump to `plugins/KotlinScriptLoader/dumps/` when it detects a main-thread stall. The report contains multiple thread snapshots, main-thread frames, lock information, deadlocks, heap/GC data, and database queues. Compare snapshots to see which frames remain unchanged. This does not replace Paper Watchdog or prove the root cause by itself.

If a dump points to an old KSL build or an addon calling `api.reloadScripts()`/`api.reloadScript(...)` on the main thread, update the addon to use `api.reloadScriptsAsync { }`. If the stack points to another plugin, investigate that plugin separately. Do not hide Paper's watchdog warning through server settings.

## Script errors in the console

KSL can print script errors as yellow WARN messages with a concise message and an error code:

~~~text
[prefixes] [6156a] Script compilation failed; 4 errors
  line 1: Expecting an element (×3)
  line 68: Expecting '}'
~~~

Duplicate lines may be grouped. Use `/ksl errors` for the latest error and `/ksl error <code>` for its explanation. Players with `ksl.admin` may receive a short notification after a failed reload.

If an active script is edited and the new version fails to compile, the previous working version remains active (code `9065a`) until the source is fixed.

Example settings:

~~~yaml
logging:
  script-errors-as-warnings: true
notifications:
  admins-in-game: true
~~~

## Common compiler messages

| Message | Meaning |
|---|---|
| `Expecting an element` | The line contains invalid code, stray text, or an extra bracket. Check the start of the file for unwanted characters. |
| `Expecting '}'` | A closing brace is missing somewhere above the reported line. |
| `Unresolved reference` | A name is unknown: it may be misspelled, not imported, or supplied by a missing addon. |
| `Type mismatch` | The supplied value has the wrong type (for example, `Int` instead of `Long`; use `20L`). |

Validate scripts without loading them: `/ksl validate`. For DSL details, see [KSL Encyclopedia](KSL-Encyclopedia.md).

## Strange characters in the console

Random characters between log lines may come from keyboard input displayed by the server console, including keys typed while switching keyboard layouts. This is not caused by KSL and does not affect the plugin.

## First load after startup is slower

KSL initializes the Kotlin compiler at startup. The first script may take longer to load than later scripts. On multi-core hosts, adjusting `scripts.compile-threads` can speed up compilation of several scripts.

## What to include in a bug report

- Plugin and server versions (`/version`).
- Output from `/ksl doctor`.
- The console error code and nearby log lines.
- The affected script and its YAML configuration, with secrets removed.
- For a watchdog warning, the dump section showing `Server thread`.
