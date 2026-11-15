# KSL Developer Handbook — How the Platform Works

This handbook explains KotlinScriptLoader as a platform: what the major layers are, what happens when a script is compiled and loaded, how hot reload is managed, where addon code fits, and how to make safe design decisions.

It is intended for server owners who want to understand the system, script authors building larger features, addon developers, and contributors working on KSL itself.

## 1. What KSL is

KotlinScriptLoader (KSL) is a Paper plugin and Kotlin scripting runtime. It executes Kotlin .kts files inside the Minecraft server process and provides a server-oriented context, APIs, libraries, diagnostics and lifecycle management around those scripts.

KSL is not a separate language comparable to Skript's syntax. A KSL script is Kotlin code compiled by Kotlin Scripting. KSL supplies the environment in which that code is compiled, evaluated and managed.

The platform has three closely related uses:

1. **Script host:** load, compile, evaluate, reload and unload Kotlin script files.
2. **Runtime framework:** provide common server services such as events, commands, scheduling, data, persistence, GUI, diagnostics and integrations.
3. **Extension platform:** allow regular Paper plugins to add services, hooks, DSL functions and reusable Standard Library packages.

## 2. The three API layers

The most important design distinction is between the script DSL, the addon Public API and KSL internals.

| Layer | Who uses it | Typical location | Stability expectation |
|---|---|---|---|
| Script DSL | .kts authors | BukkitScriptContext, dsl, std, rt, meslib2 | User-facing script API |
| Addon Public API | Kotlin/Java Paper addons | ru.privateserver.ksl.api and documented contracts | Compatibility-conscious external API |
| Internal implementation | KSL contributors | KSL core packages and managers | Internal; may change as implementation evolves |

A method that is available in a .kts script is not automatically an addon API method. A Kotlin typealias is not automatically a Java class. A class in an internal package should not be treated as a supported contract merely because it is visible in the plugin JAR.

When building an addon, start from [Public API](Public-API.md) and [Addon Development](Addon-Development-Guide.md). When writing a script, start from [Getting Started](Getting-Started.md) and [Script DSL Reference](Script-DSL-Reference.md).

## 3. High-level architecture

A simplified view of the script path is:

    .kts source file
        |
        v
    KSL script discovery and metadata
        |
        v
    Kotlin Scripting compilation configuration
        |
        v
    Kotlin Scripting compiler
        |
        v
    compiled script instance / evaluation
        |
        v
    BukkitScriptContext + KSL DSL + default imports
        |
        v
    KSL-managed listeners, tasks, commands and other resources
        |
        v
    Paper / Bukkit server APIs

The runtime also exposes shared services and integration points:

    KSL core
       |-- script lifecycle and resource ownership
       |-- scheduler and worker pool
       |-- standard library and runtime utilities
       |-- database / persistence / data formats
       |-- GUI and command integration
       |-- addon manager and module manager
       |-- hooks and optional plugin integrations
       |-- diagnostics, error catalog and watchdog

This is an architectural map, not a promise that every feature is implemented by one class or one manager. The source tree is split into packages and focused components.

## 4. Script definition and compilation

### 4.1 Script definition

KSL uses Kotlin Script with a script template and an implicit BukkitScriptContext receiver. The shared script metadata records:

- file extension;
- JVM target;
- context class;
- default imports.

These values are maintained in KSL script metadata so the runtime and tooling can use a common definition rather than maintaining unrelated import lists.

### 4.2 Default imports

A KSL script has access to common Bukkit/Paper, Adventure, DSL, public API, STD, runtime and MesLib2 packages through its default imports.

Default imports are ordinary Kotlin imports. They do not recursively import subpackages. If an addon registers a package import, the addon must also have its compiled classes available to the compiler and evaluator.

Do not confuse an import with a dependency resolver. Adding a package to the import list does not download a library or compile loose source files from the scripts folder.

### 4.3 Compilation is not evaluation

Kotlin Scripting compilation checks and compiles script source. Evaluation runs the resulting script in the KSL context.

The two phases can fail for different reasons:

- syntax or type errors are compilation failures;
- evaluation exceptions occur while executing top-level script code;
- runtime exceptions may occur later inside event handlers, commands or scheduled tasks.

KSL has separate diagnostics for script compilation and evaluation so that these failures are not all presented as the same generic syntax error.

