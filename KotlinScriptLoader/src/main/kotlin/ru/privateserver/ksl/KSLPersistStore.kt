package ru.privateserver.ksl

import java.sql.Connection
import kotlin.reflect.KProperty

class KSLPersistStore(private val plugin: KotlinScriptLoaderPlugin) {

    private val memory = java.util.concurrent.ConcurrentHashMap<String, Any?>()

    fun <T> property(scriptName: String, key: String, persistent: Boolean, default: () -> T): KSLPersistProperty<T> =
        KSLPersistProperty(this, scriptName, key, persistent, default)

    fun <T> get(fullKey: String, persistent: Boolean, default: () -> T): T {
        if (memory.containsKey(fullKey)) {
            @Suppress("UNCHECKED_CAST")
            return memory[fullKey] as T
        }
        val loaded = if (persistent) loadFromDb(fullKey) else null
        val value = loaded ?: default()
        memory[fullKey] = value
        return value as T
    }

    fun <T> set(fullKey: String, value: T, persistent: Boolean) {
        memory[fullKey] = value
        if (persistent) saveToDb(fullKey, value)
    }

    fun clearScript(scriptName: String) {
        memory.keys.removeIf { it.startsWith("$scriptName:") }
    }

    private fun loadFromDb(fullKey: String): Any? {
        val (scriptName, key) = splitKey(fullKey) ?: return null
        return try {
            plugin.database.connection.use { conn ->
                conn.prepareStatement("SELECT value FROM ksl_persist WHERE script_name = ? AND key = ?").use { ps ->
                    ps.setString(1, scriptName)
                    ps.setString(2, key)
                    ps.executeQuery().use { rs -> if (rs.next()) decode(rs.getString("value")) else null }
                }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("[persist] Не удалось загрузить '$fullKey': ${ex.message}")
            null
        }
    }

    private fun saveToDb(fullKey: String, value: Any?) {
        val (scriptName, key) = splitKey(fullKey) ?: return
        val encoded = encode(value) ?: run {
            plugin.logger.warning("[persist] Тип ${value?.let { it::class.simpleName }} не поддерживается для persistent = true у ключа '$key' — поддерживаются только String, Int, Long, Double, Boolean")
            return
        }
        try {
            plugin.database.connection.use { conn: Connection ->
                conn.prepareStatement(
                    "INSERT INTO ksl_persist (script_name, key, value) VALUES (?, ?, ?) " +
                        "ON CONFLICT(script_name, key) DO UPDATE SET value = excluded.value"
                ).use { ps ->
                    ps.setString(1, scriptName)
                    ps.setString(2, key)
                    ps.setString(3, encoded)
                    ps.executeUpdate()
                }
            }
        } catch (ex: Exception) {
            plugin.logger.warning("[persist] Не удалось сохранить '$fullKey' в БД: ${ex.message} — значение осталось только в памяти до следующего рестарта")
        }
    }

    private fun splitKey(fullKey: String): Pair<String, String>? {
        val idx = fullKey.indexOf(':')
        if (idx == -1) return null
        return fullKey.substring(0, idx) to fullKey.substring(idx + 1)
    }

    private fun encode(value: Any?): String? = KSLPersistCodec.encode(value)

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
