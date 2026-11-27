package ru.privateserver.ksl

import ru.privateserver.ksl.addon.KSLAddonClassLoader
import ru.privateserver.ksl.api.library.KSLLocalLibraryManager
import ru.privateserver.ksl.script.KSLScriptEngineBackend

import ru.privateserver.ksl.script.KSLCompilationConfigCache
import ru.privateserver.ksl.script.KSLCompileCacheStats
import ru.privateserver.ksl.script.KSLCompileCacheValue

import ru.privateserver.ksl.script.KSLCompileCacheKey
import ru.privateserver.ksl.script.KSLCompiledScriptCache
import ru.privateserver.ksl.script.KSLSourceFingerprint

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
    addonLoadersSnapshot: List<ClassLoader>? = null,
    localLibraryLoaderSnapshot: ClassLoader? = localLibraries?.classLoader
): ScriptCompilationConfiguration =
    ScriptCompilationConfiguration(KSLScriptCompilationConfiguration) {
        val addonImports = addonImportsSnapshot ?: addonManager.getExtraImports()
        if (addonImports.isNotEmpty()) defaultImports(*addonImports.toTypedArray())
        val addonClassLoaders = addonLoadersSnapshot ?: addonManager.getScriptClassLoaders()
        val localLibraryLoader = localLibraryLoaderSnapshot
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
    private val compilationConfigCache = KSLCompilationConfigCache()
    @Volatile private var activeCompilationEnvironmentStamp: String? = null
    private val automaticCompileCacheCapacity =
        ru.privateserver.ksl.rt.KSLRuntimeSizing.recommendedCompileCacheEntries(
            Runtime.getRuntime().maxMemory(),
            runCatching {
                (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: -1L
            }.getOrDefault(-1L)
        )
    private val compiledCache = KSLCompiledScriptCache<KSLCompileCacheValue>(
        plugin.config.getInt("scripts.compile-cache-size", 0).let { configured ->
            if (configured <= 0) automaticCompileCacheCapacity else configured.coerceIn(16, 2048)
        }
    )

    /**
     * Automatic mode trims only the reusable compilation cache, never active script state.
     * Explicit positive config values always win.
     */
    private fun refreshCompileCacheBudget() {
        val configured = plugin.config.getInt("scripts.compile-cache-size", 0)
        if (configured > 0) {
            compiledCache.resize(configured.coerceIn(16, 2048))
            return
        }
        val heap = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val target = ru.privateserver.ksl.rt.KSLRuntimeSizing.recommendedCompileCacheCapacity(
            automaticCompileCacheCapacity,
            heap.used,
            heap.max
        )
        compiledCache.resize(target)
    }

    override fun compileCacheStats(): KSLCompileCacheStats = compiledCache.stats()
    override fun clearCompileCache(): Int = compiledCache.clear()
    override fun invalidateCompileCache(scriptId: String): Int = compiledCache.invalidateScript(scriptId)

    /** Compatibility alias retained for internal integrations. */
    fun invalidateCachedScript(scriptId: String): Int = invalidateCompileCache(scriptId)

    override fun close() {
        compiledCache.clear()
        compilationConfigCache.clear()
        activeCompilationEnvironmentStamp = null
        localLibraries.close()
    }

    private fun compilationConfigCacheEnvironmentMatches(environmentStamp: String): Boolean =
        activeCompilationEnvironmentStamp == environmentStamp

    companion object {
        private const val SLOW_LOAD_THRESHOLD_MS = 2000
    }

    override fun compile(file: File): CompiledUnit {
        refreshCompileCacheBudget()
        val id = plugin.scriptId(file)
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        var sourceStamp = ""
        var pendingLibraryLease: AutoCloseable? = null
        return try {
            // Validation and compilation operate on the same immutable source snapshot.
            val sourceText = file.readText(Charsets.UTF_8)
            sourceStamp = KSLSourceFingerprint.of(sourceText)
            val textIssues = textSandboxIssues(sourceText, id)
            if (textIssues.isNotEmpty()) {
                return CompiledUnit(file, id, null, textIssues, emptyList(), KSLError.SCRIPT_SANDBOX, null, elapsedMs(), sourceStamp)
            }
            val previousLibraryLoader = localLibraries.classLoader
            val (reloadDetail, libraryLease) = localLibraries.reloadAndAcquireForScript(id)
            pendingLibraryLease = libraryLease
            reloadDetail?.let { detail ->
                if (detail.isNotBlank()) plugin.logger.warning("[libs] $detail")
            }
            if (previousLibraryLoader !== localLibraries.classLoader) compiledCache.clear()
            // Snapshot imports and classloaders once so the cache key describes the exact
            // compilation environment used to build the configuration.
            val addonImports = plugin.addonManager.getExtraImports()
            val addonLoaders = plugin.addonManager.getScriptClassLoaders()
            val localLoader = libraryLease?.classLoader
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
            activeCompilationEnvironmentStamp = environmentStamp
            val compilationConfig = compilationConfigCache.getOrBuild(
                environmentStamp,
                builder = {
                    buildScriptCompilationConfig(
                        plugin.addonManager,
                        localLibraries,
                        addonImports,
                        addonLoaders,
                        localLoader
                    )
                },
                onEnvironmentChanged = { compiledCache.clear() }
            )
            val cacheKey = KSLCompileCacheKey(
                id,
                KSLSourceFingerprint.of("$sourceStamp|$environmentStamp")
            )
            compiledCache.get(cacheKey)?.let { cached ->
                if (cached.libraryClassLoader === localLoader) {
                    val leaseForUnit = pendingLibraryLease
                    pendingLibraryLease = null
                    return CompiledUnit(
                        file, id, cached.compiled, emptyList(), cached.warnings,
                        null, null, elapsedMs(), sourceStamp, cached.libraryClassLoader, leaseForUnit
                    )
                }
            }
            compiledCache.invalidateScript(id)
            val compile = {
                plugin.withCompilePermit { runBlocking { scriptCompiler(sourceText.toScriptSource(file.absolutePath), compilationConfig) } }
            }
            val result = plugin.watchdogMonitor?.trackOperation("KSL compile '$id'", compile) ?: compile()
            val warnings = ArrayList<String>()
            val errors = ArrayList<String>()
            result.reports.forEach { diagnostic ->
                when {
                    diagnostic.severity == ScriptDiagnostic.Severity.WARNING ->
                        warnings += formatDiagnostic(diagnostic, file)
                    diagnostic.severity >= ScriptDiagnostic.Severity.ERROR ->
                        errors += formatDiagnostic(diagnostic, file)
                }
            }
            when (result) {
                is ResultWithDiagnostics.Failure -> {
                    runCatching { pendingLibraryLease?.close() }
                    pendingLibraryLease = null
                    CompiledUnit(
                        file, id, null, errors, warnings,
                        KSLError.SCRIPT_COMPILE, null, elapsedMs(), sourceStamp
                    )
                }
                is ResultWithDiagnostics.Success -> {
                    // Do not repopulate the cache with artifacts from a dependency
                    // environment that was replaced while this compilation was running.
                    if (compilationConfigCacheEnvironmentMatches(environmentStamp)) {
                        compiledCache.put(
                            cacheKey,
                            KSLCompileCacheValue(result.value, warnings, localLoader)
                        )
                    }
                    val leaseForUnit = pendingLibraryLease
                    pendingLibraryLease = null
                    CompiledUnit(
                        file, id, result.value, emptyList(), warnings,
                        null, null, elapsedMs(), sourceStamp, localLoader, leaseForUnit
                    )
                }
            }
        } catch (ex: Throwable) {
            runCatching { pendingLibraryLease?.close() }
            pendingLibraryLease = null
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
            // Transfer the compile-time lease on first activation. Transactional rollback
            // may evaluate the same artifact again, in which case it receives a fresh lease.
            val libraryLease = unit.takeLibraryLease()
                ?: localLibraries.acquireForScript(id, unit.libraryClassLoader)
            libraryLease?.let { plugin.scriptRuntimeScope(id).own(it) }
            unit.warnings.forEach { plugin.logger.warning("[$id] ${KSLError.SCRIPT_COMPILE_WARNING.tag} ⚠ $it") }
            val context = BukkitScriptContext(plugin, id)
            plugin.addonManager.notifyContextCreated(context)
            val baseLoader = loaderFor(id, unit.libraryClassLoader)
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

    private fun loaderFor(id: String, libraryLoader: ClassLoader? = localLibraries.classLoader): ClassLoader {
        val addonAwareLoader = KSLAddonClassLoader(
            pluginClassLoader,
            plugin.addonManager.getScriptClassLoaders() + listOfNotNull(libraryLoader)
        )
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
        } finally {
            runCatching { plugin.closeScriptRuntimeScope("__selftest__") }
        }
    }

    override fun sandboxProbe(): Pair<Boolean, String> {
        if (!plugin.isSandboxed("__sandbox_probe__")) {
            return true to "Sandbox выключена или probe script доверенный; проверка ограничений пропущена"
        }
        // The probe does not activate a generation, so its classloader lease is closed below.
        val libraryLease = localLibraries.acquireForScript("__sandbox_probe__")
        return try {
            val context = BukkitScriptContext(plugin, "__sandbox_probe__")
            // Use the exact effective policy and parent loader used by normal script evaluation.
            val loader = loaderFor("__sandbox_probe__", libraryLease?.classLoader)
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
        } finally {
            runCatching { plugin.closeScriptRuntimeScope("__sandbox_probe__") }
            // Probe scopes are short-lived and do not participate in generation activation.
            // Release their library lease explicitly rather than retaining it until shutdown.
            runCatching { libraryLease?.close() }
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
