package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class KSLScriptIndexTest {

    @Test
    fun cachedSnapshotReusesFileIdsAndCompletionTargetsUntilInvalidated() {
        val scans = AtomicInteger()
        val files = mutableListOf(
            File("scripts/z.kts"),
            File("scripts/economy/shop.kts"),
            File("scripts/a.kts")
        )
        val index = KSLScriptIndex(
            scanFiles = { scans.incrementAndGet(); files.toList() },
            scriptId = { it.path.replace('\\', '/').substringAfter("scripts/").removeSuffix(".kts") }
        )

        val first = index.snapshot()
        assertSame(first, index.snapshot())
        assertEquals(1, scans.get())
        assertEquals(listOf("a", "economy/shop", "z"), first.ids)
        assertEquals(listOf("a", "changed", "economy/", "economy/shop", "z"), first.reloadTargets)

        files += File("scripts/economy/shops.kts")
        assertSame(first, index.snapshot(), "mutations remain invisible until the index is invalidated")
        assertEquals(1, scans.get())

        index.invalidate()
        val second = index.snapshot()
        assertNotSame(first, second)
        assertEquals(2, scans.get())
        assertEquals(listOf("a", "economy/shop", "economy/shops", "z"), second.ids)
    }

    @Test
    fun refreshFilesRescansAndPreservesDeterministicOrderWithoutCachingFileObjects() {
        val scans = AtomicInteger()
        val index = KSLScriptIndex(
            scanFiles = {
                scans.incrementAndGet()
                listOf(File("scripts/c.kts"), File("scripts/a.kts"), File("scripts/b.kts"))
            },
            scriptId = { it.nameWithoutExtension }
        )

        val first = index.snapshot()
        val refreshedFiles = index.refreshFiles()
        val refreshed = index.snapshot()
        assertNotSame(first, refreshed)
        assertEquals(2, scans.get())
        assertEquals(listOf("a", "b", "c"), refreshed.ids)
        assertEquals(refreshed.ids, refreshedFiles.map { it.nameWithoutExtension })
        assertTrue(refreshed.reloadTargets.containsAll(refreshed.ids))
    }
}
