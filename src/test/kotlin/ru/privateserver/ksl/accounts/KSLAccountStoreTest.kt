package ru.privateserver.ksl.accounts

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class KSLAccountStoreTest {
    private lateinit var keeper: Connection
    private lateinit var store: KSLAccountStore
    private lateinit var source: SQLiteDataSource
    private lateinit var clock: MutableClock

    @BeforeEach
    fun setUp() {
        source = SQLiteDataSource().apply {
            url = "jdbc:sqlite:file:ksl-${UUID.randomUUID()}?mode=memory&cache=shared"
        }
        keeper = source.connection
        clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        store = KSLAccountStore(
            source,
            clock,
            passwordIterations = 100_000
        )
        store.initialize()
    }

    @AfterEach
    fun tearDown() {
        keeper.close()
    }

    @Test
    fun registrationAuthenticationAndSessionLifecycle() {
        val password = "correct horse battery".toCharArray()
        val account = store.register("Example_User", password)
        assertEquals("example_user", account.username)
        assertEquals("correct horse battery", String(password))
        assertThrows(KSLAccountAlreadyExistsException::class.java) {
            store.register("EXAMPLE_USER", "another correct password".toCharArray())
        }

        assertNull(store.authenticate(account.username, "incorrect password".toCharArray()))
        val session = store.authenticate("EXAMPLE_USER", password, 60_000)
        assertNotNull(session)
        assertEquals(account.id, session!!.account.id)
        assertEquals(account, store.findSession(session.token))
        assertTrue(store.revokeSession(session.token))
        assertNull(store.findSession(session.token))
        assertFalse(store.revokeSession(session.token))
    }

    @Test
    fun expiredSessionsAreRejectedAtTheExpiryBoundary() {
        val account = store.register("expiry_user", "correct horse battery".toCharArray())
        val session = store.authenticate(account.username, "correct horse battery".toCharArray(), 60_000)!!

        assertEquals(account, store.findSession(session.token))
        clock.advanceMillis(60_000)
        assertNull(store.findSession(session.token))
    }

    @Test
    fun oversizedUsernamesAreRejectedBeforeNormalization() {
        val oversized = "a".repeat(100_000)
        assertNull(store.authenticate(oversized, "correct horse battery".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) {
            store.register(oversized, "correct horse battery".toCharArray())
        }
    }

    @Test
    fun tableNamespacesIsolateAccountsAndSessionsInOneDatabase() {
        val secondStore = KSLAccountStore(
            source,
            clock,
            passwordIterations = 100_000,
            tablePrefix = "addon_two"
        )
        secondStore.initialize()

        val firstAccount = store.register("shared_name", "first account password".toCharArray())
        val secondAccount = secondStore.register("shared_name", "second account password".toCharArray())
        assertNotEquals(firstAccount.id, secondAccount.id)
        assertEquals(firstAccount, store.findSession(store.authenticate(
            firstAccount.username, "first account password".toCharArray()
        )!!.token))
        assertNull(secondStore.authenticate(firstAccount.username, "first account password".toCharArray()))
        assertNull(store.authenticate(secondAccount.username, "second account password".toCharArray()))
    }

    @Test
    fun tablePrefixMustBeAConservativeSqlIdentifier() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLAccountStore(source, tablePrefix = "addon; DROP TABLE users")
        }
        assertEquals(
            KSLAccountStore.tablePrefixFor("Example Addon"),
            KSLAccountStore.tablePrefixFor("Example Addon")
        )
        assertNotEquals(
            KSLAccountStore.tablePrefixFor("Example-Addon"),
            KSLAccountStore.tablePrefixFor("Example Addon")
        )
    }

    @Test
    fun invalidNamesPasswordsAndTokensAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            store.register("ab", "long enough password".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.register("valid_name", "short".toCharArray())
        }
        assertNull(store.findSession("bad"))
        assertFalse(store.revokeSession("bad"))
        assertThrows(IllegalArgumentException::class.java) {
            store.authenticate("valid_name", "long enough password".toCharArray(), 1)
        }
    }

    private class MutableClock(
        private var current: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current

        fun advanceMillis(millis: Long) {
            current = current.plusMillis(millis)
        }
    }
}
