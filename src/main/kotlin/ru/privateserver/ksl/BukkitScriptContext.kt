package ru.privateserver.ksl

import com.zaxxer.hikari.HikariDataSource
import de.tr7zw.changeme.nbtapi.NBT
import net.kyori.adventure.text.Component
import ru.privateserver.ksl.std.component.toMiniMessageComponent
import ru.privateserver.ksl.std.text.colorizeLegacy
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.TileState
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataContainer
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
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import ru.privateserver.ksl.std.toml.KSLTomlCodec
import ru.privateserver.ksl.kslshu.KSLTaskScope
import ru.privateserver.ksl.rt.tasks.KSLTaskFailurePolicy

open class BukkitScriptContext(
    plugin: KotlinScriptLoaderPlugin,
    val scriptName: String
) {

    private val kslPlugin = plugin
    // Capture the scope once. A context created by an old script generation must not
    // start owning resources in the replacement generation after /ksl reload.
    private val contextRuntimeScope = plugin.scriptRuntimeScope(scriptName)
    private val scopedTableViews = java.util.concurrent.ConcurrentHashMap<String, KSLQuickTable>()

    @PublishedApi
    internal fun isGenerationActive(): Boolean = !contextRuntimeScope.isClosed

    @PublishedApi
    internal fun requireActiveGeneration(operation: String) {
        check(isGenerationActive()) {
            "[$scriptName] $operation: этот контекст принадлежит выгруженному поколению; повторно получи контекст после reload"
        }
    }

    @PublishedApi
    internal fun trackListenerForCurrentGeneration(listener: Listener) {
        internalPlugin.trackListener(scriptName, listener, contextRuntimeScope)
    }

    /**
     * Raw plugin access is retained for trusted scripts and addon compatibility.
     * Sandboxed scripts must use the explicit KSL context API instead.
     */
    val plugin: KotlinScriptLoaderPlugin
        get() {
            check(!contextRuntimeScope.isClosed) {
                "[$scriptName] This script context belongs to an unloaded runtime. Reload the script before using the plugin API."
            }
            KSLSandboxAccessPolicy.requireTrustedAccess(scriptName, kslPlugin.isSandboxed(scriptName), "plugin")
            return kslPlugin
        }

    @PublishedApi
    @get:JvmSynthetic
    internal val internalPlugin: KotlinScriptLoaderPlugin
        get() = kslPlugin

    /** Per-script scope; caches, profilers and owned AutoCloseable resources close on reload. */
    val runtimeScope: ru.privateserver.ksl.rt.KSLRuntimeScope
        get() = contextRuntimeScope

    /** Official owner-scoped scheduler. Its tasks are cancelled automatically on generation unload. */
    val scheduler: KSLTaskScope
        get() {
            requireActiveGeneration("scheduler")
            return internalPlugin.taskScope(scriptName, contextRuntimeScope)
        }

    /** Registers an AutoCloseable resource for automatic cleanup when this script unloads. */
    fun <T : AutoCloseable> own(resource: T): T = runtimeScope.own(resource)

    /** Registers a cleanup callback. Close the returned handle to unregister it early. */
    fun onUnload(action: () -> Unit): AutoCloseable = runtimeScope.onClose(action)

    private fun requireScriptDatabaseAccess() {
        internalPlugin.requireScriptDatabaseAccess(scriptName)
        check(!contextRuntimeScope.isClosed) {
            "[$scriptName] This script context belongs to an unloaded runtime. Reload the script before using its database API."
        }
    }

    private fun <T> submitDatabaseOperation(
        executor: java.util.concurrent.Executor,
        operation: () -> T
    ): java.util.concurrent.CompletableFuture<T> {
        requireScriptDatabaseAccess()
        return contextRuntimeScope.submit(executor) {
            requireScriptDatabaseAccess()
            operation()
        }
    }

    private val scriptDatabase: HikariDataSource
        get() {
            requireScriptDatabaseAccess()
            return internalPlugin.database
        }

    private fun requirePrimaryThread(operation: String) {
        check(Bukkit.isPrimaryThread()) {
            "[$scriptName] $operation требует главного потока сервера; используй runSync { ... }"
        }
    }

    /** Non-fatal warning; execution continues. */
    fun warn(message: String) {
        internalPlugin.logger.warning("[$scriptName] ⚠ $message")
    }

    /** Logs an error and aborts the current script operation, not the server. */
    fun error(message: String): Nothing = throw KSLScriptError(message)

    /** Logs a fatal error and unloads this script's runtime resources. */
    fun fatal(message: String): Nothing = throw KSLFatalScriptError(message)

    private val configFile: File
        get() = File(internalPlugin.dataFolder, "$scriptName.yml")

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

    private fun featurePath(name: String): String {
        val clean = name.trim()
        require(clean.matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))) {
            "Feature name must be a dot-separated key (letters, numbers, '_' or '-')"
        }
        return "features.$clean"
    }

    /** Reads a script-local feature flag from its YAML config. */
    @Synchronized
    fun featureEnabled(name: String, default: Boolean = true): Boolean =
        config.getBoolean(featurePath(name), default)

    /** Persists a script-local feature flag and returns its new state. */
    @Synchronized
    fun setFeatureEnabled(name: String, enabled: Boolean): Boolean {
        config.set(featurePath(name), enabled)
        saveConfig()
        return enabled
    }

    /** Flips a feature flag and returns the resulting state. */
    @Synchronized
    fun toggleFeature(name: String): Boolean =
        setFeatureEnabled(name, !featureEnabled(name))

    /** Short aliases for script feature flags. */
    fun feature(name: String, default: Boolean = true): Boolean = featureEnabled(name, default)
    fun enableFeature(name: String): Boolean = setFeatureEnabled(name, true)
    fun disableFeature(name: String): Boolean = setFeatureEnabled(name, false)

    // Synchronized with editConfig so a save cannot overlap an editConfig block.
    // Direct mutations through the public config property must still be confined to one thread.
    @Synchronized
    fun saveConfig() {
        try { writeAtomically(config, configFile) }
        catch (ex: IOException) { internalPlugin.logger.warning("[$scriptName] ${KSLError.YAML_SAVE_FAILED.tag} Не удалось сохранить $scriptName.yml: ${ex.message}") }
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
        // Accept JSONC-style comments and trailing commas on input for existing config files.
        // Encoding remains standards-compliant JSON and does not write comments.
        allowComments = true
        allowTrailingComma = true
    }

    private fun resolveJsonFile(fileName: String, operation: String): File {
        require(fileName.endsWith(".json", ignoreCase = true)) {
            "$operation expects a .json file: '$fileName'"
        }
        val root = internalPlugin.scriptsFolder.canonicalFile
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
            jsonCodec.parseToJsonElement(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
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
            KSLAtomicFileWriter.writeText(
                file,
                jsonCodec.encodeToString(JsonElement.serializer(), value)
            )
            true
        } catch (ex: Exception) {
            internalPlugin.logger.warning("[$scriptName] Не удалось сохранить JSON '$fileName': ${ex.message}")
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


    private fun resolveTomlFile(fileName: String, operation: String): File {
        require(fileName.endsWith(".toml", ignoreCase = true)) {
            operation + " expects a .toml file: '" + fileName + "'"
        }
        val root = internalPlugin.scriptsFolder.canonicalFile
        val file = File(root, fileName).canonicalFile
        require(file.toPath() != root.toPath() && file.toPath().startsWith(root.toPath())) {
            KSLError.YAML_PATH_ESCAPE.tag + " " + operation + ": '" + fileName + "' выходит за пределы scripts/"
        }
        return file
    }

    /** Reads a TOML document from scripts/. Returns null when the file does not exist. */
    @Synchronized
    fun loadToml(fileName: String): Map<String, Any?>? {
        val file = resolveTomlFile(fileName, "loadToml")
        if (!file.isFile) return null
        return try {
            KSLTomlCodec.parse(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (ex: Exception) {
            val backup = File(file.parentFile, file.name + ".broken-" + System.currentTimeMillis())
            val saved = runCatching { file.copyTo(backup) }.isSuccess
            throw IllegalArgumentException(
                "Invalid TOML in '" + file.name + "': " + ex.message +
                    if (saved) " (backup: " + backup.name + ")" else " (backup could not be created)",
                ex
            )
        }
    }

    /** Writes TOML atomically inside scripts/. Returns false on I/O or serialization failure. */
    @Synchronized
    fun saveToml(fileName: String, values: Map<String, Any?>): Boolean {
        val file = resolveTomlFile(fileName, "saveToml")
        return try {
            KSLAtomicFileWriter.writeText(file, KSLTomlCodec.encode(values))
            true
        } catch (ex: Exception) {
            internalPlugin.logger.warning("[" + scriptName + "] Не удалось сохранить TOML '" + fileName + "': " + ex.message)
            false
        }
    }

    /** Loads, edits and atomically saves a TOML document. */
    @Synchronized
    fun editToml(fileName: String, block: MutableMap<String, Any?>.() -> Unit): Map<String, Any?> {
        val values = KSLTomlCodec.mutableCopy(loadToml(fileName).orEmpty())
        values.block()
        check(saveToml(fileName, values)) { "Could not save TOML file '" + fileName + "'" }
        return values
    }

    private fun resolveCsvFile(fileName: String, operation: String): File {
        require(fileName.endsWith(".csv", ignoreCase = true)) {
            "$operation expects a .csv file: '$fileName'"
        }
        val root = internalPlugin.scriptsFolder.canonicalFile
        val file = File(root, fileName).canonicalFile
        require(file.toPath() != root.toPath() && file.toPath().startsWith(root.toPath())) {
            "${KSLError.YAML_PATH_ESCAPE.tag} $operation: '$fileName' выходит за пределы scripts/"
        }
        return file
    }

    /** Reads a CSV table from scripts/. Returns null if the file does not exist. */
    @Synchronized
    fun loadCsv(
        fileName: String,
        options: ru.privateserver.ksl.std.csv.KslCsvOptions = ru.privateserver.ksl.std.csv.KslCsvOptions()
    ): ru.privateserver.ksl.std.csv.KslCsvTable? {
        requireActiveGeneration("loadCsv")
        return ru.privateserver.ksl.std.csv.KslCsvFiles.read(
            resolveCsvFile(fileName, "loadCsv"),
            options
        )
    }

    /** Saves a CSV table as UTF-8 with an atomic file replacement. */
    @Synchronized
    fun saveCsv(
        fileName: String,
        table: ru.privateserver.ksl.std.csv.KslCsvTable,
        options: ru.privateserver.ksl.std.csv.KslCsvOptions = ru.privateserver.ksl.std.csv.KslCsvOptions()
    ): Boolean {
        requireActiveGeneration("saveCsv")
        val file = resolveCsvFile(fileName, "saveCsv")
        return try {
            ru.privateserver.ksl.std.csv.KslCsvFiles.write(file, table, options)
            true
        } catch (ex: Exception) {
            internalPlugin.logger.warning("[$scriptName] Не удалось сохранить CSV '$fileName': ${ex.message}")
            false
        }
    }

    /** Loads or creates a CSV table, applies edits, and atomically saves the result. */
    @Synchronized
    fun editCsv(
        fileName: String,
        columns: List<String> = emptyList(),
        options: ru.privateserver.ksl.std.csv.KslCsvOptions = ru.privateserver.ksl.std.csv.KslCsvOptions(),
        block: ru.privateserver.ksl.std.csv.KslCsvTable.() -> Unit
    ): ru.privateserver.ksl.std.csv.KslCsvTable {
        requireActiveGeneration("editCsv")
        val existing = loadCsv(fileName, options)
        val table = existing ?: ru.privateserver.ksl.std.csv.KslCsvTable(columns)
        if (existing != null && columns.isNotEmpty()) {
            require(existing.columns == columns) {
                "CSV schema mismatch for '$fileName': existing ${existing.columns}, requested $columns"
            }
        }
        table.block()
        check(saveCsv(fileName, table, options)) { "Could not save CSV file '$fileName'" }
        return table
    }

    private fun resolveNbtFile(fileName: String, operation: String): File {
        require(fileName.endsWith(".nbt", ignoreCase = true)) {
            "$operation expects a .nbt file: '$fileName'"
        }
        val root = internalPlugin.scriptsFolder.canonicalFile
        val file = File(root, fileName).canonicalFile
        require(file.toPath() != root.toPath() && file.toPath().startsWith(root.toPath())) {
            "${KSLError.YAML_PATH_ESCAPE.tag} $operation: '$fileName' выходит за пределы scripts/"
        }
        return file
    }

    /** Reads a binary NBT compound from scripts/. Returns null when the file is missing. */
    @Synchronized
    fun loadNBT(
        fileName: String,
        limits: ru.privateserver.ksl.nbt.KSLNBTLimits = ru.privateserver.ksl.nbt.KSLNBTLimits.DEFAULT,
        maxFileBytes: Long = ru.privateserver.ksl.nbt.KSLNBTFiles.DEFAULT_MAX_FILE_BYTES
    ): ru.privateserver.ksl.nbt.KSLNBTDocument? {
        requireActiveGeneration("loadNBT")
        return ru.privateserver.ksl.nbt.KSLNBTFiles.load(
            resolveNbtFile(fileName, "loadNBT"),
            limits,
            maxFileBytes
        )
    }

    /** Writes a detached document as binary NBT. The existing file survives a failed write. */
    @Synchronized
    fun saveNBT(fileName: String, document: ru.privateserver.ksl.nbt.KSLNBTDocument): Boolean {
        requireActiveGeneration("saveNBT")
        val file = resolveNbtFile(fileName, "saveNBT")
        return try {
            ru.privateserver.ksl.nbt.KSLNBTFiles.save(file, document)
            true
        } catch (ex: Exception) {
            internalPlugin.logger.warning("[$scriptName] Не удалось сохранить NBT '$fileName': ${ex.message}")
            false
        }
    }

    /** Loads or creates a detached binary NBT document, applies edits and atomically saves it. */
    @Synchronized
    fun editNBT(
        fileName: String,
        limits: ru.privateserver.ksl.nbt.KSLNBTLimits = ru.privateserver.ksl.nbt.KSLNBTLimits.DEFAULT,
        block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit
    ): ru.privateserver.ksl.nbt.KSLNBTDocument {
        requireActiveGeneration("editNBT")
        val document = loadNBT(fileName, limits) ?: ru.privateserver.ksl.nbt.KSLNBTEngine.empty(limits)
        document.edit(block)
        check(saveNBT(fileName, document)) { "Could not save binary NBT file '$fileName'" }
        return document
    }

    private fun resolveYamlFile(fileName: String, operation: String): File {
        val root = internalPlugin.scriptsFolder.canonicalFile
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
            internalPlugin.logger.warning("[$scriptName] ${KSLError.YAML_SAVE_FAILED.tag} Не удалось сохранить '$fileName': ${ex.message}")
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
            internalPlugin.logger.severe(
                "[$scriptName] ${KSLError.YAML_BROKEN.tag} ${file.name} содержит ошибку YAML (${ex.message?.lineSequence()?.firstOrNull()}). " +
                    if (saved) "Копия: ${backup.name}, используется пустой конфиг." else "Копию сделать не удалось, используется пустой конфиг."
            )
        } catch (ex: IOException) {
            internalPlugin.logger.severe("[$scriptName] ${KSLError.YAML_READ_FAILED.tag} Не удалось прочитать ${file.name}: ${ex.message}")
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
            text = KSLTextPlaceholders.apply(text) { key -> internalPlugin.resolveTextPlaceholder(key, player) }
        }

        if (player != null && internalPlugin.papiEnabled && text.indexOf('%') >= 0) {
            text = KSLPapiResolver.resolve(player, text)
        }

        if (text.indexOf('@') >= 0) {
            unmatchedPlaceholderRegex.findAll(text).forEach { match ->
                val warnKey = "$path:${match.value}"
                if (warnedPlaceholders.add(warnKey)) {
                    internalPlugin.logger.warning("[$scriptName] ${KSLError.PLACEHOLDER_UNMATCHED.tag} В '$path' остался незаменённый плейсхолдер ${match.value} — проверь имя ключа в message()/messageList()/richMessage() или зарегистрируй textPlaceholder")
                }
            }
        }

        return text
    }

    // @key@ in message()/richMessage(); removed when the script unloads. Names of built-in tokens are reserved.
    fun registerTextPlaceholder(key: String, resolver: (Player?) -> String?) {
        requireActiveGeneration("registerTextPlaceholder")
        internalPlugin.registerTextPlaceholder("script:$scriptName", key, resolver, contextRuntimeScope)
    }

    fun YamlConfiguration.message(path: String, vararg replacements: Pair<String, Any?>): String {
        val raw = getString(path) ?: return path
        return substitute(path, raw, null, replacements).colorizeLegacy()
    }

    fun YamlConfiguration.message(path: String, player: Player, vararg replacements: Pair<String, Any?>): String {
        val raw = getString(path) ?: return path
        return substitute(path, raw, player, replacements).colorizeLegacy()
    }

    fun YamlConfiguration.messageList(path: String, vararg replacements: Pair<String, Any?>): List<String> =
        getStringList(path).map { substitute(path, it, null, replacements).colorizeLegacy() }

    fun YamlConfiguration.messageList(path: String, player: Player, vararg replacements: Pair<String, Any?>): List<String> =
        getStringList(path).map { substitute(path, it, player, replacements).colorizeLegacy() }

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

    // Compact YAML aliases; Bukkit's YAML parser uses '#' for comments.
    fun YamlConfiguration.hasPath(path: String): Boolean = contains(path)
    fun YamlConfiguration.stringOr(path: String, default: String = ""): String = getString(path) ?: default
    fun YamlConfiguration.intOr(path: String, default: Int = 0): Int = getInt(path, default)
    fun YamlConfiguration.longOr(path: String, default: Long = 0L): Long = getLong(path, default)
    fun YamlConfiguration.boolOr(path: String, default: Boolean = false): Boolean = getBoolean(path, default)
    fun YamlConfiguration.listOfStrings(path: String): List<String> = getStringList(path)
    fun YamlConfiguration.removePath(path: String): Boolean {
        if (!contains(path)) return false
        set(path, null)
        return true
    }

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

    fun table(name: String): KSLQuickTable {
        requireScriptDatabaseAccess()
        return scopedTableViews.computeIfAbsent(name) {
            internalPlugin.tableStore.scopedTable(it, scriptName, contextRuntimeScope)
        }
    }

    val database: HikariDataSource
        get() {
            check(!contextRuntimeScope.isClosed) {
                "[$scriptName] This script context belongs to an unloaded runtime. Reload the script before using its database API."
            }
            KSLSandboxAccessPolicy.requireTrustedAccess(scriptName, internalPlugin.isSandboxed(scriptName), "database")
            return internalPlugin.database
        }

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

    /** Future-based, parameterized write on KSL's ordered database writer. */
    fun dbExecuteAsync(sql: String, vararg args: Any?): java.util.concurrent.CompletableFuture<Int> =
        submitDatabaseOperation(internalPlugin.dbExecutor) {
            scriptDatabase.connection.use { connection ->
                internalPlugin.databaseExecutionPolicy.prepare(connection, sql, args, query = false).use { statement ->
                    requireScriptDatabaseAccess()
                    statement.executeUpdate()
                }
            }
        }

    /**
     * Future-based prepared query. The mapper runs on KSL's database reader pool.
     * It must return detached values and must not access Bukkit state.
     */
    fun <T> dbQueryAsync(
        sql: String,
        vararg args: Any?,
        mapper: (ResultSet) -> T
    ): java.util.concurrent.CompletableFuture<T> =
        submitDatabaseOperation(internalPlugin.dbQueryExecutor) {
            scriptDatabase.connection.use { connection ->
                internalPlugin.databaseExecutionPolicy.prepare(connection, sql, args, query = true).use { statement ->
                    requireScriptDatabaseAccess()
                    statement.executeQuery().use { rows ->
                        requireScriptDatabaseAccess()
                        mapper(rows)
                    }
                }
            }
        }

    /** Returns detached mapped rows, capped by database.query-max-rows. */
    fun <T> dbQueryListAsync(
        sql: String,
        vararg args: Any?,
        mapper: ResultSet.() -> T
    ): java.util.concurrent.CompletableFuture<List<T>> =
        submitDatabaseOperation(internalPlugin.dbQueryExecutor) {
            scriptDatabase.connection.use { connection ->
                KSLDatabaseSession(connection, internalPlugin.databaseExecutionPolicy) {
                    !contextRuntimeScope.isClosed
                }.queryList(sql, *args, mapper = mapper)
            }
        }

    /** Returns the first detached row, or null when the query matched no rows. */
    fun <T> dbQueryOneAsync(
        sql: String,
        vararg args: Any?,
        mapper: ResultSet.() -> T
    ): java.util.concurrent.CompletableFuture<T?> =
        submitDatabaseOperation(internalPlugin.dbQueryExecutor) {
            scriptDatabase.connection.use { connection ->
                KSLDatabaseSession(connection, internalPlugin.databaseExecutionPolicy) {
                    !contextRuntimeScope.isClosed
                }.queryOne(sql, *args, mapper = mapper)
            }
        }

    /**
     * Runs a multi-statement transaction on the ordered writer thread.
     * Exceptions roll the transaction back; successful blocks commit once.
     */
    fun <T> dbTransactionAsync(
        block: KSLDatabaseSession.() -> T
    ): java.util.concurrent.CompletableFuture<T> =
        submitDatabaseOperation(internalPlugin.dbExecutor) {
            scriptDatabase.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                var transactionActive = true
                var failure: Throwable? = null
                try {
                    val session = KSLDatabaseSession(
                        connection,
                        internalPlugin.databaseExecutionPolicy
                    ) { !contextRuntimeScope.isClosed && transactionActive }
                    val result = session.block()
                    requireScriptDatabaseAccess()
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    failure = error
                    runCatching { connection.rollback() }.onFailure(error::addSuppressed)
                    throw error
                } finally {
                    transactionActive = false
                    try {
                        connection.autoCommit = previousAutoCommit
                    } catch (restoreError: Throwable) {
                        failure?.addSuppressed(restoreError) ?: throw restoreError
                    }
                }
            }
        }

    fun dbExecuteYamlAsync(queryPath: String, vararg args: Any?): java.util.concurrent.CompletableFuture<Int> =
        dbExecuteAsync(yamlSqlQuery(queryPath), *args)

    fun <T> dbQueryYamlAsync(
        queryPath: String,
        vararg args: Any?,
        mapper: (ResultSet) -> T
    ): java.util.concurrent.CompletableFuture<T> =
        dbQueryAsync(yamlSqlQuery(queryPath), *args, mapper = mapper)

    fun dbExecute(sql: String, vararg args: Any?) {
        dbExecuteAsync(sql, *args).whenComplete { _, error ->
            if (error != null && error !is java.util.concurrent.CancellationException) {
                internalPlugin.logger.warning("[$scriptName] ${KSLError.DB_SCRIPT_QUERY_FAILED.tag} dbExecute: ${error.cause?.message ?: error.message}")
            }
        }
    }

    fun dbQuery(sql: String, vararg args: Any?, block: (ResultSet) -> Unit) {
        submitDatabaseOperation(internalPlugin.dbQueryExecutor) {
            scriptDatabase.connection.use { connection ->
                internalPlugin.databaseExecutionPolicy.prepare(connection, sql, args, query = true).use { statement ->
                    requireScriptDatabaseAccess()
                    statement.executeQuery().use { rows ->
                        requireScriptDatabaseAccess()
                        block(rows)
                    }
                }
            }
        }.whenComplete { _, error ->
            if (error != null && error !is java.util.concurrent.CancellationException) {
                internalPlugin.logger.warning("[$scriptName] ${KSLError.DB_SCRIPT_QUERY_FAILED.tag} dbQuery: ${error.cause?.message ?: error.message}")
            }
        }
    }

    /**
     * Short alias for [dbExecute]. SQL parameters are bound through a prepared statement.
     */
    fun execute(sql: String, vararg args: Any?) = dbExecute(sql, *args)
    fun executeAsync(sql: String, vararg args: Any?) = dbExecuteAsync(sql, *args)
    fun executeYamlAsync(queryPath: String, vararg args: Any?) = dbExecuteYamlAsync(queryPath, *args)
    fun <T> queryAsync(sql: String, vararg args: Any?, mapper: (ResultSet) -> T) =
        dbQueryAsync(sql, *args, mapper = mapper)
    fun <T> queryListAsync(sql: String, vararg args: Any?, mapper: ResultSet.() -> T) =
        dbQueryListAsync(sql, *args, mapper = mapper)
    fun <T> queryOneAsync(sql: String, vararg args: Any?, mapper: ResultSet.() -> T) =
        dbQueryOneAsync(sql, *args, mapper = mapper)
    fun <T> transactionAsync(block: KSLDatabaseSession.() -> T) = dbTransactionAsync(block)
    fun <T> queryYamlAsync(queryPath: String, vararg args: Any?, mapper: (ResultSet) -> T) =
        dbQueryYamlAsync(queryPath, *args, mapper = mapper)

    /**
     * Short alias for [dbQuery]. The ResultSet is the lambda receiver, so use
     * `next()`, `getLong("balance")`, etc. directly inside the block.
     *
     * The callback runs on KSL's database reader pool, not the Bukkit main thread.
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

    /**
     * Every scheduled callback is isolated from Bukkit's scheduler. One-shot tasks
     * release their registry entry on completion; repeating tasks are stopped after
     * repeated consecutive failures instead of running a broken callback forever.
     */
    private fun guarded(
        where: String,
        repeating: Boolean,
        taskId: java.util.concurrent.atomic.AtomicInteger,
        completed: java.util.concurrent.atomic.AtomicBoolean,
        failures: KSLTaskFailurePolicy,
        block: () -> Unit
    ) = Runnable {
        if (!isGenerationActive()) {
            completed.set(true)
            val id = taskId.get()
            if (id > 0) {
                Bukkit.getScheduler().cancelTask(id)
                internalPlugin.untrackTask(scriptName, id)
            }
            return@Runnable
        }
        try {
            internalPlugin.measureScriptExecution(scriptName, "task:$where") {
                val monitor = internalPlugin.watchdogMonitor
                if (monitor != null) monitor.trackOperation("script '$scriptName' task '$where'", block)
                else block()
            }
            failures.onSuccess()
        } catch (ex: Throwable) {
            KSLErrors.log(internalPlugin, scriptName, where, ex, KSLError.TASK_FAILED)
            if (repeating && failures.onFailure()) {
                val id = taskId.get()
                if (id > 0) {
                    Bukkit.getScheduler().cancelTask(id)
                    internalPlugin.untrackTask(scriptName, id)
                }
                completed.set(true)
                internalPlugin.logger.warning("[$scriptName] Периодическая задача '$where' остановлена после ${internalPlugin.taskFailureLimit} последовательных ошибок")
            }
        } finally {
            if (!repeating) {
                completed.set(true)
                taskId.get().takeIf { it > 0 }?.let { internalPlugin.untrackTask(scriptName, it) }
            }
        }
    }

    private fun scheduleTracked(
        where: String,
        repeating: Boolean = false,
        block: () -> Unit,
        schedule: (Runnable) -> BukkitTask
    ): Int {
        requireActiveGeneration(where)
        val taskId = java.util.concurrent.atomic.AtomicInteger()
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        val failures = KSLTaskFailurePolicy(internalPlugin.taskFailureLimit)
        val task = schedule(guarded(where, repeating, taskId, completed, failures, block))
        taskId.set(task.taskId)
        internalPlugin.trackTask(scriptName, task.taskId, contextRuntimeScope)
        // A very short async task may finish before its id is published.
        if (completed.get()) internalPlugin.untrackTask(scriptName, task.taskId)
        return task.taskId
    }

    fun runAsync(block: () -> Unit): Int =
         scheduleTracked("runAsync", block = block) { Bukkit.getScheduler().runTaskAsynchronously(internalPlugin, it) }

    /** Runs a worker callback and records failures before returning them to the scheduler. */
    private fun <T> executeWorker(where: String, block: () -> T): T =
        try {
            requireActiveGeneration(where)
            internalPlugin.measureScriptExecution(scriptName, where) {
                requireActiveGeneration(where)
                block()
            }
        } catch (ex: Throwable) {
            KSLErrors.log(internalPlugin, scriptName, where, ex, KSLError.TASK_FAILED)
            throw ex
        }

    /** Runs work on KSL's bounded priority-aware worker pool. Bukkit world access belongs in runSync(). */
    private fun ownWorkerHandle(handle: ru.privateserver.ksl.rt.KSLWorkHandle): ru.privateserver.ksl.rt.KSLWorkHandle {
        val cleanup = contextRuntimeScope.onClose { handle.cancel(true) }
        // One-shot work releases its ownership record on completion; repeating work
        // keeps the record until it is cancelled or the generation is retired.
        handle.completion.whenComplete { _, _ -> cleanup.close() }
        return handle
    }

    private fun <T> ownWorkerFuture(
        future: java.util.concurrent.CompletableFuture<T>
    ): java.util.concurrent.CompletableFuture<T> {
        val cleanup = contextRuntimeScope.onClose { future.cancel(true) }
        future.whenComplete { _, _ -> cleanup.close() }
        return future
    }

    /** Runs on KSL's bounded priority-aware worker pool. Bukkit world access belongs in runSync(). */
    fun worker(priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> Unit): Long {
        requireActiveGeneration("worker")
        return ownWorkerHandle(internalPlugin.workScheduler.submitHandle(scriptName, priority) {
            executeWorker("worker:$priority", block)
        }).id
    }

    /** Submits work and returns a handle with cancellation, state and completion information. */
    fun workerHandle(
        priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL,
        block: () -> Unit
    ): ru.privateserver.ksl.rt.KSLWorkHandle {
        requireActiveGeneration("workerHandle")
        return ownWorkerHandle(internalPlugin.workScheduler.submitHandle(scriptName, priority) {
            executeWorker("worker:$priority", block)
        })
    }

    /** Returns a future for a value computed off-thread. Completion callbacks may run on the worker or registering thread; use runSync() for Bukkit operations. */
    fun <T> workerFuture(
        priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL,
        block: () -> T
    ): java.util.concurrent.CompletableFuture<T> {
        requireActiveGeneration("workerFuture")
        return ownWorkerFuture(internalPlugin.workScheduler.submitFuture(scriptName, priority) {
            executeWorker("worker-future:$priority", block)
        })
    }

    /** Runs one delayed worker callback; cancel the returned handle to stop it before execution. */
    fun workerAfter(
        delayMillis: Long,
        priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL,
        block: () -> Unit
    ): ru.privateserver.ksl.rt.KSLWorkHandle {
        requireActiveGeneration("workerAfter")
        return ownWorkerHandle(internalPlugin.workScheduler.scheduleHandle(
            scriptName, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS, priority
        ) { executeWorker("worker-after:$priority", block) })
    }

    /** Runs repeatedly on a fixed-rate clock. Overlapping ticks are skipped, never queued. */
    fun workerAtFixedRate(
        periodMillis: Long,
        initialDelayMillis: Long = 0L,
        priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL,
        block: () -> Unit
    ): ru.privateserver.ksl.rt.KSLWorkHandle {
        requireActiveGeneration("workerAtFixedRate")
        return ownWorkerHandle(internalPlugin.workScheduler.scheduleAtFixedRateHandle(
            scriptName, initialDelayMillis, periodMillis, java.util.concurrent.TimeUnit.MILLISECONDS, priority
        ) { executeWorker("worker-fixed-rate:$priority", block) })
    }

    /** Runs repeatedly with the delay measured from completion of the previous run. */
    fun workerWithFixedDelay(
        delayMillis: Long,
        initialDelayMillis: Long = 0L,
        priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL,
        block: () -> Unit
    ): ru.privateserver.ksl.rt.KSLWorkHandle {
        requireActiveGeneration("workerWithFixedDelay")
        return ownWorkerHandle(internalPlugin.workScheduler.scheduleWithFixedDelayHandle(
            scriptName, initialDelayMillis, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS, priority
        ) { executeWorker("worker-fixed-delay:$priority", block) })
    }

    fun cpu(block: () -> Unit): Long = worker(block = block)
    fun work(block: () -> Unit): Long = worker(block = block)
    fun cancelWorker(taskId: Long): Boolean = internalPlugin.cancelWorker(scriptName, taskId)

    fun runSync(block: () -> Unit): Int =
        scheduleTracked("runSync", block = block) { Bukkit.getScheduler().runTask(internalPlugin, it) }

    fun delay(ticks: Long, block: () -> Unit): Int =
        scheduleTracked("delay", block = block) { Bukkit.getScheduler().runTaskLater(internalPlugin, it, ticks) }

    fun every(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int {
        val period = KSLErrors.validPeriod(internalPlugin, scriptName, "every", ticks)
        return scheduleTracked("every", repeating = true, block = block) { Bukkit.getScheduler().runTaskTimer(internalPlugin, it, initialDelay, period) }
    }

    /**
     * Compatibility alias for scripts using the older named argument `period`.
     * Prefer `every(ticks = ...)` in new scripts.
     */
    @Suppress("UNUSED_PARAMETER")
    fun every(period: Long, initialDelay: Long = 0L, compatibilityAlias: Unit = Unit, block: () -> Unit): Int =
        every(ticks = period, initialDelay = initialDelay, block = block)

    fun delayAsync(ticks: Long, block: () -> Unit): Int =
        scheduleTracked("delayAsync", block = block) { Bukkit.getScheduler().runTaskLaterAsynchronously(internalPlugin, it, ticks) }

    fun everyAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int {
        val period = KSLErrors.validPeriod(internalPlugin, scriptName, "everyAsync", ticks)
        return scheduleTracked("everyAsync", repeating = true, block = block) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(internalPlugin, it, initialDelay, period)
        }
    }

    // Short DSL aliases; original method names remain available.
    fun sync(block: () -> Unit): Int = runSync(block)
    fun async(block: () -> Unit): Int = runAsync(block)
    fun after(ticks: Long, block: () -> Unit): Int = delay(ticks, block)
    fun repeat(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int =
        every(ticks, initialDelay, block)
    fun afterAsync(ticks: Long, block: () -> Unit): Int = delayAsync(ticks, block)
    fun repeatAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int =
        everyAsync(ticks, initialDelay, block)

    // Works for any id returned by delay/every/runSync/...; stopEffect is the same call for effects.
    fun cancelTask(taskId: Int) {
        if (internalPlugin.effectManager.stop(scriptName, taskId)) return
        internalPlugin.cancelTrackedTask(scriptName, taskId)
    }

    inline fun <reified T : Event> onEvent(
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        crossinline action: T.() -> Unit
    ) {
        requireActiveGeneration("onEvent")
        val listener = object : Listener {}
        val executor = EventExecutor { _, event ->
            if (event is T && isGenerationActive()) {
                try {
                    internalPlugin.measureScriptExecution(scriptName, "event:${T::class.simpleName}") {
                        event.action()
                    }
                } catch (ex: Throwable) {
                    KSLErrors.log(internalPlugin, scriptName, "onEvent<${T::class.simpleName}>", ex, KSLError.EVENT_HANDLER_FAILED)
                }
            }
        }
        Bukkit.getPluginManager().registerEvent(T::class.java, listener, priority, executor, internalPlugin, ignoreCancelled)
        trackListenerForCurrentGeneration(listener)
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
        addCommand(DynamicCommand(name, aliases, scriptName, internalPlugin, permission, completer, true) { sender, args ->
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
        addCommand(DynamicCommand(name, aliases, scriptName, internalPlugin, permission, tabComplete, false, action))
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
        requireActiveGeneration("registerCommand")
        Bukkit.getCommandMap().register(internalPlugin.name.lowercase(), command)
        internalPlugin.trackCommand(scriptName, command, contextRuntimeScope)
    }

    // Subscriptions are dropped automatically when the script unloads.
    fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable {
        requireActiveGeneration("subscribe")
        val subscription = internalPlugin.addonManager.subscribeFromScript(scriptName, topic, handler)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val cleanup = contextRuntimeScope.onClose {
            if (closed.compareAndSet(false, true)) subscription.close()
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                cleanup.close()
                subscription.close()
            }
        }
    }

    fun publish(topic: String, payload: Any? = null): Int = internalPlugin.addonManager.publish(topic, payload)

    fun parseMM(text: String): Component = text.toMiniMessageComponent()

    /** Compact MiniMessage parser alias. */
    fun mm(text: String): Component = parseMM(text)

    fun broadcastMM(text: String) = Bukkit.getServer().broadcast(parseMM(text))
    fun broadcastRich(text: String) = broadcastMM(text)

    fun broadcast(message: String) =
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(message.colorizeLegacy()) }

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

    /** Sends formatted text to any command sender, including console. */
    fun CommandSender.msg(text: String) = sendMessage(parseMM(text))
    fun CommandSender.sendRichMessage(text: String) = msg(text)
    fun CommandSender.sendMM(text: String) = msg(text)

    /** True when the sender has at least one of the supplied permission nodes. */
    fun CommandSender.hasPermissionAny(vararg permissions: String): Boolean =
        permissions.any { it.isNotBlank() && hasPermission(it) }

    /** True when the sender has every supplied permission node. */
    fun CommandSender.hasPermissionAll(vararg permissions: String): Boolean =
        permissions.all { it.isNotBlank() && hasPermission(it) }

    fun Player.sendRichMessage(text: String) = msg(text)
    fun Player.sendActionBarMessage(text: String) = sendActionBar(parseMM(text))
    fun Player.ab(text: String) = sendActionBarMessage(text)
    fun Player.actionBar(text: String) = sendActionBarMessage(text)

    fun Player.t(title: String, subtitle: String = "", fadeIn: Int = 10, stay: Int = 70, fadeOut: Int = 20) =
        showRichTitle(title, subtitle, fadeIn, stay, fadeOut)
    fun Player.title(title: String, subtitle: String = "", fadeIn: Int = 10, stay: Int = 70, fadeOut: Int = 20) =
        showRichTitle(title, subtitle, fadeIn, stay, fadeOut)

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
        get() = internalPlugin.essentials?.balance(this)
        set(value) { if (value != null) internalPlugin.essentials?.setBalance(this, value) }

    val Player.group: String?
        get() = internalPlugin.luckPerms?.primaryGroup(this)

    val Player.prefix: String?
        get() = internalPlugin.luckPerms?.prefix(this)

    /** Custom item NBT (custom_data on Minecraft 1.20.5+), separate from PDC. */
    fun ItemStack.modifyNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) {
        requirePrimaryThread("ItemStack.modifyNBT")
        internalPlugin.requireNbtApiAvailable("ItemStack.modifyNBT")
        NBT.modify(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTCompound(nbt).block()
        })
    }

    fun ItemStack.readNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) {
        requirePrimaryThread("ItemStack.readNBT")
        internalPlugin.requireNbtApiAvailable("ItemStack.readNBT")
        NBT.get(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTView(nbt).block()
        })
    }

    /** Reads vanilla data components such as minecraft:custom_name. */
    fun ItemStack.readComponents(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) {
        requirePrimaryThread("ItemStack.readComponents")
        internalPlugin.requireNbtApiAvailable("ItemStack.readComponents")
        NBT.getComponents(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTView(nbt).block()
        })
    }

    /** Modifies vanilla data components; component values must use Minecraft's expected format. */
    fun ItemStack.modifyComponents(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) {
        requirePrimaryThread("ItemStack.modifyComponents")
        internalPlugin.requireNbtApiAvailable("ItemStack.modifyComponents")
        NBT.modifyComponents(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTCompound(nbt).block()
        })
    }

    fun Entity.readNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) {
        requirePrimaryThread("Entity.readNBT")
        internalPlugin.requireNbtApiAvailable("Entity.readNBT")
        NBT.get(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTView(nbt).block()
        })
    }

    fun Entity.modifyNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) {
        requirePrimaryThread("Entity.modifyNBT")
        internalPlugin.requireNbtApiAvailable("Entity.modifyNBT")
        NBT.modify(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTCompound(nbt).block()
        })
    }

    fun Entity.readPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) {
        requirePrimaryThread("Entity.readPersistentNBT")
        internalPlugin.requireNbtApiAvailable("Entity.readPersistentNBT")
        NBT.getPersistentData(this, java.util.function.Function { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTView(nbt).block()
        })
    }

    fun Entity.modifyPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) {
        requirePrimaryThread("Entity.modifyPersistentNBT")
        internalPlugin.requireNbtApiAvailable("Entity.modifyPersistentNBT")
        NBT.modifyPersistentData(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTCompound(nbt).block()
        })
    }

    /** Vanilla NBT is available only for block entities (TileState), not ordinary blocks. */
    fun TileState.readNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) {
        requirePrimaryThread("TileState.readNBT")
        internalPlugin.requireNbtApiAvailable("TileState.readNBT")
        check(isPlaced) { "TileState must refer to a placed block entity before reading NBT." }
        NBT.get(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTView(nbt).block()
        })
    }

    fun TileState.modifyNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) {
        requirePrimaryThread("TileState.modifyNBT")
        internalPlugin.requireNbtApiAvailable("TileState.modifyNBT")
        check(isPlaced) { "TileState must refer to a placed block entity before modifying NBT." }
        NBT.modify(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTCompound(nbt).block()
        })
    }

    fun TileState.readPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) {
        requirePrimaryThread("TileState.readPersistentNBT")
        internalPlugin.requireNbtApiAvailable("TileState.readPersistentNBT")
        check(isPlaced) { "TileState must refer to a placed block entity before reading persistent NBT." }
        NBT.getPersistentData(this, java.util.function.Function { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTView(nbt).block()
        })
    }

    fun TileState.modifyPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) {
        requirePrimaryThread("TileState.modifyPersistentNBT")
        internalPlugin.requireNbtApiAvailable("TileState.modifyPersistentNBT")
        check(isPlaced) { "TileState must refer to a placed block entity before modifying persistent NBT." }
        NBT.modifyPersistentData(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTCompound(nbt).block()
        })
    }

    /** Reads custom data for this ordinary block from its chunk's persistent data container. */
    fun org.bukkit.block.Block.readChunkNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) {
        requirePrimaryThread("Block.readChunkNBT")
        internalPlugin.requireNbtApiAvailable("Block.readChunkNBT")
        NBT.readChunkPDC(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTView(nbt).block()
        })
    }

    /** Modifies custom data for this block, stored in the containing chunk's persistent data container. */
    fun org.bukkit.block.Block.modifyChunkNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) {
        requirePrimaryThread("Block.modifyChunkNBT")
        internalPlugin.requireNbtApiAvailable("Block.modifyChunkNBT")
        NBT.modifyChunkPDC(this, java.util.function.Consumer { nbt ->
            ru.privateserver.ksl.nbt.KSLNBTCompound(nbt).block()
        })
    }

    /** Removes one key from this block's chunk-backed custom data. */
    fun org.bukkit.block.Block.removeChunkNBT(key: String): Boolean {
        requirePrimaryThread("Block.removeChunkNBT")
        internalPlugin.requireNbtApiAvailable("Block.removeChunkNBT")
        var removed = false
        NBT.modifyChunkPDC(this, java.util.function.Consumer { nbt ->
            if (nbt.hasTag(key)) {
                nbt.removeKey(key)
                removed = true
            }
        })
        return removed
    }

    fun ItemStack.setTag(key: String, value: String) {
        val meta = itemMeta ?: return
        meta.persistentDataContainer.set(NamespacedKey(internalPlugin, key), PersistentDataType.STRING, value)
        itemMeta = meta
    }

    fun ItemStack.getTag(key: String): String? =
        itemMeta?.persistentDataContainer?.get(NamespacedKey(internalPlugin, key), PersistentDataType.STRING)

    fun ItemStack.hasTag(key: String): Boolean =
        itemMeta?.persistentDataContainer?.has(NamespacedKey(internalPlugin, key), PersistentDataType.STRING) == true

    // New typed PDC tags live under a script-specific path and never overwrite KSL's internal keys.
    private val persistentTagStore by lazy(LazyThreadSafetyMode.NONE) {
        KSLPersistentTagStore(internalPlugin, scriptName)
    }

    private fun ItemStack.updateScriptTag(block: (PersistentDataContainer) -> Unit) {
        val meta = itemMeta ?: return
        block(meta.persistentDataContainer)
        itemMeta = meta
    }

    private fun <T> ItemStack.readScriptTag(block: (PersistentDataContainer) -> T): T? =
        itemMeta?.let { block(it.persistentDataContainer) }

    fun PersistentDataHolder.setStringTag(key: String, value: String) =
        persistentTagStore.setString(persistentDataContainer, key, value)
    fun PersistentDataHolder.getStringTag(key: String): String? =
        persistentTagStore.getString(persistentDataContainer, key)
    fun PersistentDataHolder.hasStringTag(key: String): Boolean =
        persistentTagStore.hasString(persistentDataContainer, key)

    fun ItemStack.setStringTag(key: String, value: String) =
        updateScriptTag { persistentTagStore.setString(it, key, value) }
    fun ItemStack.getStringTag(key: String): String? =
        readScriptTag { persistentTagStore.getString(it, key) }
    fun ItemStack.hasStringTag(key: String): Boolean =
        readScriptTag { persistentTagStore.hasString(it, key) } == true

    fun PersistentDataHolder.setIntTag(key: String, value: Int) =
        persistentTagStore.setInt(persistentDataContainer, key, value)
    fun PersistentDataHolder.getIntTag(key: String): Int? =
        persistentTagStore.getInt(persistentDataContainer, key)
    fun PersistentDataHolder.hasIntTag(key: String): Boolean =
        persistentTagStore.hasInt(persistentDataContainer, key)
    fun ItemStack.setIntTag(key: String, value: Int) =
        updateScriptTag { persistentTagStore.setInt(it, key, value) }
    fun ItemStack.getIntTag(key: String): Int? =
        readScriptTag { persistentTagStore.getInt(it, key) }
    fun ItemStack.hasIntTag(key: String): Boolean =
        readScriptTag { persistentTagStore.hasInt(it, key) } == true

    fun PersistentDataHolder.setLongTag(key: String, value: Long) =
        persistentTagStore.setLong(persistentDataContainer, key, value)
    fun PersistentDataHolder.getLongTag(key: String): Long? =
        persistentTagStore.getLong(persistentDataContainer, key)
    fun PersistentDataHolder.hasLongTag(key: String): Boolean =
        persistentTagStore.hasLong(persistentDataContainer, key)
    fun ItemStack.setLongTag(key: String, value: Long) =
        updateScriptTag { persistentTagStore.setLong(it, key, value) }
    fun ItemStack.getLongTag(key: String): Long? =
        readScriptTag { persistentTagStore.getLong(it, key) }
    fun ItemStack.hasLongTag(key: String): Boolean =
        readScriptTag { persistentTagStore.hasLong(it, key) } == true

    fun PersistentDataHolder.setDoubleTag(key: String, value: Double) =
        persistentTagStore.setDouble(persistentDataContainer, key, value)
    fun PersistentDataHolder.getDoubleTag(key: String): Double? =
        persistentTagStore.getDouble(persistentDataContainer, key)
    fun PersistentDataHolder.hasDoubleTag(key: String): Boolean =
        persistentTagStore.hasDouble(persistentDataContainer, key)
    fun ItemStack.setDoubleTag(key: String, value: Double) =
        updateScriptTag { persistentTagStore.setDouble(it, key, value) }
    fun ItemStack.getDoubleTag(key: String): Double? =
        readScriptTag { persistentTagStore.getDouble(it, key) }
    fun ItemStack.hasDoubleTag(key: String): Boolean =
        readScriptTag { persistentTagStore.hasDouble(it, key) } == true

    fun PersistentDataHolder.setBooleanTag(key: String, value: Boolean) =
        persistentTagStore.setBoolean(persistentDataContainer, key, value)
    fun PersistentDataHolder.getBooleanTag(key: String): Boolean? =
        persistentTagStore.getBoolean(persistentDataContainer, key)
    fun PersistentDataHolder.hasBooleanTag(key: String): Boolean =
        persistentTagStore.hasBoolean(persistentDataContainer, key)
    fun ItemStack.setBooleanTag(key: String, value: Boolean) =
        updateScriptTag { persistentTagStore.setBoolean(it, key, value) }
    fun ItemStack.getBooleanTag(key: String): Boolean? =
        readScriptTag { persistentTagStore.getBoolean(it, key) }
    fun ItemStack.hasBooleanTag(key: String): Boolean =
        readScriptTag { persistentTagStore.hasBoolean(it, key) } == true

    fun PersistentDataHolder.setUuidTag(key: String, value: UUID) =
        persistentTagStore.setUuid(persistentDataContainer, key, value)
    fun PersistentDataHolder.getUuidTag(key: String): UUID? =
        persistentTagStore.getUuid(persistentDataContainer, key)
    fun PersistentDataHolder.hasUuidTag(key: String): Boolean =
        persistentTagStore.hasUuid(persistentDataContainer, key)
    fun ItemStack.setUuidTag(key: String, value: UUID) =
        updateScriptTag { persistentTagStore.setUuid(it, key, value) }
    fun ItemStack.getUuidTag(key: String): UUID? =
        readScriptTag { persistentTagStore.getUuid(it, key) }
    fun ItemStack.hasUuidTag(key: String): Boolean =
        readScriptTag { persistentTagStore.hasUuid(it, key) } == true

    fun PersistentDataHolder.removePersistentTag(key: String) =
        persistentTagStore.remove(persistentDataContainer, key)
    fun ItemStack.removePersistentTag(key: String) =
        updateScriptTag { persistentTagStore.remove(it, key) }

    fun registerPlaceholder(key: String, resolver: (OfflinePlayer) -> String?) {
        requireActiveGeneration("registerPlaceholder")
        if (!internalPlugin.papiEnabled) {
            internalPlugin.logger.warning("[$scriptName] ${KSLError.PAPI_MISSING.tag} PlaceholderAPI не найден — плейсхолдер '$key' пропущен"); return
        }
        internalPlugin.registerPlaceholder(scriptName, key, resolver, contextRuntimeScope)
    }

    fun luckPermsGroup(player: Player): String? = internalPlugin.luckPerms?.primaryGroup(player)
    fun luckPermsPrefix(player: Player): String? = internalPlugin.luckPerms?.prefix(player)
    fun luckPermsSuffix(player: Player): String? = internalPlugin.luckPerms?.suffix(player)
    fun hasGroup(player: Player, group: String): Boolean = internalPlugin.luckPerms?.isInGroup(player, group) ?: false

    fun balance(player: Player): Double? = internalPlugin.essentials?.balance(player)
    fun setBalance(player: Player, amount: Double) { internalPlugin.essentials?.setBalance(player, amount) }
    fun isAfk(player: Player): Boolean = internalPlugin.essentials?.isAfk(player) ?: false

    fun weSelection(player: Player): Pair<org.bukkit.Location, org.bukkit.Location>? = internalPlugin.worldEdit?.selection(player)
    fun weSelectionVolume(player: Player): Long? = internalPlugin.worldEdit?.selectionVolume(player)
    fun weFillSelection(player: Player, material: org.bukkit.Material): Int? = internalPlugin.worldEdit?.fillSelection(player, material)
    fun weHasClipboard(player: Player): Boolean = internalPlugin.worldEdit?.hasClipboard(player) ?: false

    private fun resolveSchematicFile(fileName: String, operation: String): File {
        require(fileName.endsWith(".schem", ignoreCase = true)) {
            "$operation expects a Sponge .schem file: '$fileName'"
        }
        val root = File(internalPlugin.dataFolder, "schematics").canonicalFile
        val file = File(root, fileName).canonicalFile
        require(file.toPath() != root.toPath() && file.toPath().startsWith(root.toPath())) {
            "${KSLError.YAML_PATH_ESCAPE.tag} $operation: '$fileName' выходит за пределы schematics/"
        }
        return file
    }

    private fun requireWorldEditForSchematic(): KSLWorldEditHook =
        internalPlugin.worldEdit
            ?: throw IllegalStateException("Для работы с .schem нужен установленный и включённый WorldEdit")

    /** Captures the player's current WorldEdit selection into a reusable clipboard. */
    fun copySchematicSelection(player: Player): ru.privateserver.ksl.schem.KSLClipboard? {
        requireActiveGeneration("copySchematicSelection")
        requirePrimaryThread("copySchematicSelection")
        return requireWorldEditForSchematic().captureSelection(player)
    }

    /** Loads a Sponge schematic from plugins/KotlinScriptLoader/schematics/. */
    fun loadSchematic(fileName: String): ru.privateserver.ksl.schem.KSLClipboard? {
        requireActiveGeneration("loadSchematic")
        val file = resolveSchematicFile(fileName, "loadSchematic")
        if (!file.exists()) return null
        return requireWorldEditForSchematic().loadSchematic(file)
    }

    /** Saves a clipboard to plugins/KotlinScriptLoader/schematics/ using atomic replacement. */
    fun saveSchematic(fileName: String, clipboard: ru.privateserver.ksl.schem.KSLClipboard): Boolean {
        requireActiveGeneration("saveSchematic")
        val file = resolveSchematicFile(fileName, "saveSchematic")
        return requireWorldEditForSchematic().saveSchematic(file, clipboard)
    }

    /** Convenience method: copies and saves the player's current WorldEdit selection. */
    fun saveSchematicSelection(player: Player, fileName: String): Boolean {
        val clipboard = copySchematicSelection(player) ?: return false
        return saveSchematic(fileName, clipboard)
    }

    /** Pastes a loaded or captured schematic at a block location. */
    fun pasteSchematic(
        player: Player,
        clipboard: ru.privateserver.ksl.schem.KSLClipboard,
        location: org.bukkit.Location,
        ignoreAirBlocks: Boolean = false
    ): Boolean {
        requireActiveGeneration("pasteSchematic")
        requirePrimaryThread("pasteSchematic")
        require(location.x.isFinite() && location.y.isFinite() && location.z.isFinite()) {
            "Schematic paste location must use finite coordinates"
        }
        return requireWorldEditForSchematic().pasteSchematic(player, clipboard, location, ignoreAirBlocks)
    }

    fun wgRegionsAt(location: org.bukkit.Location): List<String> = internalPlugin.worldGuard?.regionsAt(location) ?: emptyList()
    fun wgIsInRegion(location: org.bukkit.Location, regionId: String): Boolean = internalPlugin.worldGuard?.isInRegion(location, regionId) ?: false
    fun wgCanBuild(player: Player, location: org.bukkit.Location): Boolean = internalPlugin.worldGuard?.canBuild(player, location) ?: true
    fun wgCanPvp(player: Player, location: org.bukkit.Location): Boolean = internalPlugin.worldGuard?.canPvp(player, location) ?: true
    fun wgFlag(location: org.bukkit.Location, flagName: String, player: Player? = null): String? = internalPlugin.worldGuard?.flagValue(location, flagName, player)

    fun vaultBalance(player: OfflinePlayer): Double? = internalPlugin.vault?.balance(player)
    fun vaultDeposit(player: OfflinePlayer, amount: Double): Boolean = internalPlugin.vault?.deposit(player, amount) ?: false
    fun vaultWithdraw(player: OfflinePlayer, amount: Double): Boolean = internalPlugin.vault?.withdraw(player, amount) ?: false
    fun vaultHas(player: OfflinePlayer, amount: Double): Boolean = internalPlugin.vault?.has(player, amount) ?: false
    fun vaultFormat(amount: Double): String = internalPlugin.vault?.format(amount) ?: amount.toString()
    fun vaultHasPermission(player: Player, node: String): Boolean = internalPlugin.vault?.hasPermission(player, node) ?: player.hasPermission(node)
    fun vaultGroup(player: Player): String? = internalPlugin.vault?.playerGroup(player)
    fun vaultInGroup(player: Player, group: String): Boolean = internalPlugin.vault?.playerInGroup(player, group) ?: false

    fun setSkin(player: Player, skinName: String): Boolean = internalPlugin.skinRestorer?.setSkin(player, skinName) ?: false
    fun setSkinRaw(player: Player, value: String, signature: String): Boolean =
        internalPlugin.skinRestorer?.setSkinRaw(player, value, signature) ?: false

    fun cooldown(player: Player, key: String, seconds: Long): Boolean =
        internalPlugin.cooldownStore.tryUse("$scriptName:$key:${player.uniqueId}", seconds)

    fun cooldownRemaining(player: Player, key: String, seconds: Long): Long =
        internalPlugin.cooldownStore.remaining("$scriptName:$key:${player.uniqueId}", seconds)

    fun resetCooldown(player: Player, key: String) =
        internalPlugin.cooldownStore.reset("$scriptName:$key:${player.uniqueId}")

    fun export(key: String, value: Any) {
        internalPlugin.libraryRegistry.export(scriptName, key, value)
    }

    /** Namespaced, typed and version-labelled exports shared between scripts. */
    val meslib2: ru.privateserver.ksl.meslib2.MesLib2 by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ru.privateserver.ksl.meslib2.MesLib2(
            owner = scriptName,
            exportValue = internalPlugin.libraryRegistry::export,
            getValue = internalPlugin.libraryRegistry::get,
            ownerOf = internalPlugin.libraryRegistry::ownerOf,
            removeValue = internalPlugin.libraryRegistry::remove,
            exportIfAbsentValue = internalPlugin.libraryRegistry::exportIfAbsent,
            exportedKeys = internalPlugin.libraryRegistry::keys
        )
    }

    /** Short aliases for the MesLib2 script-library facade. */
    val ml2: ru.privateserver.ksl.meslib2.MesLib2 get() = meslib2
    val lib2: ru.privateserver.ksl.meslib2.MesLib2 get() = meslib2
    val libs: ru.privateserver.ksl.meslib2.MesLib2 get() = meslib2

    inline fun <reified T : Any> library(key: String): T? = internalPlugin.libraryRegistry.get(key) as? T

    inline fun <reified T : Any> requireLibrary(key: String): T =
        library<T>(key) ?: error("Библиотека '$key' не найдена — проверь, что скрипт с export(\"$key\", ...) загружен раньше (см. @requires)")

    fun gui(title: String, rows: Int = 3, builder: KSLGuiInstance.() -> Unit): KSLGuiInstance {
        requireActiveGeneration("gui")
        val instance = KSLGuiInstance(scriptName, internalPlugin, rows, title)
        internalPlugin.trackGui(scriptName, instance, contextRuntimeScope)
        instance.builder()
        return instance
    }

    fun <T> persist(key: String, persistent: Boolean = false, default: () -> T): KSLPersistProperty<T> =
        internalPlugin.persistStore.property(scriptName, key, persistent, default)

    fun executeConsole(command: String) {
        requireActiveGeneration("executeConsole")
        // The one DSL call that runs with full console rights; op, stop and similar are refused by default.
        if (!internalPlugin.canScriptExecuteConsole(scriptName, command)) {
            internalPlugin.logger.warning("[$scriptName] ${KSLError.COMMAND_BLOCKED.tag} Консольная команда '${command.trim().substringBefore(' ')}' заблокирована правилами безопасности")
            return
        }
        internalPlugin.logger.info("[KSL] Скрипт '$scriptName' выполняет консольную команду: $command")
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command)
        } else {
            runSync { org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command) }
        }
    }

    inline fun <reified T : Any> service(key: String): T? = internalPlugin.addonManager.getService(key) as? T

    fun discordSend(channel: String, message: String) {
        internalPlugin.discord?.send(channel, message)
            ?: internalPlugin.logger.warning("[$scriptName] ${KSLError.DISCORD_NOT_CONFIGURED.tag} discordSend: Discord не настроен в config.yml")
    }

    fun discordEmbed(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = "",
        fields: List<KSLDiscordEmbedField> = emptyList(),
        allowMentions: Boolean = false
    ) {
        internalPlugin.discord?.sendEmbed(channel, title, description, color, footer, allowMentions, fields)
            ?: internalPlugin.logger.warning("[$scriptName] ${KSLError.DISCORD_NOT_CONFIGURED.tag} discordEmbed: Discord не настроен в config.yml")
    }

    /** Sends a Discord message asynchronously; true means Discord returned a successful HTTP status. */
    fun discordSendAsync(channel: String, message: String, allowMentions: Boolean = false): java.util.concurrent.CompletableFuture<Boolean> =
        internalPlugin.discord?.sendAsync(channel, message, allowMentions)
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
        internalPlugin.discord?.sendEmbedAsync(channel, title, description, color, footer, allowMentions, fields)
            ?: java.util.concurrent.CompletableFuture.completedFuture(false)

    // Concise Discord aliases. All paths delegate to the canonical methods above.
    fun discord(channel: String, message: String) = discordSend(channel, message)
    fun discordMessage(channel: String, message: String) = discordSend(channel, message)
    fun discordAsync(channel: String, message: String, allowMentions: Boolean = false) =
        discordSendAsync(channel, message, allowMentions)
    fun discordMessageAsync(channel: String, message: String, allowMentions: Boolean = false) =
        discordSendAsync(channel, message, allowMentions)

    fun discordEmbedMessage(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = "",
        fields: List<KSLDiscordEmbedField> = emptyList()
    ) = discordEmbed(channel, title, description, color, footer, fields)

    fun discordEmbedMessageAsync(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = "",
        fields: List<KSLDiscordEmbedField> = emptyList(),
        allowMentions: Boolean = false
    ) = discordEmbedAsync(channel, title, description, color, footer, fields, allowMentions)

    /** Create a reusable sound preset using a Bukkit sound or a resource-pack key. */
    fun sound(sound: Sound, builder: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec =
        KSLSoundSpec(this, sound.key.toString()).apply(builder)

    fun sound(key: String, builder: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec =
        KSLSoundSpec(this, key).apply(builder)

    fun music(sound: Sound, builder: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec =
        sound(sound) {
            category(SoundCategory.MUSIC)
            builder()
        }

    fun music(key: String, builder: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec =
        sound(key) {
            category(SoundCategory.MUSIC)
            builder()
        }

    fun soundSequence(builder: KSLSoundSequenceBuilder.() -> Unit): KSLSoundSequence {
        val sequence = KSLSoundSequenceBuilder(this).apply(builder)
        return KSLSoundSequence(this, sequence.cues.toList())
    }

    fun Location.playEffect(particle: Particle, builder: KSLEffectSpec.() -> Unit): Int {
        requireActiveGeneration("playEffect")
        requirePrimaryThread("playEffect")
        val frozen = this.clone()
        val spec = KSLEffectSpec(particle, { frozen }, internalPlugin.addonManager.effectShapes)
        spec.builder()
        return internalPlugin.effectManager.play(scriptName, spec, contextRuntimeScope)
    }

    fun Entity.playEffect(particle: Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int {
        requireActiveGeneration("playEffect")
        requirePrimaryThread("playEffect")
        val entity = this
        val supplier: () -> Location = if (follow) {
            { entity.location }
        } else {
            val frozen = entity.location.clone()
            ({ frozen })
        }
        val spec = KSLEffectSpec(particle, supplier, internalPlugin.addonManager.effectShapes)
        if (follow) spec.stopCondition = { !entity.isValid }
        spec.builder()
        return internalPlugin.effectManager.play(scriptName, spec, contextRuntimeScope)
    }

    fun Location.effect(particle: Particle, builder: KSLEffectSpec.() -> Unit): Int =
        playEffect(particle, builder)

    fun Entity.effect(particle: Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int =
        playEffect(particle, follow, builder)

    fun stopEffect(taskId: Int) = cancelTask(taskId)

    fun Location.particles(particle: Particle, builder: KSLEffectSpec.() -> Unit): Int =
        playEffect(particle, builder)

    fun Entity.particles(particle: Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int =
        playEffect(particle, follow, builder)

    fun Location.particle(particle: Particle, builder: KSLEffectSpec.() -> Unit): Int =
        playEffect(particle, builder)

    fun Entity.particle(particle: Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int =
        playEffect(particle, follow, builder)

    fun stopParticles(taskId: Int) = stopEffect(taskId)

    fun spawnCustomEntity(
        location: Location,
        template: String,
        removeOnReload: Boolean = true,
        builder: KSLEntityBuilder.() -> Unit = {}
    ): LivingEntity {
        requireActiveGeneration("spawnCustomEntity")
        requirePrimaryThread("spawnCustomEntity")
        require(location.x.isFinite() && location.y.isFinite() && location.z.isFinite() && location.yaw.isFinite() && location.pitch.isFinite()) {
            "[$scriptName] spawnCustomEntity: координаты и поворот должны быть конечными числами"
        }
        val attrs = KSLTemplateParser.parse(template)
        val typeName = attrs["TYPE"]?.trim()?.takeIf { it.isNotEmpty() }
        val type = if (typeName == null) {
            EntityType.ZOMBIE
        } else {
            runCatching { EntityType.valueOf(typeName.uppercase()) }.getOrElse {
                throw IllegalArgumentException(
                    "[$scriptName] ${KSLError.ENTITY_UNKNOWN_TYPE.tag} spawnCustomEntity: неизвестный TYPE:'$typeName'. Укажи имя EntityType, например ZOMBIE или VILLAGER.",
                    it
                )
            }
        }
        require(type.isSpawnable && type.isAlive) {
            "[$scriptName] spawnCustomEntity: TYPE:${type.name} нельзя создать как живую сущность. Укажи спавнимый живой тип, например ZOMBIE, SKELETON или COW."
        }
        val world = location.world ?: error("${KSLError.ENTITY_NO_WORLD.tag} spawnCustomEntity: у локации нет мира")
        val spawned = try {
            world.spawnEntity(location, type)
        } catch (ex: Throwable) {
            throw IllegalStateException(
                "[$scriptName] spawnCustomEntity: Paper не смог создать ${type.name} в ${world.name} на ${location.blockX},${location.blockY},${location.blockZ}: ${ex.message}",
                ex
            )
        }
        val entity = spawned as? LivingEntity ?: run {
            spawned.remove()
            error("${KSLError.ENTITY_NOT_LIVING.tag} spawnCustomEntity: TYPE:${type.name} — не LivingEntity (нужен живой моб вроде ZOMBIE/SKELETON/COW), заспавненная сущность удалена")
        }

        internalPlugin.entityManager.trackSpawn(scriptName, entity, removeOnReload, contextRuntimeScope)
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
                    internalPlugin.logger.warning("[$scriptName] ${KSLError.ENTITY_BAD_HP.tag} spawnCustomEntity: HP:$hp вне (0, 1024] — игнорирую")
                }
            }
            attrs["AI"]?.toBooleanStrictOrNull()?.let { entity.setAI(it) }
            if (entity is Ageable) {
                attrs["BABY"]?.toBooleanStrictOrNull()?.let { isBaby -> if (isBaby) entity.setBaby() else entity.setAdult() }
            }
            // Template keys beyond the core ones come from the attribute registry (built-ins and addons).
            attrs.forEach { (key, value) ->
                if (key in KSLEntityAttributes.coreKeys) return@forEach
                val handler = internalPlugin.addonManager.entityAttributes.get(key)
                if (handler == null) {
                    val known = (KSLEntityAttributes.coreKeys + internalPlugin.addonManager.entityAttributes.keys()).sorted()
                    internalPlugin.logger.warning("[$scriptName] ${KSLError.ENTITY_UNKNOWN_ATTRIBUTE.tag} spawnCustomEntity: неизвестный атрибут $key (доступны: ${known.joinToString(", ")})")
                    return@forEach
                }
                runCatching { handler(entity, value) }
                    .onFailure { internalPlugin.logger.warning("[$scriptName] ${KSLError.ENTITY_ATTRIBUTE_FAILED.tag} spawnCustomEntity: $key:$value — ${it.message}") }
            }
            KSLEntityBuilder(entity, internalPlugin, scriptName, contextRuntimeScope).builder()
        } catch (ex: Throwable) {
            // Do not leave a half-initialised mob in the world.
            internalPlugin.entityManager.forget(scriptName, entity)
            entity.remove()
            throw ex
        }
        return entity
    }

    /**
     * Spawns a bounded number of custom mobs over multiple ticks instead of creating
     * the entire batch during script evaluation or one command callback.
     *
     * Returns the repeating task id; it is cancelled automatically when the batch ends
     * or when the owning script is unloaded.
     */
    fun spawnCustomEntities(
        location: Location,
        template: String,
        amount: Int,
        perTick: Int = 1,
        intervalTicks: Long = 1L,
        removeOnReload: Boolean = true,
        builder: KSLEntityBuilder.() -> Unit = {}
    ): Int {
        requirePrimaryThread("spawnCustomEntities")
        require(amount in 1..10_000) { "[$scriptName] spawnCustomEntities: amount должен быть от 1 до 10000" }
        require(perTick in 1..10) { "[$scriptName] spawnCustomEntities: perTick должен быть от 1 до 10" }
        require(location.world != null) { "[$scriptName] spawnCustomEntities: у локации нет мира" }
        val frozen = location.clone()
        var spawned = 0
        var taskId = -1
        taskId = every(ticks = intervalTicks) {
            try {
                repeat((amount - spawned).coerceAtMost(perTick)) {
                    spawnCustomEntity(frozen, template, removeOnReload, builder)
                    spawned++
                }
                if (spawned >= amount) cancelTask(taskId)
            } catch (ex: Throwable) {
                cancelTask(taskId)
                throw ex
            }
        }
        return taskId
    }

    /**
     * Creates a stationary, persistent villager with a native Minecraft merchant GUI.
     * Trades are configured once and owned by the script for predictable reload cleanup.
     */
    fun spawnShopkeeper(
        location: Location,
        name: String,
        removeOnReload: Boolean = true,
        builder: KSLShopkeeperBuilder.() -> Unit
    ): Villager {
        requireActiveGeneration("spawnShopkeeper")
        requirePrimaryThread("spawnShopkeeper")
        require(name.isNotBlank()) { "[$scriptName] spawnShopkeeper: имя не должно быть пустым" }
        require(location.x.isFinite() && location.y.isFinite() && location.z.isFinite() && location.yaw.isFinite() && location.pitch.isFinite()) {
            "[$scriptName] spawnShopkeeper: координаты и поворот должны быть конечными числами"
        }
        val world = location.world ?: error("[$scriptName] spawnShopkeeper: у локации нет мира")
        val spawned = try {
            world.spawnEntity(location, EntityType.VILLAGER)
        } catch (ex: Throwable) {
            throw IllegalStateException(
                "[$scriptName] spawnShopkeeper: Paper не смог создать Villager в ${world.name} на ${location.blockX},${location.blockY},${location.blockZ}: ${ex.message}",
                ex
            )
        }
        val villager = spawned as? Villager ?: run {
            spawned.remove()
            error("[$scriptName] spawnShopkeeper: Paper не вернул сущность Villager")
        }

        internalPlugin.entityManager.trackSpawn(scriptName, villager, removeOnReload, contextRuntimeScope)
        try {
            villager.customName(parseMM(name))
            villager.isCustomNameVisible = true
            KSLShopkeeperBuilder(villager).apply(builder).apply()
        } catch (ex: Throwable) {
            internalPlugin.entityManager.forget(scriptName, villager)
            villager.remove()
            throw ex
        }
        return villager
    }

    /** Opens the built-in villager trade screen for a player and one of this script's merchants. */
    fun openShopkeeper(player: Player, villager: Villager, force: Boolean = true): Boolean {
        requirePrimaryThread("openShopkeeper")
        if (!villager.isValid) return false
        return player.openMerchant(villager, force) != null
    }

    fun mob(location: Location, template: String, removeOnReload: Boolean = true,
            builder: KSLEntityBuilder.() -> Unit = {}): LivingEntity =
        spawnCustomEntity(location, template, removeOnReload, builder)

    fun mob(location: Location, type: EntityType, removeOnReload: Boolean = true,
            builder: KSLEntityBuilder.() -> Unit = {}): LivingEntity =
        spawnCustomEntity(location, "TYPE:" + type.name, removeOnReload, builder)

    fun spawnMob(location: Location, type: EntityType, removeOnReload: Boolean = true,
                 builder: KSLEntityBuilder.() -> Unit = {}): LivingEntity =
        mob(location, type, removeOnReload, builder)

    fun mobs(location: Location, template: String, amount: Int, perTick: Int = 1,
             intervalTicks: Long = 1L, removeOnReload: Boolean = true,
             builder: KSLEntityBuilder.() -> Unit = {}): Int =
        spawnCustomEntities(location, template, amount, perTick, intervalTicks, removeOnReload, builder)

    fun mobs(location: Location, type: EntityType, amount: Int, perTick: Int = 1,
             intervalTicks: Long = 1L, removeOnReload: Boolean = true,
             builder: KSLEntityBuilder.() -> Unit = {}): Int =
        spawnCustomEntities(location, "TYPE:" + type.name, amount, perTick, intervalTicks, removeOnReload, builder)

    fun spawnMobs(location: Location, type: EntityType, amount: Int, perTick: Int = 1,
                  intervalTicks: Long = 1L, removeOnReload: Boolean = true,
                  builder: KSLEntityBuilder.() -> Unit = {}): Int =
        mobs(location, type, amount, perTick, intervalTicks, removeOnReload, builder)

    fun shopkeeper(location: Location, name: String, removeOnReload: Boolean = true,
                   builder: KSLShopkeeperBuilder.() -> Unit): Villager =
        spawnShopkeeper(location, name, removeOnReload, builder)

    fun LivingEntity.behavior(builder: KSLEntityBehaviorBuilder.() -> Unit) {
        requireActiveGeneration("behavior")
        KSLEntityBehaviorBuilder(internalPlugin, scriptName, this, contextRuntimeScope).builder()
    }
}
