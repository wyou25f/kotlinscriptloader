package ru.privateserver.ksl.std.component
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
class ComponentExtensionsTest {
 @Test fun `plain conversion keeps markup literal`() { val s="<red>literal</red>"; assertEquals(Component.text(s),s.toTextComponent()) }
 @Test fun `MiniMessage conversion parses formatting`() { assertEquals(Component.text("hello").color(NamedTextColor.GREEN),"<green>hello</green>".toMiniMessageComponent()) }
 @Test fun `append text treats appended markup literally`() {
  assertEquals(Component.text("hello").append(Component.text("<red>world</red>")),Component.text("hello").appendText("<red>world</red>"))
 }
}