### 4.4 Background compilation and main-thread application

Kotlin compilation is comparatively expensive, so KSL supports compiling scripts away from the server's main thread. Applying server-facing state still has to respect Paper's threading rules.

A safe conceptual sequence is:

1. Discover target files and determine which scripts need work.
2. Compile source on KSL's compile workers.
3. Return the prepared result to the appropriate server-thread lifecycle stage.
4. Replace the previous loaded script only after the replacement has reached the required successful stage.
5. Release resources belonging to the previous instance.

This design avoids doing expensive compilation directly inside a synchronous server command. It does not mean all script code can run asynchronously.

## 5. Script loading and execution

Each loaded script receives a BukkitScriptContext. The context supplies the script identity and the script-facing services/DSL.

A script can use context functions such as event registration, command registration, scheduler operations, configuration, logging, persistence, JSON/TOML helpers and resource cleanup. The exact public surface is documented in [Script DSL Reference](Script-DSL-Reference.md).

A script can also call ordinary Kotlin. For example, it can define private classes, extension functions and helper functions, or use types exposed by registered addon classloaders.

### 5.1 Top-level code

Code at the top level of a .kts file runs during script evaluation. Use it to:

- read configuration;
- construct immutable or script-local state;
- register listeners, commands and placeholders;
- create tasks and caches that belong to the script;
- register cleanup callbacks.

Avoid performing long database operations, HTTP calls or expensive calculations in top-level code if they could delay script activation or the server lifecycle.

### 5.2 Event callbacks

Event handlers should do a small amount of work. If an event requires SQL or network data:

1. capture stable values such as the player's UUID and the relevant event inputs;
2. move blocking or expensive work to an appropriate worker;
3. return to the server thread before modifying player, entity, inventory or world state;
4. check that the script or addon is still active before applying delayed results.

Do not keep a live Player or World object and access it from an arbitrary worker thread.

## 6. Hot reload and incremental reload

KSL supports full reload, single-script reload and changed-script reload. The purpose of hot reload is to update scripts without restarting the server, not to leave old callbacks running beside the new version.

The script lifecycle is built around resource ownership. Resources created through KSL-supported context APIs are associated with their owning script. When that script is replaced or removed, KSL can release those resources.

A typical reload has these conceptual stages:

1. Identify scripts to reload, add or remove.
2. Compile changed source.
3. Preserve the active version if the new version cannot be compiled.
4. Stop the old script instance at the appropriate lifecycle stage.
5. Unregister old listeners, commands, placeholders, tasks and subscriptions.
6. Close script-owned runtime scopes and their resources.
7. Evaluate and activate the replacement.
8. Publish lifecycle diagnostics/events.

Incremental reload avoids recompiling unchanged scripts. The exact dependency behavior depends on the script dependency metadata and the selected reload operation; consult the current DSL and lifecycle documentation rather than assuming that every file in the directory is always recompiled.

See [Script Lifecycle and Resources](Script-Lifecycle-and-Resources.md) for the complete ownership model.

## 7. Resource ownership

A resource should always have one clear owner and a clear cleanup point.

Examples of resources include:

- Bukkit event listeners;
- scheduled tasks;
- dynamic commands;
- placeholders;
- KSL event-bus subscriptions;
- caches and profilers;
- external HTTP clients;
- database clients;
- file watchers;
- third-party plugin callbacks;
- threads and executors.

### 7.1 Script-owned resources

Prefer KSL DSL/context APIs for resources that belong to a .kts file. KSL can then associate them with the script and clean them on reload, removal or shutdown.

For external AutoCloseable objects, use the script runtime scope:

~~~kotlin
val client = own(createClient())
val subscription = onUnload {
    thirdPartyApi.removeListener(listener)
}
~~~

The scope closes resources in reverse registration order. A cleanup handle can be closed early when the resource is no longer needed.

### 7.2 Addon-owned resources

An addon normally lives longer than one script. It should manage its global listeners, tasks, clients and subscriptions in its own lifecycle.

Use KSLAddonScope when one object should own the addon's resource group:

~~~kotlin
class MetricsAddon : KSLAddon {
    private var resources: KSLAddonScope? = null

