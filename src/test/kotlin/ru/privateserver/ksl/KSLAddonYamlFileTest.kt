package ru.privateserver.ksl

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger

class KSLAddonYamlFileTest {
    @Test
    fun malformedYamlIsBackedUpBeforeDefaultsAreReturned() {
        val directory = Files.createTempDirectory("ksl-addon-yaml").toFile()
        try {
            val file = File(directory, "config.yml")
            val brokenYaml = "broken: [one, two\n"
            file.writeText(brokenYaml)

            val logger = Logger.getLogger("KSLAddonYamlFileTest").apply { useParentHandlers = false }
            val loaded = KSLAddonYamlFile.load(file, logger)

            assertTrue(loaded.getKeys(false).isEmpty())
            val backup = directory.listFiles()?.singleOrNull { it.name.startsWith("config.yml.broken-") }
            assertNotNull(backup)
            assertEquals(brokenYaml, backup!!.readText())
            assertEquals(brokenYaml, file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun saveAtomicallyReplacesTheExistingConfiguration() {
        val directory = Files.createTempDirectory("ksl-addon-yaml-save").toFile()
        try {
            val file = File(directory, "config.yml")
            file.writeText("old: true\n")
            val configuration = YamlConfiguration().apply {
                set("enabled", true)
                set("message", "ready")
            }

            KSLAddonYamlFile.save(configuration, file)

            val reloaded = YamlConfiguration()
            reloaded.load(file)
            assertEquals(true, reloaded.getBoolean("enabled"))
            assertEquals("ready", reloaded.getString("message"))
            assertTrue(directory.listFiles()?.none { it.name.endsWith(".tmp") } == true)
        } finally {
            directory.deleteRecursively()
        }
    }
}
