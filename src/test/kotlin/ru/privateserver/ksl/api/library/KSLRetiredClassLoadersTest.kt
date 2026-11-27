package ru.privateserver.ksl.api.library
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files

class KSLRetiredClassLoadersTest {
    @Test
    fun `retired loader closes after its final script releases the generation`() {
        val directory = Files.createTempDirectory("ksl-retired-loader-lease").toFile()
        val retired = KSLRetiredClassLoaders()
        try {
            java.io.File(directory, "probe.txt").writeText("available")
            val loader = URLClassLoader(arrayOf(directory.toURI().toURL()), null)

            retired.acquire("script-a", loader)
            retired.acquire("script-b", loader)
            retired.retire(loader)

            assertNotNull(loader.getResource("probe.txt"))
            assertEquals(1, retired.retainedGenerationCount())
            assertEquals(2, retired.activeOwnerCount())

            assertTrue(retired.release("script-a"))
            assertNotNull(loader.getResource("probe.txt"), "one remaining script still needs lazy class resolution")
            assertEquals(1, retired.retainedGenerationCount())

            assertTrue(retired.release("script-b"))
            assertNull(loader.getResource("probe.txt"), "the loader should close as soon as its final owner unloads")
            assertEquals(0, retired.retainedGenerationCount())
            assertEquals(0, retired.activeOwnerCount())
        } finally {
            retired.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `previous generation can reacquire a retired loader during rollback`() {
        val directory = Files.createTempDirectory("ksl-library-rollback").toFile()
        val retired = KSLRetiredClassLoaders()
        try {
            java.io.File(directory, "generation.txt").writeText("available")
            val loader = URLClassLoader(arrayOf(directory.toURI().toURL()), null)

            retired.acquire("active-generation", loader)
            retired.retire(loader)
            retired.acquire("rollback-generation", loader)

            assertEquals(1, retired.retainedGenerationCount())
            assertEquals(2, retired.activeOwnerCount())
            assertNotNull(loader.getResource("generation.txt"))

            retired.release("active-generation")
            assertNotNull(loader.getResource("generation.txt"))
            retired.release("rollback-generation")
            assertNull(loader.getResource("generation.txt"))
            assertEquals(0, retired.retainedGenerationCount())
        } finally {
            retired.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reloading one script transfers its lease to the new library generation`() {
        val firstDirectory = Files.createTempDirectory("ksl-library-old").toFile()
        val secondDirectory = Files.createTempDirectory("ksl-library-new").toFile()
        val retired = KSLRetiredClassLoaders()
        try {
            java.io.File(firstDirectory, "generation.txt").writeText("old")
            java.io.File(secondDirectory, "generation.txt").writeText("new")
            val first = URLClassLoader(arrayOf(firstDirectory.toURI().toURL()), null)
            val second = URLClassLoader(arrayOf(secondDirectory.toURI().toURL()), null)

            retired.acquire("script", first)
            retired.retire(first)
            assertNotNull(first.getResource("generation.txt"))

            retired.acquire("script", second)
            assertNull(first.getResource("generation.txt"), "the previous generation should close after its final owner switches")
            assertEquals(0, retired.retainedGenerationCount())
            assertNotNull(second.getResource("generation.txt"))

            retired.retire(second)
            assertNotNull(second.getResource("generation.txt"))
            assertTrue(retired.release("script"))
            assertNull(second.getResource("generation.txt"))
            assertFalse(retired.release("script"))
        } finally {
            retired.close()
            firstDirectory.deleteRecursively()
            secondDirectory.deleteRecursively()
        }
    }

    @Test
    fun `fifty scripts release five retired library generations without accumulation`() {
        val directories = (0 until 5).map { Files.createTempDirectory("ksl-library-generation-$it").toFile() }
        val loaders = directories.mapIndexed { index, directory ->
            java.io.File(directory, "generation.txt").writeText("generation-$index")
            URLClassLoader(arrayOf(directory.toURI().toURL()), null)
        }
        val retired = KSLRetiredClassLoaders()

        try {
            repeat(50) { scriptIndex ->
                val generation = scriptIndex / 10
                retired.acquire("script-$scriptIndex", loaders[generation])
            }
            loaders.forEach(retired::retire)

            assertEquals(5, retired.retainedGenerationCount())
            assertEquals(50, retired.activeOwnerCount())

            repeat(10) { scriptIndex -> retired.release("script-$scriptIndex") }
            assertNull(loaders[0].getResource("generation.txt"))
            assertEquals(4, retired.retainedGenerationCount())
            for (generation in 1 until 5) {
                assertNotNull(loaders[generation].getResource("generation.txt"))
            }

            repeat(10) { scriptIndex -> retired.release("script-${scriptIndex + 10}") }
            repeat(10) { scriptIndex -> retired.release("script-${scriptIndex + 20}") }
            repeat(10) { scriptIndex -> retired.release("script-${scriptIndex + 30}") }
            repeat(10) { scriptIndex -> retired.release("script-${scriptIndex + 40}") }

            assertEquals(0, retired.retainedGenerationCount())
            assertEquals(0, retired.activeOwnerCount())
            loaders.forEach { loader -> assertNull(loader.getResource("generation.txt")) }
        } finally {
            retired.close()
            directories.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `retired loader remains usable until orderly close`() {
        val directory = Files.createTempDirectory("ksl-retired-loader").toFile()
        try {
            java.io.File(directory, "probe.txt").writeText("available")
            val loader = URLClassLoader(arrayOf(directory.toURI().toURL()), null)
            val retired = KSLRetiredClassLoaders()

            retired.acquire("live-script", loader)
            retired.retain(loader)
            assertNotNull(loader.getResource("probe.txt"), "retiring a loader must not break existing scripts")

            retired.close()
            assertNull(loader.getResource("probe.txt"), "retired loaders should close during plugin shutdown")
        } finally {
            directory.deleteRecursively()
        }
    }
}
