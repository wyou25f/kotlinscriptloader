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
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

/**
 * Builds the runtime configuration from the same canonical definition IntelliJ
 * discovers. Addon-provided imports remain runtime-only and are appended here.
 */
internal fun buildScriptCompilationConfig(
    addonManager: KSLAddonManager,
    localLibraries: KSLLocalLibraryManager? = null,
    addonImportsSnapshot: List<String>? = null,
    addonLoadersSnapshot: List<ClassLoader>? = null
): ScriptCompilationConfiguration =
    ScriptCompilationConfiguration(KSLScriptCompilationConfiguration) {
        val addonImports = addonImportsSnapshot ?: addonManager.getExtraImports()
        if (addonImports.isNotEmpty()) defaultImports(*addonImports.toTypedArray())
        val addonClassLoaders = addonLoadersSnapshot ?: addonManager.getScriptClassLoaders()
        val localLibraryLoader = localLibraries?.classLoader
        if (addonClassLoaders.isNotEmpty() || localLibraryLoader != null) {
            jvm {
                addonClassLoaders.forEach { classLoader ->
                    dependenciesFromClassloader(classLoader = classLoader, wholeClasspath = true)
                }
                localLibraryLoader?.let { dependenciesFromClassloader(classLoader = it, wholeClasspath = true) }
            }
        }
    }

internal class ScriptRunner(private val plugin: KotlinScriptLoaderPlugin) : KSLScriptEngineBackend {

    private val host = BasicJvmScriptingHost()
    private val scriptCompiler = JvmScriptCompiler()
    private val evaluator = BasicJvmScriptEvaluator()
    private val pluginClassLoader = plugin::class.java.classLoader
    private val localLibraries = KSLLocalLibraryManager(plugin)
    private val compiledCache = KSLCompiledScriptCache<KSLCompileCacheValue>(
        plugin.config.getInt("scripts.compile-cache-size", 128).coerceIn(16, 2048)
    )

    override fun compileCacheStats(): KSLCompileCacheStats = compiledCache.stats()
    override fun clearCompileCache(): Int = compiledCache.clear()
    fun invalidateCachedScript(scriptId: String): Int = compiledCache.invalidateScript(scriptId)

    companion object {
        private const val SLOW_LOAD_THRESHOLD_MS = 2000
    }

