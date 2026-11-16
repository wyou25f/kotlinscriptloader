package ru.privateserver.ksl.api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class KSLRuntimeConvenienceTest {
    @Test
    fun runtimeHelpersExposeReadOnlyJvmTelemetry() {
        val api = Proxy.newProxyInstance(
            KSLAPI::class.java.classLoader,
            arrayOf(KSLAPI::class.java)
        ) { _, _, _ -> error("Unexpected KSLAPI call") } as KSLAPI

        val snapshot = api.runtimeStats()
        assertTrue(snapshot.processors >= 1)
        assertTrue(snapshot.heapUsedBytes >= 0L)
        assertTrue(snapshot.heapCommittedBytes >= 0L)
        assertTrue(api.availableProcessors() >= 1)
        assertTrue(api.heapUsedBytes() >= 0L)
    }
}
