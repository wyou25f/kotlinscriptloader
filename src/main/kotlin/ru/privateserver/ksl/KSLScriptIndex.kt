package ru.privateserver.ksl

import java.io.File
import java.util.Collections

/**
 * Caches the relatively expensive filesystem discovery result.
 *
 * Reads are lock-free after the first scan. Mutations are explicit: reload/validation
 * paths call [refresh], while the watcher calls [invalidate] when it observes a change.
 */
internal class KSLScriptIndex(
    private val scanFiles: () -> List<File>,
    private val scriptId: (File) -> String
) {
    internal data class Snapshot(
        val files: List<File>,
        val ids: List<String>,
        val reloadTargets: List<String>
    )

    private val lock = Any()

    @Volatile
    private var cached: Snapshot? = null

    fun snapshot(): Snapshot {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: createSnapshot().also { cached = it }
        }
    }

    /** Performs a fresh directory scan and atomically replaces the cached snapshot. */
    fun refresh(): Snapshot = synchronized(lock) {
        createSnapshot().also { cached = it }
    }

    /** Invalidates the snapshot after a watcher event or other external filesystem mutation. */
    fun invalidate() {
        synchronized(lock) {
            cached = null
        }
    }

    private fun createSnapshot(): Snapshot {
        val sortedFiles = scanFiles().sortedBy(scriptId)
        val files = immutableList(sortedFiles)
        val ids = immutableList(files.map(scriptId))
        val folders = LinkedHashSet<String>()
        ids.forEach { id ->
            val lastSeparator = id.lastIndexOf('/')
            if (lastSeparator >= 0) folders += id.substring(0, lastSeparator + 1)
        }
        val reloadTargets = immutableList((ids + folders + "changed").distinct().sorted())
        return Snapshot(files, ids, reloadTargets)
    }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
