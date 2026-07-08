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

    private val sandboxForbidden = listOf(
        "java.lang.Runtime",
        "Runtime.getRuntime",
        "Runtime.exec",
        "java.lang.ProcessBuilder",
        "ProcessBuilder(",
        "java.lang.reflect.",
        "Class.forName",
        ".getDeclaredMethod",
        ".getDeclaredField",
        ".getDeclaredConstructor",
        "ClassLoader",
        "System.exit",
        "System.halt",
        "sun.misc.Unsafe",
        "jdk.internal.misc.Unsafe",
        "java.rmi.",
        "java.net.Socket",
        "java.net.ServerSocket",
        "javax.script.",
        "org.objectweb.asm.",
        "javassist.",
        "net.bytebuddy."
    )

    fun loadScript(file: File): Boolean {
        val scriptName = file.nameWithoutExtension
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

            when (result) {
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
                        logScriptException(scriptName, file.name, rv.error)
                        false
                    } else {
                        plugin.logger.info("[$scriptName] ✅ загружен")
                        true
                    }
                }
            }
        } catch (ex: Throwable) {
            plugin.logger.severe("[$scriptName] ❌ ${ex::class.simpleName}: ${ex.message}")
            false
        }
    }

    private fun checkSandbox(file: File, scriptName: String): Boolean {
        if (!plugin.sandboxEnabled) return true
        val lines = file.readLines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
            for (pattern in sandboxForbidden) {
                if (line.contains(pattern)) {
                    plugin.logger.severe("[$scriptName] 🚫 Sandbox нарушение строка ${index + 1}: запрещённый паттерн '$pattern'")
                    return false
                }
            }
        }
        return true
    }

    private fun formatDiagnostic(d: ScriptDiagnostic): String {
        val loc = d.location
        return if (loc != null) "строка ${loc.start.line}: ${d.message}" else d.message
    }

    private fun logScriptException(scriptName: String, fileName: String, ex: Throwable) {
        val cause = ex.cause ?: ex
        plugin.logger.severe("[$scriptName] ❌ ${cause::class.simpleName}: ${cause.message}")
        val relevant = cause.stackTrace.filter { frame ->
            frame.fileName?.endsWith(".kts") == true ||
                frame.className.contains(scriptName, ignoreCase = true)
        }
        if (relevant.isNotEmpty()) {
            relevant.take(5).forEach { frame ->
                val line = if (frame.lineNumber > 0) "строка ${frame.lineNumber}" else "строка ?"
                plugin.logger.severe("  → $fileName, $line")
            }
        } else {
            cause.stackTrace.firstOrNull()?.let {
                plugin.logger.severe("  → ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
            }
        }
    }
}
