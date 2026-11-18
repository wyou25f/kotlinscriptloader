package ru.privateserver.ksl.api
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLLoggingPolicyTest {
    @Test
    fun `parses default and per-owner levels case insensitively`() {
        val policy = KSLLoggingPolicy.parse("warning", mapOf(
            "script:economy/shop" to "debug",
            "addon:example-addon" to "TRACE",
            "addon:bad" to "not-a-level"
        ), 20_000)

        assertEquals(KSLLogLevel.WARN, policy.defaultMinimumLevel)
        assertEquals(KSLLogLevel.DEBUG, policy.minimumLevel("SCRIPT:ECONOMY/SHOP"))
        assertEquals(KSLLogLevel.TRACE, policy.minimumLevel("addon:example-addon"))
        assertEquals(KSLLogLevel.WARN, policy.minimumLevel("addon:bad"))
        assertEquals(16_384, policy.maxMessageLength)
    }

    @Test
    fun `invalid default level safely falls back to info`() {
        val policy = KSLLoggingPolicy.parse("verbose", emptyMap(), 4_096)
        assertEquals(KSLLogLevel.INFO, policy.minimumLevel("script:any"))
    }
}
