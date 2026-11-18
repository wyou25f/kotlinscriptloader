package ru.privateserver.ksl.nbt

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files

/**
 * Runs NBT binary-file checks inside a real Paper server.
 *
 * MockBukkit intentionally remains the fast unit-test environment; this runner exists
 * for Item-NBT-API operations that need the live Minecraft/NMS runtime.
 */
class KSLNBTFilesPaperIntegrationPlugin : JavaPlugin() {
    override fun onEnable() {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            error("Could not create integration-test data directory: $dataFolder")
        }

        server.scheduler.runTask(this, Runnable { runIntegrationChecks() })
    }

    private fun runIntegrationChecks() {
        try {
            val directory = Files.createTempDirectory(dataFolder.toPath(), "nbt-binary-").toFile()
            try {
                val file = File(directory, "roundtrip.nbt")
                val original = KSLNBTEngine.parse(
                    """{owner:"PaperIntegration",stats:{level:4},values:[I;1,2,3]}"""
                )

                KSLNBTFiles.save(file, original)
                check(file.isFile && file.length() > 0L) {
                    "KSLNBTFiles.save did not create a non-empty binary NBT file"
                }

                val loaded = requireNotNull(KSLNBTFiles.load(file)) {
                    "KSLNBTFiles.load returned null for an existing file"
                }
                check(loaded.read { string("owner") } == "PaperIntegration") {
                    "String value did not survive binary NBT round-trip"
                }
                check(loaded.read { intAt("stats.level") } == 4) {
                    "Nested integer did not survive binary NBT round-trip"
                }

                loaded.edit { setIntAt("stats.level", 5) }
                check(original.read { intAt("stats.level") } == 4) {
                    "Editing the loaded document mutated the original detached document"
                }
                check(loaded.read { intAt("stats.level") } == 5) {
                    "Detached document edit was not applied"
                }

                KSLNBTFiles.save(file, loaded)
                val persisted = requireNotNull(KSLNBTFiles.load(file))
                check(persisted.read { intAt("stats.level") } == 5) {
                    "Updated NBT document was not persisted"
                }

                val beforeFailedEdit = persisted.snbt()
                var failedEditRejected = false
                try {
                    persisted.edit {
                        setIntAt("stats.level", 99)
                        throw IllegalStateException("intentional integration-test rollback")
                    }
                } catch (_: IllegalStateException) {
                    failedEditRejected = true
                }
                check(failedEditRejected) { "A failing edit unexpectedly completed" }
                check(persisted.snbt() == beforeFailedEdit) {
                    "Failed document edit changed the published SNBT"
                }

                val beforeRejectedSave = file.readBytes()
                val invalidDocument = KSLNBTDocument.fromCanonical("{unfinished", KSLNBTLimits.DEFAULT)
                var rejected = false
                try {
                    KSLNBTFiles.save(file, invalidDocument)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                check(rejected) { "Invalid SNBT was accepted for binary file save" }
                check(file.readBytes().contentEquals(beforeRejectedSave)) {
                    "Rejected save changed the existing binary NBT file"
                }
            } finally {
                directory.deleteRecursively()
            }

            complete(success = true)
        } catch (failure: Throwable) {
            logger.severe("[KSL-PAPER-IT] FAILED: " + failure.stackTraceToString())
            complete(success = false)
        }
    }

    private fun complete(success: Boolean) {
        val marker = File(dataFolder, "result.txt")
        marker.writeText(if (success) "PASS\n" else "FAIL\n")
        if (success) {
            logger.info("[KSL-PAPER-IT] PASSED")
        } else {
            logger.severe("[KSL-PAPER-IT] FAILED")
        }
        Bukkit.shutdown()
    }
}
