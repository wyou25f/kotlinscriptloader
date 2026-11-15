# KSL Error Codes

The catalog contains **273 error codes**. Each code consists of four digits and a category letter, for example `6156a`.

- Numeric parts are unique and kept at least 20 positions apart to reduce confusion.
- The final letter identifies the subsystem.
- Every code has a clear title and a recommended next step.

In-game or in the console:

- `/ksl error 6156a` — explain one specific code.
- `/ksl errors` — show the latest recorded error and the error count for this server session.
- `/ksl errors list` — show the full catalog.
- `/ksl errors list b` — show database errors.
- `/ksl errors list b 2` — show page 2 of a category.
- `/ksl errors list all 2` — show page 2 of the full catalog.

## Categories

| Letter | Subsystem | Codes |
|---|---|---:|
| `a` | Script loading and execution | 37 |
| `b` | Database and storage | 29 |
| `c` | Commands and events | 19 |
| `d` | Effects and particles | 17 |
| `e` | Mobs and entities | 23 |
| `f` | GUI menus | 18 |
| `g` | Integrations, hooks, and Discord | 24 |
| `h` | Addons and API | 23 |
| `i` | Configuration and files | 23 |
| `j` | Scheduler and tasks | 20 |
| `k` | Placeholders and text | 17 |
| `l` | System, diagnostics, and plugin shutdown | 23 |

## Full catalog

### a — Script loading and execution

| Code | What happened | What to do |
|---|---|---|
| `0248a` | The script explicitly stopped the current operation with error() | This is a controlled error: the current operation stops, but the script is not unloaded. Read the script's message and fix the reported cause. |
| `0695a` | The script tried to access a forbidden class | Sandbox denied a class from its deny-list (Runtime, ProcessBuilder, reflection, ClassLoader, etc.). Remove the call or add the script to security.sandbox-trusted if it is trusted. |
| `0858a` | Script source is unavailable | Check file read permissions and encoding; use UTF-8. |
| `0878a` | The script called fatal() and was unloaded | This is an intentional signal: KSL stopped the current script and released resources owned by it. Review the message before reloading. |
| `0983a` | Script loading took too long | The script's top-level code may perform expensive work (network, disk, or database I/O). Move it to runAsync or an event callback. |
| `1029a` | An internal failure occurred during script compilation | This is not an ordinary syntax error. Check Java and the integrity of the KSL JAR, run /ksl doctor, and save the stack trace for a report. |
| `1292a` | The script engine could not execute a compiled script | Review the diagnostic lines below the message. If the script has no obvious issue, check compatibility between Java, the Kotlin scripting engine, and KSL. |
| `1426a` | The source file changed during compilation | Run reload again after the file has finished saving. |
| `1842a` | The script context is already closed | Do not run callbacks or register resources after a script has been unloaded. |
| `1867a` | Some resources could not be removed while unloading the script | Some script resources (tasks, listeners, commands) may remain. If this repeats, restart the server. |
| `2413a` | Compiler warning | The script loaded, but the compiler found a suspicious construct. Fix it to avoid unexpected behavior. |
| `3008a` | The script exceeded its task limit | The script creates too many delay/every/sync tasks. Do not create tasks in an unbounded loop. Limit: security.limits.max-tasks-per-script. |
| `3065a` | The script is incompatible with the current API | Check changed KSL methods and update the script calls. |
| `3297a` | The script path resolves outside scripts/ | Use a path inside scripts/ and do not use '..'. |
| `3519a` | Dependency order is invalid | Declare required scripts with // @requires and check for dependency cycles. |
| `4010a` | A script ID is duplicated | Keep only one file with that relative path under scripts/. |
| `4039a` | The compiler is unavailable | Check the KSL JAR and Java version; restart the server after updating. |
| `4409a` | The script is busy and could not be unloaded | Check long-running callbacks and active tasks; retry unloading after they finish. |
| `4438a` | Script auto-reload did not start | Check permissions for scripts/ and the operating-system file watcher limit. Scripts can still be reloaded with /ksl reload. |
| `4726a` | A forbidden call was detected by Sandbox | The script uses a pattern from the Sandbox deny-list. Remove the call or disable Sandbox in config.yml only if the script is trusted. |
| `4902a` | The compiler ran out of memory | Check available JVM memory and the size of the script being compiled. |
| `4958a` | A script import could not be resolved | Check the file name and import path; the file must be inside the permitted scripts/ directory. |
| `5242a` | A reload is already running | Wait for the current operation to finish, then retry. |
| `5610a` | A cyclic @requires dependency was detected | Scripts depend on each other in a loop. Break the cycle by moving shared code into a third script. |
| `5689a` | Script compilation timed out | Check expensive constructs and the number of imports; try validating one script separately. |
| `6132a` | Script classes conflict | Rename conflicting declarations or move the shared type into an addon API. |
| `6156a` | Script compilation failed | Read the diagnostic message below for the line and cause. Validate the script with /ksl validate. |
| `6752a` | The script exceeded its event-handler limit | The script registers onEvent in a loop or from another event. Register each handler once at top level. Limit: security.limits.max-listeners-per-script. |
| `7040a` | Script was not found | Check the name: it is relative to scripts/ without .kts, for example economy/shop. Use /ksl reload and Tab completion to inspect IDs. |
| `7342a` | Invalid import syntax | Compare the import format with KSL documentation and check the name's capitalization. |
| `7529a` | Invalid source encoding | Save the .kts file as UTF-8 and check for corrupted characters. |
| `7995a` | Reload was cancelled | Check whether the plugin is shutting down or an administrator cancelled the task. |
| `8470a` | An exception occurred while executing a script | The error occurred in script code. The log identifies the .kts line. Fix the code and run /ksl reload <script>. |
| `8623a` | Cyclic import | Remove the mutual import and move shared declarations into a separate script. |
| `9065a` | The new version did not compile; the previous version remains active | The old script version is still running. Fix the errors above and run /ksl reload <script> again. |
| `9317a` | A dependency could not be loaded | Fix the dependent script first, then reload the current script. |
| `9353a` | @requires points to a missing script | Check the name in // @requires: it must match the script ID (path without .kts). |

