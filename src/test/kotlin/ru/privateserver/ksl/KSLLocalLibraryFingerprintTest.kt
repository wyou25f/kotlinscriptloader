package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class KSLLocalLibraryFingerprintTest {

    @Test
    fun `detects content changes when size and timestamp stay the same`() {
        val root = Files.createTempDirectory("ksl-local-libraries").toFile()
        try {
            val source = File(root, "helpers/Helper.kt").apply {
                parentFile.mkdirs()
                writeText("val marker = 1\\n")
            }
            val fixedTime = FileTime.fromMillis(1_700_000_000_000L)
            Files.setLastModifiedTime(source.toPath(), fixedTime)

            val before = localLibrarySourceFingerprint(listOf(source), root)
            val previousSize = source.length()

            source.writeText("val marker = 2\\n")
            Files.setLastModifiedTime(source.toPath(), fixedTime)

            assertEquals(previousSize, source.length())
            assertEquals(fixedTime, Files.getLastModifiedTime(source.toPath()))
            assertNotEquals(before, localLibrarySourceFingerprint(listOf(source), root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `fingerprint does not depend on source order`() {
        val root = Files.createTempDirectory("ksl-local-libraries-order").toFile()
        try {
            val first = File(root, "a/First.kt").apply {
                parentFile.mkdirs()
                writeText("package a\\n")
            }
            val second = File(root, "b/Second.kt").apply {
                parentFile.mkdirs()
                writeText("package b\\n")
            }

            assertEquals(
                localLibrarySourceFingerprint(listOf(first, second), root),
                localLibrarySourceFingerprint(listOf(second, first), root)
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
