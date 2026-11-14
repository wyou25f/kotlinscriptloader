package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLSandboxHardeningTest {

    @Test
    fun `raw plugin and database capabilities are denied to sandboxed scripts`() {
        KSLSandboxAccessPolicy.requireTrustedAccess(scriptName = "trusted.kts", sandboxed = false, capability = "plugin")
        KSLSandboxAccessPolicy.requireTrustedAccess(scriptName = "trusted.kts", sandboxed = false, capability = "database")

        assertThrows(SecurityException::class.java) {
            KSLSandboxAccessPolicy.requireTrustedAccess(scriptName = "untrusted.kts", sandboxed = true, capability = "plugin")
        }
        assertThrows(SecurityException::class.java) {
            KSLSandboxAccessPolicy.requireTrustedAccess(scriptName = "untrusted.kts", sandboxed = true, capability = "database")
        }
    }


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
    fun `sandboxed scripts cannot use console unless command is explicitly allowed`() {
        val blocked = setOf("op", "stop")
        val allow = setOf("say", "tell")

        assertTrue(KSLCommandPolicy.isAllowedForScript("say hello", sandboxed = true, blocked, allow))
        assertFalse(KSLCommandPolicy.isAllowedForScript("op Steve", sandboxed = true, blocked, allow))
        assertFalse(KSLCommandPolicy.isAllowedForScript("give Steve stone", sandboxed = true, blocked, allow))
        assertTrue(KSLCommandPolicy.isAllowedForScript("give Steve stone", sandboxed = false, blocked, allow))
        assertFalse(KSLCommandPolicy.isAllowedForScript(" /minecraft:STOP ", sandboxed = false, blocked, allow))
        assertFalse(KSLCommandPolicy.isAllowedForScript("   ", sandboxed = false, blocked, allow))
    }

    @Test
    fun `script paths must stay inside scripts and cannot traverse symlinks`() {
        val root = java.nio.file.Files.createTempDirectory("ksl-script-root").toFile()
        val outside = java.nio.file.Files.createTempDirectory("ksl-script-outside").toFile()
        try {
            val nested = java.io.File(root, "nested").apply { mkdirs() }
            val normal = java.io.File(nested, "safe.kts").apply { writeText("1") }
            val external = java.io.File(outside, "outside.kts").apply { writeText("2") }

            assertTrue(KSLScriptPathPolicy.isSafeChild(root, normal))
            assertFalse(KSLScriptPathPolicy.isSafeChild(root, external))

            val link = java.io.File(root, "linked.kts").toPath()
            try {
                java.nio.file.Files.createSymbolicLink(link, external.toPath())
                assertFalse(KSLScriptPathPolicy.isSafeChild(root, link.toFile()))
            } catch (_: UnsupportedOperationException) {
                // Symlink creation is not supported by this filesystem.
            } catch (_: java.nio.file.FileSystemException) {
                // Some Windows configurations disable symlink creation for unprivileged users.
            }
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
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
