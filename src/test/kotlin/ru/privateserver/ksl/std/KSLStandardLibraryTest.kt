package ru.privateserver.ksl.std

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.dsl.hours
import ru.privateserver.ksl.dsl.mins
import ru.privateserver.ksl.dsl.sec
import ru.privateserver.ksl.std.component.appendText
import ru.privateserver.ksl.std.component.toMiniMessageComponent
import ru.privateserver.ksl.std.component.toTextComponent
import ru.privateserver.ksl.std.math.clamped
import ru.privateserver.ksl.std.math.mapToRange
import ru.privateserver.ksl.std.math.randomChance
import ru.privateserver.ksl.std.text.isUuid
import ru.privateserver.ksl.std.text.stripLegacyColors
import ru.privateserver.ksl.std.text.toUuidOrNull
import ru.privateserver.ksl.std.text.truncateCodePoints
import ru.privateserver.ksl.std.text.words
import ru.privateserver.ksl.std.time.hoursToTicks
import ru.privateserver.ksl.std.time.minutesToTicks
import ru.privateserver.ksl.std.time.secondsToTicks
import ru.privateserver.ksl.std.time.ticksToDuration
import ru.privateserver.ksl.std.time.toTicksCeiling
import java.time.Duration

class KSLStandardLibraryTest {
    @Test
    fun `text helpers handle unicode and empty values`() {
        assertEquals("ab…", "ab😀cd".truncateCodePoints(3))
        assertEquals("😀s", "abcdef".truncateCodePoints(2, "😀suffix"))
        assertEquals("", "anything".truncateCodePoints(0))
        assertEquals("hello world", "hello   world".words().joinToString(" "))
        assertEquals("red", "§c red§r".stripLegacyColors().trim())
        assertTrue("  550e8400-e29b-41d4-a716-446655440000 ".isUuid())
        assertNull("not-a-uuid".toUuidOrNull())
    }

    @Test
    fun `component helpers keep plain text literal and parse MiniMessage`() {
        assertEquals(Component.text("<red>literal</red>"), "<red>literal</red>".toTextComponent())
        assertEquals(Component.text("hello").append(Component.text(" world")), Component.text("hello").appendText(" world"))
        assertEquals(Component.text("hello").color(NamedTextColor.GREEN), "<green>hello</green>".toMiniMessageComponent())
    }

    @Test
    fun `math helpers validate ranges`() {
        assertEquals(10, 12.clamped(0, 10))
        assertEquals(0L, (-1L).clamped(0L, 10L))
        assertEquals(5.0, 12.0.clamped(0.0, 5.0))
        assertEquals(50.0, 5.0.mapToRange(0.0, 10.0, 0.0, 100.0))
        assertEquals(-10.0, 0.0.mapToRange(0.0, 10.0, -10.0, 10.0))
        assertThrows(IllegalArgumentException::class.java) { 1.0.mapToRange(2.0, 2.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { 1.clamped(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { randomChance(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { randomChance(101.0) }
        assertTrue(randomChance(100.0))
        assertFalse(randomChance(0.0))
    }

    @Test
    fun `time helpers use exact tick arithmetic`() {
        assertEquals(20L, 1.secondsToTicks())
        assertEquals(1_200L, 1.minutesToTicks())
        assertEquals(72_000L, 1.hoursToTicks())
        assertEquals(1_001L, Duration.ofMillis(50_001).toTicksCeiling())
        assertEquals(Duration.ofMillis(150), 3L.ticksToDuration())
        assertEquals(20L, 1.sec)
        assertEquals(1_200L, 1.mins)
        assertEquals(72_000L, 1.hours)
        assertThrows(IllegalArgumentException::class.java) { Duration.ofSeconds(-1).toTicksCeiling() }
        assertThrows(ArithmeticException::class.java) { Long.MAX_VALUE.secondsToTicks() }
        assertThrows(ArithmeticException::class.java) { Long.MAX_VALUE.ticksToDuration() }
        assertThrows(ArithmeticException::class.java) { Long.MAX_VALUE.sec }
    }
}
