package ru.privateserver.ksl.std.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComponentExtensionsTest {
    @Test fun `component parser accepts minimessage and legacy colors`() {
        assertEquals("Hello world", "<green>Hello</green> world".mm().plainText())
        assertEquals("Hello world", "&aHello&r world".mm().plainText())
    }
    @Test fun `component helpers append rich markup`() {
        val component = "<red>Alert</red>".mm().appendRich(" <bold>now</bold>")
        assertEquals("Alert now", component.plainText())
        val serialized = component.toMiniMessageString()
        assertTrue(serialized.contains("<red>"))
        assertTrue(serialized.contains("<bold>"))
    }
}