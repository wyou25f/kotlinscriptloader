package ru.privateserver.ksl.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KSLAtomicFileWriterTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `replaces existing UTF-8 content atomically`() {
        val destination = File(directory, "nested/config.json")
        destination.parentFile.mkdirs()
        destination.writeText("old")

        KSLAtomicFileWriter.writeText(destination, """{"message":"Привет 🌍"}""")

        assertTrue(destination.readText().contains("Привет 🌍"))
        assertTrue(destination.readText().startsWith("{"))
        assertTrue(destination.parentFile.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `concurrent writers never leave partial content or temporary files`() {
        val destination = File(directory, "shared/state.toml")
        val writers = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(writers)
        val payloads = (0 until writers).map { index ->
            "writer = \"$index\"\n" + ("x".repeat(32_000))
        }

        try {
            val completions = payloads.map { payload ->
                pool.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    KSLAtomicFileWriter.writeText(destination, payload)
                }
            }
            start.countDown()
            completions.forEach { it.get(10, TimeUnit.SECONDS) }

            assertTrue(payloads.contains(destination.readText()))
            assertFalse(destination.parentFile.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            pool.shutdownNow()
        }
    }
}