### b — Database and storage

| Code | What happened | What to do |
|---|---|---|
| `0437b` | A persist value could not be read | Check the database connection with /ksl doctor. The script will receive its default value. |
| `1139b` | The database detected a deadlock | Retry a safe operation and check row-update order across concurrent transactions. |
| `1272b` | The write queue rejected an operation | Check the queue size and database speed. Handle the rejection; do not assume the value was saved. |
| `1321b` | KSL service tables are unavailable | Migrations were not applied. Check whether the database user can CREATE TABLE, then restart the server. |
| `1376b` | The database schema is newer than this KSL version | Update KSL to a version that supports the schema. Do not edit the version manually to downgrade it. |
| `1579b` | The database write queue is growing | The database writes more slowly than scripts submit writes. Check database performance and set()/persist frequency; see /ksl doctor. |
| `2750b` | A table() operation failed | Check the database connection with /ksl doctor. The operation did not complete; other scripts were not affected. |
| `3634b` | increment() failed | The key contains a non-numeric value or the database is unavailable. Check the value and /ksl doctor. |
| `3713b` | The database rejected authentication | Check the username, password, and permissions for the selected database. |
| `4180b` | Some records were not saved during shutdown | The database write queue did not drain in time. Do not forcibly kill the server; let it shut down normally. |
| `4479b` | Connection acquisition timed out | Check database availability, network connectivity, and pool size. |
| `4792b` | SQL syntax error | Check the SQL and compatibility with the SQLite/MySQL/MariaDB dialect. |
| `5064b` | A SQL query from a script failed | Check the query text. It should be portable across SQLite and MySQL/MariaDB (avoid PRAGMA and AUTOINCREMENT). |
| `5999b` | A migration is already running | Make sure another KSL instance is not changing the same database. |
| `6494b` | A persist value could not be saved | The value remains in memory only until restart. Check the database connection with /ksl doctor. |
| `6550b` | A SQL result could not be converted | Compare column types with the types expected by the script. |
| `7377b` | A key is longer than 190 characters | MySQL/MariaDB cannot store keys this long. Shorten the key in ksl.db and retry /ksl dbmigrate confirm. |
| `7657b` | The database is read-only | Check SQLite file mode or MySQL/MariaDB user permissions. |
| `7924b` | The database connection failed | Check database.* in config.yml, that MySQL/MariaDB is running, the database exists, and the user has permissions. See docs/Database-Guide.md. |
| `7974b` | There is not enough disk space for the database | Free disk space and check the filesystem. |
| `8127b` | A SQL query took too long | Check the query plan, indexes, and locks. Do not run a heavy query on the main thread. |
| `8205b` | A table constraint was violated | Check uniqueness, NOT NULL constraints, and foreign keys for the supplied data. |
| `8532b` | The database migration did not complete | Save a backup and inspect the original SQL error before retrying. |
| `8667b` | A transaction could not be rolled back | Save the original error and check the connection. Restore from backup if necessary. |
| `8807b` | The storage does not support this value type | Store only String, Int, Long, Double, Boolean, or null. Serialize lists and objects to strings yourself. |
| `8877b` | The database connection was lost | Check the MySQL/MariaDB or SQLite log and network interruptions. |
| `9201b` | A transaction could not be committed | Check database state. The operation result may be unknown; verify the stored data. |
| `9244b` | A transaction could not be started | Check the connection and transaction support of the selected database engine. |
| `9652b` | The connection pool is exhausted | Check long transactions and unclosed resources. Increase pool-size only after measuring the load. |