    override fun onLoad(api: KSLAPI) {
        val scope = api.addonScope()
        resources = scope

        scope.listen<PlayerJoinEvent> { event ->
            recordJoin(event.player.uniqueId)
        }

        scope.runSyncRepeating(periodTicks = 20L) {
            publishMetricsSnapshot()
        }

        scope.own(createMetricsClient())
    }

    override fun onUnload() {
        resources?.close()
        resources = null
    }
}
~~~

KSLAddonScope is explicit: call close() from onUnload(). It can own external AutoCloseable resources, Bukkit listeners, typed event handlers, event-bus subscriptions and scheduled sync/async tasks. The scope releases resources in reverse registration order and attempts all closes even when one fails.

KSL automatically manages some registrations made during addon onLoad, including services, default imports, context extensions, hooks and event-bus subscriptions. KSLAddonScope is an additional unified owner for resources that otherwise require manual cleanup, especially tasks, Bukkit listeners and external resources. Avoid registering the same resource independently through multiple ownership mechanisms unless its close behavior is intentionally safe to repeat.

## 8. The Standard Library

STD is the built-in utility layer. It provides convenience functions for text, math, time, players, inventories, ItemStacks, Adventure components, worlds, JSON, TOML, security and validation.

STD helpers are regular Kotlin code. They are not magic server commands and do not bypass the underlying APIs.

Good reasons to use STD:

- less repeated input validation;
- explicit overflow and invalid-range behavior;
- reusable ItemStack transformations;
- inventory capacity checks that include partial stacks;
- type-aware JSON/TOML access;
- consistent time conversion;
- shared text and component operations.

STD is not a database ORM, a security boundary, or a replacement for Paper's API. Use [STD Complete Reference](STD-Complete-Reference.md) for the complete method inventory and behavior.

## 9. Addons and the Public API

An addon is an ordinary Paper plugin that implements KSLAddon and registers itself through KSL. It can expose services and compiled Kotlin packages to scripts, create hooks for optional integrations, register default imports, add context extensions and contribute versioned Standard Library modules.

The addon Public API is designed to provide extension points without requiring addons to modify KSL core.

### 9.1 Services

A service is an object registered under a stable key. Scripts or other addons retrieve it by key and type.

Use service registration for reusable business logic such as economy, profile, permissions or server-specific systems. Prefer an interface in a shared API package when several addons need to share the same service type.

### 9.2 Default imports and classloaders

An addon may register package imports so scripts can refer to its compiled functions and classes without repeating import statements. The addon JAR must be available through KSL's addon classloader path.

Use compileOnly for the KSL plugin/API dependency. Do not shade a second copy of KSL API classes into the addon JAR: separate classloaders can make two types with the same name incompatible.

An import only makes a declaration convenient to reference. It does not install an absent class, download an external dependency or compile source files from the scripts folder.

### 9.3 Context extensions

KSLContextExtension is the hook for per-script context state. Use it when an addon needs to initialize data for each script and release that data when each script context is destroyed.

Do not keep references to a destroyed BukkitScriptContext or store per-script data in an unbounded global map.

### 9.4 Hooks

Hooks provide optional integrations with other plugins. Check plugin availability and hook availability rather than assuming another plugin is installed.

A hook should expose a narrow, stable capability instead of leaking large third-party implementation details into every script.

### 9.5 Versioned Standard Library modules

A module declares its ID, version, minimum API level, dependencies and default imports. KSL's module planner validates the module graph and resolves dependency order before installation.

Modules are compiled code shipped by an addon JAR. They are not downloaded source packages. Keep install and uninstall symmetrical, and release external resources in the owning addon lifecycle.

## 10. MesLib2 and sharing code between scripts

MesLib2 is a script-scoped export registry for sharing typed objects/services between .kts files on the same server.

A provider script exports a value under a namespace and name. A consumer script retrieves it by type and may declare a script dependency so the provider is initialized first.

Use MesLib2 when the shared object is server-specific script logic and should be iterated without building an addon JAR.

Use an addon service or Standard Library module when the code is reusable compiled Kotlin that should be shared across servers or integrated into a regular Paper plugin.

MesLib2 export ownership follows the provider script. When the provider is unloaded, its exports are removed. See [Runtime API](Runtime-API.md) for methods, quotas, versions and classloader notes.

## 11. Scheduler and background work

KSL has several ways to schedule work. Choose based on the kind of work and ownership.

