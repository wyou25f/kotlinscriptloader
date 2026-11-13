# KSL Standard Library — architecture and built-in utilities

**Status:** a small set of pure Kotlin utilities is shipped. Bukkit-specific convenience modules and the module host are future work.

## Built-in packages

These packages are included in the default imports for KSL scripts:

- `ru.privateserver.ksl.std.text.*`: Unicode-safe truncation, legacy color stripping, UUID parsing and whitespace tokenization.
- `ru.privateserver.ksl.std.math.*`: checked clamping, range mapping and validated percentage chance.
- `ru.privateserver.ksl.std.time.*`: explicit seconds/minutes/hours-to-ticks conversion, duration rounding and tick-to-duration conversion.

The existing `ru.privateserver.ksl.dsl.*` package remains available. Its `.sec`, `.mins` and `.hours` extensions now use checked arithmetic and therefore throw on overflow instead of silently wrapping.

## Module contracts

The future Standard Library should be distributed as independently versioned modules rather than growing the KSL core. Each module declares its identity, version, required KSL API level, dependencies and any default imports it contributes.

Implementations use:

- `KSLModuleDescriptor` for metadata and dependency declarations;
- `KSLStandardLibraryModule` for install/uninstall lifecycle;
- `KSLModulePlanner.resolve(...)` to validate a complete module set and obtain a deterministic dependency-first order before changing server state.

The planner rejects duplicate IDs, missing dependencies, dependency cycles and modules requiring a newer KSL API. Uninstall should happen in reverse order. Module implementations should register resources through `KSLAPI` so addon ownership and unload cleanup remain the lifecycle boundary.

## Planned module families

These are planning categories, not shipped functionality:

| Module family | Intended scope |
| --- | --- |
| `ksl.std.core` | Common script helpers and shared types |
| `ksl.std.text` | Components, formatting and text utilities |
| `ksl.std.player` | Player-oriented convenience APIs |
| `ksl.std.world` | World and entity helpers |
| `ksl.std.storage` | Persistence abstractions |
| `ksl.std.scheduler` | Task scheduling helpers |

## Compatibility policy

- Additive API changes must preserve existing signatures.
- Breaking changes require a deliberate API-version change and migration notes.
- Every module declares the minimum KSL API version it needs.
- Prefer one documented package namespace per module; avoid wildcard imports that collide.
- Keep module implementations outside KSL core wherever possible.

The module API is a foundation for a later loader/registry. It does not auto-install modules or change the runtime addon lifecycle.
