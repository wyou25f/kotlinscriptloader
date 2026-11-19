package ru.privateserver.ksl.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class KSLTypedApiTest {
    @Test
    fun `typed topics validate names and payload types`() {
        val topic = kslTopic<String>("example.economy.balance-changed")
        assertEquals("example.economy.balance-changed", topic.name)
        assertTrue(topic.accepts("ok"))
        assertFalse(topic.accepts(42))
        assertFalse(topic.accepts(null))
        assertEquals("ok", topic.cast("ok"))
        assertThrows(IllegalArgumentException::class.java) { topic.cast(42) }
        assertThrows(IllegalArgumentException::class.java) { kslTopic<String>(" bad topic ") }
        assertThrows(IllegalArgumentException::class.java) { kslTopic<String>("x".repeat(129)) }
    }

    @Test
    fun `typed topic subscription and publication delegate to legacy event bus`() {
        var subscribedName: String? = null
        var callback: ((Any?) -> Unit)? = null
        var closed = false
        val api = Proxy.newProxyInstance(
            KSLAPI::class.java.classLoader,
            arrayOf(KSLAPI::class.java)
        ) { _, method, args ->
            when (method.name) {
                "subscribe" -> {
                    subscribedName = args!![0] as String
                    callback = args[1] as (Any?) -> Unit
                    AutoCloseable { closed = true }
                }
                "publish" -> {
                    callback?.invoke(args!![1])
                    1
                }
                else -> error("Unexpected KSLAPI call: ${method.name}")
            }
        } as KSLAPI

        val topic = kslTopic<String>("example.message")
        var received: String? = null
        val subscription = api.subscribe(topic) { received = it }
        assertEquals(topic.name, subscribedName)
        assertEquals(1, api.publish(topic, "hello"))
        assertEquals("hello", received)
        assertThrows(IllegalArgumentException::class.java) { callback?.invoke(17) }
        subscription.close()
        assertTrue(closed)
    }

    @Test
    fun `typed service key performs runtime checked lookup and registration`() {
        val stored = mutableMapOf<String, Any>()
        val api = Proxy.newProxyInstance(
            KSLAPI::class.java.classLoader,
            arrayOf(KSLAPI::class.java)
        ) { _, method, args ->
            when (method.name) {
                "registerService" -> {
                    stored[args!![0] as String] = args[1] as Any
                    null
                }
                "getService" -> {
                    val value = stored[args!![0] as String]
                    (args[1] as Class<*>).takeIf { it.isInstance(value) }?.cast(value)
                }
                "unregisterService" -> {
                    stored.remove(args!![0] as String)
                    null
                }
                else -> error("Unexpected KSLAPI call: ${method.name}")
            }
        } as KSLAPI

        val key = kslServiceKey<String>("example.economy.service")
        api.registerService(key, "economy")
        assertEquals("economy", api.service(key))
        assertEquals("economy", api.requireService(key))
        assertNotNull(api.service(key))
        api.unregisterService(key)
        assertNull(api.service(key))
        assertThrows(IllegalStateException::class.java) { api.requireService(key) }
    }

    @Test
    fun `typed service keys reject invalid names`() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLServiceKey.of("contains spaces", String::class.java)
        }
    }
}
