package ru.privateserver.ksl.meslib2
import org.junit.jupiter.api.Assertions.*
import ru.privateserver.ksl.KSLExportStore
import org.junit.jupiter.api.Test

class MesLib2Test {
    private fun facade(): MesLib2 {
        val values = linkedMapOf<String, Any>()
        val owners = linkedMapOf<String, String>()
        return MesLib2("provider",
            { owner, key, value -> values[key] = value; owners[key] = owner },
            { values[it] },
            { owners[it] })
    }
    @Test fun resolvesTypedVersionedExports() {
        val api = facade()
        api.export("economy", "service", "ready", "2.0")
        assertEquals("ready", api.get<String>("economy", "service"))
        assertEquals("ready", api.require<String>("economy", "service", "2.0"))
        assertNull(api.get<Int>("economy", "service"))
        assertNull(api.get<String>("economy", "service", "1.0"))
        assertEquals("2.0", api.info("economy", "service")?.version)
        assertEquals("provider", api.info("economy", "service")?.owner)
    }
    @Test fun exportsAreRemovedWithTheirOwningScript() {
        val store = KSLExportStore()
        val api = MesLib2("provider", store::export, store::get, store::ownerOf)
        api.export("economy", "service", "ready")
        assertEquals("ready", api.get<String>("economy", "service"))
        store.clearOwner("provider")
        assertNull(api.get<String>("economy", "service"))
    }
    @Test fun requireFailsForMissingExports() {
        val api = facade()
        assertThrows(IllegalStateException::class.java) { api.require<String>("missing", "entry") }
    }
    @Test fun validatesNamespaceAndName() {
        val api = facade()
        assertThrows(IllegalArgumentException::class.java) { api.export("Bad Namespace", "service", "value") }
        assertThrows(IllegalArgumentException::class.java) { api.export("economy", "Bad Name", "value") }
    }
}
