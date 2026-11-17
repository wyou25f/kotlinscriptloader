package ru.privateserver.ksl.nbt

import de.tr7zw.changeme.nbtapi.NBT
import ru.privateserver.ksl.KSLAtomicFileWriter
import java.io.File
import java.io.IOException

/**
 * Binary .nbt file I/O backed by Item-NBT-API.
 *
 * Files are read into detached KSL documents: editing the returned document never
 * mutates a live Bukkit object. Writes go through a sibling temp file and atomic move.
 */
object KSLNBTFiles {
    const val DEFAULT_MAX_FILE_BYTES: Long = 16L * 1024L * 1024L

    /**
     * Reads a compressed or uncompressed binary NBT compound file.
     * Returns null only when the path does not exist.
     */
    @JvmOverloads
    fun load(
        file: File,
        limits: KSLNBTLimits = KSLNBTLimits.DEFAULT,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
    ): KSLNBTDocument? {
        require(maxFileBytes in 1L..256L * 1024L * 1024L) {
            "maxFileBytes must be between 1 byte and 256 MiB"
        }
        if (!file.exists()) return null
        require(file.isFile) { "NBT path is not a regular file: $file" }
        val size = file.length()
        require(size in 1..maxFileBytes) {
            "NBT file size $size is outside the allowed range 1..$maxFileBytes bytes"
        }
        return try {
            val snbt = NBT.readFile(file).toString()
            KSLNBTEngine.parse(snbt, limits)
        } catch (error: Exception) {
            throw IllegalArgumentException("Could not read binary NBT file '${file.name}': ${error.message}", error)
        }
    }

    /** Writes the detached document as a binary NBT file. Existing target survives a failed write. */
    fun save(file: File, document: KSLNBTDocument) {
        val snbt = document.snbt()
        KSLNBTSnbtPolicy.validate(snbt)
        try {
            KSLAtomicFileWriter.writeFile(file) { temporary ->
                NBT.writeFile(temporary, NBT.parseNBT(snbt))
            }
        } catch (error: Exception) {
            throw IOException("Could not write binary NBT file '${file.name}': ${error.message}", error)
        }
    }
}
