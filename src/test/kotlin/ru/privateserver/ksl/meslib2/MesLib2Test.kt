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
    @Test fun shortAliasesAndDefaultNamespaceResolveTheSameEntry() {
        val api = facade()
        assertEquals("provider", api.namespace)
        api.publish("service", "ready")
        assertTrue(api.containsLocal("service"))
        assertEquals("ready", api.findLocal<String>("service"))
        assertEquals("ready", api.resolveLocal<String>("service"))
        assertEquals("provider", api.infoLocal("service")?.namespace)
    }
    @Test fun aliasesPublishAndResolveExplicitNamespaces() {
        val api = facade()
        api.provide("economy", "service", "ready", "2.0")
        assertTrue(api.contains("economy", "service", "2.0"))
        assertEquals("ready", api.resolve<String>("economy", "service", "2.0"))
    }
    @Test fun aliasesOfferConcisePutLookupAndDescribeNames() {
        val api = facade()
        assertEquals("provider", api.scope)
        api.put("economy", "service", "ready", "3.0")
        assertTrue(api.has("economy", "service", "3.0"))
        assertEquals("ready", api.lookup<String>("economy", "service", "3.0"))
        assertEquals("ready", api.mustGet<String>("economy", "service", "3.0"))
        assertEquals("3.0", api.describe("economy", "service")?.version)
        api.putLocal("local", "value")
        assertEquals("value", api.lookupLocal<String>("local"))
        assertEquals("value", api.mustGetLocal<String>("local"))
        assertTrue(api.hasLocal("local"))
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
