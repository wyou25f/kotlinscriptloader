package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLSandboxHardeningTest {

    @Test
    fun `denies process reflection file network and raw thread APIs`() {
        val denyList = KSLClassDenyList()

        listOf(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.reflect.Method",
            "java.lang.reflect.Type",
            "java.io.File",
            "java.nio.file.Files",
            "java.nio.channels.FileChannel",
            "java.net.Socket",
            "java.net.URL",
            "java.lang.Thread",
            "java.util.concurrent.Executors",
        ).forEach { assertTrue(denyList.denies(it), "Expected $it to be denied") }

        // JVM lambda linkage needs this type; the public MethodHandles entry point remains denied.
        assertFalse(denyList.denies("java.lang.invoke.MethodHandles\$Lookup"))
        assertFalse(denyList.denies("java.lang.String"))
        assertFalse(denyList.denies("java.util.List"))
    }

    @Test
    fun `allows exact class exceptions without allowing sibling classes`() {
        val denyList = KSLClassDenyList(allowed = listOf("java.io.File"))

        assertFalse(denyList.denies("java.io.File"))
        assertTrue(denyList.denies("java.io.FileInputStream"))
    }

    @Test
    fun `allows an explicitly trusted package prefix`() {
        val denyList = KSLClassDenyList(allowed = listOf("java.io."))

        assertFalse(denyList.denies("java.io.File"))
        assertFalse(denyList.denies("java.io.FileInputStream"))
        assertTrue(denyList.denies("java.net.Socket"))
    }

    @Test
    fun `class loader blocks denied classes and reports each name once`() {
        val denied = mutableListOf<String>()
        val loader = KSLSandboxClassLoader(
            javaClass.classLoader,
            KSLClassDenyList(),
            "sandbox-test",
            denied::add
        )

        assertThrows(ClassNotFoundException::class.java) { loader.loadClass("java.lang.Runtime") }
        assertThrows(ClassNotFoundException::class.java) { loader.loadClass("java.lang.Runtime") }
        assertEquals(listOf("java.lang.Runtime"), denied)
        assertSame(String::class.java, loader.loadClass("java.lang.String"))
    }
}
