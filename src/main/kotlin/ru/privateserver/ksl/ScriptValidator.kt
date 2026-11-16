package ru.privateserver.ksl

import java.io.File

// Dry run: use the production compiler and sandbox checks, but never evaluate script code.
class ScriptValidator(private val plugin: KotlinScriptLoaderPlugin) {

    fun validate(file: File): List<String> = validateDetailed(file).errors

    fun validateDetailed(file: File): KSLScriptValidationResult {
        val id = plugin.scriptId(file)
        return try {
            val unit = plugin.compileScript(file)
            val errors = when {
                unit.ok -> emptyList()
                unit.failure == KSLError.SCRIPT_SANDBOX -> unit.errors.map { "🚫 $it" }
                else -> unit.errors
            }
            KSLScriptValidationResult(
                scriptId = id,
                fileName = file.name,
                errors = errors,
                warnings = unit.warnings,
                compileMillis = unit.compileMillis,
                failureCode = unit.failure?.code
            )
        } catch (ex: Throwable) {
            KSLScriptValidationResult(
                scriptId = id,
                fileName = file.name,
                errors = listOf("не удалось проверить: ${ex::class.simpleName}: ${ex.message}"),
                warnings = emptyList(),
                compileMillis = -1L,
                failureCode = KSLError.SCRIPT_COMPILER_CRASH.code
            )
        }
    }
}