    override fun compile(file: File): CompiledUnit {
        val id = plugin.scriptId(file)
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        var sourceStamp = ""
        return try {
            // Validation and compilation operate on the same immutable source snapshot.
            val sourceText = file.readText(Charsets.UTF_8)
            sourceStamp = KSLSourceFingerprint.of(sourceText)
            val textIssues = textSandboxIssues(sourceText, id)
            if (textIssues.isNotEmpty()) {
                return CompiledUnit(file, id, null, textIssues, emptyList(), KSLError.SCRIPT_SANDBOX, null, elapsedMs(), sourceStamp)
            }
            val previousLibraryLoader = localLibraries.classLoader
            localLibraries.reloadIfChanged()?.let { detail ->
                if (detail.isNotBlank()) plugin.logger.warning("[libs] $detail")
            }
            if (previousLibraryLoader !== localLibraries.classLoader) compiledCache.clear()
            // Snapshot imports and classloaders once so the cache key describes the exact
            // compilation environment used to build the configuration.
            val addonImports = plugin.addonManager.getExtraImports()
            val addonLoaders = plugin.addonManager.getScriptClassLoaders()
            val compilationConfig = buildScriptCompilationConfig(
                plugin.addonManager, localLibraries, addonImports, addonLoaders
            )
            val localLoader = localLibraries.classLoader
            val environmentStamp = buildString {
                append(KSL.API_VERSION).append('|')
                addonImports.forEach { append(it).append('\u001f') }
                addonLoaders.forEach {
                    append(it.javaClass.name).append('@').append(System.identityHashCode(it)).append('|')
                }
                localLoader?.let {
                    append(it.javaClass.name).append('@').append(System.identityHashCode(it))
                }
            }
            val cacheKey = KSLCompileCacheKey(
                id,
                KSLSourceFingerprint.of("$sourceStamp|$environmentStamp")
            )
            compiledCache.get(cacheKey)?.let { cached ->
                return CompiledUnit(
                    file, id, cached.compiled, emptyList(), cached.warnings,
                    null, null, elapsedMs(), sourceStamp
                )
            }
            compiledCache.invalidateScript(id)
            val compile = {
                plugin.withCompilePermit { runBlocking { scriptCompiler(sourceText.toScriptSource(file.absolutePath), compilationConfig) } }
            }
            val result = plugin.watchdogMonitor?.trackOperation("KSL compile '$id'", compile) ?: compile()
            val warnings = result.reports.filter { it.severity == ScriptDiagnostic.Severity.WARNING }
                .map { formatDiagnostic(it, file) }
            when (result) {
                is ResultWithDiagnostics.Failure -> CompiledUnit(
                    file, id, null,
                    result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.map { formatDiagnostic(it, file) },
                    warnings, KSLError.SCRIPT_COMPILE, null, elapsedMs(), sourceStamp
                )
                is ResultWithDiagnostics.Success -> {
                    compiledCache.put(cacheKey, KSLCompileCacheValue(result.value, warnings))
                    CompiledUnit(file, id, result.value, emptyList(), warnings, null, null, elapsedMs(), sourceStamp)
                }
            }
        } catch (ex: Throwable) {
            CompiledUnit(file, id, null, listOf("${file.name}: ${ex::class.simpleName}: ${ex.message}"), emptyList(), KSLError.SCRIPT_COMPILER_CRASH, ex, elapsedMs(), sourceStamp)
        }
    }
    override fun reportFailure(unit: CompiledUnit) {
        val id = unit.id
        val failureCode = unit.failure ?: KSLError.SCRIPT_COMPILE
        unit.warnings.forEach { plugin.logger.warning("[$id] ${KSLError.SCRIPT_COMPILE_WARNING.tag} ⚠ $it") }
        when (failureCode) {
            KSLError.SCRIPT_SANDBOX -> {
                plugin.logScriptProblem("[$id] ${KSLError.SCRIPT_SANDBOX.tag} 🚫 Sandbox не пропустил скрипт, нарушений: ${unit.errors.size}")
                KSLReport.compactErrors(unit.errors).forEach { plugin.logScriptProblem("  $it") }
            }
            KSLError.SCRIPT_COMPILE -> {
                plugin.logScriptProblem("[$id] ${KSLError.SCRIPT_COMPILE.tag} ❌ Скрипт не скомпилировался, ошибок: ${unit.errors.size}")
                KSLReport.compactErrors(unit.errors).forEach { plugin.logScriptProblem("  $it") }
            }
            else -> unit.crash?.let { KSLErrors.log(plugin, id, "компиляция скрипта", it, failureCode) }
                ?: unit.errors.forEach { plugin.logScriptProblem("[$id] ${failureCode.tag} $it") }
        }
        if (unit.crash == null) KSLErrors.recordFailure(id, plugin.message("console.script.compile-location"), unit.errors, failureCode)
    }

