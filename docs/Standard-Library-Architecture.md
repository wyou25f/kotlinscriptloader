# KSL Standard Library — architecture foundation

**Status:** contracts and planning utilities only. No Standard Library feature modules are implemented in this phase.

## Goals

The future Standard Library should be distributed as independently versioned modules rather than growing the KSL core. Each module should declare its identity, version, required KSL API level, dependencies and any default imports it contributes.

## Public module contract

Implementations use:

- `KSLModuleDescriptor` for metadata and dependency declarations;
- `KSLStandardLibraryModule` for install/uninstall lifecycle;
- `KSLModulePlanner.resolve(...)` to validate a complete module set and obtain a deterministic dependency-first order before changing server state.

The planner rejects duplicate IDs, missing dependencies, dependency cycles and modules requiring a newer KSL API. Uninstall should happen in reverse order. Module implementations should register services, imports, hooks and context extensions through `KSLAPI`, allowing the existing addon ownership cleanup to remain the lifecycle boundary.

## Naming and imports

- Module IDs use lowercase ASCII: `[a-z][a-z0-9._-]*`.
- Module versions are declared by the module and are independent of the KSL API version.
- Default imports must be explicit, unique and non-blank.
- Prefer a single documented package namespace per module; avoid wildcard imports that create collisions between modules.

## Future module families

These are planning categories, not implemented functionality:

| Module family | Intended scope |
| --- | --- |
| `ksl.std.core` | Common script helpers and shared types |
| `ksl.std.text` | Components, formatting and text utilities |
| `ksl.std.player` | Player-oriented convenience APIs |
| `ksl.std.world` | World and entity helpers |
| `ksl.std.storage` | Persistence abstractions |
| `ksl.std.scheduler` | Task scheduling helpers |

## Compatibility policy

- Additive API changes may increment the minor compatibility level only when binary/source compatibility is preserved.
- Removing or changing existing public contracts requires a deliberate breaking API version.
- A module must declare the minimum API version it needs.
- Keep module implementations outside the KSL core wherever possible.

The current module API is a foundation for a later loader/registry. It intentionally does not auto-install modules, ship feature implementations, or change the runtime's current addon lifecycle.
