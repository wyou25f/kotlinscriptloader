package ru.privateserver.ksl.std.player
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
class PlayerExtensionsTest {
 private fun playerWithPermissions(vararg granted:String):Player {
  val permissions=granted.toSet()
  return Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)){ proxy,method,args ->
   when(method.name){
    "hasPermission" -> permissions.contains(args?.firstOrNull() as? String)
    "toString" -> "KSL test player"
    "hashCode" -> System.identityHashCode(proxy)
    "equals" -> proxy===args?.firstOrNull()
    else -> throw UnsupportedOperationException("Unexpected Player method: ${method.name}")
   }
  } as Player
 }
 @Test fun `has any permission ignores blank nodes`() {
  val player=playerWithPermissions("ksl.admin")
  assertTrue(player.hasAnyPermission("","ksl.admin","ksl.use")); assertFalse(player.hasAnyPermission("","ksl.missing")); assertFalse(player.hasAnyPermission())
 }
 @Test fun `has all permissions requires every non blank node`() {
  val player=playerWithPermissions("ksl.admin","ksl.use")
  assertTrue(player.hasAllPermissions("ksl.admin","ksl.use")); assertFalse(player.hasAllPermissions("ksl.admin","ksl.missing"))
  assertFalse(player.hasAllPermissions("ksl.admin"," ")); assertFalse(player.hasAllPermissions())
 }
 @Test fun feedRejectsNaNAndClampsFiniteValues() {
  val values = mutableMapOf<String, Any>("food" to 20, "saturation" to 5f)
  val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
   when (method.name) {
    "getFoodLevel" -> values["food"]
    "setFoodLevel" -> { values["food"] = args!![0] as Int; null }
    "getSaturation" -> values["saturation"]
    "setSaturation" -> { values["saturation"] = args!![0] as Float; null }
    else -> throw UnsupportedOperationException("Unexpected Player method: " + method.name)
   }
  } as Player
  assertThrows(IllegalArgumentException::class.java) { player.feed(saturation = Float.NaN) }
  assertEquals(20, values["food"])
  assertEquals(5f, values["saturation"])
  player.feed(food = 4, saturation = 50f)
  assertEquals(4, values["food"])
  assertEquals(4f, values["saturation"])
 }

}