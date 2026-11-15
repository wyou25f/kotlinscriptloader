package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.api.KSL_SCRIPT_CONTEXT_CLASS
import ru.privateserver.ksl.api.KSL_SCRIPT_DEFAULT_IMPORTS
import ru.privateserver.ksl.api.KSL_SCRIPT_FILE_EXTENSION
import ru.privateserver.ksl.api.KSL_SCRIPT_JVM_TARGET
import kotlin.script.experimental.annotations.KotlinScript

class KSLScriptDefinitionTest {
    @Test
    fun `script source cannot access the internal plugin capability`() {
        val result = runBlocking {
            JvmScriptCompiler()(
                "internalPlugin".toScriptSource("sandbox_internal_access.kts"),
                KSLScriptCompilationConfiguration
            )
        }

        assertTrue(
            result is ResultWithDiagnostics.Failure,
            "KSL scripts must not be able to compile direct access to internalPlugin"
        )
        if (result is ResultWithDiagnostics.Failure) {
            assertTrue(
                result.reports.any { it.message.contains("internalPlugin", ignoreCase = true) },
                "Compilation should fail specifically because internalPlugin is not visible: ${result.reports}"
            )
        }
    }


    @Test
    fun `sound particle and mob helpers compile in a KSL script`() {
        val source = """
            val click = sound(Sound.UI_BUTTON_CLICK) {
                volume(0.8)
                pitchRange(0.9, 1.1)
                category(SoundCategory.PLAYERS)
            }
            val bgm = music("custompack:music.theme") {
                volume(0.5)
            }
            val timeline = soundSequence {
                at(0, Sound.BLOCK_NOTE_BLOCK_HARP)
                after(4, "entity.player.levelup")
                then(Sound.UI_BUTTON_CLICK)
            }
            if (false) {
                Bukkit.getOnlinePlayers().firstOrNull()?.let { player ->
                    click.playTo(player)
                    player.sound(click)
                    player.location.sound(click)
                    timeline.playNearby(player.location, 16.0)
                    bgm.playTo(player)
                    player.location.particles(Particle.DUST) {
                        amount(3)
                        every(2)
                        forTicks(40)
                        ring(1.0, 24)
                        color("#55CCFF")
                        origin(y = 1.0)
                        spin(4.0)
                    }
                    val boss = mob(player.location, EntityType.ZOMBIE) {
                        name("<red>Test boss")
                        maxHealth(80.0)
                        health(60.0)
                        damage(8.0)
                        speed(1.2)
                        target(player)
                        mainHand(item(Material.IRON_SWORD))
                        ai(true)
                        persistent()
                    }
                    boss.behavior { onTick(20L) { it.isGlowing = true } }
                    mobs(player.location, EntityType.SKELETON, amount = 3)
                }
            }
        """.trimIndent()
        val result = runBlocking {
            JvmScriptCompiler()(
                source.toScriptSource("sound_particles_mobs_api.kts"),
                KSLScriptCompilationConfiguration
            )
        }

        assertTrue(
            result is ResultWithDiagnostics.Success,
            "The new sound, particle and mob DSL must compile for scripts: " +
                (result as? ResultWithDiagnostics.Failure)?.reports?.joinToString("; ") { it.message }
        )
    }

    @Test
    fun `KSL mixins and defensive custom enchantments compile in scripts`() {
        val source = """
            val joinBehavior = mixin("join-behavior") {
                description("Shared player hooks")
                on<PlayerJoinEvent> { player.msg("<green>Welcome, " + player.name) }
                every(periodTicks = 20L) { onlineCount }
                after(delayTicks = 5L) { maxPlayers }
            }
            val typedMixin: KSLMixin = joinBehavior
            val typedBuilder: KSLMixinBuilder? = null
            if (false) useMixin(typedMixin)
            if (false) useMixin(joinBehavior)
            val ward = customEnchant("ward") {
                targets(Material.IRON_HELMET, Material.IRON_CHESTPLATE)
                onDamageTaken { event.damage = (event.damage - level * 0.5).coerceAtLeast(0.0) }
            }
        """.trimIndent()
        val result = runBlocking {
            JvmScriptCompiler()(source.toScriptSource("mixin_and_defense.kts"), KSLScriptCompilationConfiguration)
        }
        assertTrue(result is ResultWithDiagnostics.Success,
            "New KSL mixin and armor-enchant APIs must compile: " +
                (result as? ResultWithDiagnostics.Failure)?.reports?.joinToString("; ") { it.message })
    }

    @Test
    fun `script template exposes the canonical runtime definition to IDE tooling`() {
        val definition = KSLScript::class.java.getAnnotation(KotlinScript::class.java)

        assertEquals(KSL_SCRIPT_FILE_EXTENSION, definition.fileExtension)
        assertEquals(KSLScriptCompilationConfiguration::class, definition.compilationConfiguration)
        assertEquals("21", KSL_SCRIPT_JVM_TARGET)
        assertEquals(BukkitScriptContext::class.java.name, KSL_SCRIPT_CONTEXT_CLASS)
        assertTrue("ru.privateserver.ksl.dsl.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.nbt.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.api.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("org.bukkit.entity.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("org.bukkit.Sound" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("org.bukkit.SoundCategory" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.std.text.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.std.mixin.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.std.math.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.std.time.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
        assertTrue("ru.privateserver.ksl.std.validation.*" in KSL_SCRIPT_DEFAULT_IMPORTS)
    }
}
