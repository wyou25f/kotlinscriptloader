package ru.privateserver.ksl

import com.zaxxer.hikari.HikariDataSource
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.EventExecutor
import java.io.File
import java.io.IOException
import java.sql.ResultSet
import java.time.Duration

open class BukkitScriptContext(
    val plugin: KotlinScriptLoaderPlugin,
    val scriptName: String
) {

    private val mm: MiniMessage = MiniMessage.miniMessage()

    private val configFile: File
        get() = File(plugin.dataFolder, "$scriptName.yml")

    val config: YamlConfiguration by lazy {
        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            configFile.createNewFile()
        }
        YamlConfiguration.loadConfiguration(configFile)
    }

    fun saveConfig() {
        try { config.save(configFile) }
        catch (ex: IOException) { plugin.logger.warning("[$scriptName] Не удалось сохранить $scriptName.yml: ${ex.message}") }
    }

    fun loadYaml(fileName: String): YamlConfiguration {
        val file = File(plugin.scriptsFolder, fileName)
        if (!file.exists()) { file.parentFile?.mkdirs(); file.createNewFile() }
        return YamlConfiguration.loadConfiguration(file)
    }

    val database: HikariDataSource get() = plugin.database

    fun dbExecute(sql: String, vararg args: Any?) = runAsync {
        try {
            database.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
                    ps.executeUpdate()
                }
            }
        } catch (ex: Exception) { plugin.logger.warning("[$scriptName] dbExecute: ${ex.message}") }
    }

    fun dbQuery(sql: String, vararg args: Any?, block: (ResultSet) -> Unit) = runAsync {
        try {
            database.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
                    ps.executeQuery().use(block)
                }
            }
        } catch (ex: Exception) { plugin.logger.warning("[$scriptName] dbQuery: ${ex.message}") }
    }

    fun runAsync(block: () -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable(block))
    }

    fun runSync(block: () -> Unit) {
        Bukkit.getScheduler().runTask(plugin, Runnable(block))
    }

    fun delay(ticks: Long, block: () -> Unit): Int {
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable(block), ticks)
        plugin.trackTask(scriptName, task.taskId); return task.taskId
    }

    fun every(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int {
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(block), initialDelay, ticks)
        plugin.trackTask(scriptName, task.taskId); return task.taskId
    }

    fun delayAsync(ticks: Long, block: () -> Unit): Int {
        val task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable(block), ticks)
        plugin.trackTask(scriptName, task.taskId); return task.taskId
    }

    fun everyAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int {
        val task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable(block), initialDelay, ticks)
        plugin.trackTask(scriptName, task.taskId); return task.taskId
    }

    inline fun <reified T : Event> onEvent(
        priority: EventPriority = EventPriority.NORMAL,
        crossinline action: T.() -> Unit
    ) {
        val listener = object : Listener {}
        val executor = EventExecutor { _, event -> if (event is T) event.action() }
        Bukkit.getPluginManager().registerEvent(T::class.java, listener, priority, executor, plugin)
        plugin.trackListener(scriptName, listener)
    }

    fun registerCommand(name: String, aliases: List<String> = emptyList(), action: (Player, Array<String>) -> Unit) {
        val command = DynamicCommand(name, aliases, scriptName, action)
        Bukkit.getCommandMap().register(plugin.name.lowercase(), command)
        plugin.trackCommand(scriptName, command)
    }

    fun parseMM(text: String): Component = mm.deserialize(text)

    fun broadcastMM(text: String) = Bukkit.getServer().broadcast(parseMM(text))

    fun broadcast(message: String) =
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(message.replace('&', '§')) }

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
            plugin.logger.warning("[$scriptName] PlaceholderAPI не найден — плейсхолдер '$key' пропущен"); return
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

    fun gui(title: String, rows: Int = 3, builder: KSLGuiInstance.() -> Unit): KSLGuiInstance {
        val instance = KSLGuiInstance(scriptName, rows, title)
        plugin.trackGui(scriptName)
        instance.builder()
        return instance
    }

    fun <T> persist(key: String, persistent: Boolean = false, default: () -> T): KSLPersistProperty<T> =
        plugin.persistStore.property(scriptName, key, persistent, default)

    fun executeConsole(command: String) {
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
            ?: plugin.logger.warning("[$scriptName] discordSend: Discord не настроен в config.yml")
    }

    fun discordEmbed(
        channel: String,
        title: String,
        description: String = "",
        color: Int = 0x5865F2,
        footer: String = ""
    ) {
        plugin.discord?.sendEmbed(channel, title, description, color, footer)
            ?: plugin.logger.warning("[$scriptName] discordEmbed: Discord не настроен в config.yml")
    }
}