### c — Commands and events

| Code | What happened | What to do |
|---|---|---|
| `0462c` | Command registration failed | Check commandMap, the command name, and conflicts with other plugins. |
| `0499c` | A command alias is already in use | Choose a unique alias or remove the conflicting registration. |
| `0775c` | A background /ksl command failed | See the error message. Retry the command; report it if the problem repeats. |
| `1350c` | A command argument has an invalid format | Check the type, allowed range, and usage message. |
| `2204c` | An exception occurred in Tab completion | The command's tabComplete handler failed. Suggestions were omitted. |
| `3602c` | The sender does not have permission to run the command | Check the permission node and the permissions assigned to the player. |
| `3682c` | The command is still on cooldown | Wait for the cooldown or check its configuration. |
| `3845c` | Command unregistration failed | Check commandMap state. A server restart may be required to clear it. |
| `4518c` | An exception occurred in an event handler | The error occurred in onEvent/on/onJoin or a similar callback. Other plugins processed the event normally. |
| `5222c` | A command handler took too long | Do not wait for network or database operations on the main thread. |
| `5322c` | A console command was blocked | The command is listed in security.console-command-blocklist. Remove it only if the script is trusted. |
| `5377c` | A required command argument is missing | Add the required argument or display the usage message. |
| `5948c` | An exception occurred in a command handler | The error is in the script command. The player saw a generic message; details are in the console. |
| `6341c` | The command called Bukkit API from an async thread | Move the Bukkit call into sync { } or the main thread. |
| `6515c` | Invalid command name | Use Latin letters, numbers, and hyphens; check Bukkit naming restrictions. |
| `7002c` | Command handler was not found | Check command registration and that the script is still loaded. |
| `8261c` | commandMap is not available for modification | Old commands may remain until the server restarts. This usually indicates an incompatible Paper version. |
| `9691c` | Command conflicts with another command | Another plugin or script already owns the name. Rename the command or use the ksl: prefix. |
| `9751c` | This command sender type is not supported | Check whether the command permits the console, players, or command blocks. |

### d — Effects and particles

