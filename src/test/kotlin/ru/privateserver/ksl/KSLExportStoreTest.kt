package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLExportStoreTest {
    @Test
    fun `replacing an export transfers ownership`() {
        val store = KSLExportStore()
        store.export("script-a", "shop", "old")
        store.export("script-b", "shop", "new")

        assertEquals("new", store.get("shop"))
        assertEquals("script-b", store.ownerOf("shop"))
        assertEquals(setOf("script-b"), store.scriptNames())

        store.clearOwner("script-a")

        assertEquals("new", store.get("shop"))
        assertEquals("script-b", store.ownerOf("shop"))
    }

    @Test
    fun `clearing an owner removes only its current exports`() {
        val store = KSLExportStore()
        store.export("a", "one", 1)
        store.export("a", "two", 2)
        store.export("b", "three", 3)
        store.export("b", "one", 10)

        store.clearOwner("a")

        assertNull(store.get("two"))
        assertEquals(10, store.get("one"))
        assertEquals(3, store.get("three"))
        assertEquals(setOf("b"), store.scriptNames())
    }

    @Test
    fun `re-exporting a key from the same owner does not duplicate ownership`() {
        val store = KSLExportStore()
        store.export("a", "shared", 1)
        store.export("a", "shared", 2)
        store.clearOwner("a")

        assertNull(store.get("shared"))
        assertEquals(emptySet<String>(), store.scriptNames())
    }

    @Test
    fun `blank owners and keys are rejected`() {
        val store = KSLExportStore()
        assertThrows(IllegalArgumentException::class.java) { store.export("", "key", 1) }
        assertThrows(IllegalArgumentException::class.java) { store.export("owner", " ", 1) }
    }
}
