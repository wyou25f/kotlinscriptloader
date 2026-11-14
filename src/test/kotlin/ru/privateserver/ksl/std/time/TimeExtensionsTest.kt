package ru.privateserver.ksl.std.time
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
class TimeExtensionsTest {
 @Test fun `tick conversions use twenty ticks per second`() {
  assertEquals(5L,5.ticks()); assertEquals(5L,5L.ticks()); assertEquals(20L,1.secondsToTicks())
  assertEquals(1200L,1.minutesToTicks()); assertEquals(72000L,1.hoursToTicks()); assertEquals(Duration.ofMillis(250),5L.ticksToDuration())
 }
 @Test fun `duration conversion rounds partial ticks upward`() {
  assertEquals(0L,Duration.ZERO.toTicksCeiling()); assertEquals(1L,Duration.ofNanos(1).toTicksCeiling())
  assertEquals(1L,Duration.ofMillis(50).toTicksCeiling()); assertEquals(2L,Duration.ofMillis(51).toTicksCeiling())
  assertEquals(1001L,Duration.ofMillis(50001).toTicksCeiling())
  assertThrows(IllegalArgumentException::class.java){Duration.ofSeconds(-1).toTicksCeiling()}
 }
 @Test fun `large conversions report overflow`() {
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.secondsToTicks()}
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.minutesToTicks()}
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.hoursToTicks()}
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.ticksToDuration()}
 }
 @Test fun `calendar and millisecond conversions use exact arithmetic` () {
  assertEquals(1_728_000L, 1.daysToTicks())
  assertEquals(12_096_000L, 1L.weeksToTicks())
  assertEquals(2L, 51L.millisecondsToTicksCeiling())
  assertEquals(1L, 51L.millisecondsToTicksFloor())
  assertEquals(0L, 49L.millisecondsToTicksFloor())
  assertEquals(1L, 49L.millisecondsToTicksCeiling())
  assertEquals(-1L, (-1L).millisecondsToTicksFloor())
  assertEquals(0L, (-1L).millisecondsToTicksCeiling())
  assertEquals(19L, Duration.ofMillis(999).toTicksFloor())
  assertThrows(ArithmeticException::class.java) { Long.MAX_VALUE.daysToTicks() }
 }

}