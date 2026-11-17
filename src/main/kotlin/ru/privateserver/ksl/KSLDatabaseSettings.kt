package ru.privateserver.ksl

/**
 * Validated database connection settings.
 *
 * Remote connections use certificate-verifying TLS by default. Disabling TLS or
 * trusting an arbitrary certificate requires an explicit administrator opt-in.
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
            require(sslMode == sslMode.trim().lowercase() && sslMode in SSL_MODES) { "Unsupported database SSL mode: $sslMode" }
            require(sslMode !in INSECURE_SSL_MODES || allowInsecureTransport) {
                "Insecure database SSL mode requires allowInsecureTransport=true"
            }
        }
    }

    fun jdbcUrl(): String {
        check(dialect.isRemote) { "jdbcUrl нужен только для MySQL/MariaDB" }
        // Public-key retrieval is enabled only together with the explicit insecure-transport opt-in.
        val extra = if (dialect == KSLDialect.MYSQL && allowInsecureTransport && sslMode in INSECURE_SSL_MODES) {
            "&allowPublicKeyRetrieval=true"
        } else {
            ""
        }
        return "jdbc:mariadb://$host:$port/$name?sslMode=$sslMode$extra"
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
        private val SSL_MODES = setOf("disable", "trust", "verify-ca", "verify-full")
        private val INSECURE_SSL_MODES = setOf("disable", "trust")

        fun parse(
            type: String,
            host: String = "localhost",
            port: Int = 3306,
            name: String = "ksl",
            user: String = "",
            password: String = "",
            sslMode: String = "verify-full",
            poolSize: Int = 4,
            allowInsecureTransport: Boolean = false
        ): KSLDatabaseSettings {
            val dialect = KSLDialect.parse(type)
                ?: throw IllegalArgumentException(
                    "${KSLError.CONFIG_DB_INVALID.tag} Неизвестный database.type '$type' — доступно: sqlite, mysql, mariadb"
                )
            val pool = poolSize.coerceIn(1, 32)
            if (!dialect.isRemote) {
                return KSLDatabaseSettings(
                    dialect, host, port, name, user, password,
                    sslMode.trim().lowercase(), pool, allowInsecureTransport
                )
            }

            val cleanHost = host.trim()
            val cleanName = name.trim()
            val cleanUser = user.trim()
            val cleanSsl = sslMode.trim().lowercase()
            require(HOST.matches(cleanHost)) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.host '$host' содержит недопустимые символы"
            }
            require(port in 1..65535) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.port $port вне диапазона 1..65535"
            }
            require(NAME.matches(cleanName)) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.name '$name' должен содержать 1..64 символа: буквы, цифры, _ . - $"
            }
            require(cleanUser.isNotBlank()) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.user не задан — создай отдельного пользователя БД и впиши его в config.yml"
            }
            require(cleanSsl in SSL_MODES) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.ssl-mode '$sslMode' — доступно: ${SSL_MODES.joinToString(", ")}"
            }
            require(cleanSsl !in INSECURE_SSL_MODES || allowInsecureTransport) {
                "${KSLError.CONFIG_DB_INVALID.tag} database.ssl-mode '$cleanSsl' небезопасен для удалённой БД. " +
                    "Используй verify-full/verify-ca или явно включи database.allow-insecure-transport для осознанного локального тестирования."
            }

            return KSLDatabaseSettings(
                dialect, cleanHost, port, cleanName, cleanUser, password,
                cleanSsl, pool, allowInsecureTransport
            )
        }
    }
}
