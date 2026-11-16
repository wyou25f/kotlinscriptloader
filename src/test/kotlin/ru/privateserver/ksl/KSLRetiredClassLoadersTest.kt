package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files

class KSLRetiredClassLoadersTest {
    @Test
    fun `retired loader remains usable until orderly close`() {
        val directory = Files.createTempDirectory("ksl-retired-loader").toFile()
        try {
            java.io.File(directory, "probe.txt").writeText("available")
            val loader = URLClassLoader(arrayOf(directory.toURI().toURL()), null)
            val retired = KSLRetiredClassLoaders()

            assertNotNull(loader.getResource("probe.txt"))
            retired.retain(loader)
            assertNotNull(loader.getResource("probe.txt"), "retiring a loader must not break existing scripts")

            retired.close()
            assertNull(loader.getResource("probe.txt"), "retired loaders should close during plugin shutdown")
        } finally {
            directory.deleteRecursively()
        }
    }
}
