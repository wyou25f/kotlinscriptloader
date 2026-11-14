package ru.privateserver.ksl.meslib2

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.KSLExportStore

class MesLib2Test {
    private fun facade(owner: String = "provider", store: KSLExportStore = KSLExportStore()): MesLib2 =
        MesLib2(
            owner,
            { script, key, value -> store.export(script, key, value) },
            store::get,
            store::ownerOf,
            store::removeOwned,
            store::exportIfAbsent,
            { prefix -> store.exportedKeys().filterTo(linkedSetOf()) { it.startsWith(prefix) } }
        )

    @Test
    fun resolvesTypedVersionedExports() {
        val api = facade()
        api.export("economy", "service", "ready", "2.0")
        assertEquals("ready", api.get<String>("economy", "service"))
        assertEquals("ready", api.require<String>("economy", "service", "2.0"))
        assertNull(api.get<Int>("economy", "service"))
        assertNull(api.get<String>("economy", "service", "1.0"))
        assertEquals("2.0", api.info("economy", "service")?.version)
        assertEquals("provider", api.info("economy", "service")?.owner)
    }

    @Test
    fun compatibilityAliasesResolveTheSameEntry() {
        val api = facade()
        assertEquals("provider", api.namespace)
        api.publish("service", "ready")
        assertTrue(api.containsLocal("service"))
        assertEquals("ready", api.findLocal<String>("service"))
        assertEquals("ready", api.resolveLocal<String>("service"))
        assertEquals("provider", api.infoLocal("service")?.namespace)
        api.provide("economy", "service", "ready", "2.0")
        assertTrue(api.contains("economy", "service", "2.0"))
        assertEquals("ready", api.resolve<String>("economy", "service", "2.0"))
        api.put("economy", "other", "value", "3.0")
        assertEquals("value", api.lookup<String>("economy", "other", "3.0"))
        assertEquals("value", api.mustGet<String>("economy", "other", "3.0"))
        assertEquals("3.0", api.describe("economy", "other")?.version)
        api.putLocal("local", "value")
        assertEquals("value", api.lookupLocal<String>("local"))
        assertEquals("value", api.mustGetLocal<String>("local"))
        assertTrue(api.hasLocal("local"))
    }

    @Test
    fun exportIfAbsentIsAtomicAndDoesNotReplaceExistingValue() {
        val api = facade()
        assertTrue(api.exportIfAbsent("shared", "service", "first", "1.0"))
        assertFalse(api.exportIfAbsent("shared", "service", "second", "2.0"))
        assertEquals("first", api.get<String>("shared", "service"))
        assertEquals("1.0", api.info("shared", "service")?.version)
    }

    @Test
    fun ownerCanRemoveOnlyItsOwnExports() {
        val store = KSLExportStore()
        val provider = facade("provider", store)
        val other = facade("other", store)
        provider.export("shared", "owned", "value")
        assertFalse(other.remove("shared", "owned"))
        assertTrue(provider.remove("shared", "owned"))
        assertFalse(provider.contains("shared", "owned"))
    }

    @Test
    fun listAndClearReturnSortedNamespaceSnapshots() {
        val store = KSLExportStore()
        val api = facade("provider", store)
        api.export("economy", "wallet", "wallet")
        api.export("economy", "account", "account")
        api.export("chat", "formatter", "formatter")

        val entries = api.list("economy")
        assertEquals(listOf("account", "wallet"), entries.map { it.name })
        assertEquals(listOf("economy/account", "economy/wallet"), entries.map { it.key })
        assertEquals(2, api.clear("economy"))
        assertTrue(api.list("economy").isEmpty())
        assertEquals("formatter", api.get<String>("chat", "formatter"))
    }

    @Test
    fun exportsAreRemovedWithTheirOwningScript() {
        val store = KSLExportStore()
        val api = facade("provider", store)
        api.export("economy", "service", "ready")
        assertEquals("ready", api.get<String>("economy", "service"))
        store.clearOwner("provider")
        assertNull(api.get<String>("economy", "service"))
    }

    @Test
    fun requireFailsForMissingExports() {
        val api = facade()
        assertThrows(IllegalStateException::class.java) { api.require<String>("missing", "entry") }
    }

    @Test
    fun validatesNamespaceAndName() {
        val api = facade()
        assertThrows(IllegalArgumentException::class.java) { api.export("Bad Namespace", "service", "value") }
        assertThrows(IllegalArgumentException::class.java) { api.export("economy", "Bad Name", "value") }
        assertThrows(IllegalArgumentException::class.java) { api.exportIfAbsent("Bad Namespace", "service", "value") }
    }
}
