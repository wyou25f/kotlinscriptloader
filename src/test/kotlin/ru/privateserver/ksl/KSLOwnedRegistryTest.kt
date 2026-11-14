package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KSLOwnedRegistryTest {
    @Test
    fun concurrentReplacementsKeepOwnerPairedWithValue() {
        val registry = KSLOwnedRegistry<String>()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val writers = (0 until 8).map { index ->
                executor.submit {
                    val owner = "addon-$index"
                    start.await()
                    repeat(5_000) {
                        registry.put("shared", owner, owner)
                    }
                }
            }
            start.countDown()
            writers.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(registry.get("shared"), registry.ownerOf("shared"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun oldOwnerCannotRemoveAReplacementOwnedByAnotherAddon() {
        val registry = KSLOwnedRegistry<String>()
        registry.put("placeholder", "new value", "beta")

        assertEquals(
            KSLRegistryRemovalResult.OWNED_BY_OTHER,
            registry.removeOwned("placeholder", "alpha")
        )
        assertEquals("new value", registry.get("placeholder"))
        assertEquals(1, registry.releaseOwner("beta"))
        assertNull(registry.get("placeholder"))
        assertEquals(KSLRegistryRemovalResult.NOT_FOUND, registry.removeOwned("placeholder", "beta"))
    }
}
