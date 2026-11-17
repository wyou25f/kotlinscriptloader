package ru.privateserver.ksl.nbt

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Read-only view over an NBT compound. Values are copied out; the view is valid only inside its callback. */
open class KSLNBTView internal constructor(protected val handle: ReadableNBT) {
    fun keys(): Set<String> = handle.getKeys()
    fun has(key: String): Boolean = handle.hasTag(key)
    fun type(key: String): String? = if (has(key)) handle.getType(key).getName() else null

    fun string(key: String): String? = if (has(key)) handle.getString(key) else null
    fun byte(key: String): Byte? = if (has(key)) handle.getByte(key) else null
    fun short(key: String): Short? = if (has(key)) handle.getShort(key) else null
    fun int(key: String): Int? = if (has(key)) handle.getInteger(key) else null
    fun long(key: String): Long? = if (has(key)) handle.getLong(key) else null
    fun float(key: String): Float? = if (has(key)) handle.getFloat(key) else null
    fun double(key: String): Double? = if (has(key)) handle.getDouble(key) else null
    fun boolean(key: String): Boolean? = if (has(key)) handle.getBoolean(key) else null
    fun uuid(key: String): UUID? = if (has(key)) handle.getUUID(key) else null
    fun itemStack(key: String): ItemStack? = if (has(key)) handle.getItemStack(key) else null
    fun itemStackArray(key: String): Array<ItemStack>? = if (has(key)) handle.getItemStackArray(key) else null

    fun byteArray(key: String): ByteArray? = if (has(key)) handle.getByteArray(key) else null
    fun intArray(key: String): IntArray? = if (has(key)) handle.getIntArray(key) else null
    fun longArray(key: String): LongArray? = if (has(key)) handle.getLongArray(key) else null

    fun stringList(key: String): List<String> = handle.getStringList(key).toListCopy()
    fun intList(key: String): List<Int> = handle.getIntegerList(key).toListCopy()
    fun intArrayList(key: String): List<IntArray> = handle.getIntArrayList(key).toListCopy()
    fun longList(key: String): List<Long> = handle.getLongList(key).toListCopy()
    fun floatList(key: String): List<Float> = handle.getFloatList(key).toListCopy()
    fun doubleList(key: String): List<Double> = handle.getDoubleList(key).toListCopy()
    fun uuidList(key: String): List<UUID> = handle.getUUIDList(key).toListCopy()
    fun compoundList(key: String): List<KSLNBTView> = handle.getCompoundList(key).map { KSLNBTView(it) }

    fun compound(key: String): KSLNBTView? = handle.getCompound(key)?.let(::KSLNBTView)

    private fun pathParent(path: String): Pair<KSLNBTView, String>? {
        val segments = KSLNBTPathPolicy.parse(path)
        var current = this
        for (segment in segments.dropLast(1)) {
            current = current.compound(segment) ?: return null
        }
        return current to segments.last()
    }

    fun stringAt(path: String): String? = pathParent(path)?.let { (parent, key) -> parent.string(key) }
    fun intAt(path: String): Int? = pathParent(path)?.let { (parent, key) -> parent.int(key) }
    fun longAt(path: String): Long? = pathParent(path)?.let { (parent, key) -> parent.long(key) }
    fun doubleAt(path: String): Double? = pathParent(path)?.let { (parent, key) -> parent.double(key) }
    fun booleanAt(path: String): Boolean? = pathParent(path)?.let { (parent, key) -> parent.boolean(key) }

    fun compoundAt(path: String): KSLNBTView? {
        val segments = KSLNBTPathPolicy.parse(path)
        var current = this
        for (segment in segments) {
            current = current.compound(segment) ?: return null
        }
        return current
    }

    /** Creates a new wrapper over the same detached read-only handle. */
    internal fun asReadOnlyView(): KSLNBTView = KSLNBTView(handle)

    fun snbt(): String = handle.toString()
    override fun toString(): String = snbt()
}

/** Mutable NBT compound exposed to KSL scripts. */
class KSLNBTCompound internal constructor(private val writable: ReadWriteNBT) : KSLNBTView(writable) {
    private fun setAt(path: String, action: (KSLNBTCompound, String) -> Unit) {
        val segments = KSLNBTPathPolicy.parse(path)
        var parent = this
        for (segment in segments.dropLast(1)) {
            parent = parent.getOrCreateCompound(segment)
        }
        action(parent, segments.last())
    }

