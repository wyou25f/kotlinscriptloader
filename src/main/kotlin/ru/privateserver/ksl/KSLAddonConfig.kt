package ru.privateserver.ksl

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

/**
 * A YAML configuration stored in an addon's own data folder.
 *
 * Bukkit configuration access should normally happen on the server thread.
 * The convenience methods are synchronized, while [config] exposes the
 * underlying Bukkit object for advanced use.
 */
class KSLAddonConfig private constructor(
    private val owner: JavaPlugin,
    val file: File,
    private val resourcePath: String,
    private val copyDefaults: Boolean
) {
    @Volatile
    private var yaml: YamlConfiguration = loadConfiguration()

    /** Underlying Bukkit configuration for advanced Bukkit API operations. */
    val config: FileConfiguration
        get() = yaml

    @Synchronized
    fun getString(path: String, defaultValue: String? = null): String? = yaml.getString(path, defaultValue)

    @Synchronized
    fun getInt(path: String, defaultValue: Int = 0): Int = yaml.getInt(path, defaultValue)

    @Synchronized
    fun getLong(path: String, defaultValue: Long = 0L): Long = yaml.getLong(path, defaultValue)

    @Synchronized
    fun getDouble(path: String, defaultValue: Double = 0.0): Double = yaml.getDouble(path, defaultValue)

    @Synchronized
    fun getBoolean(path: String, defaultValue: Boolean = false): Boolean = yaml.getBoolean(path, defaultValue)

    @Synchronized
    fun getStringList(path: String): List<String> = yaml.getStringList(path)

    @Synchronized
    fun contains(path: String): Boolean = yaml.contains(path)

    @Synchronized
    fun getKeys(deep: Boolean = false): Set<String> = yaml.getKeys(deep)

    @Synchronized
    fun getSection(path: String): ConfigurationSection? = yaml.getConfigurationSection(path)

    @Synchronized
    fun set(path: String, value: Any?) {
        yaml.set(path, value)
    }

    /** Writes the current configuration to this addon's data folder. */
    @Synchronized
    fun save() {
        file.parentFile?.mkdirs()
        yaml.save(file)
    }

    /** Reloads the file from disk, discarding unsaved in-memory changes. */
    @Synchronized
    fun reload(): KSLAddonConfig {
        yaml = loadConfiguration()
        return this
    }

    private fun loadConfiguration(): YamlConfiguration {
        val loaded = YamlConfiguration.loadConfiguration(file)
        owner.getResource(resourcePath)?.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                loaded.setDefaults(YamlConfiguration.loadConfiguration(reader))
            }
        }
        if (copyDefaults) loaded.options().copyDefaults(true)
        return loaded
    }

    companion object {
        /**
         * Loads fileName from the plugin jar as defaults and stores the live file
         * under the plugin's data folder. Absolute paths and parent traversal are rejected.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            owner: JavaPlugin,
            fileName: String = "config.yml",
            copyDefaults: Boolean = true
        ): KSLAddonConfig {
            require(fileName.isNotBlank()) { "Configuration file name cannot be blank" }
            val normalizedName = fileName.replace('\\', '/')
            val relativePath = Paths.get(normalizedName).normalize()
            require(!relativePath.isAbsolute && relativePath.toString().isNotBlank() &&
                relativePath.toString() != "." && relativePath.none { it.toString() == ".." }) {
                "Configuration path must stay inside the addon's data folder: $fileName"
            }

            val dataFolder = owner.dataFolder.canonicalFile
            val target = File(dataFolder, normalizedName).canonicalFile
            require(target.toPath().startsWith(dataFolder.toPath()) && target != dataFolder) {
                "Configuration path must stay inside the addon's data folder: $fileName"
            }

            target.parentFile?.mkdirs()
            if (!target.exists()) {
                val resource = owner.getResource(normalizedName)
                if (resource != null) {
                    resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                } else {
                    target.createNewFile()
                }
            }

            return KSLAddonConfig(owner, target, normalizedName, copyDefaults)
        }
    }
}
