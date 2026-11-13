package ru.privateserver.ksl

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

/**
 * Builds the runtime configuration from the same canonical definition IntelliJ
 * discovers. Addon-provided imports remain runtime-only and are appended here.
 */
fun buildScriptCompilationConfig(addonManager: KSLAddonManager): ScriptCompilationConfiguration =
    ScriptCompilationConfiguration(KSLScriptCompilationConfiguration) {
        val addonImports = addonManager.getExtraImports()
        if (addonImports.isNotEmpty()) defaultImports(*addonImports.toTypedArray())
    }

class ScriptRunner(private val plugin: KotlinScriptLoaderPlugin) {

    private val host = BasicJvmScriptingHost()
    private val scriptCompiler = JvmScriptCompiler()
    private val evaluator = BasicJvmScriptEvaluator()
    private val pluginClassLoader = plugin::class.java.classLoader

    companion object {
        private const val SLOW_LOAD_THRESHOLD_MS = 2000
    }

    fun compile(file: File): CompiledUnit {
        val id = plugin.scriptId(file)
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        return try {
            val textIssues = textSandboxIssues(file, id)
            if (textIssues.isNotEmpty()) {
                return CompiledUnit(file, id, null, textIssues, emptyList(), KSLError.SCRIPT_SANDBOX, null, elapsedMs())
            }

            val compilationConfig = buildScriptCompilationConfig(plugin.addonManager)
            val compile = {
                plugin.withCompilePermit { runBlocking { scriptCompiler(file.toScriptSource(), compilationConfig) } }
            }
            val result = plugin.watchdogMonitor?.trackOperation("KSL compile '$id'", compile) ?: compile()
            val warnings = result.reports
                .filter { it.severity == ScriptDiagnostic.Severity.WARNING }
                .map { formatDiagnostic(it) }
            when (result) {
                is ResultWithDiagnostics.Failure -> CompiledUnit(
                    file, id, null,
                    result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.map { formatDiagnostic(it) },
                    warnings, KSLError.SCRIPT_COMPILE, null, elapsedMs()
                )
                is ResultWithDiagnostics.Success -> CompiledUnit(file, id, result.value, emptyList(), warnings, null, null, elapsedMs())
            }
        } catch (ex: Throwable) {
            CompiledUnit(file, id, null, listOf("${ex::class.simpleName}: ${ex.message}"), emptyList(), KSLError.SCRIPT_RUNTIME, ex, elapsedMs())
        }
    }

    fun reportFailure(unit: CompiledUnit) {
        val id = unit.id
        unit.warnings.forEach { plugin.logger.warning("[$id] ${KSLError.SCRIPT_COMPILE_WARNING.tag} ⚠ $it") }
        when (unit.failure) {
            KSLError.SCRIPT_SANDBOX -> {
                plugin.logScriptProblem("[$id] ${KSLError.SCRIPT_SANDBOX.tag} 🚫 Sandbox не пропустил скрипт, нарушений: ${unit.errors.size}")
                KSLReport.compactErrors(unit.errors).forEach { plugin.logScriptProblem("  $it") }
            }
            KSLError.SCRIPT_COMPILE -> {
                plugin.logScriptProblem("[$id] ${KSLError.SCRIPT_COMPILE.tag} ❌ Скрипт не скомпилировался, ошибок: ${unit.errors.size}")
                KSLReport.compactErrors(unit.errors).forEach { plugin.logScriptProblem("  $it") }
            }
            else -> unit.crash?.let { KSLErrors.log(plugin, id, "компиляция скрипта", it) }
                ?: unit.errors.forEach { plugin.logScriptProblem("[$id] ${KSLError.SCRIPT_RUNTIME.tag} $it") }
        }
        if (unit.crash == null) KSLErrors.recordFailure(id, "компиляция", unit.errors, unit.failure ?: KSLError.SCRIPT_COMPILE)
    }