    fun getOrCreateCompoundAt(path: String): KSLNBTCompound {
        val segments = KSLNBTPathPolicy.parse(path)
        var current = this
        for (segment in segments) {
            current = current.getOrCreateCompound(segment)
        }
        return current
    }

    fun setStringAt(path: String, value: String) = setAt(path) { parent, key -> parent.setString(key, value) }
    fun setIntAt(path: String, value: Int) = setAt(path) { parent, key -> parent.setInt(key, value) }
    fun setLongAt(path: String, value: Long) = setAt(path) { parent, key -> parent.setLong(key, value) }
    fun setDoubleAt(path: String, value: Double) = setAt(path) { parent, key -> parent.setDouble(key, value) }
    fun setBooleanAt(path: String, value: Boolean) = setAt(path) { parent, key -> parent.setBoolean(key, value) }

    fun setString(key: String, value: String) = writable.setString(key, value)
    fun setByte(key: String, value: Byte) = writable.setByte(key, value)
    fun setShort(key: String, value: Short) = writable.setShort(key, value)
    fun setInt(key: String, value: Int) = writable.setInteger(key, value)
    fun setLong(key: String, value: Long) = writable.setLong(key, value)
    fun setFloat(key: String, value: Float) = writable.setFloat(key, value)
    fun setDouble(key: String, value: Double) = writable.setDouble(key, value)
    fun setBoolean(key: String, value: Boolean) = writable.setBoolean(key, value)
    fun setUuid(key: String, value: UUID) = writable.setUUID(key, value)
    fun setItemStack(key: String, value: ItemStack) = writable.setItemStack(key, value)
    fun setItemStackArray(key: String, value: Array<ItemStack>) = writable.setItemStackArray(key, value)

    fun setByteArray(key: String, value: ByteArray) = writable.setByteArray(key, value)
    fun setIntArray(key: String, value: IntArray) = writable.setIntArray(key, value)
    fun setLongArray(key: String, value: LongArray) = writable.setLongArray(key, value)

    fun setStringList(key: String, values: Iterable<String>) {
        val target = writable.getStringList(key)
        target.clear()
        values.forEach { target.add(it) }
    }
    fun setIntList(key: String, values: Iterable<Int>) {
        val target = writable.getIntegerList(key)
        target.clear()
        values.forEach { target.add(it) }
    }
    fun setIntArrayList(key: String, values: Iterable<IntArray>) {
        val target = writable.getIntArrayList(key)
        target.clear()
        values.forEach { target.add(it) }
    }
    fun setLongList(key: String, values: Iterable<Long>) {
        val target = writable.getLongList(key)
        target.clear()
        values.forEach { target.add(it) }
    }
    fun setFloatList(key: String, values: Iterable<Float>) {
        val target = writable.getFloatList(key)
        target.clear()
        values.forEach { target.add(it) }
    }
    fun setDoubleList(key: String, values: Iterable<Double>) {
        val target = writable.getDoubleList(key)
        target.clear()
        values.forEach { target.add(it) }
    }
    fun setUuidList(key: String, values: Iterable<UUID>) {
        val target = writable.getUUIDList(key)
        target.clear()
        values.forEach { target.add(it) }
    }

    fun addCompoundToList(key: String, block: KSLNBTCompound.() -> Unit) {
        KSLNBTCompound(writable.getCompoundList(key).addCompound()).block()
    }
    fun removeCompoundFromList(key: String, index: Int): Boolean {
        val target = writable.getCompoundList(key)
        if (index !in 0 until target.size()) return false
        target.remove(index)
        return true
    }

    fun remove(key: String): Boolean {
        if (!writable.hasTag(key)) return false
        writable.removeKey(key)
        return true
    }
    fun getOrCreateCompound(key: String): KSLNBTCompound = KSLNBTCompound(writable.getOrCreateCompound(key))
    fun merge(snbt: String) = mergeValidated(snbt, KSLNBTLimits.DEFAULT)

    internal fun mergeValidated(snbt: String, limits: KSLNBTLimits) {
        KSLNBTSnbtPolicy.validate(snbt, limits)
        writable.mergeCompound(NBT.parseNBT(snbt))
    }

    companion object {
        fun empty(): KSLNBTCompound = KSLNBTCompound(NBT.createNBTObject())
        fun parse(snbt: String): KSLNBTCompound {
            KSLNBTSnbtPolicy.validate(snbt)
            return KSLNBTCompound(NBT.parseNBT(snbt))
        }
    }
}
