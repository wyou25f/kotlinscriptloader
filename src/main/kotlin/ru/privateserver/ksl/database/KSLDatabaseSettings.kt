package ru.privateserver.ksl

import java.util.Locale

/**
 * Validated database connection settings.
 *
 * Remote connections use certificate and hostname verification by default.
 * Non-verifying TLS modes require an explicit administrator opt-in.
 */
data class KSLDatabaseSettings(
    val dialect: KSLDialect,
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val sslMode: String,
    val poolSize: Int,
    val allowInsecureTransport: Boolean = false
) {
    init {
        require(poolSize in 1..32) { "poolSize must be in 1..32" }
        if (dialect.isRemote) {
            require(host == host.trim() && HOST.matches(host)) { "Invalid remote database host" }
            require(port in 1..65535) { "Remote database port must be in 1..65535" }
            require(name == name.trim() && NAME.matches(name)) { "Invalid remote database name" }
            require(user.isNotBlank() && user == user.trim()) { "Remote database user must not be blank or padded" }
            require(sslMode == sslMode.trim().lowercase(Locale.ROOT) && sslMode in allowedSslModes(dialect)) {
                "Unsupported ${dialect.label} SSL mode: $sslMode"
            }
            require(sslMode !in insecureSslModes(dialect) || allowInsecureTransport) {
                "A non-verifying database SSL mode requires allowInsecureTransport=true"
            }
        }
    }

    fun jdbcUrl(): String {
        check(dialect.isRemote) { "jdbcUrl нужен только для удалённой БД" }
        return when (dialect) {
            KSLDialect.POSTGRESQL ->
                "jdbc:postgresql://$host:$port/$name?sslmode=$sslMode"
            KSLDialect.MYSQL, KSLDialect.MARIADB -> {
                // Public-key retrieval is enabled only together with the explicit insecure-transport opt-in.
                val extra = if (
                    dialect == KSLDialect.MYSQL &&
                    allowInsecureTransport &&
                    sslMode in insecureSslModes(dialect)
                ) "&allowPublicKeyRetrieval=true" else ""
                "jdbc:mariadb://$host:$port/$name?sslMode=$sslMode$extra"
            }
            KSLDialect.SQLITE -> error("Unreachable")
        }
    }

    /** Safe diagnostic description. Never includes the password. */
    fun describe(): String = if (dialect.isRemote) {
        "${dialect.label} $user@$host:$port/$name (ssl=$sslMode)"
    } else {
        dialect.label
    }

    companion object {
        private val HOST = Regex("""^[A-Za-z0-9._:\-\[\]]{1,255}$""")
        private val NAME = Regex("""^[A-Za-z0-9_.$-]{1,64}$""")
        private val MYSQL_SSL_MODES = setOf("disable", "trust", "verify-ca", "verify-full")
        private val POSTGRES_SSL_MODES = setOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full")

        private fun allowedSslModes(dialect: KSLDialect): Set<String> =
            if (dialect == KSLDialect.POSTGRESQL) POSTGRES_SSL_MODES else MYSQL_SSL_MODES

        private fun insecureSslModes(dialect: KSLDialect): Set<String> =
            if (dialect == KSLDialect.POSTGRESQL) setOf("disable", "allow", "prefer", "require")
            else setOf("disable", "trust")

        fun parse(
            type: String,
            host: String = "localhost",
            port: Int = 0,
            name: String = "ksl",
            user: String = "",
            password: String = "",
            sslMode: String = "verify-full",
            poolSize: Int = 4,
            allowInsecureTransport: Boolean = false
        ): KSLDatabaseSettings {
            val dialect = KSLDialect.parse(type)
                ?: throw IllegalArgumentException(
                    "${KSLError.CONFIG_DB_INVALID.tag} Неизвестный database.type '$type' — доступно: sqlite, mysql, mariadb, postgresql"
                )
            val pool = poolSize.coerceIn(1, 32)
            val resolvedPort = if (port == 0) {
                if (dialect == KSLDialect.POSTGRESQL) 5432 else 3306
            } else port
            if (!dialect.isRemote) {
                return KSLDatabaseSettings(
                    dialect, host, resolvedPort, name, user, password,
                    sslMode.trim().lowercase(), pool, allowInsecureTransport
                )
            }

            val cleanHost = host.trim()
            val cleanName = name.trim()
            val cleanUser = user.trim()
            val cleanSsl = sslMode.trim().lowercase(Locale.ROOT)
            require(HOST.matches(cleanHost)) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.host '$host' содержит недопустимые символы"
            }
            require(resolvedPort in 1..65535) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.port $resolvedPort вне диапазона 1..65535"
            }
            require(NAME.matches(cleanName)) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.name '$name' должен содержать 1..64 символа: буквы, цифры, _ . - $"
            }
            require(cleanUser.isNotBlank()) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.user не задан — создай отдельного пользователя БД и впиши его в config.yml"
            }
            require(cleanSsl in allowedSslModes(dialect)) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.ssl-mode '$sslMode' не поддерживается для ${dialect.label}: ${allowedSslModes(dialect).joinToString(", ")}"
            }
            require(cleanSsl !in insecureSslModes(dialect) || allowInsecureTransport) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.ssl-mode '$cleanSsl' не проверяет сертификат сервера. " +
                    "Используй verify-full/verify-ca или явно включи database.allow-insecure-transport для осознанного локального тестирования."
            }

            return KSLDatabaseSettings(
                dialect, cleanHost, resolvedPort, cleanName, cleanUser, password,
                cleanSsl, pool, allowInsecureTransport
            )
        }
    }
}
