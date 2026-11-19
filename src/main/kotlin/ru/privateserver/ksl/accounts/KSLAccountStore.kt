package ru.privateserver.ksl.accounts

import ru.privateserver.ksl.database.KSLDatabaseTransactions
import ru.privateserver.ksl.std.security.KSLSecurity
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.util.UUID
import java.util.Locale
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import javax.sql.DataSource

/** Public account profile. Password hashes and session credentials are deliberately excluded. */
data class KSLAccount(
    val id: String,
    val username: String,
    val createdAtEpochMillis: Long
)

/** A newly-issued bearer session. The raw token is returned once and is never stored in the database. */
data class KSLAccountSession(
    val account: KSLAccount,
    val token: String,
    val expiresAtEpochMillis: Long
)

/** Raised when registration collides with an existing normalized username. */
class KSLAccountAlreadyExistsException(username: String) :
    IllegalStateException("KSL account '$username' already exists")

/**
 * JDBC-backed account storage for addons and scripts.
 *
 * The supplied DataSource remains owned by the caller. Call [initialize] once during addon startup.
 * Password hashing and verification are CPU-expensive; call registration/authentication away from
 * the Bukkit main thread. The schema uses portable SQL supported by SQLite, PostgreSQL and MySQL.
 *
 * Only a SHA-256 digest of each random session token is persisted. Passwords are stored as salted
 * PBKDF2-HMAC-SHA256 hashes via [KSLSecurity], never as plaintext or reversible encryption.
 */
