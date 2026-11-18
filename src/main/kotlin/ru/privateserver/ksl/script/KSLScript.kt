package ru.privateserver.ksl

import ru.privateserver.ksl.api.KSL_SCRIPT_DEFAULT_IMPORTS
import ru.privateserver.ksl.api.KSL_SCRIPT_FILE_EXTENSION
import ru.privateserver.ksl.api.KSL_SCRIPT_JVM_TARGET
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm

/**
 * The canonical script configuration shared by the KSL runtime and IntelliJ's
 * Kotlin scripting integration. Addon imports are appended by the runtime.
 */
object KSLScriptCompilationConfiguration : ScriptCompilationConfiguration({
    implicitReceivers(BukkitScriptContext::class)
    compilerOptions("-jvm-target", KSL_SCRIPT_JVM_TARGET)
    defaultImports(*KSL_SCRIPT_DEFAULT_IMPORTS.toTypedArray())
    jvm {
        dependenciesFromClassloader(
            classLoader = BukkitScriptContext::class.java.classLoader,
            wholeClasspath = true
        )
    }
})

@KotlinScript(
    fileExtension = KSL_SCRIPT_FILE_EXTENSION,
    compilationConfiguration = KSLScriptCompilationConfiguration::class
)
abstract class KSLScript(@Suppress("UNUSED_PARAMETER") source: SourceCode)