| Code | What happened | What to do |
|---|---|---|
| `1169d` | Invalid animation step | Use a positive step and check the allowed range. |
| `1494d` | Particle data is incompatible with the particle type | Pass data of the type expected by the selected particle. |
| `1617d` | Invalid effect duration | Pass a duration in the supported ticks or milliseconds unit according to the API. |
| `1658d` | Invalid color format | Use color(r, g, b) or a HEX value such as "#RRGGBB". |
| `2449d` | The effect exceeded its rendering budget | Reduce count, the number of points, or the update frequency. |
| `2903d` | The effect was cancelled | Check stopWhen, script lifecycle, and task cancellation. |
| `3088d` | An exception occurred while rendering an effect | The effect was stopped. Check custom{}, stopWhen{}, and onEnd{} in this effect. |
| `3263d` | The effect world is unavailable | Make sure the world is loaded and is not being unloaded. |
| `3924d` | The effect has too many points | Reduce shape detail or split the effect into multiple stages. |
| `4929d` | Unknown particle type | Use a Particle value that exists in your Paper version. |
| `5401d` | Unknown effect shape | Check the name in shape("..."); available shapes are listed in the message. |
| `6831d` | The particle requires additional data | For DUST, call color(255, 0, 0); for other particles, assign data = ... (BlockData, ItemStack, etc.). |
| `6917d` | The active-effect limit has been reached | Stop unnecessary effects and reduce how often they are created. |
| `7267d` | The effect has already stopped | Create a new effect instance instead of trying to restart the old one. |
| `7459d` | Invalid effect location | Check the world and coordinates before starting the effect. |
| `8956d` | An effect callback failed | Check custom{}, stopWhen{}, and onEnd{}; details are in the stack trace. |
| `9145d` | count is outside the allowed range | count is limited to 0..100 per point to prevent client lag. |

### e — Mobs and entities

| Code | What happened | What to do |
|---|---|---|
| `0228e` | The location has no world | The world may be unloaded or the location was created without a world. Check loc(...) for null. |
| `0532e` | The entity target is unavailable | Make sure the target is alive, in the same world, and accessible. |
| `1112e` | equip() contains a value that is not an item | Check the material name, for example DIAMOND_SWORD. |
| `2542e` | Unknown mob-template key | Allowed keys are listed in the message; custom keys can be added with api.registerEntityAttribute. |
| `3426e` | An exception occurred in a mob handler | The error occurred in onTick/onDeath/onDamaged/onAttack. The mob's other behavior continues. |
| `3902e` | Invalid entity coordinates | Check that coordinates are finite and that the chunk is available. |
| `3972e` | The entity is not a living mob | spawnCustomEntity creates only LivingEntity types (ZOMBIE, COW, etc.). Items and projectiles cannot be spawned this way. |
| `4224e` | The entity could not be moved | Check world/chunk loading and whether the target location is valid. |
| `4278e` | The entity's chunk is not loaded | Load the chunk through the supported API or defer the operation. |
| `4855e` | Unknown slot in equip() | Supported slots: HAND, OFFHAND, HELMET, CHESTPLATE, LEGGINGS, BOOTS. |
| `5039e` | An attribute value is outside its allowed range | Check the attribute's bounds and whether the mob type supports it. |
| `5452e` | Invalid entity UUID | Pass a UUID in the standard format and verify the value source. |
| `5904e` | Invalid entity data | Check the type and format of metadata/PersistentDataContainer values. |
| `6285e` | HP is outside the allowed range | HP must be greater than 0 and no more than 1024. The value was ignored. |
| `7169e` | The mob's onTick handler was disabled after errors | The handler failed five times consecutively and was stopped. Fix the code and recreate the mob. |
| `7715e` | Unknown TYPE in the mob template | Use a name from EntityType, such as ZOMBIE. A ZOMBIE was spawned instead. |
| `7797e` | An entity operation was called outside the main thread | Perform the entity change through sync { }. |
| `8599e` | A mob attribute could not be applied | Check the value and whether this mob supports the attribute (a cow has no attack_damage). |
| `8830e` | The entity's world was unloaded | Do not use an entity after its world is unloaded; check its lifecycle. |
| `9031e` | The entity has already been removed | Check isValid and do not use stale references. |
| `9482e` | A mob could not be removed during unload | The mob may remain in the world and will be removed when the chunk is loaded again. |
| `9891e` | The entity could not be created | Check the world, entity type, coordinates, and server restrictions. |
| `9953e` | The entity does not support equipment | Check that the entity type implements the required Equipment interface. |

