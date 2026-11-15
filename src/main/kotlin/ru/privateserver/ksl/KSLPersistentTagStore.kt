package ru.privateserver.ksl

import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Script-scoped helpers for Bukkit PersistentDataContainer.
 *
 * The "scriptdata/" path is intentionally separate from KSL's internal keys
 * (ksl_owner and ksl_ephemeral) and from legacy ItemStack.setTag() keys.
 */
internal object KSLPersistentTagKey {
    fun path(scriptName: String, raw: String): String {
        val normalized = raw.lowercase(Locale.ROOT)
        require(normalized.matches(Regex("[a-z0-9._/-]{1,128}"))) {
            "Persistent tag key must contain 1..128 lowercase letters, digits, '.', '_', '-', or '/': '$raw'"
        }
        val slug = scriptName.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]"), "_")
            .take(32)
            .ifBlank { "script" }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(scriptName.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        val owner = "$slug-$fingerprint"
        return "scriptdata/$owner/$normalized"
    }
}

internal class KSLPersistentTagStore(private val plugin: Plugin, private val scriptName: String) {
    private fun key(raw: String): NamespacedKey =
        NamespacedKey(plugin, KSLPersistentTagKey.path(scriptName, raw))

    fun setString(container: PersistentDataContainer, key: String, value: String) =
        container.set(key(key), PersistentDataType.STRING, value)
    fun getString(container: PersistentDataContainer, key: String): String? =
        container.get(key(key), PersistentDataType.STRING)
    fun hasString(container: PersistentDataContainer, key: String): Boolean =
        container.has(key(key), PersistentDataType.STRING)

    fun setInt(container: PersistentDataContainer, key: String, value: Int) =
        container.set(key(key), PersistentDataType.INTEGER, value)
    fun getInt(container: PersistentDataContainer, key: String): Int? =
        container.get(key(key), PersistentDataType.INTEGER)
    fun hasInt(container: PersistentDataContainer, key: String): Boolean =
        container.has(key(key), PersistentDataType.INTEGER)

    fun setLong(container: PersistentDataContainer, key: String, value: Long) =
        container.set(key(key), PersistentDataType.LONG, value)
    fun getLong(container: PersistentDataContainer, key: String): Long? =
        container.get(key(key), PersistentDataType.LONG)
    fun hasLong(container: PersistentDataContainer, key: String): Boolean =
        container.has(key(key), PersistentDataType.LONG)

    fun setDouble(container: PersistentDataContainer, key: String, value: Double) =
        container.set(key(key), PersistentDataType.DOUBLE, value)
    fun getDouble(container: PersistentDataContainer, key: String): Double? =
        container.get(key(key), PersistentDataType.DOUBLE)
    fun hasDouble(container: PersistentDataContainer, key: String): Boolean =
        container.has(key(key), PersistentDataType.DOUBLE)

    fun setBoolean(container: PersistentDataContainer, key: String, value: Boolean) =
        container.set(key(key), PersistentDataType.BYTE, if (value) 1.toByte() else 0.toByte())
    fun getBoolean(container: PersistentDataContainer, key: String): Boolean? =
        container.get(key(key), PersistentDataType.BYTE)?.let { it.toInt() != 0 }
    fun hasBoolean(container: PersistentDataContainer, key: String): Boolean =
        container.has(key(key), PersistentDataType.BYTE)

    fun setUuid(container: PersistentDataContainer, key: String, value: UUID) {
        val bytes = ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()
        container.set(key(key), PersistentDataType.BYTE_ARRAY, bytes)
    }
    fun getUuid(container: PersistentDataContainer, key: String): UUID? {
        val bytes = container.get(key(key), PersistentDataType.BYTE_ARRAY) ?: return null
        if (bytes.size != 16) return null
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.getLong(), buffer.getLong())
    }
    fun hasUuid(container: PersistentDataContainer, key: String): Boolean =
        container.get(key(key), PersistentDataType.BYTE_ARRAY)?.size == 16

    fun remove(container: PersistentDataContainer, key: String) = container.remove(key(key))
}
