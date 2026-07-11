package ru.privateserver.ksl

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

class ScriptValidator(private val plugin: KotlinScriptLoaderPlugin) {

    private val compiler = JvmScriptCompiler()

    fun validate(file: File): List<String> {
        return try {
            val sandboxIssues = if (plugin.sandboxEnabled) {
                KSLSandboxRules.scan(file).map { "🚫 строка ${it.line}: запрещённый паттерн '${it.pattern}'" }
            } else {
                emptyList()
            }

            val compilationConfig = buildScriptCompilationConfig(plugin.addonManager)
            val result = runBlocking { compiler(file.toScriptSource(), compilationConfig) }
            val compileIssues = when (result) {
                is ResultWithDiagnostics.Failure -> result.reports
                    .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                    .map { formatDiagnostic(it) }
                is ResultWithDiagnostics.Success -> emptyList()
            }

            sandboxIssues + compileIssues
        } catch (ex: Throwable) {
            listOf("не удалось проверить: ${ex::class.simpleName}: ${ex.message}")
        }
    }

    private fun formatDiagnostic(d: ScriptDiagnostic): String {
        val loc = d.location
        return if (loc != null) "строка ${loc.start.line}: ${d.message}" else d.message
    }
}