### f — GUI menus

| Code | What happened | What to do |
|---|---|---|
| `0566f` | An exception occurred in a GUI handler | An error occurred while clicking, closing, or rendering a GUI page. The menu remains open. |
| `1996f` | Slot is outside the inventory | Slots are numbered from 0 to rows*9-1. The item was not added. |
| `2070f` | The GUI window is already closed | Do not update an inventory after it closes; check the player's lifecycle. |
| `2155f` | Two GUI elements use the same slot | Assign unique slots or explicitly define a priority. |
| `2181f` | Could not open the GUI for the player | Check that the player is online and that opening runs on the main thread. |
| `3341f` | Could not update the GUI | Check inventory state and perform updates on the main thread. |
| `4309f` | paginate() was called without slots | Pass a non-empty list of page slots. |
| `4601f` | The GUI session is stale | Open the menu again and do not use the old session object. |
| `5739f` | rows is outside 1..6 | An inventory must have 1..6 rows. The nearest valid value was used. |
| `5769f` | Could not create the GUI inventory | Check the size, title, and Bukkit inventory availability. |
| `6183f` | Invalid GUI item | Check Material, ItemMeta, and serializable data. |
| `6623f` | gui() was called outside the main thread | Wrap menu creation in sync { } or call it from an event. |
| `7235f` | GUI action was cancelled | Check event cancellation and the state of the open menu. |
| `7750f` | The GUI player is no longer online | Check isOnline before opening or updating the menu. |
| `8052f` | MiniMessage could not parse the GUI title | Check title tags. The title was displayed as plain text. |
| `8339f` | GUI title is too long | Shorten the title according to the limits of your server version. |
| `9125f` | Event belongs to an outdated GUI | Compare the current menu session with the object handling the click. |
| `9431f` | A non-existent page was requested | Recalculate the number of pages and check the zero-based index. |

### g — Integrations, hooks, and Discord

| Code | What happened | What to do |
|---|---|---|
| `0270g` | The integration version is not supported | Update the dependent plugin or use a compatible KSL version. |
| `0297g` | PlaceholderAPI was called without a player | Use the placeholder only where a Player context is available. |
| `1208g` | The integration API denied access | Check permissions, configuration, and service availability. |
| `1450g` | Discord returned an error | The response code is in the message: 401/404 usually means an invalid webhook; 400 means invalid content. |
| `1752g` | Discord rate-limited requests | Reduce the send frequency and respect Retry-After. |
| `1955g` | The placeholder received an invalid argument | Check %ksl_...% syntax and the supplied parameters. |
| `2488g` | The integration returned an invalid response | Check the response format and third-party API version. |
| `2850g` | Discord channel was not found in configuration | Add the channel to discord.webhooks and check the exact key name. |
| `2879g` | Could not initialize a hook | The plugin is installed, but its API is unavailable or incompatible. Check the plugin version. |
| `2968g` | Discord message is too long | Shorten the text or split it into multiple messages. |
| `3737g` | Integration is disabled by configuration | Enable the required integration or remove its call from the script. |
| `3763g` | PlaceholderAPI is not installed | The placeholder was skipped. Install PlaceholderAPI to use %ksl_*%. |
| `3783g` | A dependency cycle between integrations was detected | Break the cycle and load the provider before the consumer. |
| `3950g` | Integration initialization timed out | Check for a slow onEnable in the dependency and avoid network calls during startup. |
| `4248g` | A hook is already registered by another owner | Use a unique key or disable the duplicate integration. |
| `5193g` | Could not send a Discord message | Network error. Check whether the server can reach discord.com. |
| `5637g` | PlaceholderAPI expansion was not registered | Check expansion registration and when the placeholder is called. |
| `5789g` | Integration classloader conflict | Use compileOnly for the API and do not package duplicate classes. |
| `6572g` | Discord webhook is invalid | Create a new webhook and update config.yml. |
| `7120g` | The integration API does not contain a required method | Check the API version and binary compatibility. |
| `7506g` | Could not register PlaceholderAPI expansion | Check the PlaceholderAPI version. %ksl_*% placeholders will not work. |
| `8499g` | The integration did not respond in time | Check the network and service timeout; do not perform network operations on the main thread. |
| `8936g` | Discord channel is not configured | Add a webhook to config.yml: discord.webhooks.<channel>. |
| `9388g` | The integration plugin was not found | Install the dependency or disable the corresponding hook. |

