package ru.privateserver.ksl.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.KSLAPI
import java.lang.reflect.Proxy

class KSLReloadApiTest {
    @Test
    fun `single reload future represents success failure and busy without null results`() {
        var callback: ((Boolean?) -> Unit)? = null
        val api = Proxy.newProxyInstance(
            KSLAPI::class.java.classLoader,
            arrayOf(KSLAPI::class.java)
        ) { _, method, args ->
            if (method.name == "reloadScriptAsync") {
                callback = args!![1] as (Boolean?) -> Unit
                null
            } else error("Unexpected KSLAPI call: ${method.name}")
        } as KSLAPI

        val future = api.reloadScriptFuture("welcome")
        callback?.invoke(true)
        assertEquals(KSLReloadOutcome.Script("welcome", true), future.get())

        val failed = api.reloadScriptFuture("welcome")
        callback?.invoke(false)
        assertEquals(KSLReloadOutcome.Script("welcome", false), failed.get())

        val busy = api.reloadScriptFuture("welcome")
        callback?.invoke(null)
        assertSame(KSLReloadOutcome.Busy, busy.get())
    }

    @Test
    fun `batch reload future maps counts and busy state`() {
        var callback: ((Pair<Int, Int>?) -> Unit)? = null
        val api = Proxy.newProxyInstance(
            KSLAPI::class.java.classLoader,
            arrayOf(KSLAPI::class.java)
        ) { _, method, args ->
            if (method.name == "reloadScriptsAsync") {
                callback = args!![0] as (Pair<Int, Int>?) -> Unit
                null
            } else error("Unexpected KSLAPI call: ${method.name}")
        } as KSLAPI

        val future = api.reloadScriptsFuture()
        callback?.invoke(7 to 2)
        assertEquals(KSLReloadOutcome.Batch(7, 2), future.get())

        val busy = api.reloadScriptsFuture()
        callback?.invoke(null)
        assertSame(KSLReloadOutcome.Busy, busy.get())
    }
}
