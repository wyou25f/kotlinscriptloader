package ru.privateserver.ksl

import java.io.File

// Dry run: the same checks a real load does (sandbox text filter, full compile), without running the script.
class ScriptValidator(private val plugin: KotlinScriptLoaderPlugin) {

    fun validate(file: File): List<String> {
        return try {
            val unit = plugin.compileScript(file)
            when {
                unit.ok -> emptyList()
                unit.failure == KSLError.SCRIPT_SANDBOX -> unit.errors.map { "🚫 $it" }
                else -> unit.errors
            }
        } catch (ex: Throwable) {
            listOf("не удалось проверить: ${ex::class.simpleName}: ${ex.message}")
        }
    }
}
