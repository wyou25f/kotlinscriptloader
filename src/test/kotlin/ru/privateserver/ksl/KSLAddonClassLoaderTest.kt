package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class KSLAddonClassLoaderTest {

    @Test
    fun `loads addon classes after checking the parent first`() {
        val addonLoader = javaClass.classLoader
        val loader = KSLAddonClassLoader(ClassLoader.getPlatformClassLoader(), listOf(addonLoader))

        assertSame(javaClass, loader.loadClass(javaClass.name))
    }

    @Test
    fun `keeps parent classes authoritative`() {
        val loader = KSLAddonClassLoader(javaClass.classLoader, listOf(ClassLoader.getSystemClassLoader()))

        assertSame(String::class.java, loader.loadClass(String::class.java.name))
        assertNotNull(loader.getResource("java/lang/String.class"))
    }
}
