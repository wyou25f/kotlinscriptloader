# KotlinScriptLoader 1.2.1

KSL 1.2.1 is a stability, security and scripting API release.

## Highlights

- **Script runtime:** owner-bound scheduler APIs, addon-extensible scheduler engines, runtime scopes, scoped caches and profiling tools.
- **Script authoring:** expanded command/task DSLs, JSON helpers, entity utilities, sound/particle/mob builders, GUI and PersistentDataContainer APIs.
- **Testing and diagnostics:** lifecycle hooks, tags, parameterized tests, filters, structured reports and runtime telemetry.
- **Performance:** bounded LRU compilation cache with environment-aware fingerprints and runtime cache statistics; bounded compilation queues with back-pressure.
- **Security:** hardened sandbox class restrictions, script/library path handling, symlink checks, MCP request boundaries and inter-script export limits.
- **Reliability:** improved classloader lifecycle, script reload/unload cleanup, scheduler ownership, database operations and custom entity/particle handling.
- **Tooling:** expanded IntelliJ IDEA completion, diagnostics, quick fixes and preflight validation; refreshed API documentation and addon starter templates.

## Compatibility

- Main release: Paper 1.21.x, Java 21.
- Compatibility builds are maintained separately in compat/mc-1.16.5-1.21 and compat/mc-1.18-plus; use the JAR matching your server line.

## Upgrade notes

- Back up plugin data and scripts before upgrading.
- Review config.yml for new settings. The compile cache defaults to 128 entries and can be configured with scripts.compile-cache-size.
- MCP remains disabled by default. The sandbox is not an operating-system or JVM security boundary; only run trusted scripts and addons.

Full details: https://github.com/wyou25f/kotlinscriptloader/blob/1.2.1/CHANGELOG.md