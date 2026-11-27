package ru.privateserver.ksl

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.script.experimental.api.CompiledScript

// Result of compiling one script. Compiling needs no Bukkit API, so it can run on any thread;
// evaluating the script (which registers listeners, commands, ...) must happen on the main thread.
class CompiledUnit(
    val file: File,
    val id: String,
    val compiled: CompiledScript?,
    val errors: List<String>,
    val warnings: List<String>,
    val failure: KSLError?,
    val crash: Throwable?,
    val compileMillis: Long,
    val sourceStamp: String = "",
    /** Exact local-library generation used to compile this artifact, for safe reload rollback. */
    val libraryClassLoader: ClassLoader? = null,
    /** Temporary compile-to-activation lease, transferred to the generation runtime scope. */
    libraryLease: AutoCloseable? = null
) {
    private val pendingLibraryLease = AtomicReference(libraryLease)

    /** Transfers the initial compile lease once; later generation recoveries acquire a fresh lease. */
    internal fun takeLibraryLease(): AutoCloseable? = pendingLibraryLease.getAndSet(null)

    val ok: Boolean get() = compiled != null
}
