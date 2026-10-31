package ru.privateserver.ksl

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.SimpleCommandMap
import org.bukkit.event.HandlerList
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class KotlinScriptLoaderPlugin : JavaPlugin() {

    lateinit var database: HikariDataSource
        private set

    @Volatile private var dbThread: Thread? = null

    val dbExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KSL-DB").apply { isDaemon = true }.also { dbThread = it }
    }

    // Runs after every write queued before it. Inline on the DB thread: waiting on our own queue would deadlock.
    fun <T> dbCall(task: () -> T): T {
        if (Thread.currentThread() === dbThread) return task()
        try {
            return dbExecutor.submit(java.util.concurrent.Callable { task() }).get()
        } catch (ex: java.util.concurrent.ExecutionException) {
            throw ex.cause ?: ex
        }
    }

    lateinit var addonManager: KSLAddonManager
        private set

    lateinit var persistStore: KSLPersistStore
        private set

    lateinit var tableStore: KSLTableStore
        private set

    lateinit var guiManager: KSLGuiManager
        private set

    lateinit var effectManager: KSLEffectManager
        private set

    lateinit var entityManager: KSLEntityManager
        private set

    lateinit var libraryRegistry: KSLLibraryRegistry
        private set

    val cooldownStore = KSLCooldownStore()

    var sandboxEnabled = false
        private set

    var papiEnabled = false
        private set

    var luckPerms: KSLLuckPermsHook? = null
        private set

    var essentials: KSLEssentialsHook? = null
        private set

    var vault: KSLVaultHook? = null
        private set

    var skinRestorer: KSLSkinHook? = null
        private set

    var worldEdit: KSLWorldEditHook? = null
        private set

    var worldGuard: KSLWorldGuardHook? = null
        private set

    var discord: KSLDiscordHook? = null
        private set

    private lateinit var scriptRunner: ScriptRunner

    private val listenersByScript    = ConcurrentHashMap<String, MutableList<Listener>>()
    private val commandsByScript     = ConcurrentHashMap<String, MutableList<DynamicCommand>>()
    private val placeholdersByScript = ConcurrentHashMap<String, MutableList<String>>()
    private val tasksByScript        = ConcurrentHashMap<String, MutableSet<Int>>()
    private val scriptsWithGuis       = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Owner label is "script:<name>" for scripts and the addon id for addons.
    val placeholders                 = KSLOwnedRegistry<(OfflinePlayer) -> String?>()
    private var papiUnregister: (() -> Unit)? = null

    // @key@ tokens for message()/richMessage(); owner is "script:<name>", an addon id, or "config".
    val textPlaceholders = KSLOwnedRegistry<(Player?) -> String?>()

    fun registerTextPlaceholder(owner: String, key: String, resolver: (Player?) -> String?) {
        val normalized = key.lowercase()
        if (normalized in KSLTextPlaceholders.reserved) {
            logger.warning("[$owner] ${KSLError.TEXT_PLACEHOLDER_RESERVED.tag} @$normalized@ встроен в KSL — выбери другое имя")
            return
        }
        val previous = textPlaceholders.ownerOf(normalized)
        if (textPlaceholders.contains(normalized) && previous != owner) {
            logger.warning("[$owner] ${KSLError.PLACEHOLDER_CONFLICT.tag} Текстовый плейсхолдер @$normalized@ уже занят (${previous ?: "?"}) — будет перезаписан")
        }
        textPlaceholders.put(normalized, resolver, owner)
    }

    fun resolveTextPlaceholder(key: String, player: Player?): String? {
        val resolver = textPlaceholders.get(key) ?: return null
        return try {
            resolver(player) ?: ""
        } catch (ex: Throwable) {
            KSLErrors.log(this, textPlaceholders.ownerOf(key)?.removePrefix("script:") ?: "?", "@$key@", ex, KSLError.TEXT_PLACEHOLDER_FAILED)
            ""
        }
    }

    // text-placeholders: in config.yml, re-read on every /ksl reload.
    private fun loadConfigTextPlaceholders() {
        textPlaceholders.releaseOwner("config")
        val section = config.getConfigurationSection("text-placeholders") ?: return
        section.getKeys(false).forEach { key ->
            val value = section.getString(key) ?: return@forEach
            registerTextPlaceholder("config", key) { _ -> value }
        }
    }

    val scriptsFolder: File by lazy { File(dataFolder, "scripts").apply { mkdirs() } }

    private val c9 = "§9"
    private val cb = "§b"
    private val cf = "§f"
    private val cg = "§a"
    private val ce = "§e"
    private val cc = "§c"
    private val c7 = "§7"
    private val bold = "§l"
    private val r = "§r"

    // Compiling scripts is not thread-safe against itself; async diagnostics and main-thread loads take turns.
    val compileLock = Any()

    private val legacyColors = Regex("§[0-9A-Fa-fK-Ok-oRrXx]")

    // The console prints "§9" literally when it comes through the plugin logger, so the codes are stripped there.
    private fun stripColorsFromLog() {
        logger.filter = java.util.logging.Filter { record ->
            record.message = record.message?.replace(legacyColors, "")
            true
        }
    }

    override fun onEnable() {
        stripColorsFromLog()
        dataFolder.mkdirs()
        saveDefaultConfig()
        loadConfigTextPlaceholders()
        KSLErrors.reset()

        addonManager   = KSLAddonManager(this)
        sandboxEnabled = config.getBoolean("sandbox", false)
        scriptRunner   = ScriptRunner(this)

        KSL.init(addonManager)
        KSLEffectShapes.registerDefaults(addonManager.effectShapes)
        KSLEntityAttributes.registerDefaults(addonManager.entityAttributes)

        getCommand("ksl")?.setExecutor(KSLCommandExecutor(this))

        logger.info("$c9$bold▶ KotlinScriptLoader v${description.version} запускается$r")

        val dbOk = initDatabase()
        persistStore = KSLPersistStore(this)
        tableStore = KSLTableStore(this)
        guiManager = KSLGuiManager(this)
        guiManager.register()
        effectManager = KSLEffectManager(this)
        entityManager = KSLEntityManager(this)
        entityManager.register()
        libraryRegistry = KSLLibraryRegistry(this)
        setupIntegrations()
        discord = KSLDiscordHook(this).takeIf { it.channels().isNotEmpty() }
        generateAutocompleteStub()

        val (loaded, failed) = loadAllScripts()
        startWatcher()
        printStartupSummary(dbOk, loaded, failed)
    }

    override fun onDisable() {
        runCatching { scriptWatcher?.stop() }
        logger.info("$c9$bold■ KotlinScriptLoader останавливается...$r")
        runCatching { unloadAllScripts() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при выгрузке скриптов: ${it.message}") }
        runCatching { addonManager.shutdown() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при выгрузке аддонов: ${it.message}") }
        runCatching { papiUnregister?.invoke() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при снятии PlaceholderAPI expansion: ${it.message}") }
        runCatching {
            dbExecutor.shutdown()
            if (!dbExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                val dropped = dbExecutor.shutdownNow().size
                if (dropped > 0) logger.warning("${KSLError.DB_WRITES_LOST.tag} DB-очередь не успела завершиться: потеряно записей — $dropped")
            }
        }.onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Не удалось корректно остановить DB-очередь: ${it.message}") }
        runCatching { if (::database.isInitialized) database.close() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при закрытии базы данных: ${it.message}") }
        KSL.shutdown()
        logger.info("$c9$bold■ KotlinScriptLoader остановлен$r")
    }

    private fun printStartupSummary(dbOk: Boolean, loaded: Int, failed: Int) {
        fun flag(on: Boolean) = if (on) "${cg}✔ найден$r" else "${ce}○ не найден$r"
        val addonsCount = addonManager.registeredAddons().size
        val servicesCount = addonManager.getServiceKeys().size

        logger.info("$c9${bold}╔══════════════════════════════════════╗$r")
        logger.info("$c9${bold}║$r $cb$bold KotlinScriptLoader $cf v${description.version}$r")
        logger.info("$c9${bold}╠══════════════════════════════════════╣$r")
        logger.info("$c9${bold}║$r ${if (dbOk) cg else cc}База данных     : ${if (dbOk) "${cg}✔ OK$r" else "${cc}✘ ОШИБКА$r"}")
        logger.info("$c9${bold}║$r ${if (failed == 0) cg else ce}Скрипты         : $cf$loaded ${c7}загружено$r$cf, $failed ${c7}с ошибками$r")
        logger.info("$c9${bold}║$r ${c7}PlaceholderAPI  : ${flag(papiEnabled)}")
        logger.info("$c9${bold}║$r ${c7}LuckPerms       : ${flag(luckPerms != null)}")
        logger.info("$c9${bold}║$r ${c7}EssentialsX     : ${flag(essentials != null)}")
        logger.info("$c9${bold}║$r ${c7}Vault           : ${flag(vault != null)}")
        logger.info("$c9${bold}║$r ${c7}SkinsRestorer   : ${flag(skinRestorer != null)}")
        logger.info("$c9${bold}║$r ${c7}WorldEdit       : ${flag(worldEdit != null)}")
        logger.info("$c9${bold}║$r ${c7}WorldGuard      : ${flag(worldGuard != null)}")
        logger.info("$c9${bold}║$r ${c7}Discord         : ${if (discord != null) "${cg}✔ ${discord!!.channels().size} канал(ов)$r" else "${ce}○ не настроен$r"}")
        logger.info("$c9${bold}║$r ${c7}Аддоны          : ${if (addonsCount > 0) "${cg}✔ $addonsCount загружено$r" else "${ce}○ нет$r"}")
        logger.info("$c9${bold}║$r ${c7}Сервисы         : ${if (servicesCount > 0) "${cg}✔ $servicesCount зарегистрировано$r" else "${ce}○ нет$r"}")
        logger.info("$c9${bold}║$r ${c7}Sandbox         : ${if (sandboxEnabled) "${cg}✔ включён$r" else "${ce}○ выключен$r"}")
        logger.info("$c9${bold}╚══════════════════════════════════════╝$r")
    }

    // SQLite by default; MySQL/MariaDB when database.type says so (manage it with phpMyAdmin or any SQL client).
    var dialect: KSLDialect = KSLDialect.SQLITE
        private set

    // database.cache in config.yml; by default on for SQLite and off for MySQL/MariaDB.
    var tableCacheEnabled: Boolean = true
        private set

    private fun clearTableCaches() {
        if (this::tableStore.isInitialized) tableStore.clearCaches()
    }

    private fun readDatabaseSettings(): KSLDatabaseSettings = KSLDatabaseSettings.parse(
        type = config.getString("database.type", "sqlite") ?: "sqlite",
        host = config.getString("database.host", "localhost") ?: "localhost",
        port = config.getInt("database.port", 3306),
        name = config.getString("database.name", "ksl") ?: "ksl",
        user = config.getString("database.user", "") ?: "",
        password = config.getString("database.password", "") ?: "",
        sslMode = config.getString("database.ssl-mode", "disable") ?: "disable",
        poolSize = config.getInt("database.pool-size", 4)
    )

    private fun initDatabase(): Boolean {
        var settings: KSLDatabaseSettings? = null
        return try {
            val parsed = readDatabaseSettings()
            settings = parsed
            val cfg = HikariConfig().apply {
                poolName        = "ksl-pool"
                maximumPoolSize = parsed.poolSize
                // Scripts get the raw pool; one unclosed connection starves everything else.
                leakDetectionThreshold = 10_000L
                if (parsed.dialect == KSLDialect.SQLITE) {
                    jdbcUrl         = "jdbc:sqlite:${File(dataFolder, "ksl.db").absolutePath}"
                    driverClassName = "org.sqlite.JDBC"
                    // Pool readers run next to the single DB writer thread; WAL + busy timeout avoid SQLITE_BUSY.
                    addDataSourceProperty("journal_mode", "WAL")
                    addDataSourceProperty("busy_timeout", "5000")
                } else {
                    jdbcUrl         = parsed.jdbcUrl()
                    driverClassName = "org.mariadb.jdbc.Driver"
                    username        = parsed.user
                    password        = parsed.password
                    connectionTimeout = 10_000L
                }
            }
            dialect = parsed.dialect
            tableCacheEnabled = config.getBoolean("database.cache", !parsed.dialect.isRemote)
            database = HikariDataSource(cfg)
            val applied = database.connection.use { Migrations.run(it, dialect) }
            logger.info("База данных: ${parsed.describe()}")
            if (applied > 0) logger.info("Применено миграций БД: $applied")
            true
        } catch (ex: Exception) {
            val rootMessage = generateSequence<Throwable>(ex) { it.cause }.last().message ?: ex.message
            logger.severe("${KSLError.DB_INIT_FAILED.tag} Не удалось инициализировать базу данных (${settings?.describe() ?: "настройки не разобраны"}): $rootMessage")
            if (settings?.dialect?.isRemote == true) {
                logger.severe("Проверь host/port/user/password в config.yml, что база '${settings?.name}' создана (phpMyAdmin → Базы данных) и у пользователя есть на неё права.")
            }
            false
        }
    }

    // Present plugin + successful init => the hook is stored in its typed field and published in the hook registry.
    private fun <T : KSLHook> integrate(pluginName: String, label: String, create: () -> T): T? {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) return null
        val hook = runCatching(create)
            .onFailure { logger.warning("${KSLError.HOOK_INIT_FAILED.tag} $label: инициализация хука не удалась: ${it.message}") }
            .getOrNull()
        hook?.let { addonManager.registerHook(it) }
        return hook
    }

    private fun setupIntegrations() {
        papiEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null
        if (papiEnabled)
            runCatching {
                val expansion = KSLPlaceholderExpansion(this)
                expansion.register()
                papiUnregister = { expansion.unregister() }
            }
                .onFailure { logger.warning("${KSLError.PAPI_REGISTER_FAILED.tag} PlaceholderAPI: регистрация expansion не удалась: ${it.message}") }

        luckPerms   = integrate("LuckPerms", "LuckPerms") { KSLLuckPermsHook() }
        essentials  = integrate("Essentials", "EssentialsX") { KSLEssentialsHook() }
        vault       = integrate("Vault", "Vault") { KSLVaultHook() }
        skinRestorer = integrate("SkinsRestorer", "SkinsRestorer") { KSLSkinHook() }
        worldEdit   = integrate("WorldEdit", "WorldEdit") { KSLWorldEditHook() }
        worldGuard  = integrate("WorldGuard", "WorldGuard") { KSLWorldGuardHook() }
    }

    private fun generateAutocompleteStub() {
        val stub = File(scriptsFolder, ".autocomplete.kts")
        runCatching {
            val addonLines = addonManager.registeredAddons()
                .filter { it.addonDescription.isNotBlank() }
                .joinToString("\n") { "// Addon: ${it.addonId} v${it.addonVersion} — ${it.addonDescription}" }

            stub.writeText(buildString {
                appendLine("// AUTO-GENERATED by KotlinScriptLoader v${description.version} — не редактировать")
                appendLine("// IntelliJ IDEA: File → Project Structure → Libraries → добавь этот файл")
                if (addonLines.isNotBlank()) { appendLine(); appendLine(addonLines) }
                appendLine()
                appendLine("import org.bukkit.entity.Player")
                appendLine("import org.bukkit.event.Event")
                appendLine("import org.bukkit.event.EventPriority")
                appendLine("import org.bukkit.inventory.ItemStack")
                appendLine("import org.bukkit.OfflinePlayer")
                appendLine("import org.bukkit.configuration.file.YamlConfiguration")
                appendLine("import net.kyori.adventure.text.Component")
                appendLine("import java.sql.ResultSet")
                appendLine("import java.util.logging.Logger")
                appendLine()
                appendLine("object plugin {")
                appendLine("    val dataFolder: java.io.File get() = java.io.File(\".\")")
                appendLine("    val logger: Logger get() = Logger.getLogger(\"KSL\")")
                appendLine("    val name: String get() = \"KSL\"")
                appendLine("    val description: org.bukkit.plugin.PluginDescriptionFile get() = throw UnsupportedOperationException()")
                appendLine("    fun getConfig(): YamlConfiguration = YamlConfiguration()")
                appendLine("    fun saveDefaultConfig() = Unit")
                appendLine("    fun reloadConfig() = Unit")
                appendLine("}")
                appendLine("val logger: Logger get() = Logger.getLogger(\"KSL\")")
                appendLine("val scriptName: String get() = \"\"")
                appendLine()
                appendLine("val config: YamlConfiguration get() = YamlConfiguration()")
                appendLine("fun saveConfig() = Unit")
                appendLine("fun loadYaml(fileName: String): YamlConfiguration = YamlConfiguration()")
                appendLine("fun YamlConfiguration.message(path: String, vararg replacements: Pair<String, Any?>): String = \"\"")
                appendLine("fun YamlConfiguration.message(path: String, player: Player, vararg replacements: Pair<String, Any?>): String = \"\"")
                appendLine("fun YamlConfiguration.messageList(path: String, vararg replacements: Pair<String, Any?>): List<String> = emptyList()")
                appendLine("fun YamlConfiguration.messageList(path: String, player: Player, vararg replacements: Pair<String, Any?>): List<String> = emptyList()")
                appendLine("fun YamlConfiguration.richMessage(path: String, vararg replacements: Pair<String, Any?>): Component = Component.empty()")
                appendLine("fun YamlConfiguration.richMessage(path: String, player: Player, vararg replacements: Pair<String, Any?>): Component = Component.empty()")
                appendLine("fun <T> YamlConfiguration.getOrSetDefault(path: String, default: T): T = default")
                appendLine()
                appendLine("class KSLQuickTable { fun set(rowKey: String, columnKey: String, value: Any?) = Unit; fun get(rowKey: String, columnKey: String): String? = null; fun getInt(rowKey: String, columnKey: String, default: Int = 0): Int = default; fun getLong(rowKey: String, columnKey: String, default: Long = 0L): Long = default; fun getDouble(rowKey: String, columnKey: String, default: Double = 0.0): Double = default; fun getBoolean(rowKey: String, columnKey: String, default: Boolean = false): Boolean = default; fun increment(rowKey: String, columnKey: String, amount: Long = 1L): Long = 0L; fun delete(rowKey: String, columnKey: String? = null) = Unit; fun row(rowKey: String): Map<String, Any?> = emptyMap() }")
                appendLine("fun table(name: String): KSLQuickTable = KSLQuickTable()")
                appendLine()
                appendLine("fun dbExecute(sql: String, vararg args: Any?) = Unit")
                appendLine("fun dbQuery(sql: String, vararg args: Any?, block: (ResultSet) -> Unit) = Unit")
                appendLine()
                appendLine("fun runAsync(block: () -> Unit): Int = 0")
                appendLine("fun runSync(block: () -> Unit): Int = 0")
                appendLine("fun cancelTask(taskId: Int) = Unit")
                appendLine("fun delay(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun every(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int = 0")
                appendLine("fun delayAsync(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun everyAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int = 0")
                appendLine()
                appendLine("inline fun <reified T : Event> onEvent(priority: EventPriority = EventPriority.NORMAL, ignoreCancelled: Boolean = false, crossinline action: T.() -> Unit) = Unit")
                appendLine("fun registerCommand(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((Player, Array<String>) -> List<String>)? = null, action: (Player, Array<String>) -> Unit) = Unit")
                appendLine("fun registerCommandAny(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((org.bukkit.command.CommandSender, Array<String>) -> List<String>)? = null, action: (org.bukkit.command.CommandSender, Array<String>) -> Unit) = Unit")
                appendLine("fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable = AutoCloseable { }")
                appendLine("fun publish(topic: String, payload: Any? = null): Int = 0")
                appendLine("// ---- ru.privateserver.ksl.dsl (aliases, imported automatically) ----")
                appendLine("val Int.sec: Long get() = 0L")
                appendLine("val Long.sec: Long get() = 0L")
                appendLine("val Int.mins: Long get() = 0L")
                appendLine("fun org.bukkit.command.CommandSender.msg(text: String) = Unit")
                appendLine("fun Player.actionBar(text: String) = Unit")
                appendLine("val online: Collection<Player> get() = emptyList()")
                appendLine("fun playerNamed(name: String): Player? = null")
                appendLine("fun chance(percent: Double): Boolean = false")
                appendLine("fun item(material: org.bukkit.Material, name: String? = null, lore: List<String> = emptyList(), amount: Int = 1): ItemStack = ItemStack(material)")
                appendLine("fun Player.giveItem(stack: ItemStack): Boolean = true")
                appendLine("fun log(message: String) = Unit")
                appendLine("fun logWarn(message: String) = Unit")
                appendLine("fun logError(message: String) = Unit")
                appendLine("fun later(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun laterAsync(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun sync(block: () -> Unit): Int = 0")
                appendLine("fun async(block: () -> Unit): Int = 0")
                appendLine("inline fun <reified T : Event> on(priority: EventPriority = EventPriority.NORMAL, ignoreCancelled: Boolean = false, crossinline action: T.() -> Unit) = Unit")
                appendLine("fun command(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((Player, Array<String>) -> List<String>)? = null, action: (Player, Array<String>) -> Unit) = Unit")
                appendLine("fun commandAny(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((org.bukkit.command.CommandSender, Array<String>) -> List<String>)? = null, action: (org.bukkit.command.CommandSender, Array<String>) -> Unit) = Unit")
                appendLine("fun <T> cfg(path: String, default: T): T = default")
                appendLine("val String.mm: net.kyori.adventure.text.Component get() = throw UnsupportedOperationException()")
                appendLine("fun Player.title(title: String, subtitle: String = \"\", fadeInTicks: Int = 10, stayTicks: Int = 40, fadeOutTicks: Int = 10) = Unit")
                appendLine("fun Player.sound(key: String, volume: Float = 1f, pitch: Float = 1f) = Unit")
                appendLine("fun Player.heal() = Unit")
                appendLine("fun loc(world: String, x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): org.bukkit.Location? = null")
                appendLine("fun org.bukkit.Location.offset(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0): org.bukkit.Location = this")
                appendLine("fun org.bukkit.Location.playersNear(radius: Double): Collection<Player> = emptyList()")
                appendLine("fun onJoin(action: org.bukkit.event.player.PlayerJoinEvent.() -> Unit) = Unit")
                appendLine("fun onQuit(action: org.bukkit.event.player.PlayerQuitEvent.() -> Unit) = Unit")
                appendLine("fun onPlayerDeath(action: org.bukkit.event.entity.PlayerDeathEvent.() -> Unit) = Unit")
                appendLine("fun onRespawn(action: org.bukkit.event.player.PlayerRespawnEvent.() -> Unit) = Unit")
                appendLine("fun onBlockBreak(action: org.bukkit.event.block.BlockBreakEvent.() -> Unit) = Unit")
                appendLine("fun onBlockPlace(action: org.bukkit.event.block.BlockPlaceEvent.() -> Unit) = Unit")
                appendLine("fun onInteract(action: org.bukkit.event.player.PlayerInteractEvent.() -> Unit) = Unit")
                appendLine("fun onChat(action: io.papermc.paper.event.player.AsyncChatEvent.() -> Unit) = Unit")
                appendLine("fun hasHook(hookId: String): Boolean = false")
                appendLine("inline fun <reified T : ru.privateserver.ksl.KSLHook> hook(hookId: String): T? = null")
                appendLine("inline fun <reified T : ru.privateserver.ksl.KSLHook> requireHook(hookId: String): T = throw UnsupportedOperationException()")
                appendLine()
                appendLine("fun parseMM(text: String): Component = Component.empty()")
                appendLine("fun broadcastMM(text: String) = Unit")
                appendLine("fun broadcast(message: String) = Unit")
                appendLine("fun Player.sendRichMessage(text: String) = Unit")
                appendLine("fun Player.sendActionBarMessage(text: String) = Unit")
                appendLine("fun Player.showRichTitle(title: String, subtitle: String = \"\", fadeIn: Int = 10, stay: Int = 70, fadeOut: Int = 20) = Unit")
                appendLine()
                appendLine("var Player.balance: Double?")
                appendLine("    get() = null")
                appendLine("    set(value) = Unit")
                appendLine("val Player.group: String? get() = null")
                appendLine("val Player.prefix: String? get() = null")
                appendLine()
                appendLine("fun registerPlaceholder(key: String, resolver: (OfflinePlayer) -> String?) = Unit")
                appendLine("fun placeholder(key: String, resolver: (OfflinePlayer) -> String?) = Unit")
                appendLine("fun textPlaceholder(key: String, resolver: (Player?) -> String?) = Unit")
                appendLine("fun registerTextPlaceholder(key: String, resolver: (Player?) -> String?) = Unit")
                appendLine()
                appendLine("fun luckPermsGroup(player: Player): String? = null")
                appendLine("fun luckPermsPrefix(player: Player): String? = null")
                appendLine("fun luckPermsSuffix(player: Player): String? = null")
                appendLine("fun hasGroup(player: Player, group: String): Boolean = false")
                appendLine()
                appendLine("fun balance(player: Player): Double? = null")
                appendLine("fun setBalance(player: Player, amount: Double) = Unit")
                appendLine("fun isAfk(player: Player): Boolean = false")
                appendLine()
                appendLine("fun vaultBalance(player: OfflinePlayer): Double? = null")
                appendLine("fun vaultDeposit(player: OfflinePlayer, amount: Double): Boolean = false")
                appendLine("fun vaultWithdraw(player: OfflinePlayer, amount: Double): Boolean = false")
                appendLine("fun vaultHas(player: OfflinePlayer, amount: Double): Boolean = false")
                appendLine("fun vaultFormat(amount: Double): String = \"\"")
                appendLine("fun vaultHasPermission(player: Player, node: String): Boolean = false")
                appendLine("fun vaultGroup(player: Player): String? = null")
                appendLine("fun vaultInGroup(player: Player, group: String): Boolean = false")
                appendLine()
                appendLine("fun setSkin(player: Player, skinName: String): Boolean = false")
                appendLine("fun setSkinRaw(player: Player, value: String, signature: String): Boolean = false")
                appendLine()
                appendLine("fun ItemStack.setTag(key: String, value: String) = Unit")
                appendLine("fun ItemStack.getTag(key: String): String? = null")
                appendLine("fun ItemStack.hasTag(key: String): Boolean = false")
                appendLine()
                appendLine("fun weSelection(player: Player): Pair<org.bukkit.Location, org.bukkit.Location>? = null")
                appendLine("fun weSelectionVolume(player: Player): Long? = null")
                appendLine("fun weFillSelection(player: Player, material: org.bukkit.Material): Int? = null")
                appendLine("fun weHasClipboard(player: Player): Boolean = false")
                appendLine()
                appendLine("fun wgRegionsAt(location: org.bukkit.Location): List<String> = emptyList()")
                appendLine("fun wgIsInRegion(location: org.bukkit.Location, regionId: String): Boolean = false")
                appendLine("fun wgCanBuild(player: Player, location: org.bukkit.Location): Boolean = true")
                appendLine("fun wgCanPvp(player: Player, location: org.bukkit.Location): Boolean = true")
                appendLine("fun wgFlag(location: org.bukkit.Location, flagName: String, player: Player? = null): String? = null")
                appendLine()
                appendLine("fun executeConsole(command: String) = Unit")
                appendLine()
                appendLine("class KSLGuiInstance { fun set(slot: Int, item: ItemStack, onClick: ((Player, org.bukkit.event.inventory.InventoryClickEvent) -> Unit)? = null) = Unit; fun clear(slot: Int) = Unit; fun fill(item: ItemStack) = Unit; fun border(item: ItemStack) = Unit; fun allowItemMovement(slot: Int) = Unit; fun onClose(handler: (Player) -> Unit) = Unit; fun <T : Any> paginate(items: List<T>, slots: List<Int>, prevSlot: Int = -1, nextSlot: Int = -1, render: (T) -> ItemStack, onClick: (Player, T) -> Unit) = Unit; fun open(player: Player) = Unit }")
                appendLine("fun gui(title: String, rows: Int = 3, builder: KSLGuiInstance.() -> Unit): KSLGuiInstance = KSLGuiInstance()")
                appendLine()
                appendLine("class KSLPersistProperty<T>(private val v: T) { operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = v; operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = Unit }")
                appendLine("fun <T> persist(key: String, persistent: Boolean = false, default: () -> T): KSLPersistProperty<T> = KSLPersistProperty(default())")
                appendLine()
                appendLine("inline fun <reified T : Any> service(key: String): T? = null")
                appendLine()
                appendLine("fun cooldown(player: Player, key: String, seconds: Long): Boolean = true")
                appendLine("fun cooldownRemaining(player: Player, key: String, seconds: Long): Long = 0L")
                appendLine("fun resetCooldown(player: Player, key: String) = Unit")
                appendLine()
                appendLine("fun export(key: String, value: Any) = Unit")
                appendLine("inline fun <reified T : Any> library(key: String): T? = null")
                appendLine("inline fun <reified T : Any> requireLibrary(key: String): T = throw UnsupportedOperationException()")
                appendLine()
                appendLine("fun discordSend(channel: String, message: String) = Unit")
                appendLine("fun discordEmbed(channel: String, title: String, description: String = \"\", color: Int = 0x5865F2, footer: String = \"\") = Unit")
                appendLine()
                appendLine("class KSLEffectSpec { var count: Int = 1; var offsetX: Double = 0.0; var offsetY: Double = 0.0; var offsetZ: Double = 0.0; var extra: Double = 0.0; var durationTicks: Long = 100L; var intervalTicks: Long = 2L; var data: Any? = null; fun circle(radius: Double, stepDegrees: Double = 15.0) = Unit; fun helix(radius: Double, turns: Double = 3.0, height: Double = 3.0) = Unit; fun lineTo(target: () -> org.bukkit.Location, points: Int = 20) = Unit; fun sphere(radius: Double, points: Int = 60, spinDegreesPerTick: Double = 0.0) = Unit; fun square(size: Double, pointsPerSide: Int = 10) = Unit; fun point() = Unit; fun custom(points: (Long, org.bukkit.Location) -> List<org.bukkit.util.Vector>) = Unit; fun shape(name: String, vararg params: Pair<String, Any?>) = Unit; fun color(r: Int, g: Int, b: Int, size: Float = 1f) = Unit; fun color(hex: String, size: Float = 1f) = Unit; fun stopWhen(condition: () -> Boolean) = Unit; fun onEnd(block: () -> Unit) = Unit }")
                appendLine("fun org.bukkit.Location.playEffect(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.playEffect(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun stopEffect(taskId: Int) = Unit")
                appendLine()
                appendLine("class KSLEntityBuilder { fun equip(template: String, dropChance: Float? = null) = Unit; fun attribute(name: String, value: Double) = Unit; fun speed(multiplier: Double) = Unit; fun damage(value: Double) = Unit; fun armor(value: Double) = Unit; fun potion(type: org.bukkit.potion.PotionEffectType, seconds: Int = -1, amplifier: Int = 0) = Unit; fun glow(on: Boolean = true) = Unit; fun silent(on: Boolean = true) = Unit; fun invulnerable(on: Boolean = true) = Unit; fun drops(vararg items: ItemStack, exp: Int? = null, keepDefault: Boolean = false) = Unit }")
                appendLine("class KSLEntityBehaviorBuilder { fun onTick(period: Long, initialDelay: Long = 0L, block: (org.bukkit.entity.LivingEntity) -> Unit) = Unit; fun onDeath(block: (org.bukkit.entity.LivingEntity, Player?) -> Unit) = Unit; fun onDamaged(block: (org.bukkit.entity.LivingEntity, org.bukkit.event.entity.EntityDamageEvent) -> Unit) = Unit; fun onAttack(block: (org.bukkit.entity.LivingEntity, org.bukkit.event.entity.EntityDamageByEntityEvent) -> Unit) = Unit }")
                appendLine("fun spawnCustomEntity(location: org.bukkit.Location, template: String, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun org.bukkit.entity.LivingEntity.behavior(builder: KSLEntityBehaviorBuilder.() -> Unit) = Unit")
            })
            logger.info("Сгенерирован .autocomplete.kts для IDE-подсказок")
        }.onFailure { logger.warning("${KSLError.AUTOCOMPLETE_FAILED.tag} Не удалось сгенерировать .autocomplete.kts: ${it.message}") }
    }

    var lastLoadStats: Pair<Int, Int> = 0 to 0
        private set

    private var scriptWatcher: KSLScriptWatcher? = null

    private fun startWatcher() {
        if (!config.getBoolean("scripts.auto-reload", false)) return
        scriptWatcher = runCatching { KSLScriptWatcher(this).also { it.start() } }
            .onFailure { logger.warning("${KSLError.SCRIPT_WATCHER_FAILED.tag} Автоперезагрузка скриптов не запущена: ${it.message}") }
            .getOrNull()
        if (scriptWatcher != null) logger.info("Автоперезагрузка скриптов включена (scripts.auto-reload)")
    }

    // Folder-relative id without the extension, e.g. "economy/shop". It is the script's identity in logs,
    // persist keys, commands and @requires, so moving a script into another folder gives it a new id.
    fun scriptId(file: File): String = file.relativeTo(scriptsFolder).invariantSeparatorsPath.removeSuffix(".kts")

    // Every .kts under scripts/ (sub-folders included, up to 8 levels). Dot-files and folders starting with "." or "_" are skipped.
    fun findScriptFiles(): List<File> {
        val root = scriptsFolder
        val depth = if (config.getBoolean("scripts.recursive", true)) 8 else 1
        return root.walkTopDown().maxDepth(depth)
            .onEnter { it == root || !(it.name.startsWith(".") || it.name.startsWith("_")) }
            .filter { it.isFile && it.extension == "kts" && !it.name.startsWith(".") }
            .sortedBy { scriptId(it) }
            .toList()
    }

    fun scriptIds(): List<String> = findScriptFiles().map { scriptId(it) }

    // Last seen state of each loaded script file; lets /ksl reload changed skip everything that did not change.
    private val scriptStamps = ConcurrentHashMap<String, Long>()

    private fun stampOf(file: File): Long = file.lastModified() * 31 + file.length()

    private fun loadOne(file: File): Boolean {
        val ok = scriptRunner.loadScript(file)
        // Recorded even when loading failed, so a broken script is not recompiled until it changes.
        scriptStamps[scriptId(file)] = stampOf(file)
        return ok
    }

    fun loadAllScripts(): Pair<Int, Int> {
        val ordered = KSLScriptOrder.sort(findScriptFiles(), { scriptId(it) }, logger)
        var loaded = 0; var failed = 0
        ordered.forEach { if (loadOne(it)) loaded++ else failed++ }
        lastLoadStats = loaded to failed
        return loaded to failed
    }

    // Public so command handlers can run the compiler check off the main thread.
    fun selfTestCompiler(): Pair<Boolean, String> = scriptRunner.selfTestCompiler()

    fun validateAllScripts(): Map<String, List<String>> {
        val validator = ScriptValidator(this)
        return findScriptFiles().associate { scriptId(it) to validator.validate(it) }
    }

    fun verifyTrackedCommands(): List<String> {
        val broken = mutableListOf<String>()
        commandsByScript.values.flatten().forEach { cmd ->
            val bare = Bukkit.getCommandMap().getCommand(cmd.name)
            val prefixed = Bukkit.getCommandMap().getCommand("${name.lowercase()}:${cmd.name}")
            if (bare !== cmd && prefixed !== cmd) broken.add(cmd.name)
        }
        return broken
    }

    fun checkDatabaseTables(): Boolean = try {
        database.connection.use { conn ->
            conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ksl_scripts").close() }
            conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ksl_persist").close() }
            conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ksl_table").close() }
        }
        true
    } catch (ex: Exception) {
        logger.warning("${KSLError.DB_DOCTOR_TABLES.tag} [doctor] Проверка таблиц БД не удалась: ${ex.message}")
        false
    }

    fun unloadAllScripts() {
        val loadedScripts = (tasksByScript.keys + listenersByScript.keys + commandsByScript.keys + placeholdersByScript.keys +
            scriptsWithGuis + libraryRegistry.scriptNames() + entityManager.scriptNames()).toSet()
        loadedScripts.forEach { unloadScript(it) }
    }

    private fun unloadScript(scriptName: String) {
        scriptStamps.remove(scriptName)
        runCatching {
            tasksByScript.remove(scriptName)?.forEach { Bukkit.getScheduler().cancelTask(it) }
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось отменить часть тасков: ${it.message}") }

        runCatching {
            listenersByScript.remove(scriptName)?.forEach { HandlerList.unregisterAll(it) }
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось снять часть listener'ов: ${it.message}") }

        runCatching {
            val commands = commandsByScript.remove(scriptName)?.toSet() ?: emptySet()
            if (commands.isNotEmpty()) unregisterCommands(commands)
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось снять часть команд: ${it.message}") }

        runCatching {
            placeholdersByScript.remove(scriptName)
            // Only this script's own entries: another script may have taken over a key with the same name.
            placeholders.releaseOwner("script:$scriptName")
            textPlaceholders.releaseOwner("script:$scriptName")
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось снять часть плейсхолдеров: ${it.message}") }

        runCatching { addonManager.notifyContextDestroyed(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Ошибка при уведомлении аддонов о выгрузке: ${it.message}") }

        runCatching { guiManager.closeGuisForScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось закрыть открытые GUI: ${it.message}") }
        scriptsWithGuis.remove(scriptName)

        runCatching { libraryRegistry.clearScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось очистить библиотечные экспорты: ${it.message}") }

        runCatching { entityManager.cleanupForScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось убрать кастомных сущностей скрипта: ${it.message}") }

        runCatching { cooldownStore.clearScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось очистить кулдауны скрипта: ${it.message}") }
    }

    private fun unregisterCommands(commands: Set<Command>) {
        if (commands.isEmpty()) return
        scheduleCommandSync()
        val viaApi = runCatching { removeOurCommands(Bukkit.getCommandMap().knownCommands, commands) }
        if (viaApi.isSuccess) return
        val viaReflection = runCatching { removeOurCommands(reflectKnownCommands(), commands) }
        if (viaReflection.isFailure)
            logger.warning("${KSLError.COMMAND_MAP_UNAVAILABLE.tag} commandMap недоступен для изменения — старые команды сохранятся до рестарта.")
    }

    private fun removeOurCommands(knownCommands: MutableMap<String, Command>, ourCommands: Set<Command>) {
        val it = knownCommands.entries.iterator()
        while (it.hasNext()) { if (it.next().value in ourCommands) it.remove() }
    }

    private fun reflectKnownCommands(): MutableMap<String, Command> {
        val field = SimpleCommandMap::class.java.getDeclaredField("knownCommands")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(Bukkit.getCommandMap() as SimpleCommandMap) as MutableMap<String, Command>
    }

    fun reloadScripts(): Pair<Int, Int> {
        reloadConfig()
        loadConfigTextPlaceholders()
        clearTableCaches()
        sandboxEnabled = config.getBoolean("sandbox", false)
        discord = KSLDiscordHook(this).takeIf { it.channels().isNotEmpty() }
        unloadAllScripts()
        generateAutocompleteStub()
        return loadAllScripts()
    }

    fun reloadScript(scriptName: String): Boolean {
        val id = scriptName.trim().removeSuffix(".kts").replace('\\', '/')
        val file = File(scriptsFolder, "$id.kts")
        if (!file.canonicalFile.toPath().startsWith(scriptsFolder.canonicalFile.toPath())) {
            logger.warning("${KSLError.SCRIPT_PATH_ESCAPE.tag} Имя скрипта '$scriptName' выходит за пределы scripts/")
            return false
        }
        if (!file.isFile) {
            logger.warning("[$id] ${KSLError.SCRIPT_NOT_FOUND.tag} Скрипт '$id.kts' не найден в scripts/")
            return false
        }
        unloadScript(id)
        return loadOne(file)
    }

    // "economy" or "economy/": reloads every script in that folder (sub-folders included) in dependency order.
    // Returns null when the folder holds no scripts.
    fun reloadFolder(folder: String): Pair<Int, Int>? {
        val clean = folder.trim().replace('\\', '/').trim('/')
        if (clean.isEmpty()) return null
        val files = findScriptFiles().filter { scriptId(it).startsWith("$clean/") }
        if (files.isEmpty()) return null
        files.forEach { unloadScript(scriptId(it)) }
        var loaded = 0; var failed = 0
        KSLScriptOrder.sort(files, { scriptId(it) }, logger).forEach { if (loadOne(it)) loaded++ else failed++ }
        return loaded to failed
    }

    data class ChangeReport(val reloaded: Int, val unloaded: Int, val unchanged: Int, val failed: Int)

    // Recompiles only new and modified scripts and unloads deleted ones; compiling is the expensive part of a reload.
    fun reloadChanged(): ChangeReport {
        val files = findScriptFiles()
        val present = files.map { scriptId(it) }.toSet()
        val removed = scriptStamps.keys.filter { it !in present }
        removed.forEach { unloadScript(it) }
        val changed = files.filter { scriptStamps[scriptId(it)] != stampOf(it) }
        changed.forEach { unloadScript(scriptId(it)) }
        var failed = 0
        KSLScriptOrder.sort(changed, { scriptId(it) }, logger).forEach { if (!loadOne(it)) failed++ }
        return ChangeReport(changed.size - failed, removed.size, files.size - changed.size, failed)
    }

    // Used by the file watcher when a script file disappears.
    fun removeScript(scriptName: String) = unloadScript(scriptName)

    data class DiagnosticLine(val label: String, val ok: Boolean, val detail: String)

    // The compiler self-test takes seconds on first use; callers on the main thread pass a result computed elsewhere.
    fun runDiagnostics(compiler: Pair<Boolean, String> = scriptRunner.selfTestCompiler()): List<DiagnosticLine> {
        val lines = mutableListOf<DiagnosticLine>()

        val dbOk = runCatching {
            database.connection.use { it.isValid(2) }
        }.getOrDefault(false)
        lines += DiagnosticLine("База данных", dbOk, if (dbOk) "соединение живое (${dialect.label})" else "не отвечает — проверь ksl.db")

        val tablesOk = checkDatabaseTables()
        lines += DiagnosticLine("Таблицы БД", tablesOk, if (tablesOk) "ksl_scripts, ksl_persist, ksl_table на месте (${dialect.label})" else "миграции не применились корректно")

        val brokenCommands = verifyTrackedCommands()
        lines += DiagnosticLine(
            "Команды скриптов",
            brokenCommands.isEmpty(),
            if (brokenCommands.isEmpty()) "все зарегистрированы корректно" else "перехвачены другим плагином: ${brokenCommands.joinToString(", ")}"
        )

        val scriptsWritable = runCatching { scriptsFolder.canWrite() }.getOrDefault(false)
        lines += DiagnosticLine("Папка scripts/", scriptsWritable, scriptsFolder.absolutePath)

        val (compilerOk, compilerDetail) = compiler
        lines += DiagnosticLine("Компилятор скриптов", compilerOk, compilerDetail)

        val commandOk = Bukkit.getCommandMap().getCommand("ksl") != null
        lines += DiagnosticLine("Команда /ksl", commandOk, if (commandOk) "зарегистрирована" else "не найдена в commandMap")

        listOf(
            "PlaceholderAPI" to papiEnabled,
            "LuckPerms" to (luckPerms != null),
            "EssentialsX" to (essentials != null),
            "Vault" to (vault != null),
            "SkinsRestorer" to (skinRestorer != null),
            "WorldEdit" to (worldEdit != null),
            "WorldGuard" to (worldGuard != null)
        ).forEach { (name, present) ->
            // The doctor label says EssentialsX, the plugin itself is registered as Essentials.
            val installed = Bukkit.getPluginManager().getPlugin(if (name == "EssentialsX") "Essentials" else name) != null
            val detail = when {
                present -> "подключен"
                installed -> "плагин найден, но хук не инициализировался — смотри лог при старте"
                else -> "не установлен (опционально)"
            }
            lines += DiagnosticLine(name, present || !installed, detail)
        }

        // Hooks contributed by addons; the built-in ones are listed above.
        val builtinHooks = setOf("luckperms", "essentials", "vault", "skinsrestorer", "worldedit", "worldguard")
        addonManager.hooks().filter { it.hookId.lowercase() !in builtinHooks }.forEach { hook ->
            val enabled = addonManager.isPluginEnabled(hook.pluginName)
            lines += DiagnosticLine(
                "${hook.hookId} (${hook.pluginName})",
                enabled,
                if (enabled) "хук аддона подключен" else "хук зарегистрирован, но плагин ${hook.pluginName} не включён"
            )
        }

        val errorCount = KSLErrors.totalCount()
        lines += DiagnosticLine("Ошибки скриптов", errorCount == 0, if (errorCount == 0) "не зафиксировано" else "$errorCount с последнего старта — см. /ksl errors")

        return lines
    }

    // May be called from async tasks, hence the concurrent set.
    fun trackTask(scriptName: String, taskId: Int) {
        val ids = tasksByScript.computeIfAbsent(scriptName) { ConcurrentHashMap.newKeySet() }
        ids.add(taskId)
        // Finished one-shot tasks would otherwise pile up for as long as the script lives.
        if (ids.size % 64 == 0) {
            val scheduler = Bukkit.getScheduler()
            ids.removeIf { !scheduler.isQueued(it) && !scheduler.isCurrentlyRunning(it) }
        }
    }

    fun untrackTask(scriptName: String, taskId: Int) {
        tasksByScript[scriptName]?.remove(taskId)
    }

    fun trackGui(scriptName: String) {
        scriptsWithGuis.add(scriptName)
    }

    fun trackListener(scriptName: String, listener: Listener) {
        listenersByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(listener)
    }

    private val commandSyncQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    // Clients keep their own command tree: without a resend, removed commands stay visible and new ones lack tab-complete.
    // Coalesced into one pass per tick; skipped while disabling because the scheduler refuses new tasks then.
    private fun scheduleCommandSync() {
        if (!isEnabled || !commandSyncQueued.compareAndSet(false, true)) return
        try {
            Bukkit.getScheduler().runTask(this, Runnable {
                commandSyncQueued.set(false)
                Bukkit.getOnlinePlayers().forEach { runCatching { it.updateCommands() } }
            })
        } catch (ex: Exception) {
            commandSyncQueued.set(false)
        }
    }

    fun trackCommand(scriptName: String, command: DynamicCommand) {
        commandsByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(command)
        scheduleCommandSync()
        if (Bukkit.getCommandMap().getCommand(command.name) !== command)
            logger.warning("${KSLError.COMMAND_CONFLICT.tag} Команда '/${command.name}' из '${command.scriptName}' конфликтует — " +
                "доступна как '/${name.lowercase()}:${command.name}'.")
    }

    fun registerPlaceholder(scriptName: String, key: String, resolver: (OfflinePlayer) -> String?) {
        val normalized = key.lowercase()
        val owner = "script:$scriptName"
        val previousOwner = placeholders.ownerOf(normalized)
        if (placeholders.contains(normalized) && previousOwner != owner) {
            logger.warning("[$scriptName] ${KSLError.PLACEHOLDER_CONFLICT.tag} Плейсхолдер %ksl_$normalized% уже занят (${previousOwner ?: "аддон/хук"}) — будет перезаписан")
        }
        placeholders.put(normalized, resolver, owner)
        placeholdersByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(normalized)
    }
}
