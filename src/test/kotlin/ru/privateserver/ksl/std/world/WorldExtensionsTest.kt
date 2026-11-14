package ru.privateserver.ksl.std.world
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
class WorldExtensionsTest {
 private fun worldStub():World = Proxy.newProxyInstance(World::class.java.classLoader,arrayOf(World::class.java)){ _,method,_ ->
  throw UnsupportedOperationException("Unexpected World call: ${method.name}")
 } as World
 @Test fun `block position uses floored integer coordinates`() {
  assertEquals(Triple(-2,64,-1),Location(null,-1.2,64.9,-0.1).blockPosition())
 }
 @Test fun `location offset copies without mutating original`() {
  val original=Location(null,1.0,2.0,3.0); val shifted=original.offset(2.0,-1.0,4.0)
  assertEquals(1.0,original.x()); assertEquals(3.0,shifted.x()); assertEquals(1.0,shifted.y()); assertEquals(7.0,shifted.z())
  assertFalse(original===shifted)
 }
 @Test fun `within radius checks world identity and distance`() {
  val world=worldStub(); val center=Location(world,0.0,0.0,0.0)
  assertTrue(center.isWithin(Location(world,3.0,4.0,0.0),5.0))
  assertFalse(center.isWithin(Location(world,3.0,4.1,0.0),5.0))
  assertFalse(center.isWithin(Location(null,0.0,0.0,0.0),5.0))
  assertThrows(IllegalArgumentException::class.java){center.isWithin(Location(world,0.0,0.0,0.0),-1.0)}
 }
 @Test fun `inside check is inclusive and rejects another world`() {
  val world=worldStub(); val other=worldStub(); val min=Location(world,0.0,0.0,0.0); val max=Location(world,10.0,10.0,10.0)
  assertTrue(Location(world,0.0,10.0,5.0).isInside(min,max)); assertFalse(Location(other,5.0,5.0,5.0).isInside(min,max))
 }
}