class KSLAccountStore @JvmOverloads constructor(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val passwordIterations: Int = 210_000,
    val tablePrefix: String = "ksl"
) {
    private val accountsTable = "${tablePrefix}_accounts"
    private val sessionsTable = "${tablePrefix}_account_sessions"

    private val dummyPasswordHash: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val dummy = CharArray(32) { ('a'.code + (it % 26)).toChar() }
        try {
            KSLSecurity.hashPassword(dummy, passwordIterations)
        } finally {
            dummy.fill(0.toChar())
        }
    }

    init {
        require(passwordIterations in 100_000..2_000_000) {
            "passwordIterations must be between 100000 and 2000000"
        }
        require(TABLE_PREFIX.matches(tablePrefix)) {
            "tablePrefix must be a safe SQL identifier prefix (1-46 lowercase letters, digits or underscores)"
        }
    }

    /** Creates the account tables if they do not already exist. Safe to call on every startup. */
    fun initialize() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS ${accountsTable} (
                        id VARCHAR(36) NOT NULL PRIMARY KEY,
                        username VARCHAR(32) NOT NULL UNIQUE,
                        password_hash VARCHAR(256) NOT NULL,
                        created_at BIGINT NOT NULL,
                        enabled BOOLEAN NOT NULL
                    )"""
                )
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS ${sessionsTable} (
                        token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
                        account_id VARCHAR(36) NOT NULL,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL,
                        FOREIGN KEY (account_id) REFERENCES ${accountsTable}(id) ON DELETE CASCADE
                    )"""
                )
            }
        }
    }

    /** Registers an account. The caller's password array is not modified. */
    fun register(username: String, password: CharArray): KSLAccount {
        val normalized = normalizeUsername(username)
        require(password.size in 10..1024) { "Password must contain 10..1024 characters" }
        val passwordCopy = password.copyOf()
        val encodedHash = try {
            KSLSecurity.hashPassword(passwordCopy, passwordIterations)
        } finally {
            passwordCopy.fill('\u0000')
        }
        val account = KSLAccount(UUID.randomUUID().toString(), normalized, clock.millis())
        try {
            dataSource.connection.use { connection ->
                KSLDatabaseTransactions.inTransaction(connection) {
                    connection.prepareStatement(
                        "INSERT INTO ${accountsTable}(id, username, password_hash, created_at, enabled) VALUES(?, ?, ?, ?, ?)"
                    ).use { statement ->
                        statement.setString(1, account.id)
                        statement.setString(2, account.username)
                        statement.setString(3, encodedHash)
                        statement.setLong(4, account.createdAtEpochMillis)
                        statement.setBoolean(5, true)
                        statement.executeUpdate()
                    }
                }
            }
        } catch (error: SQLException) {
            if (error.isConstraintViolation()) throw KSLAccountAlreadyExistsException(normalized)
            throw error
        }
        return account
    }

    /**
     * Verifies credentials and creates a bearer session, or returns null for invalid credentials.
     * The token is shown only in the returned value; persist it securely on the caller's side.
     */
    @JvmOverloads
    fun authenticate(
        username: String,
        password: CharArray,
        sessionTtlMillis: Long = DEFAULT_SESSION_TTL_MILLIS
    ): KSLAccountSession? {
        val normalized = normalizeUsernameOrNull(username) ?: return null
        require(sessionTtlMillis in MIN_SESSION_TTL_MILLIS..MAX_SESSION_TTL_MILLIS) {
            "Session TTL must be between 1 minute and 30 days"
        }
        if (password.isEmpty() || password.size > 1024) return null

        val row = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, username, password_hash, created_at, enabled FROM ${accountsTable} WHERE username = ?"
            ).use { statement ->
                statement.setString(1, normalized)
                statement.executeQuery().use { result ->
                    if (result.next()) result.toAccountCredential() else null
                }
            }
        }

        // Unknown accounts still pay the configured PBKDF2 work factor to reduce username
        // enumeration through response timing. Disabled accounts verify their stored hash too.
        val hashToVerify = row?.passwordHash ?: dummyPasswordHash
        val passwordCopy = password.copyOf()
        val verified = try {
            KSLSecurity.verifyPassword(passwordCopy, hashToVerify)
        } finally {
            passwordCopy.fill(0.toChar())
        }
        if (row == null || !row.enabled || !verified) return null

        val now = clock.millis()
        val expiresAt = Math.addExact(now, sessionTtlMillis)
        val token = KSLSecurity.randomToken()
        val tokenHash = KSLSecurity.sha256(token)
        dataSource.connection.use { connection ->
            KSLDatabaseTransactions.inTransaction(connection) {
                if (KSLSecurity.needsPasswordRehash(row.passwordHash, passwordIterations)) {
                    val copy = password.copyOf()
                    val upgraded = try {
                        KSLSecurity.hashPassword(copy, passwordIterations)
                    } finally {
                        copy.fill('\u0000')
                    }
                    connection.prepareStatement(
                        "UPDATE ${accountsTable} SET password_hash = ? WHERE id = ? AND password_hash = ?"
                    ).use { statement ->
                        statement.setString(1, upgraded)
                        statement.setString(2, row.id)
                        statement.setString(3, row.passwordHash)
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    "INSERT INTO ${sessionsTable}(token_hash, account_id, created_at, expires_at) VALUES(?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.setString(2, row.id)
                    statement.setLong(3, now)
                    statement.setLong(4, expiresAt)
                    statement.executeUpdate()
                }
            }
        }
        return KSLAccountSession(row.toPublicAccount(), token, expiresAt)
    }

    /** Resolves a session token if it exists, is unexpired and belongs to an enabled account. */
    fun findSession(token: String): KSLAccount? {
        if (token.length !in 32..128) return null
        val tokenHash = KSLSecurity.sha256(token)
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT a.id, a.username, a.created_at
                   FROM ${sessionsTable} s
                   JOIN ${accountsTable} a ON a.id = s.account_id
                   WHERE s.token_hash = ? AND s.expires_at > ? AND a.enabled = ?"""
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setLong(2, clock.millis())
                statement.setBoolean(3, true)
                statement.executeQuery().use { result ->
                    if (result.next()) result.toPublicAccount() else null
                }
            }
        }
    }

    /** Revokes one session. Returns false for an unknown or already-revoked token. */
    fun revokeSession(token: String): Boolean {
        if (token.length !in 32..128) return false
        return dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM ${sessionsTable} WHERE token_hash = ?").use { statement ->
                statement.setString(1, KSLSecurity.sha256(token))
                statement.executeUpdate() > 0
            }
        }
    }

    /** Revokes all active sessions for an account. */
    fun revokeAllSessions(accountId: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM ${sessionsTable} WHERE account_id = ?").use { statement ->
                statement.setString(1, accountId)
                statement.executeUpdate()
            }
        }

    /**
     * Enables or disables an account. Disabling it revokes every session in the same
     * transaction, so existing bearer tokens stop working immediately.
     */
    fun setAccountEnabled(accountId: String, enabled: Boolean): Boolean =
        dataSource.connection.use { connection ->
            KSLDatabaseTransactions.inTransaction(connection) {
                val updated = connection.prepareStatement(
                    "UPDATE ${accountsTable} SET enabled = ? WHERE id = ?"
                ).use { statement ->
                    statement.setBoolean(1, enabled)
                    statement.setString(2, accountId)
                    statement.executeUpdate()
                }
                if (updated > 0 && !enabled) {
                    connection.prepareStatement(
                        "DELETE FROM ${sessionsTable} WHERE account_id = ?"
                    ).use { statement ->
                        statement.setString(1, accountId)
                        statement.executeUpdate()
                    }
                }
                updated > 0
            }
        }

    private data class Credential(
        val id: String,
        val username: String,
        val passwordHash: String,
        val createdAt: Long,
        val enabled: Boolean
    ) {
        fun toPublicAccount() = KSLAccount(id, username, createdAt)
    }

    private fun ResultSet.toAccountCredential() = Credential(
        getString("id"), getString("username"), getString("password_hash"),
        getLong("created_at"), getBoolean("enabled")
    )

    private fun ResultSet.toPublicAccount() =
        KSLAccount(getString("id"), getString("username"), getLong("created_at"))

    private fun normalizeUsername(username: String): String =
        normalizeUsernameOrNull(username)
            ?: throw IllegalArgumentException("Username must be 3..32 ASCII letters, digits, dots, underscores or hyphens")

    private fun normalizeUsernameOrNull(username: String): String? {
        // Bound work before trim/lowercase so hostile oversized input cannot trigger
        // another full-size allocation during authentication or registration.
        if (username.length !in 3..64) return null
        val trimmed = username.trim()
        if (trimmed.length !in 3..32) return null
        val normalized = trimmed.lowercase(java.util.Locale.ROOT)
        return normalized.takeIf { USERNAME.matches(it) }
    }

    private fun SQLException.isConstraintViolation(): Boolean =
        this is java.sql.SQLIntegrityConstraintViolationException ||
            sqlState?.startsWith("23") == true ||
            errorCode == 19

    companion object {
        private val TABLE_PREFIX = Regex("[a-z][a-z0-9_]{0,45}")

        /**
         * Builds a stable, SQL-safe prefix for a Bukkit plugin/addon name.
         * A short digest prevents collisions after slug normalization/truncation.
         */
        @JvmStatic
        fun tablePrefixFor(ownerId: String): String {
            require(ownerId.isNotBlank()) { "ownerId must not be blank" }
            val normalized = ownerId.trim().lowercase(Locale.ROOT)
            val slug = normalized
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(28)
                .ifEmpty { "addon" }
                .let { if (it.first().isLetter()) it else "addon_$it" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(StandardCharsets.UTF_8))
                .take(6)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "ksl_${slug}_$digest"
        }

        const val DEFAULT_SESSION_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        const val MIN_SESSION_TTL_MILLIS: Long = 60_000
        const val MAX_SESSION_TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
        private val USERNAME = Regex("[a-z0-9._-]+")
    }
}
