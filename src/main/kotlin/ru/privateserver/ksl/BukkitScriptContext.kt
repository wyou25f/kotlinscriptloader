package ru.privateserver.ksl

import com.zaxxer.hikari.HikariDataSource
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.Particle
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.EventExecutor
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.ResultSet
import java.time.Duration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

open class BukkitScriptContext(
    val plugin: KotlinScriptLoaderPlugin,
    val scriptName: String
) {

    private val mm: MiniMessage = MiniMessage.miniMessage()

    /** Non-fatal warning; execution continues. */
    fun warn(message: String) {
        plugin.logger.warning("[$scriptName] ⚠ $message")
    }

    /** Logs an error and aborts the current script operation, not the server. */
    fun error(message: String): Nothing = throw KSLScriptError(message)

    /** Logs a fatal error and unloads this script's runtime resources. */
    fun fatal(message: String): Nothing = throw KSLFatalScriptError(message)

    private val configFile: File
        get() = File(plugin.dataFolder, "$scriptName.yml")

    val config: YamlConfiguration by lazy { loadYamlFile(configFile) }

    private val yamlSqlQueryCache = KSLYamlSqlQueryCache()
    @Volatile private var yamlSqlQueriesLoaded = false

    /**
     * Reloads this script's YAML from disk and refreshes the cached SQL query map.
     * A malformed YAML file leaves the current in-memory config and cache untouched.
     *
     * @return number of cached SQL queries
     */
    @Synchronized
    fun reloadScriptConfig(): Int {
        val reloaded = YamlConfiguration()
        if (configFile.exists()) reloaded.load(configFile)
        config.loadFromString(reloaded.saveToString())
        return reloadYamlSqlQueries()
    }

    /**
     * Rebuilds the SQL query cache from the current in-memory YAML.
     * Call after changing database.queries with editConfig(...).
     *
     * @return number of cached SQL queries
     */
    @Synchronized
    fun reloadYamlSqlQueries(): Int {
        val section = config.getConfigurationSection("database.queries")
        val queries = linkedMapOf<String, String>()
        section?.getKeys(true)?.forEach { key ->
            val sql = section.get(key) as? String
            if (sql != null && sql.isNotBlank()) {
                queries["database.queries.$key"] = sql
            }
        }
        yamlSqlQueryCache.replace(queries)
        yamlSqlQueriesLoaded = true
        return queries.size
    }

    @Synchronized
    private fun yamlSqlQuery(path: String): String {
        if (!yamlSqlQueriesLoaded) reloadYamlSqlQueries()
        return yamlSqlQueryCache.get(path)
            ?: throw IllegalArgumentException("SQL query is missing or blank at YAML path '$path' in ${configFile.absolutePath}. Put this script's YAML file in the KotlinScriptLoader plugin folder (next to scripts/) and define the SQL query at YAML path '$path'.")
    }

    // YamlConfiguration is not thread-safe. Use editConfig for every mutation that may
    // overlap with another task; the lock covers both the mutation and the optional disk write.
    @Synchronized
    fun editConfig(save: Boolean = true, block: YamlConfiguration.() -> Unit) {
        config.block()
        if (save) saveConfig()
    }

    // Synchronized with editConfig so a save cannot overlap an editConfig block.
    // Direct mutations through the public config property must still be confined to one thread.
    @Synchronized
    fun saveConfig() {
        try { writeAtomically(config, configFile) }
        catch (ex: IOException) { plugin.logger.warning("[$scriptName] ${KSLError.YAML_SAVE_FAILED.tag} Не удалось сохранить $scriptName.yml: ${ex.message}") }
    }

    /**
     * Applies sequential config migrations and persists them atomically.
     *
     * Each integer key in [migrations] represents the version being migrated to.
     * Every version between the stored version + 1 and [targetVersion] must be present.
     * The original config is left untouched if a migration fails.
     */
    @Synchronized
    fun migrateConfig(
        targetVersion: Int,
        versionPath: String = "ksl.config-version",
        migrations: Map<Int, YamlConfiguration.() -> Unit>
    ): Int {
        require(versionPath.isNotBlank()) { "versionPath must not be blank" }
        val currentVersion = config.getInt(versionPath, 0)

        return KSLConfigMigrationRunner.migrate(
            currentVersion = currentVersion,
            targetVersion = targetVersion,
            migrations = migrations,
            createWorkingCopy = {
                YamlConfiguration().apply { loadFromString(config.saveToString()) }
            },
            setVersion = { yaml, version -> yaml.set(versionPath, version) },
            persist = { migrated ->
                writeAtomically(migrated, configFile)
                config.loadFromString(migrated.saveToString())
            }
        )
    }
    private val jsonCodec = Json {
        prettyPrint = true
        explicitNulls = true
        allowSpecialFloatingPointValues = false
    }

    private fun resolveJsonFile(fileName: String, operation: String): File {
        require(fileName.endsWith(".json", ignoreCase = true)) {
            "$operation expects a .json file: '$fileName'"
        }
        val root = plugin.scriptsFolder.canonicalFile
        val file = File(root, fileName).canonicalFile
        require(file.toPath() != root.toPath() && file.toPath().startsWith(root.toPath())) {
            "${KSLError.YAML_PATH_ESCAPE.tag} $operation: '$fileName' выходит за пределы scripts/"
        }
        return file
    }

    /** Reads JSON from scripts/. Returns null if missing; malformed files are preserved and reported. */
    @Synchronized
    fun loadJson(fileName: String): JsonElement? {
        val file = resolveJsonFile(fileName, "loadJson")
        if (!file.isFile) return null
        return try {
            jsonCodec.parseToJsonElement(file.readText(Charsets.UTF_8))
        } catch (ex: SerializationException) {
            val backup = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
            val saved = runCatching { file.copyTo(backup) }.isSuccess
            throw IllegalArgumentException(
                "Invalid JSON in '${file.name}': ${ex.message}" +
                    if (saved) " (backup: ${backup.name})" else " (backup could not be created)",
                ex
            )
        } catch (ex: IOException) {
            throw IllegalStateException("Could not read JSON file '${file.name}': ${ex.message}", ex)
        }
    }

    /** Reads a JSON object; returns null when missing and rejects non-object roots. */
    @Synchronized
    fun loadJsonObject(fileName: String): JsonObject? =
        loadJson(fileName)?.let {
            it as? JsonObject ?: throw IllegalArgumentException("JSON file '$fileName' must contain an object at its root")
        }

    /** Writes JSON atomically inside scripts/. Returns false and logs a warning on I/O failure. */
    @Synchronized
    fun saveJson(fileName: String, value: JsonElement): Boolean {
        val file = resolveJsonFile(fileName, "saveJson")
        return try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(jsonCodec.encodeToString(JsonElement.serializer(), value), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (ex: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (ex: IOException) {
            plugin.logger.warning("[$scriptName] Не удалось сохранить JSON '$fileName': ${ex.message}")
            false
        }
    }

    /** Loads or creates a JSON object, applies edits and saves it atomically. Untouched keys are preserved. */
    @Synchronized
    fun editJsonObject(fileName: String, block: JsonObjectBuilder.() -> Unit): JsonObject {
        val current = loadJsonObject(fileName)
        val updated = buildJsonObject {
            current?.forEach { (key, value) -> put(key, value) }
            block()
        }
        check(saveJson(fileName, updated)) { "Could not save JSON file '$fileName'" }
        return updated
    }

    private fun resolveYamlFile(fileName: String, operation: String): File {
        val root = plugin.scriptsFolder.canonicalFile
        val file = File(root, fileName).canonicalFile
        // Canonical paths also prevent escaping scripts/ through ../ or symbolic links.
        require(file.toPath() != root.toPath() && file.toPath().startsWith(root.toPath())) {
            "${KSLError.YAML_PATH_ESCAPE.tag} $operation: '$fileName' выходит за пределы scripts/"
        }
        return file
    }

    fun loadYaml(fileName: String): YamlConfiguration =
        loadYamlFile(resolveYamlFile(fileName, "loadYaml"))

    /** Loads, edits and optionally saves a YAML file inside scripts/. */
    @Synchronized
    fun editYaml(
        fileName: String,
        save: Boolean = true,
        block: YamlConfiguration.() -> Unit
    ): YamlConfiguration {
        val yaml = loadYamlFile(resolveYamlFile(fileName, "editYaml"))
        yaml.block()
        if (save) saveYaml(fileName, yaml)
        return yaml
    }

    /** Saves a configuration previously obtained from loadYaml() or editYaml(). */
    @Synchronized
    fun saveYaml(fileName: String, yaml: YamlConfiguration) {
        val file = resolveYamlFile(fileName, "saveYaml")
        try {
            file.parentFile?.mkdirs()
            writeAtomically(yaml, file)
        } catch (ex: IOException) {
            plugin.logger.warning("[$scriptName] ${KSLError.YAML_SAVE_FAILED.tag} Не удалось сохранить '$fileName': ${ex.message}")
        }
    }

    private fun loadYamlFile(file: File): YamlConfiguration {
        if (!file.exists()) { file.parentFile?.mkdirs(); file.createNewFile() }
        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (ex: InvalidConfigurationException) {
            // YamlConfiguration.loadConfiguration() swallows this and returns an empty config;
            // the next save would then overwrite the user's file with defaults.
            val backup = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
            val saved = runCatching { file.copyTo(backup) }.isSuccess
            plugin.logger.severe(
                "[$scriptName] ${KSLError.YAML_BROKEN.tag} ${file.name} содержит ошибку YAML (${ex.message?.lineSequence()?.firstOrNull()}). " +
                    if (saved) "Копия: ${backup.name}, используется пустой конфиг." else "Копию сделать не удалось, используется пустой конфиг."
            )
        } catch (ex: IOException) {
            plugin.logger.severe("[$scriptName] ${KSLError.YAML_READ_FAILED.tag} Не удалось прочитать ${file.name}: ${ex.message}")
        }
        return yaml
    }

    // A crash mid-write must not leave a truncated yml behind.
    private fun writeAtomically(yaml: YamlConfiguration, target: File) {
        val tmp = File(target.absoluteFile.parentFile, "${target.name}.tmp")
        yaml.save(tmp)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (ex: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private val warnedPlaceholders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val unmatchedPlaceholderRegex = Regex("""@[a-zA-Z0-9_]+@""")

    private fun formatPlaceholderValue(value: Any?): String = when (value) {
        null -> ""
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
        else -> value.toString()
    }

    private fun substitute(path: String, raw: String, player: Player?, replacements: Array<out Pair<String, Any?>>): String {
        var text = raw
        // Most messages contain no @token@ and no %placeholder%; those skip the scans below entirely.
        val hasTokens = text.indexOf('@') >= 0

        if (hasTokens) {
            if (player != null) {
                text = text
                    .replace("@player@", player.name)
                    .replace("@world@", player.world.name)
                    .replace("@online@", Bukkit.getOnlinePlayers().size.toString())
                    .replace("@maxplayers@", Bukkit.getMaxPlayers().toString())
                    .replace("@health@", formatPlaceholderValue(player.health))
                    .replace("@ping@", player.ping.toString())
            }
            replacements.forEach { (key, value) -> text = text.replace("@$key@", formatPlaceholderValue(value)) }
            text = KSLTextPlaceholders.apply(text) { key -> plugin.resolveTextPlaceholder(key, player) }
        }

        if (player != null && plugin.papiEnabled && text.indexOf('%') >= 0) {
            text = KSLPapiResolver.resolve(player, text)
        }

        if (text.indexOf('@') >= 0) {
            unmatchedPlaceholderRegex.findAll(text).forEach { match ->
                val warnKey = "$path:${match.value}"
                if (warnedPlaceholders.add(warnKey)) {
                    plugin.logger.warning("[$scriptName] ${KSLError.PLACEHOLDER_UNMATCHED.tag} В '$path' остался незаменённый плейсхолдер ${match.value} — проверь имя ключа в message()/messageList()/richMessage() или зарегистрируй textPlaceholder")
                }
            }
        }

        return text
    }

    // @key@ in message()/richMessage(); removed when the script unloads. Names of built-in tokens are reserved.
    fun registerTextPlaceholder(key: String, resolver: (Player?) -> String?) =
        plugin.registerTextPlaceholder("script:$scriptName", key, resolver)

    fun YamlConfiguration.message(path: String, vararg replacements: Pair<String, Any?>): String {
        val raw = getString(path) ?: return path
        return substitute(path, raw, null, replacements).replace('&', '§')
    }

    fun YamlConfiguration.message(path: String, player: Player, vararg replacements: Pair<String, Any?>): String {
        val raw = getString(path) ?: return path
        return substitute(path, raw, player, replacements).replace('&', '§')
    }

    fun YamlConfiguration.messageList(path: String, vararg replacements: Pair<String, Any?>): List<String> =
        getStringList(path).map { substitute(path, it, null, replacements).replace('&', '§') }

    fun YamlConfiguration.messageList(path: String, player: Player, vararg replacements: Pair<String, Any?>): List<String> =
        getStringList(path).map { substitute(path, it, player, replacements).replace('&', '§') }

    fun YamlConfiguration.richMessage(path: String, vararg replacements: Pair<String, Any?>): Component =
        parseMM(substitute(path, getString(path) ?: path, null, replacements))

    fun YamlConfiguration.richMessage(path: String, player: Player, vararg replacements: Pair<String, Any?>): Component =
        parseMM(substitute(path, getString(path) ?: path, player, replacements))

    inline fun <reified T> YamlConfiguration.getOrSetDefault(path: String, default: T): T {
        if (!contains(path)) {
            set(path, default)
            if (this === config) saveConfig()
        }
        return get(path) as? T ?: default
    }

    /** Reads a value without modifying YAML when the key is absent. */
    inline fun <reified T> YamlConfiguration.getOrDefault(path: String, default: T): T =
        get(path) as? T ?: default

    /** Sets a value only when the path does not already exist. */
    fun YamlConfiguration.setIfAbsent(path: String, value: Any?): Boolean {
        if (contains(path)) return false
        set(path, value)
        return true
    }

    fun YamlConfiguration.string(path: String, default: String = ""): String =
        getString(path) ?: default

    fun YamlConfiguration.int(path: String, default: Int = 0): Int = getInt(path, default)
    fun YamlConfiguration.long(path: String, default: Long = 0L): Long = getLong(path, default)
    fun YamlConfiguration.double(path: String, default: Double = 0.0): Double = getDouble(path, default)
    fun YamlConfiguration.bool(path: String, default: Boolean = false): Boolean = getBoolean(path, default)
    fun YamlConfiguration.strings(path: String): List<String> = getStringList(path)

    /** Creates the section if needed and applies a receiver lambda to it. */
    fun YamlConfiguration.section(
        path: String,
        create: Boolean = true,
        block: ConfigurationSection.() -> Unit
    ) {
        val target = getConfigurationSection(path)
            ?: if (create) createSection(path) else return
        target.block()
    }

    fun table(name: String): KSLQuickTable = plugin.tableStore.table(name)

    val database: HikariDataSource get() = plugin.database

    /** Validates script YAML; missing/invalid values can be replaced by schema defaults. */
    @Synchronized
    fun validateConfig(
        applyDefaults: Boolean = true,
        schema: KSLYamlSchema.() -> Unit
    ): List<KSLYamlValidationIssue> {
        val issues = KSLYamlSchema().apply(schema).validate(config, applyDefaults)
        if (applyDefaults && issues.any { it.corrected }) saveConfig()
        return issues
    }

    /** Executes a cached prepared SQL statement stored at the given path in this script's YAML. */
    fun dbExecuteYaml(queryPath: String, vararg args: Any?) {
        dbExecute(yamlSqlQuery(queryPath), *args)
    }

    /** Runs a cached prepared SQL query stored at the given path in this script's YAML. */
    fun dbQueryYaml(queryPath: String, vararg args: Any?, block: (ResultSet) -> Unit) {
        dbQuery(yamlSqlQuery(queryPath), *args, block = block)
    }

    fun dbExecute(sql: String, vararg args: Any?) {
        plugin.dbExecutor.submit {
            try {
                database.connection.use { conn ->
                    conn.prepareStatement(sql).use { ps ->
                        args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
                        ps.executeUpdate()
                    }
                }
            } catch (ex: Exception) {
                plugin.logger.warning("[$scriptName] ${KSLError.DB_SCRIPT_QUERY_FAILED.tag} dbExecute: ${ex.message}")
            }
        }
    }

    fun dbQuery(sql: String, vararg args: Any?, block: (ResultSet) -> Unit) {
        plugin.dbExecutor.submit {
            try {
                database.connection.use { conn ->
                    conn.prepareStatement(sql).use { ps ->
                        args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
                        ps.executeQuery().use(block)
                    }
                }
            } catch (ex: Exception) {
                plugin.logger.warning("[$scriptName] ${KSLError.DB_SCRIPT_QUERY_FAILED.tag} dbQuery: ${ex.message}")
            }
        }
    }

    /**
     * Short alias for [dbExecute]. SQL parameters are bound through a prepared statement.
     */
    fun execute(sql: String, vararg args: Any?) = dbExecute(sql, *args)

    /**
     * Short alias for [dbQuery]. The ResultSet is the lambda receiver, so use
     * `next()`, `getLong("balance")`, etc. directly inside the block.
     *
     * The callback runs on KSL's database executor, not the Bukkit main thread.
     */
    fun query(sql: String, vararg args: Any?, block: ResultSet.() -> Unit) {
        dbQuery(sql, *args) { resultSet -> resultSet.block() }
    }

    /** Short alias for [dbExecuteYaml]. */
    fun executeYaml(queryPath: String, vararg args: Any?) = dbExecuteYaml(queryPath, *args)

    /** Short alias for [dbQueryYaml] with ResultSet receiver syntax. */
    fun queryYaml(queryPath: String, vararg args: Any?, block: ResultSet.() -> Unit) {
        dbQueryYaml(queryPath, *args) { resultSet -> resultSet.block() }
    }

    // Scheduler tasks swallow nothing: an uncaught exception would print a raw Bukkit trace every period.
    private fun guarded(where: String, block: () -> Unit) = Runnable {
        try {
            plugin.measureScriptExecution(scriptName, "task:$where") {
                val monitor = plugin.watchdogMonitor
                if (monitor != null) monitor.trackOperation("script '$scriptName' task '$where'", block)
                else block()
            }
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, where, ex, KSLError.TASK_FAILED)
        }
    }

    private fun track(task: BukkitTask): Int {
        plugin.trackTask(scriptName, task.taskId)
        return task.taskId
    }

    fun runAsync(block: () -> Unit): Int =
        track(Bukkit.getScheduler().runTaskAsynchronously(plugin, guarded("runAsync", block)))

    /** Runs work on KSL's bounded priority-aware worker pool. Bukkit world access belongs in runSync(). */
    fun worker(priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> Unit): Long =
        plugin.submitWorker(scriptName, priority) { plugin.measureScriptExecution(scriptName, "worker:$priority", block) }

    fun cpu(block: () -> Unit): Long = worker(block = block)
    fun work(block: () -> Unit): Long = worker(block = block)
    fun cancelWorker(taskId: Long): Boolean = plugin.cancelWorker(taskId)

    fun runSync(block: () -> Unit): Int =
        track(Bukkit.getScheduler().runTask(plugin, guarded("runSync", block)))

    fun delay(ticks: Long, block: () -> Unit): Int =
        track(Bukkit.getScheduler().runTaskLater(plugin, guarded("delay", block), ticks))

    fun every(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int {
        val period = KSLErrors.validPeriod(plugin, scriptName, "every", ticks)
        return track(Bukkit.getScheduler().runTaskTimer(plugin, guarded("every", block), initialDelay, period))
    }

    /**
     * Compatibility alias for scripts using the older named argument `period`.
     * Prefer `every(ticks = ...)` in new scripts.
     */
    @Suppress("UNUSED_PARAMETER")
    fun every(period: Long, initialDelay: Long = 0L, compatibilityAlias: Unit = Unit, block: () -> Unit): Int =
        every(ticks = period, initialDelay = initialDelay, block = block)

    fun delayAsync(ticks: Long, block: () -> Unit): Int =
        track(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, guarded("delayAsync", block), ticks))

    fun everyAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int {
        val period = KSLErrors.validPeriod(plugin, scriptName, "everyAsync", ticks)
        return track(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, guarded("everyAsync", block), initialDelay, period))
    }

    // Short DSL aliases; original method names remain available.
    fun sync(block: () -> Unit): Int = runSync(block)
    fun async(block: () -> Unit): Int = runAsync(block)
    fun after(ticks: Long, block: () -> Unit): Int = delay(ticks, block)
    fun repeat(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int =
        every(ticks, initialDelay, block)

    // Works for any id returned by delay/every/runSync/...; stopEffect is the same call for effects.
    fun cancelTask(taskId: Int) {
        Bukkit.getScheduler().cancelTask(taskId)
        plugin.untrackTask(scriptName, taskId)
    }

    inline fun <reified T : Event> onEvent(
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        crossinline action: T.() -> Unit
    ) {
        val listener = object : Listener {}
        val executor = EventExecutor { _, event ->
            if (event is T) {
                try {
                    plugin.measureScriptExecution(scriptName, "event:${T::class.simpleName}") {
                        event.action()
                    }
                } catch (ex: Throwable) {
                    KSLErrors.log(plugin, scriptName, "onEvent<${T::class.simpleName}>", ex, KSLError.EVENT_HANDLER_FAILED)
                }
            }
        }
        Bukkit.getPluginManager().registerEvent(T::class.java, listener, priority, executor, plugin, ignoreCancelled)
        plugin.trackListener(scriptName, listener)
    }

    inline fun <reified T : Event> on(
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        crossinline action: T.() -> Unit
    ) = onEvent<T>(priority, ignoreCancelled, action)

    fun registerCommand(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = null,
        tabComplete: ((Player, Array<String>) -> List<String>)? = null,
        action: (Player, Array<String>) -> Unit
    ) {
        val completer: ((CommandSender, Array<String>) -> List<String>)? = tabComplete?.let { tab ->
            { sender: CommandSender, args: Array<String> -> if (sender is Player) tab(sender, args) else emptyList() }
        }
        addCommand(DynamicCommand(name, aliases, scriptName, plugin, permission, completer, true) { sender, args ->
            action(sender as Player, args)
        })
    }

    /** Short alias for registerCommand(). */
    fun cmd(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = null,
        tabComplete: ((Player, Array<String>) -> List<String>)? = null,
        action: (Player, Array<String>) -> Unit
    ) = registerCommand(name, aliases, permission, tabComplete, action)

    // Like registerCommand, but console and command blocks may run it too.
    fun registerCommandAny(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = null,
        tabComplete: ((CommandSender, Array<String>) -> List<String>)? = null,
        action: (CommandSender, Array<String>) -> Unit
    ) {
        addCommand(DynamicCommand(name, aliases, scriptName, plugin, permission, tabComplete, false, action))
    }

    /** Short alias for registerCommandAny(). */
    fun cmdAny(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = null,
        tabComplete: ((CommandSender, Array<String>) -> List<String>)? = null,
        action: (CommandSender, Array<String>) -> Unit
    ) = registerCommandAny(name, aliases, permission, tabComplete, action)

    private fun addCommand(command: DynamicCommand) {
        Bukkit.getCommandMap().register(plugin.name.lowercase(), command)
        plugin.trackCommand(scriptName, command)
    }

    // Subscriptions are dropped automatically when the script unloads.
    fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable =
        plugin.addonManager.subscribeFromScript(scriptName, topic, handler)

    fun publish(topic: String, payload: Any? = null): Int = plugin.addonManager.publish(topic, payload)

    fun parseMM(text: String): Component = mm.deserialize(text)

    fun broadcastMM(text: String) = Bukkit.getServer().broadcast(parseMM(text))

    fun broadcast(message: String) =
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(message.replace('&', '§')) }

    /** Snapshot of currently online players; safe to iterate while handling a script callback. */
    fun onlinePlayers(): List<Player> = Bukkit.getOnlinePlayers().toList()

    /** Number of online players at the time of access. */
    val onlineCount: Int
        get() = Bukkit.getOnlinePlayers().size

    /** Maximum player count configured for this server. */
    val maxPlayers: Int
        get() = Bukkit.getMaxPlayers()

    /**
     * Finds an online player by exact name (case-insensitive) or UUID.
     * Returns null for offline or unknown players.
     */
    fun findPlayer(nameOrUuid: String): Player? {
        val query = nameOrUuid.trim()
        if (query.isEmpty()) return null
        val uuid = runCatching { java.util.UUID.fromString(query) }.getOrNull()
        if (uuid != null) return Bukkit.getPlayer(uuid)
        return Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(query, ignoreCase = true) }
    }

    /** Sends MiniMessage to any command sender, including console. */
    fun CommandSender.sendRichMessage(text: String) = sendMessage(parseMM(text))

    /** True when the sender has at least one of the supplied permission nodes. */
    fun CommandSender.hasPermissionAny(vararg permissions: String): Boolean =
        permissions.any { it.isNotBlank() && hasPermission(it) }

    /** True when the sender has every supplied permission node. */
    fun CommandSender.hasPermissionAll(vararg permissions: String): Boolean =
        permissions.all { it.isNotBlank() && hasPermission(it) }

    fun Player.sendRichMessage(text: String) = sendMessage(parseMM(text))

    fun Player.sendActionBarMessage(text: String) = sendActionBar(parseMM(text))

    fun Player.showRichTitle(
        title: String,
        subtitle: String = "",
        fadeIn: Int = 10,
        stay: Int = 70,
        fadeOut: Int = 20
    ) {
        val times = Title.Times.times(
            Duration.ofMillis(fadeIn * 50L),
            Duration.ofMillis(stay * 50L),
            Duration.ofMillis(fadeOut * 50L)
        )
        showTitle(Title.title(parseMM(title), parseMM(subtitle), times))
    }

    var Player.balance: Double?
        get() = plugin.essentials?.balance(this)
        set(value) { if (value != null) plugin.essentials?.setBalance(this, value) }

    val Player.group: String?
        get() = plugin.luckPerms?.primaryGroup(this)

    val Player.prefix: String?
        get() = plugin.luckPerms?.prefix(this)

    fun ItemStack.setTag(key: String, value: String) {
        val meta = itemMeta ?: return
        meta.persistentDataContainer.set(NamespacedKey(plugin, key), PersistentDataType.STRING, value)
        itemMeta = meta
    }

    fun ItemStack.getTag(key: String): String? =
        itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, key), PersistentDataType.STRING)

    fun ItemStack.hasTag(key: String): Boolean =
        itemMeta?.persistentDataContainer?.has(NamespacedKey(plugin, key), PersistentDataType.STRING) == true

    fun registerPlaceholder(key: String, resolver: (OfflinePlayer) -> String?) {
        if (!plugin.papiEnabled) {
            plugin.logger.warning("[$scriptName] ${KSLError.PAPI_MISSING.tag} PlaceholderAPI не найден — плейсхолдер '$key' пропущен"); return
        }
        plugin.registerPlaceholder(scriptName, key, resolver)
    }

    fun luckPermsGroup(player: Player): String? = plugin.luckPerms?.primaryGroup(player)
    fun luckPermsPrefix(player: Player): String? = plugin.luckPerms?.prefix(player)
    fun luckPermsSuffix(player: Player): String? = plugin.luckPerms?.suffix(player)
    fun hasGroup(player: Player, group: String): Boolean = plugin.luckPerms?.isInGroup(player, group) ?: false

    fun balance(player: Player): Double? = plugin.essentials?.balance(player)
    fun setBalance(player: Player, amount: Double) { plugin.essentials?.setBalance(player, amount) }
    fun isAfk(player: Player): Boolean = plugin.essentials?.isAfk(player) ?: false

    fun weSelection(player: Player): Pair<org.bukkit.Location, org.bukkit.Location>? = plugin.worldEdit?.selection(player)
    fun weSelectionVolume(player: Player): Long? = plugin.worldEdit?.selectionVolume(player)
    fun weFillSelection(player: Player, material: org.bukkit.Material): Int? = plugin.worldEdit?.fillSelection(player, material)
    fun weHasClipboard(player: Player): Boolean = plugin.worldEdit?.hasClipboard(player) ?: false

    fun wgRegionsAt(location: org.bukkit.Location): List<String> = plugin.worldGuard?.regionsAt(location) ?: emptyList()
    fun wgIsInRegion(location: org.bukkit.Location, regionId: String): Boolean = plugin.worldGuard?.isInRegion(location, regionId) ?: false
    fun wgCanBuild(player: Player, location: org.bukkit.Location): Boolean = plugin.worldGuard?.canBuild(player, location) ?: true
    fun wgCanPvp(player: Player, location: org.bukkit.Location): Boolean = plugin.worldGuard?.canPvp(player, location) ?: true
    fun wgFlag(location: org.bukkit.Location, flagName: String, player: Player? = null): String? = plugin.worldGuard?.flagValue(location, flagName, player)

    fun vaultBalance(player: OfflinePlayer): Double? = plugin.vault?.balance(player)
    fun vaultDeposit(player: OfflinePlayer, amount: Double): Boolean = plugin.vault?.deposit(player, amount) ?: false
    fun vaultWithdraw(player: OfflinePlayer, amount: Double): Boolean = plugin.vault?.withdraw(player, amount) ?: false
    fun vaultHas(player: OfflinePlayer, amount: Double): Boolean = plugin.vault?.has(player, amount) ?: false
    fun vaultFormat(amount: Double): String = plugin.vault?.format(amount) ?: amount.toString()
    fun vaultHasPermission(player: Player, node: String): Boolean = plugin.vault?.hasPermission(player, node) ?: player.hasPermission(node)
    fun vaultGroup(player: Player): String? = plugin.vault?.playerGroup(player)
    fun vaultInGroup(player: Player, group: String): Boolean = plugin.vault?.playerInGroup(player, group) ?: false

    fun setSkin(player: Player, skinName: String): Boolean = plugin.skinRestorer?.setSkin(player, skinName) ?: false
    fun setSkinRaw(player: Player, value: String, signature: String): Boolean =
        plugin.skinRestorer?.setSkinRaw(player, value, signature) ?: false

    fun cooldown(player: Player, key: String, seconds: Long): Boolean =
        plugin.cooldownStore.tryUse("$scriptName:$key:${player.uniqueId}", seconds)

    fun cooldownRemaining(player: Player, key: String, seconds: Long): Long =
        plugin.cooldownStore.remaining("$scriptName:$key:${player.uniqueId}", seconds)

    fun resetCooldown(player: Player, key: String) =
        plugin.cooldownStore.reset("$scriptName:$key:${player.uniqueId}")

    fun export(key: String, value: Any) {
        plugin.libraryRegistry.export(scriptName, key, value)
    }

    /** Namespaced, typed and version-labelled exports shared between scripts. */
    val meslib2: ru.privateserver.ksl.meslib2.MesLib2 by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ru.privateserver.ksl.meslib2.MesLib2(
            owner = scriptName,
            exportValue = plugin.libraryRegistry::export,
            getValue = plugin.libraryRegistry::get,
            ownerOf = plugin.libraryRegistry::ownerOf
        )
    }

    /** Short aliases for the MesLib2 script-library facade. */
    val ml2: ru.privateserver.ksl.meslib2.MesLib2 get() = meslib2
    val lib2: ru.privateserver.ksl.meslib2.MesLib2 get() = meslib2
    val libs: ru.privateserver.ksl.meslib2.MesLib2 get() = meslib2

    inline fun <reified T : Any> library(key: String): T? = plugin.libraryRegistry.get(key) as? T

    inline fun <reified T : Any> requireLibrary(key: String): T =
        library<T>(key) ?: error("Библиотека '$key' не найдена — проверь, что скрипт с export(\"$key\", ...) загружен раньше (см. @requires)")

    fun gui(title: String, rows: Int = 3, builder: KSLGuiInstance.() -> Unit): KSLGuiInstance {
        val instance = KSLGuiInstance(scriptName, plugin, rows, title)
        plugin.trackGui(scriptName)
        instance.builder()
        return instance
    }

    fun <T> persist(key: String, persistent: Boolean = false, default: () -> T): KSLPersistProperty<T> =
        plugin.persistStore.property(scriptName, key, persistent, default)

    fun executeConsole(command: String) {
        // The one DSL call that runs with full console rights; op, stop and similar are refused by default.
        if (plugin.isConsoleCommandBlocked(command)) {
            plugin.logger.warning("[$scriptName] ${KSLError.COMMAND_BLOCKED.tag} Консольная команда '${command.trim().substringBefore(' ')}' заблокирована (security.console-command-blocklist)")
            return
        }
        plugin.logger.info("[KSL] Скрипт '$scriptName' выполняет консольную команду: $command")
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command)
        } else {
            runSync { org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command) }
        }
    }

    inline fun <reified T : Any> service(key: String): T? = plugin.addonManager.getService(key) as? T

    fun discordSend(channel: String, message: String) {
        plugin.discord?.send(channel, message)
            ?: plugin.logger.warning("[$scriptName] ${KSLError.DISCORD_NOT_CONFIGURED.tag} discordSend: Discord не настроен в config.yml")
    }

    fun discordEmbed(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = ""
    ) {
        plugin.discord?.sendEmbed(channel, title, description, color, footer)
            ?: plugin.logger.warning("[$scriptName] ${KSLError.DISCORD_NOT_CONFIGURED.tag} discordEmbed: Discord не настроен в config.yml")
    }

    /** Sends a Discord message asynchronously; true means Discord returned a successful HTTP status. */
    fun discordSendAsync(channel: String, message: String, allowMentions: Boolean = false): java.util.concurrent.CompletableFuture<Boolean> =
        plugin.discord?.sendAsync(channel, message, allowMentions)
            ?: java.util.concurrent.CompletableFuture.completedFuture(false)

    /** Sends an embed with optional fields without blocking the Bukkit server thread. */
    fun discordEmbedAsync(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = "",
        fields: List<KSLDiscordEmbedField> = emptyList(),
        allowMentions: Boolean = false
    ): java.util.concurrent.CompletableFuture<Boolean> =
        plugin.discord?.sendEmbedAsync(channel, title, description, color, footer, allowMentions, fields)
            ?: java.util.concurrent.CompletableFuture.completedFuture(false)

    fun Location.playEffect(particle: Particle, builder: KSLEffectSpec.() -> Unit): Int {
        val frozen = this.clone()
        val spec = KSLEffectSpec(particle, { frozen }, plugin.addonManager.effectShapes)
        spec.builder()
        return plugin.effectManager.play(scriptName, spec)
    }

    fun Entity.playEffect(particle: Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int {
        val entity = this
        val supplier: () -> Location = if (follow) {
            { entity.location }
        } else {
            val frozen = entity.location.clone()
            ({ frozen })
        }
        val spec = KSLEffectSpec(particle, supplier, plugin.addonManager.effectShapes)
        if (follow) spec.stopCondition = { !entity.isValid }
        spec.builder()
        return plugin.effectManager.play(scriptName, spec)
    }

    fun stopEffect(taskId: Int) = cancelTask(taskId)

    fun spawnCustomEntity(
        location: Location,
        template: String,
        removeOnReload: Boolean = true,
        builder: KSLEntityBuilder.() -> Unit = {}
    ): LivingEntity {
        val attrs = KSLTemplateParser.parse(template)
        val typeName = attrs["TYPE"]
        val type = typeName?.let { runCatching { EntityType.valueOf(it.uppercase()) }.getOrNull() }
            ?: EntityType.ZOMBIE.also {
                if (typeName != null) plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_UNKNOWN_TYPE.tag} spawnCustomEntity: неизвестный TYPE:$typeName — использую ZOMBIE")
            }
        val world = location.world ?: error("${KSLError.ENTITY_NO_WORLD.tag} spawnCustomEntity: у локации нет мира")
        val spawned = world.spawnEntity(location, type)
        val entity = spawned as? LivingEntity ?: run {
            spawned.remove()
            error("${KSLError.ENTITY_NOT_LIVING.tag} spawnCustomEntity: TYPE:${type.name} — не LivingEntity (нужен живой моб вроде ZOMBIE/SKELETON/COW), заспавненная сущность удалена")
        }

        plugin.entityManager.trackSpawn(scriptName, entity, removeOnReload)
        try {
            attrs["NAME"]?.let { name ->
                entity.customName(parseMM(name))
                entity.isCustomNameVisible = true
            }
            attrs["HP"]?.toDoubleOrNull()?.let { hp ->
                // Bukkit throws outside (0, 1024]; after the spawn that would leave an untouched mob behind.
                if (hp > 0.0 && hp <= 1024.0) {
                    @Suppress("DEPRECATION")
                    entity.setMaxHealth(hp)
                    entity.health = hp
                } else {
                    plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_BAD_HP.tag} spawnCustomEntity: HP:$hp вне (0, 1024] — игнорирую")
                }
            }
            attrs["AI"]?.toBooleanStrictOrNull()?.let { entity.setAI(it) }
            if (entity is Ageable) {
                attrs["BABY"]?.toBooleanStrictOrNull()?.let { isBaby -> if (isBaby) entity.setBaby() else entity.setAdult() }
            }
            // Template keys beyond the core ones come from the attribute registry (built-ins and addons).
            attrs.forEach { (key, value) ->
                if (key in KSLEntityAttributes.coreKeys) return@forEach
                val handler = plugin.addonManager.entityAttributes.get(key)
                if (handler == null) {
                    val known = (KSLEntityAttributes.coreKeys + plugin.addonManager.entityAttributes.keys()).sorted()
                    plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_UNKNOWN_ATTRIBUTE.tag} spawnCustomEntity: неизвестный атрибут $key (доступны: ${known.joinToString(", ")})")
                    return@forEach
                }
                runCatching { handler(entity, value) }
                    .onFailure { plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_ATTRIBUTE_FAILED.tag} spawnCustomEntity: $key:$value — ${it.message}") }
            }
            KSLEntityBuilder(entity, plugin, scriptName).builder()
        } catch (ex: Throwable) {
            // Do not leave a half-initialised mob in the world.
            plugin.entityManager.forget(scriptName, entity)
            entity.remove()
            throw ex
        }
        return entity
    }

    fun LivingEntity.behavior(builder: KSLEntityBehaviorBuilder.() -> Unit) {
        KSLEntityBehaviorBuilder(plugin, scriptName, this).builder()
    }
}