    override fun evaluate(unit: CompiledUnit): Boolean {
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
                    val errors = result.reports
                        .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                        .map { formatDiagnostic(it, unit.file) }
                    plugin.logScriptProblem("[$id] ${KSLError.SCRIPT_EVALUATION_FAILED.tag} ❌ Не удалось выполнить скрипт:")
                    KSLReport.compactErrors(errors).forEach { plugin.logScriptProblem("  $it") }
                    KSLErrors.recordFailure(id, plugin.message("console.script.execution-location"), errors, KSLError.SCRIPT_EVALUATION_FAILED)
                    false
                }
                is ResultWithDiagnostics.Success -> {
                    val rv = result.value.returnValue
                    if (rv is ResultValue.Error) {
                        KSLErrors.log(plugin, id, "загрузка скрипта", rv.error, KSLError.SCRIPT_RUNTIME)
                        false
                    } else {
                        plugin.logger.info("[$id] ✅ ${plugin.message("console.script.loaded")}")
                        true
                    }
                }
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsedMs > SLOW_LOAD_THRESHOLD_MS) {
                plugin.logger.warning("[$id] ${KSLError.SCRIPT_SLOW_LOAD.tag} ${plugin.message("console.script.slow-load", "milliseconds" to elapsedMs)}")
            }
            ok
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, id, "загрузка скрипта", ex, KSLError.SCRIPT_RUNTIME)
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
        val addonAwareLoader = KSLAddonClassLoader(pluginClassLoader, plugin.addonManager.getScriptClassLoaders() + listOfNotNull(localLibraries.classLoader))
        if (!plugin.isSandboxed(id) || !plugin.sandboxStrict) return addonAwareLoader
        return KSLSandboxClassLoader(addonAwareLoader, plugin.sandboxDenyList, id) { denied ->
            plugin.logScriptProblem("[$id] ${KSLError.SANDBOX_CLASS_DENIED.tag} 🚫 Sandbox: скрипт обратился к запрещённому классу $denied")
        }
    }

    private val warmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun warmUp() {
        if (warmedUp.compareAndSet(false, true)) selfTestCompiler()
    }

    override fun selfTestCompiler(): Pair<Boolean, String> {
        return try {
            val context = BukkitScriptContext(plugin, "__selftest__")
            val compilationConfig = buildScriptCompilationConfig(plugin.addonManager, localLibraries)
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

    override fun sandboxProbe(): Pair<Boolean, String> {
        if (!plugin.isSandboxed("__sandbox_probe__")) {
            return true to "Sandbox выключена или probe script доверенный; проверка ограничений пропущена"
        }
        return try {
            val context = BukkitScriptContext(plugin, "__sandbox_probe__")
            // Use the exact effective policy and parent loader used by normal script evaluation.
            val loader = loaderFor("__sandbox_probe__")
            val compilationConfig = buildScriptCompilationConfig(plugin.addonManager)
            val evalConfig = ScriptEvaluationConfiguration {
                implicitReceivers(context)
                jvm { baseClassLoader(loader) }
            }
            val probe = if (plugin.sandboxStrict) {
                """
                    val runtimeBlocked = try {
                        Runtime.getRuntime().availableProcessors()
                        false
                    } catch (_: Throwable) {
                        true
                    }
                    val pluginBlocked = runCatching { plugin }.exceptionOrNull() is SecurityException
                    val databaseBlocked = runCatching { database }.exceptionOrNull() is SecurityException
                    if (runtimeBlocked && pluginBlocked && databaseBlocked) "blocked" else "open"
                """.trimIndent()
            } else {
                """
                    val pluginBlocked = runCatching { plugin }.exceptionOrNull() is SecurityException
                    val databaseBlocked = runCatching { database }.exceptionOrNull() is SecurityException
                    if (pluginBlocked && databaseBlocked) "blocked" else "open"
                """.trimIndent()
            }
            val result = plugin.withCompilePermit { host.eval(probe.toScriptSource("ksl_sandbox_probe.kts"), compilationConfig, evalConfig) }
            when (result) {
                is ResultWithDiagnostics.Failure ->
                    false to "проба не скомпилировалась: ${result.reports.joinToString("; ") { formatDiagnostic(it) }}"
                is ResultWithDiagnostics.Success -> {
                    val rv = result.value.returnValue
                    when {
                        rv is ResultValue.Value && rv.value == "blocked" -> true to if (plugin.sandboxStrict) "Runtime, raw plugin и raw database недоступны sandboxed-скрипту" else "raw plugin и raw database недоступны; текстовый режим не включает запрет классов"
                        rv is ResultValue.Value && rv.value == "open" ->
                            false to "sandbox probe не смог заблокировать ожидаемые raw-доступы или классы; проверь security.sandbox-level и sandbox access policy"
                        rv is ResultValue.Error -> false to "проба упала: ${rv.error.message}"
                        else -> false to "неожиданный результат: $rv"
                    }
                }
            }
        } catch (ex: Throwable) {
            false to "${ex::class.simpleName}: ${ex.message}"
        }
    }

    private fun textSandboxIssues(source: String, id: String): List<String> {
        if (!plugin.isSandboxed(id)) return emptyList()
        return KSLSandboxRules.scanSource(source, plugin.sandboxPatterns)
            .map { "строка ${it.line}: запрещённый паттерн '${it.pattern}'" }
    }

    private fun formatDiagnostic(d: ScriptDiagnostic, file: File? = null): String {
        val source = file?.name?.let { "$it: " }.orEmpty()
        val loc = d.location
        return if (loc != null) "${source}строка ${loc.start.line}: ${d.message}" else "$source${d.message}"
    }
}
