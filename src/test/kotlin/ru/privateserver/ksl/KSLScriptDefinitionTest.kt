package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.api.KSL_SCRIPT_CONTEXT_CLASS
import ru.privateserver.ksl.api.KSL_SCRIPT_DEFAULT_IMPORTS
import ru.privateserver.ksl.api.KSL_SCRIPT_FILE_EXTENSION
import ru.privateserver.ksl.api.KSL_SCRIPT_JVM_TARGET
import kotlin.script.experimental.annotations.KotlinScript

class KSLScriptDefinitionTest {
    @Test
    fun `script template exposes the canonical runtime definition to IDE tooling`() {
        val definition = KSLScript::class.java.getAnnotation(KotlinScript::class.java)

        assertEquals(KSL_SCRIPT_FILE_EXTENSION, definition.fileExtension)
        assertEquals(KSLScriptCompilationConfiguration::class, definition.compilationConfiguration)
        assertEquals("21", KSL_SCRIPT_JVM_TARGET)
        assertEquals(BukkitScriptContext::class.java.name, KSL_SCRIPT_CONTEXT_CLASS)
        assertTrue("ru.privateserver.ksl.dsl.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("org.bukkit.entity.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
    }
}
