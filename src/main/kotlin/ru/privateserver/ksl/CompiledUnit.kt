package ru.privateserver.ksl

import java.io.File
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
    val compileMillis: Long
) {
    val ok: Boolean get() = compiled != null
}
