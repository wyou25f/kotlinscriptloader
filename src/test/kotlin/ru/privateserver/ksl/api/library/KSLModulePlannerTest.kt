package ru.privateserver.ksl.api.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.api.KSLAPI

class KSLModulePlannerTest {
    private class TestModule(
        override val descriptor: KSLModuleDescriptor
    ) : KSLStandardLibraryModule {
        override fun install(api: KSLAPI) = Unit
    }

    @Test
    fun `resolves dependencies before dependents deterministically`() {
        val core = TestModule(KSLModuleDescriptor("core", "1.0.0", "Core"))
        val chat = TestModule(KSLModuleDescriptor("chat", "1.0.0", "Chat", dependencies = setOf("core")))
        val storage = TestModule(KSLModuleDescriptor("storage", "1.0.0", "Storage"))

        assertEquals(
            listOf("core", "chat", "storage"),
            KSLModulePlanner.resolve(listOf(storage, chat, core)).map { it.descriptor.id }
        )
    }

    @Test
    fun `rejects missing dependencies`() {
        val module = TestModule(KSLModuleDescriptor("chat", "1.0.0", "Chat", dependencies = setOf("core")))

        assertThrows(IllegalArgumentException::class.java) {
            KSLModulePlanner.resolve(listOf(module))
        }
    }

    @Test
    fun `rejects dependency cycles`() {
        val first = TestModule(KSLModuleDescriptor("first", "1.0.0", "First", dependencies = setOf("second")))
        val second = TestModule(KSLModuleDescriptor("second", "1.0.0", "Second", dependencies = setOf("first")))

        assertThrows(IllegalArgumentException::class.java) {
            KSLModulePlanner.resolve(listOf(first, second))
        }
    }

    @Test
    fun `rejects modules requiring a newer api`() {
        val module = TestModule(KSLModuleDescriptor("future", "1.0.0", "Future", requiredApiVersion = 99))

        assertThrows(IllegalArgumentException::class.java) {
            KSLModulePlanner.resolve(listOf(module), apiVersion = 6)
        }
    }

    @Test
    fun `rejects duplicate module ids`() {
        val first = TestModule(KSLModuleDescriptor("core", "1.0.0", "First"))
        val second = TestModule(KSLModuleDescriptor("core", "2.0.0", "Second"))

        assertThrows(IllegalArgumentException::class.java) {
            KSLModulePlanner.resolve(listOf(first, second))
        }
    }
}
