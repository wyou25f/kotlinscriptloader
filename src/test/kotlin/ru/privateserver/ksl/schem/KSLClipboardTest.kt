package ru.privateserver.ksl.schem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLClipboardTest {

    @Test
    fun exposesStableClipboardMetadataWithoutLeakingWorldEditTypes() {
        val native = Any()
        val clipboard = KSLClipboard(
            nativeClipboard = native,
            width = 3,
            height = 4,
            length = 5,
            blockCount = 60L,
            minimumX = -12,
            minimumY = 64,
            minimumZ = 8
        )

        assertSame(native, clipboard.nativeClipboard)
        assertEquals(3, clipboard.width)
        assertEquals(4, clipboard.height)
        assertEquals(5, clipboard.length)
        assertEquals(60L, clipboard.blockCount)
        assertEquals(60L, clipboard.volume)
        assertEquals(-12, clipboard.minimumX)
        assertEquals(64, clipboard.minimumY)
        assertEquals(8, clipboard.minimumZ)
        assertTrue(clipboard.toString().contains("3x4x5"))
    }
}
