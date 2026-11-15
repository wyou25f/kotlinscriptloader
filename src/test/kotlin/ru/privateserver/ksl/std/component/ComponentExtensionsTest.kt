package ru.privateserver.ksl.std.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComponentExtensionsTest {
    @Test fun `component parser accepts minimessage and legacy formatting`() {
        assertEquals("Hello world", "<green>Hello</green> world".mm().plainText())
        assertEquals("Hello world", "&aHello&r world".mm().plainText())
    }
    @Test fun `component helper appends rich markup`() {
        val component = "<red>Alert</red>".mm().appendRich(" <bold>now</bold>")
        assertEquals("Alert now", component.plainText())
        assertEquals("<red>Alert</red> <bold>now</bold>", component.toMiniMessageString())
    }
}