    fun evaluate(unit: CompiledUnit): Boolean {
        val compiled = unit.compiled ?: return false
        val id = unit.id
        return try {
            unit.warnings.forEach { plugin.logger.warning("[$id] ${KSLError.SCRIPT_COMPILE_WARNING.tag} ⚠ $it") }
            val context = BukkitScriptContext(plugin, id)
            plugin.addonManager.notifyContextCreated(context)
            val baseLoader = loaderFor(id)
            val evalConfig = ScriptEvaluationConfiguration {
                implicitReceivers(context)
                jvm { baseClassLoader(baseLoader) }
            }
            val startedAt = System.nanoTime()
            val evaluate = { runBlocking { evaluator(compiled, evalConfig) } }
            val result = plugin.watchdogMonitor?.trackOperation("KSL evaluate '$id'", evaluate) ?: evaluate()
            val ok = when (result) {
                is ResultWithDiagnostics.Failure -> {
                    plugin.logScriptProblem("[$id] ${KSLError.SCRIPT_RUNTIME.tag} ❌ Не удалось выполнить скрипт:")
                    KSLReport.compactErrors(
                        result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.map { formatDiagnostic(it) }
                    ).forEach { plugin.logScriptProblem("  $it") }
                    false
                }
                is ResultWithDiagnostics.Success -> {
                    val rv = result.value.returnValue
                    if (rv is ResultValue.Error) {
                        KSLErrors.log(plugin, id, "загрузка скрипта", rv.error)
                        false
                    } else {
                        plugin.logger.info("[$id] ✅ загружен")
                        true
                    }
                }
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsedMs > SLOW_LOAD_THRESHOLD_MS) {
                plugin.logger.warning("[$id] ${KSLError.SCRIPT_SLOW_LOAD.tag} ⏱ Выполнение top-level кода заняло ${elapsedMs}мс — это дольше обычного, проверь скрипт на тяжёлые операции (сеть, БД синхронно и т.п.)")
            }
            ok
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, id, "загрузка скрипта", ex)
            false
        }
    }

    fun loadScript(file: File): Boolean {
        val unit = compile(file)
        if (!unit.ok) {
            reportFailure(unit)
            return false
        }
        return evaluate(unit)
    }

    private fun loaderFor(id: String): ClassLoader {
        if (!plugin.isSandboxed(id) || !plugin.sandboxStrict) return pluginClassLoader
        return KSLSandboxClassLoader(pluginClassLoader, plugin.sandboxDenyList, id) { denied ->
            plugin.logScriptProblem("[$id] ${KSLError.SANDBOX_CLASS_DENIED.tag} 🚫 Sandbox: скрипт обратился к запрещённому классу $denied")
        }
    }

    private val warmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    fun warmUp() {
        if (warmedUp.compareAndSet(false, true)) selfTestCompiler()
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
            val result = plugin.withCompilePermit { host.eval(source, compilationConfig, evalConfig) }
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

    fun sandboxProbe(): Pair<Boolean, String> {
        return try {
            val context = BukkitScriptContext(plugin, "__sandbox_probe__")
            val loader = KSLSandboxClassLoader(pluginClassLoader, KSLClassDenyList(), "__sandbox_probe__") { }
            val compilationConfig = buildScriptCompilationConfig(plugin.addonManager)
            val evalConfig = ScriptEvaluationConfiguration {
                implicitReceivers(context)
                jvm { baseClassLoader(loader) }
            }
            val probe = "try { Runtime.getRuntime().availableProcessors(); \"open\" } catch (t: Throwable) { \"blocked\" }"
            val result = plugin.withCompilePermit { host.eval(probe.toScriptSource("ksl_sandbox_probe.kts"), compilationConfig, evalConfig) }
            when (result) {
                is ResultWithDiagnostics.Failure ->
                    false to "проба не скомпилировалась: ${result.reports.joinToString("; ") { formatDiagnostic(it) }}"
                is ResultWithDiagnostics.Success -> {
                    val rv = result.value.returnValue
                    when {
                        rv is ResultValue.Value && rv.value == "blocked" -> true to "класс Runtime недоступен скрипту"
                        rv is ResultValue.Value && rv.value == "open" ->
                            false to "скрипт смог загрузить Runtime: изоляция классов не работает, используй security.sandbox-level: text"
                        rv is ResultValue.Error -> false to "проба упала: ${rv.error.message}"
                        else -> false to "неожиданный результат: $rv"
                    }
                }
            }
        } catch (ex: Throwable) {
            false to "${ex::class.simpleName}: ${ex.message}"
        }
    }

    private fun textSandboxIssues(file: File, id: String): List<String> {
        if (!plugin.isSandboxed(id)) return emptyList()
        return KSLSandboxRules.scan(file, plugin.sandboxPatterns)
            .map { "строка ${it.line}: запрещённый паттерн '${it.pattern}'" }
    }

    private fun formatDiagnostic(d: ScriptDiagnostic): String {
        val loc = d.location
        return if (loc != null) "строка ${loc.start.line}: ${d.message}" else d.message
    }
}
