package ru.privateserver.ksl

import ru.privateserver.ksl.script.KSLScriptPathPolicy
import ru.privateserver.ksl.api.library.discoverLocalLibrarySources

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
    fun `internal plugin capability is not exposed as a normal JVM accessor`() {
        val getters = BukkitScriptContext::class.java.declaredMethods.filter {
            it.name.startsWith("getInternalPlugin")
        }
        assertTrue(getters.isNotEmpty(), "Expected the internal plugin accessor to exist")
        assertTrue(getters.all { it.isSynthetic }, "Internal plugin accessors must be JVM-synthetic")
    }

    @Test
    fun `denies process reflection file network and raw thread APIs`() {
        val denyList = KSLClassDenyList()

        listOf(
            "java.lang.System",
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
    fun `strict source preflight rejects denied Java APIs before evaluation`() {
        val deniedSnippets = listOf(
            "import java.io.File",
            "import java.nio.file.Files",
            "import java.net.URL",
            "System.getenv(\"TOKEN\")",
            "Thread.sleep(10)",
            "Executors.newFixedThreadPool(4)",
            "import kotlin.io.path.*"
        )

        deniedSnippets.forEach { snippet ->
            assertTrue(
                KSLSandboxRules.scanSource(snippet).isNotEmpty(),
                "Expected strict sandbox source preflight to reject: $snippet"
            )
        }
    }

    @Test
    fun `strict source preflight keeps normal Java and Kotlin APIs available`() {
        val safeSnippet = """
            val amount = BigDecimal("10.00")
            val formatted = DecimalFormat("0.00").format(amount)
            val matcher = Pattern.compile("[a-z]+").matcher("ksl")
            val instant = Instant.now()
            val values = listOf(3, 1, 2).sorted()
            val result = sqrt(81.0)
        """.trimIndent()

        assertTrue(
            KSLSandboxRules.scanSource(safeSnippet).isEmpty(),
            "Ordinary JDK and Kotlin standard-library calls must remain available"
        )
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
    fun `local library discovery rejects symlinked files directories and roots`() {
        val base = java.nio.file.Files.createTempDirectory("ksl-library-paths").toFile()
        val root = java.io.File(base, "libs").apply { mkdirs() }
        val outside = java.io.File(base, "outside").apply { mkdirs() }
        try {
            java.io.File(root, "safe.kt").writeText("class Safe")
            val externalFile = java.io.File(outside, "external.kt").apply { writeText("class External") }
            val externalDir = java.io.File(outside, "nested").apply { mkdirs() }
            java.io.File(externalDir, "nested.kt").writeText("class Nested")
            try {
                java.nio.file.Files.createSymbolicLink(java.io.File(root, "external.kt").toPath(), externalFile.toPath())
                java.nio.file.Files.createSymbolicLink(java.io.File(root, "external-dir").toPath(), externalDir.toPath())
                val discovered = discoverLocalLibrarySources(root)
                assertEquals(listOf("safe.kt"), discovered.map { it.name })
                val linkedRoot = java.io.File(base, "linked-libs")
                java.nio.file.Files.createSymbolicLink(linkedRoot.toPath(), root.toPath())
                assertTrue(discoverLocalLibrarySources(linkedRoot).isEmpty())
            } catch (_: UnsupportedOperationException) {
                assertEquals(listOf("safe.kt"), discoverLocalLibrarySources(root).map { it.name })
            } catch (_: java.nio.file.FileSystemException) {
                assertEquals(listOf("safe.kt"), discoverLocalLibrarySources(root).map { it.name })
            }
        } finally {
            base.deleteRecursively()
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

        assertThrows(ClassNotFoundException::class.java) { loader.loadClass("java.lang.System") }
        assertThrows(ClassNotFoundException::class.java) { loader.loadClass("java.lang.Runtime") }
        assertThrows(ClassNotFoundException::class.java) { loader.loadClass("java.lang.Runtime") }
        assertEquals(listOf("java.lang.System", "java.lang.Runtime"), denied)
        assertSame(String::class.java, loader.loadClass("java.lang.String"))
    }

    @Test
    fun `class denial diagnostics are capped while all denied names stay blocked`() {
        val denied = mutableListOf<String>()
        val loader = KSLSandboxClassLoader(
            javaClass.classLoader,
            KSLClassDenyList(extra = listOf("ksl.blocked.")),
            "sandbox-test",
            denied::add
        )

        repeat(KSLSandboxClassLoader.MAX_REPORTED_DENIALS + 20) { index ->
            assertThrows(ClassNotFoundException::class.java) {
                loader.loadClass("ksl.blocked.Type$index")
            }
        }

        assertEquals(KSLSandboxClassLoader.MAX_REPORTED_DENIALS, denied.size)
        assertThrows(ClassNotFoundException::class.java) {
            loader.loadClass("ksl.blocked.StillBlocked")
        }
    }
}
