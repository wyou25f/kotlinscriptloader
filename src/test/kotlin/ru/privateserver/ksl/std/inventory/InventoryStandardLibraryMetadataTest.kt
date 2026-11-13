package ru.privateserver.ksl.std.inventory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.api.KSL_SCRIPT_DEFAULT_IMPORTS

class InventoryStandardLibraryMetadataTest {
    @Test
    fun `inventory extensions are part of default script imports`() {
        assertEquals(true, "ru.privateserver.ksl.std.inventory.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
    }
}
