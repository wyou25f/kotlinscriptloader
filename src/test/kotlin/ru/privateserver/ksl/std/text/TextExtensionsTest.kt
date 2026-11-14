package ru.privateserver.ksl.std.text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
class TextExtensionsTest {
 @Test fun `truncation counts Unicode code points and includes suffix`() {
  assertEquals("a😀…","a😀bc".truncateCodePoints(3)); assertEquals("😀s","abcdef".truncateCodePoints(2,"😀suffix"))
  assertEquals("","abcdef".truncateCodePoints(0)); assertEquals("abc","abc".truncateCodePoints(3))
  assertThrows(IllegalArgumentException::class.java){"abc".truncateCodePoints(-1)}
 }
 @Test fun `legacy formatting codes including hex are removed`() {
  assertEquals("Hello world","§x§1§2§A§B§3§4Hello§r world".stripLegacyColors()); assertEquals("plain","plain".stripLegacyColors())
 }
 @Test fun `uuid parsing safely rejects invalid input`() {
  assertTrue("550e8400-e29b-41d4-a716-446655440000".isUuid())
  assertEquals("550e8400-e29b-41d4-a716-446655440000"," 550e8400-e29b-41d4-a716-446655440000 ".toUuidOrNull().toString())
  assertNull("not-a-uuid".toUuidOrNull()); assertFalse("not-a-uuid".isUuid())
 }
 @Test fun `word splitting handles whitespace`() {
  assertEquals(listOf("one","two","three"),"  one\t two\nthree ".words()); assertTrue("   ".words().isEmpty())
 }
 @Test fun `formatting helpers normalize names and validate usernames`() {
  assertEquals("one two three","  one\t two\nthree ".normalizeWhitespace())
  assertEquals("http_server_id","HTTPServer-ID".toSnakeCase()); assertEquals("helloWorldAgain","hello_world-again".toCamelCase())
  assertEquals("","   ".toCamelCase()); assertTrue("Player_123".isMinecraftUsername())
  assertFalse("ab".isMinecraftUsername()); assertFalse("player-name".isMinecraftUsername()); assertFalse("this_name_is_far_too_long".isMinecraftUsername())
 }
 @Test fun \`strict UUID and Unicode whitespace edge cases are covered\`() {
  assertNull("1-1-1-1-1".toUuidOrNull())
  assertFalse("1-1-1-1-1".isUuid())
  assertEquals(listOf("one","two"), "one\u00A0two".words())
  assertEquals("one two", " one\u00A0\t two ".normalizeWhitespace())
 }
 @Test fun \`case insensitive helpers preserve original text\`() {
  assertTrue("WelcomeHome".containsIgnoreCase("comeh"))
  assertTrue("WelcomeHome".startsWithIgnoreCase("wel"))
  assertTrue("WelcomeHome".endsWithIgnoreCase("HOME"))
  assertEquals("Home", "WelcomeHome".removePrefixIgnoreCase("welcome"))
  assertEquals("Welcome", "WelcomeHome".removeSuffixIgnoreCase("HOME"))
  assertEquals("WelcomeHome", "WelcomeHome".removePrefixIgnoreCase("missing"))
 }

}