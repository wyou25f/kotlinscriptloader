package ru.privateserver.ksl

import java.sql.Connection
import kotlin.reflect.KProperty

class KSLPersistStore(private val plugin: KotlinScriptLoaderPlugin) {

    // ConcurrentHashMap rejects null values; box them to keep nullable properties working.
    private object Null
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun <T> property(scriptName: String, key: String, persistent: Boolean, default: () -> T): KSLPersistProperty<T> =
        KSLPersistProperty(this, scriptName, key, persistent, default)

    fun <T> get(fullKey: String, persistent: Boolean, default: () -> T): T {
        memory[fullKey]?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            return (if (cached === Null) null else cached) as T
        }
        val loaded = if (persistent) loadFromDb(fullKey) else null
        val value = loaded ?: default()
        memory[fullKey] = value ?: Null
        return value as T
    }

    fun <T> set(fullKey: String, value: T, persistent: Boolean) {
        // Fail fast on the caller thread; a background warning would silently lose the write.
        val encoded = if (persistent) {
            KSLPersistCodec.encode(value) ?: throw IllegalArgumentException(
                "[persist] Тип ${value?.let { it::class.simpleName }} ${KSLError.DB_UNSUPPORTED_TYPE.tag} не поддерживается для persistent = true у ключа '$fullKey' — " +
                    "поддерживаются только String, Int, Long, Double, Boolean, null"
            )
        } else null
        memory[fullKey] = value ?: Null
        if (encoded != null) plugin.dbExecutor.submit { saveToDb(fullKey, encoded) }
    }

    // "key" is a reserved word in MySQL/MariaDB, hence the quoting inside the dialect helper.
    private val upsertSql by lazy {
        plugin.dialect.upsert("ksl_persist", listOf("script_name", "key", "value"), listOf("script_name", "key"))
    }

    fun clearScript(scriptName: String) {
        memory.keys.removeIf { it.startsWith("$scriptName:") }
    }

    private fun loadFromDb(fullKey: String): Any? {
        val (scriptName, key) = splitKey(fullKey) ?: return null
        return try {
            plugin.database.connection.use { conn ->
                conn.prepareStatement("SELECT value FROM ksl_persist WHERE script_name = ? AND `key` = ?").use { ps ->
                    ps.setString(1, scriptName)
                    ps.setString(2, key)
                    ps.executeQuery().use { rs -> if (rs.next()) decode(rs.getString("value")) else null }
                }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("[persist] ${KSLError.DB_PERSIST_LOAD_FAILED.tag} Не удалось загрузить '$fullKey': ${ex.message}")
            null
        }
    }

    private fun saveToDb(fullKey: String, encoded: String) {
        val (scriptName, key) = splitKey(fullKey) ?: return
        try {
            plugin.database.connection.use { conn: Connection ->
                conn.prepareStatement(upsertSql).use { ps ->
                    ps.setString(1, scriptName)
                    ps.setString(2, key)
                    ps.setString(3, encoded)
                    ps.executeUpdate()
                }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("[persist] ${KSLError.DB_PERSIST_SAVE_FAILED.tag} Не удалось сохранить '$fullKey' в БД: ${ex.message} — значение осталось только в памяти до следующего рестарта")
        }
    }

    private fun splitKey(fullKey: String): Pair<String, String>? {
        val idx = fullKey.indexOf(':')
        if (idx == -1) return null
        return fullKey.substring(0, idx) to fullKey.substring(idx + 1)
    }

    private fun decode(raw: String?): Any? = KSLPersistCodec.decode(raw)
}

class KSLPersistProperty<T>(
    private val store: KSLPersistStore,
    private val scriptName: String,
    private val key: String,
    private val persistent: Boolean,
    private val default: () -> T
) {
    private val fullKey = "$scriptName:$key"

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T =
        store.get(fullKey, persistent, default)

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        store.set(fullKey, value, persistent)
    }
}
