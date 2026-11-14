package ru.privateserver.ksl

import java.io.File
import java.util.Collections

/**
 * Caches only compact script IDs and reload completion targets.
 *
 * File objects and their path strings are deliberately not retained between scans:
 * reload operations get fresh File instances, while hot tab-completion reads retain
 * only the metadata they need.
 */
internal class KSLScriptIndex(
    private val scanFiles: () -> List<File>,
    private val scriptId: (File) -> String
) {
    internal data class Snapshot(
        val ids: List<String>,
        val reloadTargets: List<String>
    )

    private val lock = Any()

    @Volatile
    private var cached: Snapshot? = null

    fun snapshot(): Snapshot {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: createSnapshot(scanFiles()).also { cached = it }
        }
    }

    /** Performs a fresh directory scan and atomically replaces the cached metadata snapshot. */
    fun refresh(): Snapshot = synchronized(lock) {
        createSnapshot(scanFiles()).also { cached = it }
    }

    /** Returns fresh, deterministically sorted files and refreshes the compact metadata snapshot. */
    fun refreshFiles(): List<File> = synchronized(lock) {
        val files = sortedFiles(scanFiles())
        cached = createSnapshot(files)
        immutableList(files)
    }

    /** Invalidates the snapshot after a watcher event or other external filesystem mutation. */
    fun invalidate() {
        synchronized(lock) {
            cached = null
        }
    }

    private fun createSnapshot(files: List<File>): Snapshot {
        val ids = immutableList(files.map(scriptId).sorted())
        val folders = LinkedHashSet<String>()
        ids.forEach { id ->
            val lastSeparator = id.lastIndexOf('/')
            if (lastSeparator >= 0) folders += id.substring(0, lastSeparator + 1)
        }
        val reloadTargets = immutableList((ids + folders + "changed").distinct().sorted())
        return Snapshot(ids, reloadTargets)
    }

    private fun sortedFiles(files: List<File>): List<File> = files.sortedBy(scriptId)

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
