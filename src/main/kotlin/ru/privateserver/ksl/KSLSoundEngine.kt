package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player

/**
 * Reusable sound definition. A spec is cheap and may be reused for many locations and players.
 * All playback methods must be called on the server thread.
 */
class KSLSoundSpec internal constructor(
    private val context: BukkitScriptContext,
    key: String
) {
    val key: String = normalizeKSLSoundKey(key)

    var volume: Float = 1f
        private set
    var pitch: Float = 1f
        private set
    var category: SoundCategory = SoundCategory.MASTER
        private set

    private var pitchRange: Pair<Float, Float>? = null

    fun volume(value: Number): KSLSoundSpec {
        val safe = value.toFloat()
        require(safe.isFinite() && safe in 0f..16f) { "Громкость звука должна быть конечным числом от 0 до 16" }
        volume = safe
        return this
    }

    fun pitch(value: Number): KSLSoundSpec {
        val safe = value.toFloat()
        require(safe.isFinite() && safe in 0f..2f) { "Высота звука должна быть конечным числом от 0 до 2" }
        pitch = safe
        pitchRange = null
        return this
    }

    fun pitchRange(min: Number, max: Number): KSLSoundSpec {
        val low = min.toFloat()
        val high = max.toFloat()
        require(low.isFinite() && high.isFinite() && low in 0f..2f && high in low..2f) {
            "Диапазон высоты звука должен находиться в пределах 0..2 и иметь min <= max"
        }
        pitchRange = low to high
        pitch = low
        return this
    }

    fun category(value: SoundCategory): KSLSoundSpec {
        category = value
        return this
    }

    /** Play for everyone in the world at this position. */
    fun playAt(location: Location): Boolean {
        requirePrimaryThread()
        val world = location.world ?: return false
        val at = location.clone()
        requireFiniteLocation(at)
        world.playSound(at, key, category, volume, resolvedPitch())
        return true
    }

    /** Play only for one player. The sound is still positioned at [at] for distance attenuation. */
    fun playTo(player: Player, at: Location = player.location): Boolean {
        requirePrimaryThread()
        if (!player.isOnline) return false
        val location = at.clone()
        if (location.world !== player.world) return false
        requireFiniteLocation(location)
        player.playSound(location, key, category, volume, resolvedPitch())
        return true
    }

    /** Play only for players within [radius] blocks of [location]. Returns the number of recipients. */
    fun playNearby(location: Location, radius: Double): Int {
        requirePrimaryThread()
        require(radius.isFinite() && radius >= 0.0) { "Радиус звука должен быть конечным неотрицательным числом" }
        val world = location.world ?: return 0
        val at = location.clone()
        requireFiniteLocation(at)
        val recipients = world.getNearbyPlayers(at, radius)
        recipients.forEach { it.playSound(at, key, category, volume, resolvedPitch()) }
        return recipients.size
    }

    /** Repeat for everyone in the world. times=-1 means infinite; the task is owned by this script. */
    fun repeatAt(location: Location, periodTicks: Long = 20L, times: Int = -1): Int {
        val at = location.clone()
        require(at.world != null) { "Для повтора звука нужна локация с загруженным миром" }
        requireFiniteLocation(at)
        return repeat(periodTicks, times) { playAt(at) }
    }

    /** Repeat for one player. */
    fun repeatTo(player: Player, periodTicks: Long = 20L, times: Int = -1, at: Location = player.location): Int {
        val location = at.clone()
        require(location.world === player.world) { "Игрок и позиция звука должны находиться в одном мире" }
        requireFiniteLocation(location)
        return repeat(periodTicks, times) { playTo(player, location) }
    }

    /** Repeat for nearby players. */
    fun repeatNearby(location: Location, radius: Double, periodTicks: Long = 20L, times: Int = -1): Int {
        require(radius.isFinite() && radius >= 0.0) { "Радиус звука должен быть конечным неотрицательным числом" }
        val at = location.clone()
        require(at.world != null) { "Для повтора звука нужна локация с загруженным миром" }
        requireFiniteLocation(at)
        return repeat(periodTicks, times) { playNearby(at, radius) }
    }

    private fun repeat(periodTicks: Long, times: Int, action: () -> Unit): Int {
        requirePrimaryThread()
        require(periodTicks > 0L) { "Период повтора должен быть больше нуля тиков" }
        require(times == -1 || times in 1..10_000) { "times должен быть -1 или находиться в диапазоне 1..10000" }
        var played = 0
        var taskId = -1
        taskId = context.every(ticks = periodTicks) {
            action()
            played++
            if (times > 0 && played >= times) context.cancelTask(taskId)
        }
        return taskId
    }

    internal fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Воспроизведение звука должно выполняться в главном потоке сервера; используй runSync { ... }" }
    }

    private fun resolvedPitch(): Float {
        val range = pitchRange ?: return pitch
        return if (range.first == range.second) range.first
        else java.util.concurrent.ThreadLocalRandom.current().nextDouble(range.first.toDouble(), range.second.toDouble()).toFloat()
    }

    private fun requireFiniteLocation(location: Location) {
        require(location.x.isFinite() && location.y.isFinite() && location.z.isFinite()) {
            "Координаты звука должны быть конечными числами"
        }
    }
}

