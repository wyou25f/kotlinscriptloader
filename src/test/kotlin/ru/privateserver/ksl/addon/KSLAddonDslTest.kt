package ru.privateserver.ksl.addon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
class KSLAddonDslTest {
 @Test fun `ring points are evenly distributed`() {
  val points=ringPoints(2.0,4,3.0)
  assertEquals(4,points.size); assertEquals(2.0,points[0].x,1e-9); assertEquals(3.0,points[0].y,1e-9)
  assertEquals(0.0,points[0].z,1e-9); assertEquals(2.0,points[1].z,1e-9)
  points.forEach { assertEquals(2.0,kotlin.math.hypot(it.x,it.z),1e-9) }
 }
 @Test fun `ring point count is clamped`() { assertEquals(1,ringPoints(1.0,0).size); assertEquals(360,ringPoints(1.0,361).size) }
 @Test fun `template parser normalizes keys and keeps quoted values`() {
  assertEquals(mapOf("NAME" to "Alex Doe","LEVEL" to "12","MODE" to "fast"),parseTemplate("""name:"Alex Doe" level:12 mode:fast"""))
 }
}