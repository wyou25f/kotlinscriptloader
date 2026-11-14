package ru.privateserver.ksl.std.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
class JsonExtensionsTest {
 private enum class State { READY }
 @Test fun `conversion handles primitives enums and nested collections`() {
  val value=mapOf("text" to 'K',"state" to State.READY,"numbers" to arrayOf(1,2L,3.5),"nested" to listOf(true,null)).toJsonElement() as JsonObject
  assertEquals("K",value.string("text")); assertEquals("READY",value.string("state"))
  assertEquals(3,(value["numbers"] as JsonArray).size); assertEquals(2,(value["nested"] as JsonArray).size)
 }
 @Test fun `builders preserve nested values`() {
  val obj=jsonObjectOf("enabled" to true,"items" to listOf("a","b")); val array=jsonArrayOf(1,"two",null)
  assertEquals(true,obj.bool("enabled")); assertEquals(2,obj.arrayValue("items")?.size); assertEquals(3,array.size)
  assertEquals(JsonPrimitive("two"),array[1])
 }
 @Test fun `typed accessors use defaults for missing or incompatible values`() {
  val value=jsonObjectOf("text" to "hello","count" to 4,"score" to 2.5,"enabled" to true)
  assertEquals("hello",value.string("text")); assertEquals(4,value.int("count")); assertEquals(4L,value.long("count"))
  assertEquals(2.5,value.double("score")); assertEquals(true,value.bool("enabled"))
  assertEquals("fallback",value.string("missing","fallback")); assertEquals(7,value.int("text",7))
  assertNull(value.objectValue("text")); assertNull(value.arrayValue("text"))
 }
 @Test fun `invalid values are rejected`() {
  assertThrows(IllegalArgumentException::class.java){Double.NaN.toJsonElement()}
  assertThrows(IllegalArgumentException::class.java){Float.POSITIVE_INFINITY.toJsonElement()}
  assertThrows(IllegalArgumentException::class.java){mapOf(1 to "bad").toJsonElement()}
  assertThrows(IllegalArgumentException::class.java){Any().toJsonElement()}
 }
 @Test fun \`string accessors do not coerce numeric or boolean values\`() {
  assertNull(JsonPrimitive(42).stringOrNull())
  assertNull(JsonPrimitive(true).stringOrNull())
  assertEquals("42", JsonPrimitive("42").stringOrNull())
 }
 @Test fun \`primitive arrays and sequences convert to json arrays\`() {
  assertEquals("[1,2,3]", intArrayOf(1,2,3).toJsonElement().toString())
  assertEquals("[true,false]", sequenceOf(true,false).toJsonElement().toString())
  assertEquals("[\"a\",\"b\"]", charArrayOf('a','b').toJsonElement().toString())
 }
 @Test fun \`required JSON accessors are strict and array accessors are safe\`() {
  val obj = jsonObjectOf("text" to "hello", "count" to 5, "enabled" to true)
  assertEquals(true, obj.hasKey("count"))
  assertEquals("hello", obj.requireString("text"))
  assertEquals(5, obj.requireInt("count"))
  assertEquals(true, obj.requireBoolean("enabled"))
  assertEquals(5, jsonArrayOf(5, "x").intAt(0))
  assertNull(jsonArrayOf(5, "x").intAt(9))
  org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { obj.requireString("count") }
 }

}