### h — Addons and API

| Code | What happened | What to do |
|---|---|---|
| `0119h` | The addon registered one of its resources twice | Remove the old registration before creating a new one. |
| `0318h` | The requested addon service was not found | Check the service ID and whether the provider addon is loaded. |
| `0903h` | An addon is being unloaded while other addons depend on it | Unload dependent addons first or restart the server. |
| `1082h` | Addon resources remained after unload | Check task, listener, and callback registrations in onUnload. |
| `1692h` | Invalid addon descriptor | Check the addon's plugin.yml/metadata and required fields. |
| `1787h` | A library export was overwritten | Two scripts export the same key. Use unique keys in export(). |
| `1888h` | Addon context is already closed | Do not register resources after onUnload; check lifecycle order. |
| `2243h` | Addon shutdown took too long | Check for blocking work in onUnload and ensure background threads are released. |
| `2333h` | An addon with this ID is already loaded | The old instance was unloaded before replacement. Check for duplicate addon JARs. |
| `2510h` | The addon service is already closed | Retrieve the service again after the provider addon loads. |
| `3217h` | Service or hook type did not match | The class was loaded by different classloaders. Do not bundle the provider API in your JAR; use compileOnly. |
| `4200h` | The addon's dependency version is incompatible | Compare the minimum and actual API versions of the dependency. |
| `4647h` | Addon dependency is not loaded | Add depend to the addon's plugin.yml or use api.whenReady(id) { } inside onLoad. |
| `4752h` | Invalid addon identifier | Use a stable, unique ID in the supported format. |
| `5530h` | Exception in api.listen | An error occurred in a Bukkit event handler registered by the addon. |
| `5668h` | Addon version could not be parsed | Use a consistent version format. |
| `6077h` | KSL API version is too old for the addon | Update KotlinScriptLoader or use an earlier addon version. |
| `6870h` | Addon callback took too long | Move long-running work out of lifecycle callbacks. |
| `6960h` | Registration replaced a resource owned by another addon | A service, hook, shape, attribute, or placeholder with this name already exists. Use a unique key prefixed with your addon ID. |
| `7399h` | Could not register an addon resource | Check for a key conflict and the addon manager state. |
| `8390h` | Error while unloading an addon | onUnload threw an exception. The addon's registrations were still removed. |
| `9274h` | Exception in an addon callback | An error occurred in onAddonReady, a context extension, or an event-bus subscriber. Other callbacks still ran. |
| `9820h` | Addon failed to load | onLoad threw an exception; the addon's registrations were rolled back. See the error message for details. |

### i — Configuration and files