| Work | Preferred mechanism |
|---|---|
| Change blocks, entities, players or inventories | Main-thread scheduler / script runSync |
| SQL, network or blocking I/O | Async worker or the subsystem's async API |
| CPU-heavy pure Kotlin calculation | Bounded KSL worker pool |
| Repeated gameplay callback | Script-owned every()/scheduler API |
| Repeated addon callback | KSLAddonScope.runSyncRepeating() or retained BukkitTask |
| Resource created by a third-party library | own()/onUnload() in a script, or KSLAddonScope in an addon |

Do not use Thread.sleep in an event callback or server-thread task. Do not use Future.get() or join() on the main thread when the operation can block.

The worker pool is bounded. If its queue or owner quota is full, KSL should reject work rather than silently execute expensive work on the caller, which might be the server thread. Worker code should respond to interruption and should not outlive its owner.

See [Performance and Scaling](Performance-and-Scaling.md) and [Runtime API](Runtime-API.md).

## 12. Threading model

A simple rule is: **Bukkit's live server state belongs to the server thread unless the exact Paper API documents otherwise.**

Safe candidates for background work:

- immutable calculations;
- hashing and password verification;
- network calls;
- SQL through an async-capable database API;
- file parsing or large file checksums;
- transformations of detached data objects.

Operations that generally belong on the server thread:

- modifying blocks or world state;
- teleporting a player;
- modifying health, food, potion effects or inventory;
- querying live entities and nearby players;
- opening or updating inventories/GUI;
- registering Bukkit listeners and other server-owned resources.

For cross-thread handoff, pass immutable values such as UUID, strings, numbers and DTOs. On completion, re-check the owner and schedule server state changes on the main thread.

## 13. Data, configuration and persistence

KSL exposes several separate data mechanisms. Choose the one that matches the problem.

| Need | Mechanism |
|---|---|
| Per-script settings | Script YAML config |
| Addon settings | KSLAddonConfig or regular JavaPlugin config |
| Structured data file | Script-local JSON/TOML helpers |
| Relational or key/value persistence | Database API, persist() and table() |
| Share a live object between scripts | MesLib2 |
| Share a compiled service across scripts/addons | Addon service or module |

Do not store secrets in files committed to source control. Do not treat a config feature flag as a security permission. See [Data Formats](Data-Formats.md) and [Database Guide](Database-Guide.md).

## 14. Security model and boundaries

KSL scripts execute in the same JVM process as the server and plugins. KSL's sandbox can restrict selected capabilities and catch some unsafe access patterns, but it is not process isolation and is not a complete security boundary.

Therefore:

- allow only trusted people to write to the scripts directory;
- restrict KSL administrative permissions;
- do not run arbitrary downloaded scripts as a security experiment;
- do not place credentials in source files;
- do not assume a sandbox can contain a malicious JVM payload;
- do not mistake a class deny-list for operating-system isolation.

For actual hostile-code isolation, use a separate process/container and a narrow authenticated protocol. A same-process scripting plugin cannot provide that guarantee.

Read [Security and Sandbox](Security-and-Sandbox.md).

## 15. Compatibility and Paper updates

KSL relies on Paper/Bukkit APIs, Java, Kotlin Scripting and the JVM. Kotlin does not make the project immune to Minecraft updates.

Compatibility work includes:

- building against the intended Paper API;
- keeping Java and Kotlin targets aligned with the supported runtime;
- adapting to removed or changed Paper/Bukkit API methods;
- validating addon hooks against optional plugin versions;
- testing scripts that use affected events and item/world APIs;
- checking classloader and dependency behavior.

KSL's own stable wrapper API can reduce how often script authors need to call unstable server details directly, but wrappers themselves still need maintenance when the underlying API changes.

A successful CI build proves that the repository compiled and its automated checks passed for that build environment. It does not prove that a real production server, every plugin combination or every future Paper build has been tested.

## 16. Performance: what the architecture does and does not promise

KSL uses background compilation, incremental reload, bounded worker execution, caches and batched/persisted operations in different subsystems. These are design mechanisms, not a guaranteed percentage performance improvement.

Measure the actual workload:

- cold startup compile;
- warm startup compile;
- single-script changed reload;
- full reload;
- event handler time;
- database latency;
- worker queue depth;
- memory and classloader retention;
- server tick duration.

