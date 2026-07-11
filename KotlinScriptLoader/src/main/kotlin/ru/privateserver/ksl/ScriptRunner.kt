package ru.privateserver.ksl

import java.io.File
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

class ScriptRunner(private val plugin: KotlinScriptLoaderPlugin) {

    private val host = BasicJvmScriptingHost()
    private val pluginClassLoader = plugin::class.java.classLoader

    companion object {
        private const val SLOW_LOAD_THRESHOLD_MS = 2000
    }

    fun loadScript(file: File): Boolean {
        val scriptName = file.nameWithoutExtension
        val startedAt = System.nanoTime()
        return try {
            if (!checkSandbox(file, scriptName)) return false

            val context = BukkitScriptContext(plugin, scriptName)
            plugin.addonManager.notifyContextCreated(context)

            val compilationConfig = buildScriptCompilationConfig(plugin.addonManager)
            val evalConfig = ScriptEvaluationConfiguration {
                implicitReceivers(context)
                jvm { baseClassLoader(pluginClassLoader) }
            }

            val result = host.eval(file.toScriptSource(), compilationConfig, evalConfig)

            result.reports
                .filter { it.severity == ScriptDiagnostic.Severity.WARNING }
                .forEach { plugin.logger.warning("[$scriptName] ⚠ ${formatDiagnostic(it)}") }

            val ok = when (result) {
                is ResultWithDiagnostics.Failure -> {
                    plugin.logger.severe("[$scriptName] ❌ Ошибки компиляции:")
                    result.reports
                        .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                        .forEach { plugin.logger.severe("  ${formatDiagnostic(it)}") }
                    false
                }
                is ResultWithDiagnostics.Success -> {
                    val rv = result.value.returnValue
                    if (rv is ResultValue.Error) {
                        KSLErrors.log(plugin, scriptName, "загрузка скрипта", rv.error)
                        false
                    } else {
                        plugin.logger.info("[$scriptName] ✅ загружен")
                        true
                    }
                }
            }

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsedMs > SLOW_LOAD_THRESHOLD_MS) {
                plugin.logger.warning("[$scriptName] ⏱ Загрузка заняла ${elapsedMs}мс — это дольше обычного, проверь скрипт на тяжёлые операции в top-level коде (сеть, БД синхронно и т.п.)")
            }

            ok
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, "загрузка скрипта", ex)
            false
        }
    }

    fun selfTestCompiler(): Pair<Boolean, String> {
        return try {
            val context = BukkitScriptContext(plugin, "__selftest__")
            val compilationConfig = buildScriptCompilationConfig(plugin.addonManager)
            val evalConfig = ScriptEvaluationConfiguration {
                implicitReceivers(context)
                jvm { baseClassLoader(pluginClassLoader) }
            }
            val source = "6 * 7".toScriptSource("ksl_selftest.kts")
            val result = host.eval(source, compilationConfig, evalConfig)
            when (result) {
                is ResultWithDiagnostics.Failure -> {
                    val reason = result.reports.joinToString("; ") { formatDiagnostic(it) }
                    false to "компиляция не удалась: $reason"
                }
                is ResultWithDiagnostics.Success -> {
                    val rv = result.value.returnValue
                    when {
                        rv is ResultValue.Error -> false to "выполнение упало: ${rv.error.message}"
                        rv is ResultValue.Value && rv.value == 42 -> true to "OK (6 * 7 = 42)"
                        else -> false to "неожиданный результат: $rv"
                    }
                }
            }
        } catch (ex: Throwable) {
            false to "${ex::class.simpleName}: ${ex.message}"
        }
    }

    private fun checkSandbox(file: File, scriptName: String): Boolean {
        if (!plugin.sandboxEnabled) return true
        val violations = KSLSandboxRules.scan(file)
        violations.firstOrNull()?.let {
            plugin.logger.severe("[$scriptName] 🚫 Sandbox нарушение строка ${it.line}: запрещённый паттерн '${it.pattern}'")
            return false
        }
        return true
    }

    private fun formatDiagnostic(d: ScriptDiagnostic): String {
        val loc = d.location
        return if (loc != null) "строка ${loc.start.line}: ${d.message}" else d.message
    }
}
