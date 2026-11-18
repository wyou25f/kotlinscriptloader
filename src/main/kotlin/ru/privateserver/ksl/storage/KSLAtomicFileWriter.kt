package ru.privateserver.ksl.storage

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Writes UTF-8 text through a temporary file in the destination directory.
 *
 * A small fixed lock stripe prevents two script contexts from racing when they
 * save the same path. The temporary file is unique, so interrupted writes never
 * leave a partially-written destination or collide with another writer.
 */
internal object KSLAtomicFileWriter {
    private val locks = Array(64) { ReentrantLock() }

    fun writeText(destination: File, content: String) {
        writeFile(destination) { temporary ->
            Files.write(temporary.toPath(), content.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** Writes binary or structured data through a unique sibling temp file, then atomically replaces the target. */
    fun writeFile(destination: File, writer: (File) -> Unit) {
        val target = destination.toPath().toAbsolutePath().normalize()
        val parent = target.parent
            ?: throw IllegalArgumentException("Destination must have a parent directory: $destination")
        val lock = locks[(target.hashCode() and Int.MAX_VALUE) % locks.size]

        lock.withLock {
            Files.createDirectories(parent)
            val safeName = target.fileName.toString()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(48)
            val temporary = Files.createTempFile(parent, ".$safeName.", ".tmp")
            try {
                writer(temporary.toFile())
                check(Files.isRegularFile(temporary)) {
                    "File writer did not produce a regular temporary file for $destination"
                }
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
