package ru.privateserver.ksl

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.SimpleCommandMap
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class KotlinScriptLoaderPlugin : JavaPlugin() {

    lateinit var database: HikariDataSource
        private set

    val dbExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KSL-DB").apply { isDaemon = true }
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
    private val tasksByScript        = ConcurrentHashMap<String, MutableList<Int>>()
    private val scriptsWithGuis       = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val placeholders                 = ConcurrentHashMap<String, (OfflinePlayer) -> String?>()

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

    override fun onEnable() {
        dataFolder.mkdirs()
        saveDefaultConfig()
        KSLErrors.reset()

        addonManager   = KSLAddonManager(this)
        sandboxEnabled = config.getBoolean("sandbox", false)
        scriptRunner   = ScriptRunner(this)

        KSL.init(addonManager)

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
        printStartupSummary(dbOk, loaded, failed)
    }

    override fun onDisable() {
        logger.info("$c9$bold■ KotlinScriptLoader останавливается...$r")
        runCatching { unloadAllScripts() }
            .onFailure { logger.warning("Ошибка при выгрузке скриптов: ${it.message}") }
        runCatching { addonManager.shutdown() }
            .onFailure { logger.warning("Ошибка при выгрузке аддонов: ${it.message}") }
        runCatching {
            dbExecutor.shutdown()
            if (!dbExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow()
            }
        }.onFailure { logger.warning("Не удалось корректно остановить DB-очередь: ${it.message}") }
        runCatching { if (::database.isInitialized) database.close() }
            .onFailure { logger.warning("Ошибка при закрытии базы данных: ${it.message}") }
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

    private fun initDatabase(): Boolean {
        return try {
            val cfg = HikariConfig().apply {
                jdbcUrl         = "jdbc:sqlite:${File(dataFolder, "ksl.db").absolutePath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 4
                poolName        = "ksl-pool"
            }
            database = HikariDataSource(cfg)
            val applied = database.connection.use { Migrations.run(it) }
            if (applied > 0) logger.info("Применено миграций БД: $applied")
            true
        } catch (ex: Exception) {
            logger.severe("Не удалось инициализировать базу данных: ${ex.message}"); false
        }
    }

    private fun setupIntegrations() {
        papiEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null
        if (papiEnabled)
            runCatching { KSLPlaceholderExpansion(this).register() }
                .onFailure { logger.warning("PlaceholderAPI: регистрация expansion не удалась: ${it.message}") }

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null)
            runCatching { luckPerms = KSLLuckPermsHook() }
                .onFailure { logger.warning("LuckPerms: инициализация хука не удалась: ${it.message}") }

        if (Bukkit.getPluginManager().getPlugin("Essentials") != null)
            runCatching { essentials = KSLEssentialsHook() }
                .onFailure { logger.warning("EssentialsX: инициализация хука не удалась: ${it.message}") }

        if (Bukkit.getPluginManager().getPlugin("Vault") != null)
            runCatching { vault = KSLVaultHook() }
                .onFailure { logger.warning("Vault: инициализация хука не удалась: ${it.message}") }

        if (Bukkit.getPluginManager().getPlugin("SkinsRestorer") != null)
            runCatching { skinRestorer = KSLSkinHook() }
                .onFailure { logger.warning("SkinsRestorer: инициализация хука не удалась: ${it.message}") }

        if (Bukkit.getPluginManager().getPlugin("WorldEdit") != null)
            runCatching { worldEdit = KSLWorldEditHook() }
                .onFailure { logger.warning("WorldEdit: инициализация хука не удалась: ${it.message}") }

        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null)
            runCatching { worldGuard = KSLWorldGuardHook() }
                .onFailure { logger.warning("WorldGuard: инициализация хука не удалась: ${it.message}") }
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
                appendLine("fun runAsync(block: () -> Unit) = Unit")
                appendLine("fun runSync(block: () -> Unit) = Unit")
                appendLine("fun delay(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun every(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int = 0")
                appendLine("fun delayAsync(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun everyAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int = 0")
                appendLine()
                appendLine("inline fun <reified T : Event> onEvent(priority: EventPriority = EventPriority.NORMAL, crossinline action: T.() -> Unit) = Unit")
                appendLine("fun registerCommand(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((Player, Array<String>) -> List<String>)? = null, action: (Player, Array<String>) -> Unit) = Unit")
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
                appendLine("class KSLEffectSpec { var count: Int = 1; var offsetX: Double = 0.0; var offsetY: Double = 0.0; var offsetZ: Double = 0.0; var extra: Double = 0.0; var durationTicks: Long = 100L; var intervalTicks: Long = 2L; fun circle(radius: Double, stepDegrees: Double = 15.0) = Unit; fun helix(radius: Double, turns: Double = 3.0, height: Double = 3.0) = Unit; fun lineTo(target: () -> org.bukkit.Location, points: Int = 20) = Unit }")
                appendLine("fun org.bukkit.Location.playEffect(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.playEffect(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun stopEffect(taskId: Int) = Unit")
                appendLine()
                appendLine("class KSLEntityBuilder { fun equip(template: String) = Unit }")
                appendLine("class KSLEntityBehaviorBuilder { fun onTick(period: Long, initialDelay: Long = 0L, block: (org.bukkit.entity.LivingEntity) -> Unit) = Unit; fun onDeath(block: (org.bukkit.entity.LivingEntity, Player?) -> Unit) = Unit }")
                appendLine("fun spawnCustomEntity(location: org.bukkit.Location, template: String, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun org.bukkit.entity.LivingEntity.behavior(builder: KSLEntityBehaviorBuilder.() -> Unit) = Unit")
            })
            logger.info("Сгенерирован .autocomplete.kts для IDE-подсказок")
        }.onFailure { logger.warning("Не удалось сгенерировать .autocomplete.kts: ${it.message}") }
    }

    var lastLoadStats: Pair<Int, Int> = 0 to 0
        private set

    fun loadAllScripts(): Pair<Int, Int> {
        val files = scriptsFolder.listFiles { f ->
            f.isFile && f.extension == "kts" && !f.name.startsWith(".")
        } ?: emptyArray()
        val ordered = KSLScriptOrder.sort(files, logger)
        var loaded = 0; var failed = 0
        ordered.forEach { if (scriptRunner.loadScript(it)) loaded++ else failed++ }
        lastLoadStats = loaded to failed
        return loaded to failed
    }

    fun validateAllScripts(): Map<String, List<String>> {
        val files = scriptsFolder.listFiles { f ->
            f.isFile && f.extension == "kts" && !f.name.startsWith(".")
        } ?: emptyArray()
        val validator = ScriptValidator(this)
        return files.associate { it.nameWithoutExtension to validator.validate(it) }
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
        }
        true
    } catch (ex: Exception) {
        logger.warning("[doctor] Проверка таблиц БД не удалась: ${ex.message}")
        false
    }

    fun unloadAllScripts() {
        val loadedScripts = (tasksByScript.keys + listenersByScript.keys + commandsByScript.keys + scriptsWithGuis + libraryRegistry.scriptNames()).toSet()
        loadedScripts.forEach { unloadScript(it) }
    }

    private fun unloadScript(scriptName: String) {
        runCatching {
            tasksByScript.remove(scriptName)?.forEach { Bukkit.getScheduler().cancelTask(it) }
        }.onFailure { logger.warning("[$scriptName] Не удалось отменить часть тасков: ${it.message}") }

        runCatching {
            listenersByScript.remove(scriptName)?.forEach { HandlerList.unregisterAll(it) }
        }.onFailure { logger.warning("[$scriptName] Не удалось снять часть listener'ов: ${it.message}") }

        runCatching {
            val commands = commandsByScript.remove(scriptName)?.toSet() ?: emptySet()
            if (commands.isNotEmpty()) unregisterCommands(commands)
        }.onFailure { logger.warning("[$scriptName] Не удалось снять часть команд: ${it.message}") }

        runCatching {
            placeholdersByScript.remove(scriptName)?.forEach { placeholders.remove(it) }
        }.onFailure { logger.warning("[$scriptName] Не удалось снять часть плейсхолдеров: ${it.message}") }

        runCatching { addonManager.notifyContextDestroyed(scriptName) }
            .onFailure { logger.warning("[$scriptName] Ошибка при уведомлении аддонов о выгрузке: ${it.message}") }

        runCatching { guiManager.closeGuisForScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] Не удалось закрыть открытые GUI: ${it.message}") }
        scriptsWithGuis.remove(scriptName)

        runCatching { libraryRegistry.clearScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] Не удалось очистить библиотечные экспорты: ${it.message}") }

        runCatching { entityManager.cleanupForScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] Не удалось убрать кастомных сущностей скрипта: ${it.message}") }

        runCatching { cooldownStore.clearScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] Не удалось очистить кулдауны скрипта: ${it.message}") }
    }

    private fun unregisterCommands(commands: Set<Command>) {
        if (commands.isEmpty()) return
        val viaApi = runCatching { removeOurCommands(Bukkit.getCommandMap().knownCommands, commands) }
        if (viaApi.isSuccess) return
        val viaReflection = runCatching { removeOurCommands(reflectKnownCommands(), commands) }
        if (viaReflection.isFailure)
            logger.warning("commandMap недоступен для изменения — старые команды сохранятся до рестарта.")
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
        sandboxEnabled = config.getBoolean("sandbox", false)
        discord = KSLDiscordHook(this).takeIf { it.channels().isNotEmpty() }
        unloadAllScripts()
        generateAutocompleteStub()
        return loadAllScripts()
    }

    fun reloadScript(scriptName: String): Boolean {
        val file = File(scriptsFolder, "$scriptName.kts")
        if (!file.exists() || !file.isFile) {
            logger.warning("Скрипт '$scriptName.kts' не найден в scripts/")
            return false
        }
        unloadScript(scriptName)
        return scriptRunner.loadScript(file)
    }

    data class DiagnosticLine(val label: String, val ok: Boolean, val detail: String)

    fun runDiagnostics(): List<DiagnosticLine> {
        val lines = mutableListOf<DiagnosticLine>()

        val dbOk = runCatching {
            database.connection.use { it.isValid(2) }
        }.getOrDefault(false)
        lines += DiagnosticLine("База данных", dbOk, if (dbOk) "соединение живое" else "не отвечает — проверь ksl.db")

        val tablesOk = checkDatabaseTables()
        lines += DiagnosticLine("Таблицы БД", tablesOk, if (tablesOk) "ksl_scripts, ksl_persist на месте" else "миграции не применились корректно")

        val brokenCommands = verifyTrackedCommands()
        lines += DiagnosticLine(
            "Команды скриптов",
            brokenCommands.isEmpty(),
            if (brokenCommands.isEmpty()) "все зарегистрированы корректно" else "перехвачены другим плагином: ${brokenCommands.joinToString(", ")}"
        )

        val scriptsWritable = runCatching { scriptsFolder.canWrite() }.getOrDefault(false)
        lines += DiagnosticLine("Папка scripts/", scriptsWritable, scriptsFolder.absolutePath)

        val (compilerOk, compilerDetail) = scriptRunner.selfTestCompiler()
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
            val installed = Bukkit.getPluginManager().getPlugin(name) != null
            val detail = when {
                present -> "подключен"
                installed -> "плагин найден, но хук не инициализировался — смотри лог при старте"
                else -> "не установлен (опционально)"
            }
            lines += DiagnosticLine(name, present || !installed, detail)
        }

        val errorCount = KSLErrors.totalCount()
        lines += DiagnosticLine("Ошибки скриптов", errorCount == 0, if (errorCount == 0) "не зафиксировано" else "$errorCount с последнего старта — см. /ksl errors")

        return lines
    }

    fun trackTask(scriptName: String, taskId: Int) {
        tasksByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(taskId)
    }

    fun trackGui(scriptName: String) {
        scriptsWithGuis.add(scriptName)
    }

    fun trackListener(scriptName: String, listener: Listener) {
        listenersByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(listener)
    }

    fun trackCommand(scriptName: String, command: DynamicCommand) {
        commandsByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(command)
        if (Bukkit.getCommandMap().getCommand(command.name) !== command)
            logger.warning("Команда '/${command.name}' из '${command.scriptName}' конфликтует — " +
                "доступна как '/${name.lowercase()}:${command.name}'.")
    }

    fun registerPlaceholder(scriptName: String, key: String, resolver: (OfflinePlayer) -> String?) {
        placeholders[key] = resolver
        placeholdersByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(key)
    }
}
