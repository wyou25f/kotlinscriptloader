package ru.privateserver.ksl

// Validated connection settings from config.yml. Pure data so the rules can be tested without a server.
data class KSLDatabaseSettings(
    val dialect: KSLDialect,
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val sslMode: String,
    val poolSize: Int
) {

    // MariaDB Connector/J talks to MySQL servers too, so one driver covers both dialects.
    fun jdbcUrl(): String {
        check(dialect.isRemote) { "jdbcUrl нужен только для MySQL/MariaDB" }
        // MySQL 8 defaults to caching_sha2_password, which over a non-TLS connection needs the server's RSA key.
        val extra = if (dialect == KSLDialect.MYSQL) "&allowPublicKeyRetrieval=true" else ""
        return "jdbc:mariadb://$host:$port/$name?sslMode=$sslMode$extra"
    }

    // Never includes the password.
    fun describe(): String = if (dialect.isRemote) "${dialect.label} $user@$host:$port/$name" else dialect.label

    companion object {
        private val HOST = Regex("^[A-Za-z0-9._\\-\\[\\]:]+$")
        private val NAME = Regex("^[A-Za-z0-9_\$.\\-]+\$")
        private val SSL_MODES = setOf("disable", "trust", "verify-ca", "verify-full")

        fun parse(
            type: String,
            host: String = "localhost",
            port: Int = 3306,
            name: String = "ksl",
            user: String = "",
            password: String = "",
            sslMode: String = "disable",
            poolSize: Int = 4
        ): KSLDatabaseSettings {
            val dialect = KSLDialect.parse(type)
                ?: throw IllegalArgumentException("${KSLError.CONFIG_DB_INVALID.tag} Неизвестный database.type '$type' — доступно: sqlite, mysql, mariadb")
            val pool = poolSize.coerceIn(1, 32)
            if (!dialect.isRemote) {
                return KSLDatabaseSettings(dialect, host, port, name, user, password, sslMode, pool)
            }

            val cleanHost = host.trim()
            val cleanName = name.trim()
            val cleanSsl = sslMode.trim().lowercase()
            require(HOST.matches(cleanHost)) { "${KSLError.CONFIG_DB_INVALID.tag} database.host '$host' содержит недопустимые символы" }
            require(port in 1..65535) { "${KSLError.CONFIG_DB_INVALID.tag} database.port $port вне диапазона 1..65535" }
            require(NAME.matches(cleanName)) { "${KSLError.CONFIG_DB_INVALID.tag} database.name '$name' должен состоять из букв, цифр, _ . - \$" }
            require(user.isNotBlank()) { "${KSLError.CONFIG_DB_INVALID.tag} database.user не задан — создай пользователя в phpMyAdmin и впиши его в config.yml" }
            require(cleanSsl in SSL_MODES) { "${KSLError.CONFIG_DB_INVALID.tag} database.ssl-mode '$sslMode' — доступно: ${SSL_MODES.joinToString(", ")}" }
            return KSLDatabaseSettings(dialect, cleanHost, port, cleanName, user, password, cleanSsl, pool)
        }
    }
}
