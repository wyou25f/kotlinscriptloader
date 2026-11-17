package ru.privateserver.ksl

import org.bukkit.event.Listener
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.bukkit.entity.LivingEntity
import java.util.function.Function

class KSLGenerationApiCompatibilityTest {

    @Test
    fun `legacy script resource tracking JVM overloads remain available`() {
        val plugin = KotlinScriptLoaderPlugin::class.java

        assertNotNull(plugin.getMethod("trackTask", String::class.java, Int::class.javaPrimitiveType))
        assertNotNull(plugin.getMethod("trackListener", String::class.java, Listener::class.java))
        assertNotNull(plugin.getMethod("trackGui", String::class.java, KSLGuiInstance::class.java))
        assertNotNull(plugin.getMethod("trackCommand", String::class.java, DynamicCommand::class.java))
        assertNotNull(
            plugin.getMethod(
                "registerPlaceholder",
                String::class.java,
                String::class.java,
                Function::class.java
            )
        )
        assertNotNull(
            plugin.getMethod(
                "registerTextPlaceholder",
                String::class.java,
                String::class.java,
                Function::class.java
            )
        )
    }

    @Test
    fun `legacy effect and entity builder JVM signatures remain available`() {
        assertNotNull(
            KSLEffectManager::class.java.getMethod("play", String::class.java, KSLEffectSpec::class.java)
        )
        assertNotNull(
            KSLEntityBehaviorBuilder::class.java.getConstructor(
                KotlinScriptLoaderPlugin::class.java,
                String::class.java,
                LivingEntity::class.java
            )
        )
        assertNotNull(
            KSLEntityBuilder::class.java.getConstructor(
                LivingEntity::class.java,
                KotlinScriptLoaderPlugin::class.java,
                String::class.java
            )
        )
    }
}
