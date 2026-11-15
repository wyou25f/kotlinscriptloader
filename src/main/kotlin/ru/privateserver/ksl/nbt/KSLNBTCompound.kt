package ru.privateserver.ksl.nbt

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.ReadableNBT
import de.tr7zw.changeme.nbtapi.ReadWriteNBT

/** Read-only view over an NBT compound. Missing keys are returned as null. */
class KSLNBTView internal constructor(protected val handle: ReadableNBT) {
    fun keys(): Set<String> = handle.keys
    fun has(key: String): Boolean = handle.hasTag(key)
    fun string(key: String): String? = if (has(key)) handle.getString(key) else null
    fun byte(key: String): Byte? = if (has(key)) handle.getByte(key) else null
    fun short(key: String): Short? = if (has(key)) handle.getShort(key) else null
    fun int(key: String): Int? = if (has(key)) handle.getInteger(key) else null
    fun long(key: String): Long? = if (has(key)) handle.getLong(key) else null
    fun float(key: String): Float? = if (has(key)) handle.getFloat(key) else null
    fun double(key: String): Double? = if (has(key)) handle.getDouble(key) else null
    fun boolean(key: String): Boolean? = if (has(key)) handle.getBoolean(key) else null
    fun byteArray(key: String): ByteArray? = if (has(key)) handle.getByteArray(key) else null
    fun intArray(key: String): IntArray? = if (has(key)) handle.getIntArray(key) else null
    fun longArray(key: String): LongArray? = if (has(key)) handle.getLongArray(key) else null
    fun compound(key: String): KSLNBTView? = handle.getCompound(key)?.let(::KSLNBTView)
    fun snbt(): String = handle.toString()
    override fun toString(): String = snbt()
}

/** Mutable NBT compound exposed to KSL scripts. */
class KSLNBTCompound internal constructor(private val writable: ReadWriteNBT) : KSLNBTView(writable) {
    fun setString(key: String, value: String) = writable.setString(key, value)
    fun setByte(key: String, value: Byte) = writable.setByte(key, value)
    fun setShort(key: String, value: Short) = writable.setShort(key, value)
    fun setInt(key: String, value: Int) = writable.setInteger(key, value)
    fun setLong(key: String, value: Long) = writable.setLong(key, value)
    fun setFloat(key: String, value: Float) = writable.setFloat(key, value)
    fun setDouble(key: String, value: Double) = writable.setDouble(key, value)
    fun setBoolean(key: String, value: Boolean) = writable.setBoolean(key, value)
    fun setByteArray(key: String, value: ByteArray) = writable.setByteArray(key, value)
    fun setIntArray(key: String, value: IntArray) = writable.setIntArray(key, value)
    fun setLongArray(key: String, value: LongArray) = writable.setLongArray(key, value)
    fun remove(key: String): Boolean {
        if (!writable.hasTag(key)) return false
        writable.removeKey(key)
        return true
    }
    fun getOrCreateCompound(key: String): KSLNBTCompound = KSLNBTCompound(writable.getOrCreateCompound(key))
    fun merge(snbt: String) { writable.mergeCompound(NBT.parseNBT(snbt)) }

    companion object {
        /** Parses SNBT text into a new mutable compound. */
        fun parse(snbt: String): KSLNBTCompound = KSLNBTCompound(NBT.parseNBT(snbt))
    }
}
