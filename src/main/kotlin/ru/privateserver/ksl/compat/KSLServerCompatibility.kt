package ru.privateserver.ksl.compat

import org.bukkit.Bukkit
import java.lang.reflect.Modifier

/**
 * Version and API-capability probes that avoid linking optional Paper/Bukkit symbols.
 *
 * Keep this class on the oldest API supported by the compatibility track. Newer
 * capabilities must be detected by name/reflection before they are used.
 */
data class KSLMinecraftVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0
) : Comparable<KSLMinecraftVersion> {

    init {
        require(major > 0) { "major must be positive" }
        require(minor >= 0) { "minor must not be negative" }
        require(patch >= 0) { "patch must not be negative" }
    }

    override fun compareTo(other: KSLMinecraftVersion): Int =
        compareValuesBy(this, other, KSLMinecraftVersion::major, KSLMinecraftVersion::minor, KSLMinecraftVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    fun isAtLeast(major: Int, minor: Int, patch: Int = 0): Boolean =
        compareTo(KSLMinecraftVersion(major, minor, patch)) >= 0

    companion object {
        private val VERSION_PATTERN = Regex("""(?:^|[^0-9])([0-9]{1,2})\.([0-9]{1,2})(?:\.([0-9]{1,3}))?""")

        /**
         * Parses server strings such as "1.16.5-R0.1-SNAPSHOT" and "git-Paper-1.21.1".
         * Returns null for unknown formats instead of guessing a version.
         */
        fun parse(raw: String?): KSLMinecraftVersion? {
            if (raw.isNullOrBlank()) return null
            val match = VERSION_PATTERN.find(raw) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return runCatching { KSLMinecraftVersion(major, minor, patch) }.getOrNull()
        }
    }
}

/** Runtime API features which differ across the compatibility tracks. */
enum class KSLServerCapability {
    PAPER_ASYNC_CHAT_EVENT,
    PAPER_ENTITY_REMOVE_EVENT,
    PAPER_ENTITIES_LOAD_EVENT,
    PARTICLE_DUST,
    PARTICLE_DUST_TRANSITION,
    ATTRIBUTE_REGISTRY,
    PAPER_MOB_PATHFINDER
}

/**
 * Runtime server inspection. Capability checks use reflection so referencing this
 * object does not eagerly load classes that are absent on older server versions.
 */
object KSLServerCompatibility {

    fun currentMinecraftVersion(): KSLMinecraftVersion? =
        KSLMinecraftVersion.parse(Bukkit.getBukkitVersion())

    fun serverImplementation(): String = Bukkit.getName()

    fun isAtLeast(minimum: KSLMinecraftVersion): Boolean =
        currentMinecraftVersion()?.let { it >= minimum } == true

    fun supports(capability: KSLServerCapability): Boolean = when (capability) {
        KSLServerCapability.PAPER_ASYNC_CHAT_EVENT ->
            classAvailable("io.papermc.paper.event.player.AsyncChatEvent")
        KSLServerCapability.PAPER_ENTITY_REMOVE_EVENT ->
            classAvailable("org.bukkit.event.entity.EntityRemoveEvent") ||
                classAvailable("com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent")
        KSLServerCapability.PAPER_ENTITIES_LOAD_EVENT ->
            classAvailable("org.bukkit.event.world.EntitiesLoadEvent")
        KSLServerCapability.PARTICLE_DUST ->
            enumConstantAvailable("org.bukkit.Particle", "REDSTONE") &&
                classAvailable("org.bukkit.Particle\$DustOptions")
        KSLServerCapability.PARTICLE_DUST_TRANSITION ->
            enumConstantAvailable("org.bukkit.Particle", "DUST_COLOR_TRANSITION") &&
                classAvailable("org.bukkit.Particle\$DustTransition")
        KSLServerCapability.ATTRIBUTE_REGISTRY ->
            publicStaticFieldAvailable("org.bukkit.Registry", "ATTRIBUTE")
        KSLServerCapability.PAPER_MOB_PATHFINDER ->
            methodAvailable("org.bukkit.entity.Mob", "getPathfinder", 0)
    }

    fun classAvailable(className: String): Boolean =
        loadServerClass(className) != null

    fun enumConstantAvailable(className: String, constantName: String): Boolean {
        val type = loadServerClass(className) ?: return false
        if (!type.isEnum) return false
        return type.enumConstants?.any { (it as? Enum<*>)?.name == constantName } == true
    }

    fun methodAvailable(className: String, methodName: String, parameterCount: Int): Boolean {
        val type = loadServerClass(className) ?: return false
        return type.methods.any { it.name == methodName && it.parameterCount == parameterCount }
    }

    fun publicStaticFieldAvailable(className: String, fieldName: String): Boolean {
        val type = loadServerClass(className) ?: return false
        val field = runCatching { type.getField(fieldName) }.getOrNull() ?: return false
        return Modifier.isStatic(field.modifiers)
    }

    private fun loadServerClass(className: String): Class<*>? =
        try {
            Class.forName(className, false, Bukkit::class.java.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        } catch (_: SecurityException) {
            null
        }
}

/**
 * Build/runtime target for this branch. This describes the intended track;
 * it is not a claim that every version in the range has passed server tests.
 */
object KSLCompatibilityTarget {
    const val TRACK_ID: String = "mc-1.16.5-to-1.21.x"
    const val MINIMUM_MINECRAFT_VERSION: String = "1.16.5"
    const val MINIMUM_JAVA_VERSION: Int = 16
    const val COMPILE_API_BASELINE: String = "Paper 1.16.5"

    /** Returns a startup failure message, or null when this server meets the branch baseline. */
    fun runtimeFailure(version: KSLMinecraftVersion?, javaFeature: Int): String? {
        if (version == null) return "Unable to determine the running Minecraft version from Bukkit."
        val minimum = KSLMinecraftVersion.parse(MINIMUM_MINECRAFT_VERSION)
            ?: return "Invalid KSL compatibility profile: $MINIMUM_MINECRAFT_VERSION"
        if (version < minimum) {
            return "This KSL build requires Minecraft $MINIMUM_MINECRAFT_VERSION or newer; detected $version."
        }
        if (javaFeature < MINIMUM_JAVA_VERSION) {
            return "This KSL build requires Java $MINIMUM_JAVA_VERSION or newer; detected Java $javaFeature."
        }
        return null
    }
}
