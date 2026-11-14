package ru.privateserver.ksl

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger

/**
 * Safe YAML file I/O shared by the public addon-config wrapper.
 * A broken live file is copied before defaults are used, and saves replace
 * the destination atomically so interrupted writes do not truncate user data.
 */
internal object KSLAddonYamlFile {
    fun load(file: File, logger: Logger): YamlConfiguration {
        val loaded = YamlConfiguration()
        if (file.exists()) {
            try {
                loaded.load(file)
            } catch (ex: InvalidConfigurationException) {
                val suffix = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
                val backup = File(file.parentFile, "${file.name}.broken-$suffix")
                try {
                    file.copyTo(backup)
                } catch (backupFailure: IOException) {
                    throw IllegalStateException(
                        "Invalid YAML in '${file.name}' and its backup could not be created; original file was left untouched",
                        backupFailure
                    )
                }
                logger.severe(
                    "Addon config '${file.name}' contains invalid YAML (${ex.message}); " +
                        "a backup was saved as '${backup.name}' and defaults will be used."
                )
            } catch (ex: IOException) {
                throw IllegalStateException("Could not read addon config '${file.name}': ${ex.message}", ex)
            }
        }
        return loaded
    }

    fun save(configuration: YamlConfiguration, file: File) {
        val target = file.absoluteFile
        val parent = target.parentFile
        Files.createDirectories(parent.toPath())
        val prefix = target.name.padEnd(3, '_')
        val temporary = Files.createTempFile(parent.toPath(), prefix, ".tmp")
        try {
            configuration.save(temporary.toFile())
            try {
                Files.move(
                    temporary,
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (ex: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
