package ru.privateserver.ksl.std.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextColorExtensionsTest {
    @Test fun `legacy and hex codes are converted without changing ordinary ampersands`() {
        assertEquals("§aGreen & gold", "&aGreen & gold".colorizeLegacy())
        assertEquals("§x§1§2§A§B§C§DHex", "&#12ABCDHex".colorizeLegacy())
        assertEquals("§x§1§2§A§B§C§DHex", "&x&1&2&A&B&C&DHex".colorizeLegacy())
    }
    @Test fun `legacy codes map to minimessage markup`() {
        assertEquals("<green>Green<bold> bold<reset>", "&aGreen&l bold&r".toMiniMessageMarkup())
        assertEquals("<#55CCFF>Blue", "&#55CCFFBlue".toMiniMessageMarkup())
    }
    @Test fun `strip removes ampersand and section sign legacy hex codes`() {
        assertEquals("Hello world", "&aHello §x§1§2§A§B§3§4 world".stripLegacyColors())
        assertEquals("Plain & text", "Plain & text".stripLegacyColors())
    }
}