Avoid claiming a speed percentage without a repeatable benchmark on the same Paper build, Java runtime, hardware, scripts and server settings.

## 17. Diagnostics and failure handling

KSL provides structured error codes, script diagnostics, logs, watchdog reporting, runtime monitoring and administrative commands such as /ksl doctor, /ksl errors, /ksl error, /ksl runtime and validation/reload operations.

When investigating an issue, collect:

1. exact KSL commit or JAR version;
2. exact Paper version/build and Java version;
3. the smallest affected .kts file and config;
4. the full error code and relevant stack trace;
5. whether the failure happened during compilation, evaluation, a later callback or unload;
6. whether optional plugins/addons were enabled.

Do not report a test as passed merely because a test file exists. Distinguish a test that was written, executed, and observed passing.

## 18. Choosing the right extension mechanism

| Goal | Recommended mechanism |
|---|---|
| One script needs a simple helper | Function in that script |
| Several scripts share live server logic | MesLib2 |
| Several scripts need compiled Kotlin extensions | Addon + default imports |
| A reusable library has version/dependency metadata | Standard Library module |
| Integrate an optional plugin | Hook |
| Add per-script state from an addon | KSLContextExtension |
| Ensure addon tasks/listeners/external clients are closed together | KSLAddonScope |
| Add a broadly useful pure helper | Consider KSL STD after tests and API review |
| Add a new core lifecycle/compiler behavior | KSL internal implementation, with focused tests |

The best extension point is usually the narrowest one that solves the requirement. Do not add every server-specific helper to KSL core.

## 19. End-to-end development workflow

### Writing a script

1. Start with Getting Started and Script DSL Reference.
2. Confirm that every helper exists in the current STD/API docs.
3. Keep Bukkit work on the server thread.
4. Register resources through the KSL context.
5. Add cleanup for external resources.
6. Validate and reload the smallest affected script.
7. Inspect diagnostics if compilation or evaluation fails.

### Writing an addon

1. Add the KSL plugin JAR as compileOnly.
2. Declare a hard dependency on KotlinScriptLoader if the addon cannot function without it.
3. Check that KSL is available before accessing the API.
4. Implement KSLAddon and register during onEnable.
5. Register services/imports/hooks/modules in onLoad.
6. Keep addon-owned resources in KSLAddonScope or close them explicitly.
7. Unregister in onDisable and release the scope in onUnload.
8. Test the addon with the exact KSL/Paper/JDK versions.

### Changing KSL core

1. Read AGENTS.md and the AI Assistant Guide.
2. Verify branch, HEAD, source and tests.
3. Keep script DSL, public API and internal core changes distinct.
4. Preserve API signatures unless a versioned breaking change is intentional.
5. Add focused tests for edge cases and lifecycle behavior.
6. Run tests and build.
7. Verify CI and report exactly what ran.
8. Update docs and changelog with behavior and compatibility notes.

## 20. Document map

| Topic | Document |
|---|---|
| Install and first script | Getting Started |
| Script context and DSL methods | Script DSL Reference |
| Common scenarios | Cookbook |
| Built-in functions, signatures and edge cases | STD Complete Reference |
| Standard library design | Standard Library Architecture |
| Addon API contracts | Public API |
| Deep addon API examples | Public API Complete Reference |
| Build an addon project | Addon Development Guide |
| Write custom extension packages | Custom Standard Library |
| Resource cleanup and hot reload | Script Lifecycle and Resources |
| Caches, profilers and workers | Runtime API |
| Databases and persistent state | Database Guide |
| JSON/TOML/YAML and path rules | Data Formats |
| Security boundaries | Security and Sandbox |
| Diagnostics | Troubleshooting and Error Codes |
| Build and CI | Build Diagnostics |

## 21. Final principles

- Prefer stable public contracts over internal implementation classes.
- Keep the script DSL, addon API and core internals conceptually separate.
- Give every task, listener, subscription and external client an owner.
- Respect Paper threading rules.
- Check every return value that represents partial success.
- Keep addon dependencies compile-only where appropriate.
- Treat imports as name resolution, not dependency installation.
- Keep secrets out of scripts and logs.
- Measure before making performance claims.
- Update documentation and tests whenever a public API changes.
