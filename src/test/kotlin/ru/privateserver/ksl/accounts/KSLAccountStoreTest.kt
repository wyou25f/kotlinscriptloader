package ru.privateserver.ksl.accounts

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class KSLAccountStoreTest {
    private lateinit var keeper: Connection
    private lateinit var store: KSLAccountStore

    @BeforeEach
    fun setUp() {
        val source = SQLiteDataSource().apply {
            url = "jdbc:sqlite:file:ksl-${UUID.randomUUID()}?mode=memory&cache=shared"
        }
        keeper = source.connection
        store = KSLAccountStore(
            source,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
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
}
