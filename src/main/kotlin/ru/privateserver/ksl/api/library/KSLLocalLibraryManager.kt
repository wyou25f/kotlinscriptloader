package ru.privateserver.ksl.api.library

import ru.privateserver.ksl.KotlinScriptLoaderPlugin
import ru.privateserver.ksl.script.KSLScriptPathPolicy
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
import java.util.Collections
import java.util.HexFormat
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * Tracks which live scripts may still resolve classes through a library generation.
 *
 * A retired URLClassLoader is closed as soon as its final script releases it. This
 * preserves lazy class resolution for live scripts without retaining every historical
 * library generation until the server shuts down.
 */
internal class KSLRetiredClassLoaders : AutoCloseable {
    private val loaders = LinkedHashSet<URLClassLoader>()
    private val retired = LinkedHashSet<URLClassLoader>()
    private val owners = HashMap<String, URLClassLoader>()
    private val references = HashMap<URLClassLoader, Int>()
    // Weak keys prevent the guard itself from retaining already-closed classloaders.
    private val closed = Collections.newSetFromMap(WeakHashMap<URLClassLoader, Boolean>())

    @Synchronized
    fun acquire(owner: String, loader: URLClassLoader?) {
        require(owner.isNotBlank()) { "owner must not be blank" }
        val previous = owners[owner]
        if (previous === loader) return
        if (previous != null) releaseLocked(owner, previous)
        if (loader == null) return
        check(loader !in closed) { "cannot acquire a closed classloader" }
        loaders += loader
        owners[owner] = loader
        references[loader] = (references[loader] ?: 0) + 1
    }

    /** Marks a generation obsolete; it remains open only while scripts still reference it. */
    @Synchronized
    fun retire(loader: URLClassLoader?) {
        if (loader == null || loader in retired) return
        loaders += loader
        retired += loader
        closeIfUnused(loader)
    }

    /** Compatibility alias for callers that only need to retire a loader. */
    fun retain(loader: URLClassLoader?) = retire(loader)

    @Synchronized
    fun release(owner: String): Boolean {
        val loader = owners[owner] ?: return false
        releaseLocked(owner, loader)
        return true
    }

    @Synchronized
    fun retainedGenerationCount(): Int = retired.size

    @Synchronized
    fun activeOwnerCount(): Int = owners.size

    private fun releaseLocked(owner: String, loader: URLClassLoader) {
        owners.remove(owner)
        val remaining = (references[loader] ?: 1) - 1
        if (remaining <= 0) references.remove(loader) else references[loader] = remaining
        closeIfUnused(loader)
    }

    private fun closeIfUnused(loader: URLClassLoader) {
        if (loader !in retired || (references[loader] ?: 0) > 0) return
        retired.remove(loader)
        loaders.remove(loader)
        references.remove(loader)
        closed += loader
        runCatching { loader.close() }
    }

    @Synchronized
    override fun close() {
        val closing = loaders.toList()
        loaders.clear()
        retired.clear()
        owners.clear()
        references.clear()
        closed.addAll(closing)
        closing.forEach { loader -> runCatching { loader.close() } }
    }
}

internal fun localLibrarySourceFingerprint(sources: List<File>, sourceFolder: File): List<String> =
    sources.sortedBy { it.relativeTo(sourceFolder).invariantSeparatorsPath }.map { source ->
        val relativePath = source.relativeTo(sourceFolder).invariantSeparatorsPath
        val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        "$relativePath:${HexFormat.of().formatHex(digest)}"
    }

/** A generation-owned reference to one local-library classloader. */
internal class KSLLocalLibraryLease(
    val classLoader: ClassLoader,
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }
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
    private val leaseSequence = AtomicLong()

    val classLoader: ClassLoader? get() = activeLoader

    /**
     * Reloads changed libraries and pins the resulting generation under one manager lock.
     * This prevents another concurrent compile from retiring the loader between snapshot
     * creation and lease acquisition.
     */
    @Synchronized
    fun reloadAndAcquireForScript(scriptId: String): Pair<String?, KSLLocalLibraryLease?> {
        val detail = reloadIfChanged()
        return detail to acquireForScript(scriptId, activeLoader)
    }

    /**
     * Pins the current library generation to the caller's script generation. Candidate and
     * previous generations can coexist during a transactional reload, so every evaluation
     * receives its own lease instead of replacing a script-ID-wide reference.
     */
    @Synchronized
    fun acquireForScript(
        scriptId: String,
        generationLoader: ClassLoader? = activeLoader
    ): KSLLocalLibraryLease? {
        require(scriptId.isNotBlank()) { "scriptId must not be blank" }
        val loader = generationLoader as? URLClassLoader ?: return null
        val leaseOwner = "$scriptId#${leaseSequence.incrementAndGet()}"
        retiredLoaders.acquire(leaseOwner, loader)
        return KSLLocalLibraryLease(loader) { retiredLoaders.release(leaseOwner) }
    }

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
        previous?.let(retiredLoaders::retire)
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