| Code | What happened | What to do |
|---|---|---|
| `0357i` | Could not save YAML | Check write permissions and available disk space. |
| `0648i` | Could not reload configuration | Fix the YAML and retry reload; make sure the active configuration is not corrupted. |
| `0718i` | Configuration file is missing | Restore the file from a template or restart KSL so it can create defaults. |
| `0835i` | Configuration migration failed | Back up config.yml and inspect the migration error message. |
| `2292i` | Could not create a backup | Check available disk space and write permissions. |
| `2671i` | Invalid database setting in config.yml | Correct the value according to the error message. See docs/Database-Guide.md for an example. |
| `3039i` | A file was expected, but another path type was found | Check the path and replace the directory with a file, or vice versa. |
| `3870i` | Unknown configuration setting | Check the key spelling; an unknown parameter may be ignored. |
| `4101i` | Could not read YAML | Check file permissions and whether another program has locked the file. |
| `4150i` | Configuration access was denied | Check the server user permissions for reading and writing. |
| `4572i` | Invalid configuration encoding | Save the YAML file as UTF-8. |
| `4875i` | A required setting is missing | Add the key to config.yml and consult the documentation. |
| `5275i` | No migration step exists for this configuration version | Update KSL as a whole; do not change the version field manually. |
| `6056i` | YAML contains a duplicate key | Remove the duplicate key; the YAML parser may select only one value. |
| `6414i` | YAML path escapes the scripts/ directory | loadYaml accepts only paths inside scripts/. |
| `6473i` | Configuration setting has the wrong type | Use the expected type: string, number, boolean, or list. |
| `6789i` | Could not write default settings | Check dataFolder permissions and disk space. |
| `7844i` | YAML file is corrupted | A *.broken-<time> copy was created and an empty config is being used. Fix indentation and quotes, then reload the script. |
| `8289i` | Could not safely replace the configuration file | Check permissions, filesystem status, and available disk space. |
| `8316i` | Path escapes the plugin directory | Use an allowed path inside dataFolder. |
| `9103i` | YAML contains too many aliases | Simplify the YAML and avoid excessive expansion of anchors/aliases. |
| `9556i` | Could not parse configuration | Check YAML indentation, quotes, and value types. |
| `9773i` | A setting is outside its allowed range | Use the range described in the comment or error message. |

### j — Scheduler and tasks

| Code | What happened | What to do |
|---|---|---|
| `0173j` | Cannot cancel another owner's task | Cancel a task only from its owning context. |
| `0204j` | Scheduler is closed | Do not submit tasks after KSL has stopped. |
| `1009j` | A repeating task was registered more than once | Check whether every() is created from every event callback or reload. |
| `2817j` | A task waited too long in the queue | Check worker-pool overload and reduce the number of background tasks. |
| `2934j` | Task owner is not registered | Make sure the task is created within a script or addon lifecycle. |
| `3470j` | Script task quota was exceeded | Stop unnecessary tasks and check loops that create delay/every tasks. |
| `4984j` | Task period is less than one tick | The period must be at least one tick. A value of 1 was used. |
| `5100j` | Operation ran on the wrong thread | Use sync/async according to Bukkit API requirements. |
| `5120j` | Task was cancelled | Check owner cancellation, script unload, and shutdown. |
| `5161j` | Scheduler is already stopping | Do not create new tasks from onDisable or after a script unload. |
| `5427j` | Task delay is too large | Pass a value within the allowed Long range. |
| `6223j` | Invalid task priority | Use a supported KSLWorkPriority value. |
| `6382j` | Task executor is unavailable | Check runtime status and worker-pool restart state. |
| `6662j` | Task queue is full | Reduce task submission frequency or increase queue-capacity after checking resources. |
| `7602j` | Worker thread was interrupted | Check task cancellation and handle InterruptedException correctly. |
| `8569j` | Task returned after its owner was unloaded | Do not apply a result to a closed context; check task cancellation. |
| `8728j` | Exception in a scheduled task | An error occurred in delay/every/sync/async or a similar callback. A repeating task will continue. |
| `8987j` | Repeating task stopped after errors | Fix the original handler error and create the task again. |
| `9576j` | Task exceeded the allowed execution time | Split long work into stages or move I/O to async code. |
| `9796j` | Task executor failed | Inspect the stack trace and check the state of resources used by the task. |

### k — Placeholders and text

