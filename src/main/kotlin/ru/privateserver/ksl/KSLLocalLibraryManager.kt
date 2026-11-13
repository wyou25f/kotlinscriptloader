package ru.privateserver.ksl

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Compiles scripts/libs/**/*.kt as one Kotlin source set. A successful output is
 * exposed to script compilation and evaluation; failed rebuilds keep the last
 * working classloader so a typo in a library does not break live scripts.
 */
internal fun localLibrarySourceFingerprint(sources: List<File>, sourceFolder: File): List<String> =
    sources.sortedBy { it.relativeTo(sourceFolder).invariantSeparatorsPath }.map { source ->
        val relativePath = source.relativeTo(sourceFolder).invariantSeparatorsPath
        val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        "$relativePath:${HexFormat.of().formatHex(digest)}"
    }

internal class KSLLocalLibraryManager(private val plugin: KotlinScriptLoaderPlugin) {
    private val sourceFolder = File(plugin.scriptsFolder, "libs").apply { mkdirs() }
    private val outputRoot = File(plugin.dataFolder, "compiled-libraries").apply { mkdirs() }

    @Volatile private var activeLoader: URLClassLoader? = null
    // Remember failed attempts too: unchanged broken sources should not trigger a full compiler run
    // for every script compiled during a reload.
    @Volatile private var attemptedStamp: List<String> = emptyList()

    val classLoader: ClassLoader? get() = activeLoader

    @Synchronized
    fun reloadIfChanged(): String? {
        val sources = sourceFolder.walkTopDown()
            .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }
            .sortedBy { it.relativeTo(sourceFolder).invariantSeparatorsPath }
            .toList()
        val nextStamp = localLibrarySourceFingerprint(sources, sourceFolder)
        if (nextStamp == attemptedStamp) return null
        attemptedStamp = nextStamp
        if (sources.isEmpty()) {
            activeLoader?.close()
            activeLoader = null
            return null
        }

        val output = File(outputRoot, "generation-${System.nanoTime()}").apply { mkdirs() }
        val messages = ByteArrayOutputStream()
        val arguments = K2JVMCompilerArguments().apply {
            destination = output.absolutePath
            classpath = compilerClasspath()
            jvmTarget = "21"
            noStdlib = true
            noReflect = true
            moduleName = "ksl-local-libraries"
            freeArgs = sources.map { it.absolutePath }
        }
        val exitCode = runCatching {
            K2JVMCompiler().exec(
                PrintingMessageCollector(PrintStream(messages), MessageRenderer.PLAIN_RELATIVE_PATHS, false),
                Services.EMPTY,
                arguments
            )
        }.getOrElse { failure ->
            output.deleteRecursively()
            return "Не удалось запустить Kotlin-компилятор локальных библиотек: ${failure.message}"
        }

        if (exitCode != org.jetbrains.kotlin.cli.common.ExitCode.OK) {
            output.deleteRecursively()
            val detail = messages.toString(Charsets.UTF_8).trim()
            return "Локальные библиотеки не скомпилированы; используется предыдущая версия. ${detail.ifBlank { "Код компилятора: $exitCode" }}"
        }

        val nextLoader = URLClassLoader(arrayOf(output.toURI().toURL()), plugin::class.java.classLoader)
        val previous = activeLoader
        activeLoader = nextLoader
        runCatching { previous?.close() }
        return "Скомпилировано локальных Kotlin-библиотек: ${sources.size} файл(ов)"
    }

    private fun compilerClasspath(): String {
        val paths = linkedSetOf<String>()
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter(String::isNotBlank)
            ?.let(paths::addAll)

        var loader: ClassLoader? = plugin::class.java.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                loader.getURLs().mapNotNull { runCatching { File(it.toURI()).absolutePath }.getOrNull() }
                    .forEach(paths::add)
            }
            loader = loader.parent
        }
        return paths.joinToString(File.pathSeparator)
    }
}
