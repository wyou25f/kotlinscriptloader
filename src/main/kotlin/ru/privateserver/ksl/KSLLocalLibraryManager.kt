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
 * Discover only regular Kotlin sources physically contained in scripts/libs.
 * Symlinked roots, directories and files are excluded to prevent source-path escapes.
 */
internal fun discoverLocalLibrarySources(sourceFolder: File): List<File> {
    if (java.nio.file.Files.isSymbolicLink(sourceFolder.toPath()) || !sourceFolder.isDirectory) {
        return emptyList()
    }
    return sourceFolder.walkTopDown()
        .onEnter { directory ->
            directory == sourceFolder || KSLScriptPathPolicy.isSafeChild(sourceFolder, directory)
        }
        .filter { source ->
            source.isFile &&
                source.extension.equals("kt", ignoreCase = true) &&
                KSLScriptPathPolicy.isSafeChild(sourceFolder, source)
        }
        .sortedBy { it.relativeTo(sourceFolder).invariantSeparatorsPath }
        .toList()
}

/**
 * Keeps retired library classloaders available while previously evaluated scripts can still
 * resolve classes lazily. They are closed together during orderly plugin shutdown.
 */
internal class KSLRetiredClassLoaders : AutoCloseable {
    private val loaders = LinkedHashSet<URLClassLoader>()

    @Synchronized
    fun retain(loader: URLClassLoader?) {
        if (loader != null) loaders += loader
    }

    @Synchronized
    override fun close() {
        val closing = loaders.toList()
        loaders.clear()
        closing.forEach { loader -> runCatching { loader.close() } }
    }
}

internal fun localLibrarySourceFingerprint(sources: List<File>, sourceFolder: File): List<String> =
    sources.sortedBy { it.relativeTo(sourceFolder).invariantSeparatorsPath }.map { source ->
        val relativePath = source.relativeTo(sourceFolder).invariantSeparatorsPath
        val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        "$relativePath:${HexFormat.of().formatHex(digest)}"
    }

/**
 * Compiles scripts/libs/**/*.kt as one Kotlin source set. A successful output is
 * exposed to script compilation and evaluation; failed rebuilds keep the last
 * working classloader so a typo in a library does not break live scripts.
 */
internal class KSLLocalLibraryManager(private val plugin: KotlinScriptLoaderPlugin) {
    // Existing scripts may lazily load library classes after a reload. Retired loaders stay
    // open until plugin shutdown, after all script-owned callbacks and tasks are unloaded.
    private val retiredLoaders = KSLRetiredClassLoaders()
    private val sourceFolder = File(plugin.scriptsFolder, "libs").apply { mkdirs() }
    private val outputRoot = File(plugin.dataFolder, "compiled-libraries").apply { mkdirs() }

    @Volatile private var activeLoader: URLClassLoader? = null
    // Remember failed attempts too: unchanged broken sources should not trigger a full compiler run
    // for every script compiled during a reload.
    @Volatile private var attemptedStamp: List<String> = emptyList()

    val classLoader: ClassLoader? get() = activeLoader

    @Synchronized
    fun reloadIfChanged(): String? {
        val sources = discoverLocalLibrarySources(sourceFolder)
        val nextStamp = localLibrarySourceFingerprint(sources, sourceFolder)
        if (nextStamp == attemptedStamp) return null
        attemptedStamp = nextStamp
        if (sources.isEmpty()) {
            activeLoader?.let(retiredLoaders::retain)
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
        previous?.let(retiredLoaders::retain)
        return "Скомпилировано локальных Kotlin-библиотек: ${sources.size} файл(ов)"
    }

    @Synchronized
    fun close() {
        val current = activeLoader
        activeLoader = null
        runCatching { current?.close() }
        retiredLoaders.close()
        runCatching { outputRoot.deleteRecursively() }
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
