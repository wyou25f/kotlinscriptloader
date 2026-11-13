package ru.privateserver.ksl.std.inventory

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.api.KSL_SCRIPT_DEFAULT_IMPORTS

class InventoryStandardLibraryMetadataTest {
    @Test
    fun `inventory extensions are part of default script imports`() {
        assertTrue("ru.privateserver.ksl.std.inventory.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.std.item.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.std.component.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
    }
}