internal fun normalizeKSLSoundKey(value: String): String {
    val clean = value.trim().lowercase()
    require(clean.isNotEmpty()) { "Ключ звука не должен быть пустым" }
    val namespaced = if (':' in clean) clean else "minecraft:" + clean
    require(namespaced.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
        "Некорректный ключ звука: " + value
    }
    return namespaced
}

internal data class KSLSoundCue(val tick: Long, val sound: KSLSoundSpec)

/** Builds a timeline using absolute tick positions or cumulative delays. */
class KSLSoundSequenceBuilder internal constructor(private val context: BukkitScriptContext) {
    internal val cues = mutableListOf<KSLSoundCue>()
    private var cursor = 0L

    fun at(tick: Int, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder =
        at(tick.toLong(), sound, block)

    fun at(tick: Int, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder =
        at(tick.toLong(), key, block)

    fun at(tick: Long, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder {
        require(tick >= 0L) { "Позиция звука не может быть отрицательной" }
        cues += KSLSoundCue(tick, context.sound(sound, block))
        cursor = tick
        return this
    }

    fun at(tick: Long, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder {
        require(tick >= 0L) { "Позиция звука не может быть отрицательной" }
        cues += KSLSoundCue(tick, context.sound(key, block))
        cursor = tick
        return this
    }

    /** Adds a cue after [ticks] from the previous cue. */
    fun after(ticks: Int, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder =
        after(ticks.toLong(), sound, block)

    fun after(ticks: Int, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder =
        after(ticks.toLong(), key, block)

    fun after(ticks: Long, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder {
        require(ticks >= 0L && cursor <= Long.MAX_VALUE - ticks) { "Задержка звука некорректна или слишком велика" }
        cursor += ticks
        return at(cursor, sound, block)
    }

    fun after(ticks: Long, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder {
        require(ticks >= 0L && cursor <= Long.MAX_VALUE - ticks) { "Задержка звука некорректна или слишком велика" }
        cursor += ticks
        return at(cursor, key, block)
    }

    fun then(sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder =
        after(1L, sound, block)

    fun then(key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder =
        after(1L, key, block)
}

/** A reusable, tick-based sound timeline. Playback returns one cancellable KSL task id. */
class KSLSoundSequence internal constructor(
    private val context: BukkitScriptContext,
    cues: List<KSLSoundCue>
) {
    private val cues = cues.sortedBy { it.tick }

    fun playAt(location: Location, repeats: Int = 1, gapTicks: Long = 0L): Int {
        val at = location.clone()
        return play(repeats, gapTicks) { cue -> cue.sound.playAt(at) }
    }

    fun playTo(player: Player, repeats: Int = 1, gapTicks: Long = 0L, at: Location = player.location): Int {
        val location = at.clone()
        return play(repeats, gapTicks) { cue -> cue.sound.playTo(player, location) }
    }

    fun playNearby(location: Location, radius: Double, repeats: Int = 1, gapTicks: Long = 0L): Int {
        require(radius.isFinite() && radius >= 0.0) { "Радиус звука должен быть конечным неотрицательным числом" }
        val at = location.clone()
        return play(repeats, gapTicks) { cue -> cue.sound.playNearby(at, radius) }
    }

    private fun play(repeats: Int, gapTicks: Long, action: (KSLSoundCue) -> Unit): Int {
        check(Bukkit.isPrimaryThread()) { "Звуковая последовательность должна запускаться в главном потоке сервера" }
        require(cues.isNotEmpty()) { "Звуковая последовательность пуста" }
        require(repeats in 1..1_000) { "Количество повторов последовательности должно быть от 1 до 1000" }
        require(gapTicks >= 0L) { "Пауза между последовательностями не может быть отрицательной" }
        val lastTick = cues.last().tick
        require(lastTick < Long.MAX_VALUE - gapTicks - 1L) { "Последовательность слишком длинная" }
        val cycleTicks = lastTick + gapTicks + 1L
        require(cycleTicks <= Long.MAX_VALUE / repeats) { "Общая длительность последовательности слишком велика" }
        val totalTicks = cycleTicks * repeats
        val byTick = cues.groupBy { it.tick }
        var elapsed = 0L
        var taskId = -1
        taskId = context.every(ticks = 1L) {
            val phase = elapsed % cycleTicks
            byTick[phase]?.forEach(action)
            elapsed++
            if (elapsed >= totalTicks) context.cancelTask(taskId)
        }
        return taskId
    }
}
