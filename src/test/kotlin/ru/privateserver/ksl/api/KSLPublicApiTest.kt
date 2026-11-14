package ru.privateserver.ksl.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class KSLPublicApiTest {
    private class Addon(override val addonId: String) : KSLAddon {
        override val addonVersion: String = "1.0.0"
        override fun onLoad(api: KSLAPI) = Unit
        override fun onUnload() = Unit
    }

    private fun api(
        addons: List<KSLAddon> = emptyList(),
        scripts: List<KSLScriptInfo> = emptyList(),
        subscribe: ((String, (Any?) -> Unit) -> AutoCloseable)? = null
    ): KSLAPI = Proxy.newProxyInstance(
        KSLAPI::class.java.classLoader,
        arrayOf(KSLAPI::class.java)
    ) { _, method, args ->
        when (method.name) {
            "registeredAddons" -> addons
            "scripts" -> scripts
            "subscribe" -> subscribe?.invoke(args!![0] as String, args[1] as (Any?) -> Unit)
                ?: AutoCloseable { }
            else -> error("Unexpected KSLAPI call: ${method.name}")
        }
    } as KSLAPI

    @Test
    fun `finds addons without case sensitivity and reports missing addons`() {
        val installed = Addon("example-addon")
        val api = api(addons = listOf(installed))

        assertEquals(installed, api.findAddon("EXAMPLE-ADDON"))
        assertEquals(installed, api.requireAddon("example-addon"))
        assertNull(api.findAddon("missing"))
        assertThrows(IllegalStateException::class.java) { api.requireAddon("missing") }
    }

    @Test
    fun `finds scripts and checks their loaded state`() {
        val api = api(scripts = listOf(
            KSLScriptInfo("welcome", loaded = true, loadMillis = 12L, errors = 0),
            KSLScriptInfo("economy", loaded = false, loadMillis = -1L, errors = 1)
        ))

        assertEquals("welcome", api.findScript("WELCOME")?.id)
        assertEquals("economy", api.requireScript("economy").id)
        assertTrue(api.isScriptLoaded("welcome"))
        assertFalse(api.isScriptLoaded("economy"))
        assertFalse(api.isScriptLoaded("unknown"))
        assertThrows(IllegalStateException::class.java) { api.requireScript("unknown") }
    }

    @Test
    fun `public api aliases and helper names remain available`() {
        val installed = api()
        assertEquals(KSL_API_VERSION, ru.privateserver.ksl.KSL.API_VERSION)
        // These aliases are compile-time typealiases; assigning the interface checks their signatures.
        val shortName: KslApi = installed
        val publicName: KSLPublicAPI = shortName
        assertSame(installed, publicName)
    }

    @Test
    fun `short public api helpers delegate to stable interface methods`() {
        val imported = mutableListOf<String>()
        val fake = Proxy.newProxyInstance(
            KSLAPI::class.java.classLoader,
            arrayOf(KSLAPI::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getService" -> "ready"
                "addDefaultImports" -> {
                    imported += (args!![0] as Array<*>).map { it as String }
                    null
                }
                else -> error("Unexpected KSLAPI call: ${method.name}")
            }
        } as KSLAPI

        fake.addImports("com.example.player.*", "com.example.text.*")
        assertEquals(listOf("com.example.player.*", "com.example.text.*"), imported)
        assertEquals("ready", fake.serviceOrThrow<String>("example.service"))
    }

    @Test
    fun `addon config is exported from the public api package`() {
        assertEquals("ru.privateserver.ksl.KSLAddonConfig", KSLAddonConfig::class.java.name)
    }

    @Test
    fun `typed lifecycle subscription filters payloads and uses stable topic`() {
        var subscribedTopic: String? = null
        var callback: ((Any?) -> Unit)? = null
        var received: KSLScriptEvent? = null
        val api = api(subscribe = { topic, handler ->
            subscribedTopic = topic
            callback = handler
            AutoCloseable { callback = null }
        })

        val subscription = api.onScriptLoaded { received = it }
        assertEquals(KSLTopics.SCRIPT_LOADED, subscribedTopic)
        callback?.invoke("not a script event")
        assertNull(received)

        val event = KSLScriptEvent("welcome", millis = 25L)
        callback?.invoke(event)
        assertEquals(event, received)
        subscription.close()
        assertNull(callback)
    }
}
