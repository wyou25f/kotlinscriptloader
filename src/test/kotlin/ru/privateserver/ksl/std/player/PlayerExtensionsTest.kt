package ru.privateserver.ksl.std.player
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertFalse
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
}