package ru.privateserver.ksl.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class KSLApiAliasesTest {
    @Test
    fun `concise aliases delegate to stable KSLAPI methods`() {
        val topics = mutableListOf<String>()
        val api = Proxy.newProxyInstance(
            KSLAPI::class.java.classLoader,
            arrayOf(KSLAPI::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getService" -> "ready"
                "registeredAddons" -> emptyList<KSLAddon>()
                "isAddonLoaded" -> args?.get(0) == "present"
                "scripts" -> emptyList<KSLScriptInfo>()
                "reloadScript" -> args?.get(0) == "welcome"
                "reloadScripts" -> 3 to 1
                "publish" -> {
                    topics += args!![0] as String
                    2
                }
                "subscribe" -> {
                    topics += args!![0] as String
                    AutoCloseable { }
                }
                "isPluginEnabled" -> args?.get(0) == "Paper"
                else -> error("Unexpected KSLAPI call: ${method.name}")
            }
        } as KSLAPI

        assertEquals("ready", api.serviceOrNull<String>("economy"))
        assertTrue(api.hasService("economy"))
        assertTrue(api.addons().isEmpty())
        assertTrue(api.hasAddon("present"))
        assertFalse(api.hasAddon("missing"))
        assertTrue(api.script("unknown") == null)
        assertTrue(api.reload("welcome"))
        assertEquals(3 to 1, api.reloadAll())
        assertEquals(2, api.emit("custom.topic", "payload"))
        api.on("custom.topic") { }
        assertTrue(api.pluginAvailable("Paper"))
        assertEquals(listOf("custom.topic", "custom.topic"), topics)
    }
}
