package ru.privateserver.ksl.nbt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class KSLNBTFilesTest {

    @Disabled("Requires real Paper/NMS mappings; MockBukkit does not provide the Minecraft server classes")
    @Test
    fun binaryFileRoundTripsDetachedDocument() {
        val directory = Files.createTempDirectory("ksl-nbt-files").toFile()
        val file = File(directory, "example.nbt")
        try {
            val source = KSLNBTEngine.parse("""{owner:"Alice",stats:{level:4}}""")
            KSLNBTFiles.save(file, source)

            val loaded = KSLNBTFiles.load(file)!!
            assertEquals("Alice", loaded.read { string("owner") })
            assertEquals(4, loaded.read { intAt("stats.level") })

            loaded.edit { setIntAt("stats.level", 5) }
            assertEquals(4, source.read { intAt("stats.level") })
            assertEquals(5, loaded.read { intAt("stats.level") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingFileReturnsNullAndOversizedFilesAreRejected() {
        val directory = Files.createTempDirectory("ksl-nbt-limits").toFile()
        try {
            assertNull(KSLNBTFiles.load(File(directory, "missing.nbt")))
            assertThrows(IllegalArgumentException::class.java) {
                KSLNBTFiles.load(File(directory, "missing-invalid-limit.nbt"), maxFileBytes = 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                KSLNBTFiles.load(directory)
            }
            val oversized = File(directory, "oversized.nbt")
            oversized.writeBytes(ByteArray(32))
            assertThrows(IllegalArgumentException::class.java) {
                KSLNBTFiles.load(oversized, maxFileBytes = 16)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Disabled("Requires real Paper/NMS mappings; MockBukkit does not provide the Minecraft server classes")
    @Test
    fun failedEditDoesNotChangeTheStoredBinaryFile() {
        val directory = Files.createTempDirectory("ksl-nbt-atomic").toFile()
        val file = File(directory, "stable.nbt")
        try {
            val original = KSLNBTEngine.parse("""{value:1}""")
            KSLNBTFiles.save(file, original)
            val before = KSLNBTFiles.load(file)!!.snbt()

            val document = KSLNBTFiles.load(file)!!
            assertThrows(IllegalStateException::class.java) {
                document.edit {
                    setInt("value", 2)
                    throw IllegalStateException("rollback")
                }
            }
            assertEquals(before, KSLNBTFiles.load(file)!!.snbt())
            assertTrue(file.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun savePreflightHonorsCustomDocumentLimitsLargerThanTheDefault() {
        val payload = "x".repeat(KSLNBTLimits.DEFAULT.maxSnbtCharacters + 32)
        val snbt = """{payload:"$payload"}"""
        val limits = KSLNBTLimits(maxSnbtCharacters = snbt.length + 8)
        val document = KSLNBTDocument.fromCanonical(snbt, limits)

        assertThrows(IllegalArgumentException::class.java) {
            KSLNBTSnbtPolicy.validate(snbt)
        }
        assertEquals(snbt, KSLNBTFiles.contentForSave(document))
    }

}