| Code | What happened | What to do |
|---|---|---|
| `0149k` | Placeholder is already owned by another provider | Choose a unique key prefixed with your script or addon ID. |
| `0808k` | Unknown placeholder key | Check the exact key name and registration order. |
| `0946k` | Placeholder resolution took too long | Do not perform synchronous SQL or network requests during substitution. |
| `1241k` | An @placeholder@ token was not replaced in text | Check the key name in message()/richMessage(), or register it with textPlaceholder. |
| `2035k` | Invalid placeholder name | Use allowed characters and do not add format delimiters. |
| `2710k` | Invalid placeholder format | Check opening and closing delimiters. |
| `3554k` | Placeholder key is already in use | Another script or addon registered the same key. The new resolver replaced the old one. |
| `4383k` | Placeholder was not registered | Check that the script or addon registered the key before using it. |
| `4687k` | Placeholder has no player context | Check whether it is called from a player or console context. |
| `5297k` | Text placeholder is already registered | Rename the key or remove the previous registration. |
| `5715k` | Resolver returned an empty value | Return a default string or explicitly handle the missing value. |
| `5818k` | Text placeholder returned an unsupported value | Return a String or null according to the API. |
| `5868k` | Exception in a text placeholder | An error occurred in textPlaceholder { }. An empty string was substituted. |
| `7298k` | Exception in a %ksl_...% resolver | An error occurred in placeholder { }. Players received an empty string. |
| `7820k` | Recursive placeholder substitution detected | Do not call the same placeholder from within its own resolver. |
| `8429k` | Could not process expansion | Check the implementation and PlaceholderAPI compatibility. |
| `9611k` | Text placeholder name is reserved | player, world, online, maxplayers, health, and ping are built in. Choose another key. |

### l — System, diagnostics, and plugin shutdown

| Code | What happened | What to do |
|---|---|---|
| `0384l` | JVM is under memory pressure | Check heap, GC, and plugin memory usage; do not increase Xmx without investigating. |
| `0413l` | A KSL service thread terminated unexpectedly | Inspect the stack trace and restart the server after fixing the cause. |
| `0600l` | JVM detected a thread deadlock | Find the listed thread IDs and lock owner in the dump; fix lock acquisition order. |
| `0623l` | JVM/OS thread limit was reached | Check for thread leaks and avoid creating a new Thread for every operation. |
| `0668l` | An internal KSL invariant was violated | Save the complete stack trace and server version, then report the issue to KSL developers. |
| `0745l` | Main thread recovered after a stall | The server is processing ticks again. Save the watchdog dump and compare stable main-thread frames. |
| `2125l` | Error while stopping the plugin | Some resources were not released normally. If this happens on every shutdown, report the issue. |
| `2780l` | The dump reached its displayed-thread limit | Check the summary for the number of omitted threads and investigate thread leaks. |
| `3367l` | Not enough disk space to write diagnostics | Free disk space and check log/dump sizes. |
| `3398l` | Could not capture one watchdog snapshot | Check JVM health and permissions for thread diagnostics; other snapshots may still have been saved. |
| `4331l` | Unsupported Java version | Install the Java version required by the current Paper and KSL versions. |
| `5589l` | Could not write the watchdog dump | Check write permissions for dumps and available disk space. |
| `6256l` | Plugin shutdown order violated a dependency | Check depend/softdepend and do not unload a provider before its consumer. |
| `6451l` | Diagnostic collection was interrupted | Check whether the server stopped while the report was being written. |
| `7061l` | JVM is already shutting down | Do not start new operations; check the shutdown sequence. |
| `7092l` | Watchdog dump was size-limited | Check thread count and stacks; the full dump may have been shortened to protect disk space. |
| `7564l` | Could not delete an old watchdog dump | Check permissions for dumps and available disk space; old diagnostic files may remain. |
| `8098l` | Diagnostics are disabled by configuration | Enable diagnostics.watchdog.enabled in config.yml and restart the server. |
| `8181l` | Could not create .autocomplete.kts | Check write permissions for scripts/. Script execution is not affected. |
| `8647l` | Paper API does not match KSL expectations | Check the server version and use a compatible KSL build. |
| `8855l` | Could not close a system resource | Check resource ownership and shutdown errors. |
| `9454l` | Main-thread stall detected | Open the watchdog dump in plugins/KotlinScriptLoader/dumps and compare multiple stack snapshots. |
| `9848l` | KSL executor rejected a task | Check whether the executor is closed or its queue is full. |

The catalog is defined in src/main/kotlin/ru/privateserver/ksl/KSLError.kt. When adding a code, choose a unique number at least 20 positions away from neighboring entries, use the correct category letter, and add a clear title and user action. Then update the tests and this table.
