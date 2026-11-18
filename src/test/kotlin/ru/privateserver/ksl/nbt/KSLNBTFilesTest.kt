package ru.privateserver.ksl.nbt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class KSLNBTFilesTest {

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
