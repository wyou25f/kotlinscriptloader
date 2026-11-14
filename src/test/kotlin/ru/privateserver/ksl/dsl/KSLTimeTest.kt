package ru.privateserver.ksl.dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
class KSLTimeTest {
 @Test fun `integer and long shortcuts convert to ticks`() {
  assertEquals(20L,1.sec); assertEquals(40L,2L.sec); assertEquals(1200L,1.mins); assertEquals(2400L,2L.mins)
  assertEquals(72000L,1.hours); assertEquals(144000L,2L.hours)
  assertEquals(1L, 1.tick); assertEquals(20L, 20.ticks)
  assertEquals(1200L, 1.minute); assertEquals(2400L, 2L.minutes)
  assertEquals(72000L, 1.hour); assertEquals(144000L, 2L.hours)
  assertEquals(1728000L, 1.day); assertEquals(3456000L, 2L.days)
 }
 @Test fun `large values fail rather than overflow`() {
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.sec}
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.mins}
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.hours}
  assertThrows(ArithmeticException::class.java){Long.MAX_VALUE.day}
 }
}