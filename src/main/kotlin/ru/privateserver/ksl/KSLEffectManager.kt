package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-effect emission limits. Bukkit count=0 is its single-particle mode, so it
 * still consumes one emission per point even though the API count argument is zero.
 */
internal object KSLEffectBudget {
    const val MAX_POINTS = 360
    const val MAX_COUNT = 100
    const val MAX_PARTICLES_PER_TICK = 2048

    fun countPerPoint(requestedCount: Int, pointCount: Int): Int {
        require(pointCount in 1..MAX_POINTS) {
            "Форма эффекта должна возвращать от 1 до $MAX_POINTS точек"
        }
        val clamped = requestedCount.coerceIn(0, MAX_COUNT)
        if (clamped == 0) return 0
        val perPointLimit = (MAX_PARTICLES_PER_TICK / pointCount).coerceAtLeast(1)
        return clamped.coerceAtMost(perPointLimit)
    }
}

internal data class KSLParticleAllocation(
    val pointsToEmit: Int,
    val countPerPoint: Int,
    val particles: Int
)

internal class KSLParticleTickBudget(
    private val maxParticlesPerTick: Int = 8_192,
    private val maxParticlesPerEffect: Int = KSLEffectBudget.MAX_PARTICLES_PER_TICK
) {
    private var budgetTick = Int.MIN_VALUE
    private var emittedThisTick = 0

    init {
        require(maxParticlesPerTick > 0)
        require(maxParticlesPerEffect > 0)
    }

    @Synchronized
    fun allocate(tick: Int, requestedCount: Int, pointCount: Int): KSLParticleAllocation {
        require(pointCount in 1..KSLEffectBudget.MAX_POINTS) {
            "Форма эффекта должна возвращать от 1 до ${KSLEffectBudget.MAX_POINTS} точек"
        }
        if (budgetTick != tick) {
            budgetTick = tick
            emittedThisTick = 0
        }

        val available = minOf(
            maxParticlesPerEffect,
            (maxParticlesPerTick - emittedThisTick).coerceAtLeast(0)
        )
        if (available == 0) return KSLParticleAllocation(0, 0, 0)

        val count = requestedCount.coerceIn(0, KSLEffectBudget.MAX_COUNT)
        val requestedPerPoint = if (count == 0) 1 else count
        val pointsToEmit: Int
        val actualPerPoint: Int
        if (available >= pointCount) {
            pointsToEmit = pointCount
            actualPerPoint = requestedPerPoint.coerceAtMost(available / pointCount)
        } else {
            pointsToEmit = available
            actualPerPoint = 1
        }

        val emitted = pointsToEmit * actualPerPoint
        emittedThisTick += emitted
        return KSLParticleAllocation(pointsToEmit, if (count == 0) 0 else actualPerPoint, emitted)
    }

    @Synchronized
    fun emittedInTick(tick: Int): Int = if (budgetTick == tick) emittedThisTick else 0
}

class KSLEffectManager(private val plugin: KotlinScriptLoaderPlugin) {

    private companion object {
        const val MAX_ACTIVE_EFFECTS_PER_SCRIPT = 16
        const val MAX_ACTIVE_EFFECTS_TOTAL = 128
    }

    private val activeByScript = ConcurrentHashMap<String, MutableSet<Int>>()
    private val particleBudget = KSLParticleTickBudget()

    private fun activeEffectCount(): Int = activeByScript.values.sumOf { it.size }

    fun play(scriptName: String, spec: KSLEffectSpec): Int {
        require(Bukkit.isPrimaryThread()) {
            "[$scriptName] playEffect должен вызываться в главном потоке сервера; используй runSync { ... }"
        }

        val activeForScript = activeByScript[scriptName]?.size ?: 0
        require(activeForScript < MAX_ACTIVE_EFFECTS_PER_SCRIPT) {
            "[$scriptName] playEffect: достигнут лимит $MAX_ACTIVE_EFFECTS_PER_SCRIPT одновременно активных эффектов на скрипт"
        }
        require(activeEffectCount() < MAX_ACTIVE_EFFECTS_TOTAL) {
            "[$scriptName] playEffect: достигнут общий лимит $MAX_ACTIVE_EFFECTS_TOTAL одновременно активных эффектов"
        }

        val required = spec.particle.dataType
        if (required != Void::class.java) {
            val supplied = spec.data
            if (supplied == null) {
                throw IllegalArgumentException(
                    "[$scriptName] ${KSLError.EFFECT_DATA_REQUIRED.tag} playEffect: Particle.${spec.particle.name} требует data (${required.simpleName})"
                )
            }
            if (!required.isInstance(supplied)) {
                throw IllegalArgumentException(
                    "[$scriptName] playEffect: Particle.${spec.particle.name} ожидает ${required.simpleName}, получено ${supplied.javaClass.simpleName}"
                )
            }
        } else if (spec.data != null) {
            throw IllegalArgumentException(
                "[$scriptName] playEffect: Particle.${spec.particle.name} не принимает дополнительные данные"
            )
        }
        require(spec.durationTicks > 0L) { "[$scriptName] playEffect: durationTicks должен быть больше нуля" }
        require(
            spec.offsetX.isFinite() && spec.offsetY.isFinite() && spec.offsetZ.isFinite() && spec.extra.isFinite()
        ) { "[$scriptName] playEffect: offsetX/offsetY/offsetZ/extra должны быть конечными числами" }

        val shape = spec.buildShape()
        var tick = 0L
        lateinit var task: BukkitTask
        val interval = KSLErrors.validPeriod(plugin, scriptName, "playEffect", spec.intervalTicks)

        if (spec.count !in 0..KSLEffectBudget.MAX_COUNT) {
            plugin.logger.warning(
                "[$scriptName] playEffect: ${KSLError.EFFECT_COUNT_CLAMPED.tag} count=${spec.count} вне 0..${KSLEffectBudget.MAX_COUNT} — значение будет ограничено"
            )
        }

        var warnedBudget = false
        var stopped = false
        fun stop() {
            if (stopped) return
            stopped = true
            task.cancel()
            plugin.untrackTask(scriptName, task.taskId)
            activeByScript.computeIfPresent(scriptName) { _, ids ->
                ids.remove(task.taskId)
                if (ids.isEmpty()) null else ids
            }
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                if (tick >= spec.durationTicks || spec.stopCondition?.invoke() == true) {
                    stop()
                    spec.finish()
                    return@Runnable
                }
                val center = spec.locationSupplier()
                val world = center.world
                if (world != null) {
                    val cx = center.x
                    val cy = center.y
                    val cz = center.z
                    val points = shape.points(tick, center)
                    require(points.size <= KSLEffectBudget.MAX_POINTS) {
                        "Форма эффекта вернула ${points.size} точек; максимум — ${KSLEffectBudget.MAX_POINTS}"
                    }
                    if (points.isNotEmpty()) {
                        points.forEach { offset ->
                            require(offset.x.isFinite() && offset.y.isFinite() && offset.z.isFinite()) {
                                "Форма эффекта вернула координаты, не являющиеся конечными числами"
                            }
                            require(
                                (cx + offset.x).isFinite() && (cy + offset.y).isFinite() && (cz + offset.z).isFinite()
                            ) { "Итоговые координаты эффекта вышли за допустимый числовой диапазон" }
                        }

                        val requestedCount = spec.count.coerceIn(0, KSLEffectBudget.MAX_COUNT)
                        val requestedParticles = (if (requestedCount == 0) 1 else requestedCount) * points.size
                        val allocation = particleBudget.allocate(Bukkit.getCurrentTick(), spec.count, points.size)
                        if (allocation.particles < requestedParticles && !warnedBudget) {
                            warnedBudget = true
                            plugin.logger.warning(
                                "[$scriptName] playEffect: достигнут лимит частиц эффекта/сервера за тик; выпущено ${allocation.particles} из $requestedParticles"
                            )
                        }
                        val selected = when {
                            allocation.pointsToEmit == points.size -> points
                            allocation.pointsToEmit == 0 -> emptyList()
                            else -> List(allocation.pointsToEmit) { index ->
                                points[(index.toLong() * points.size / allocation.pointsToEmit).toInt()]
                            }
                        }
                        selected.forEach { offset ->
                            world.spawnParticle(
                                spec.particle, cx + offset.x, cy + offset.y, cz + offset.z,
                                allocation.countPerPoint, spec.offsetX, spec.offsetY, spec.offsetZ,
                                spec.extra, spec.data
                            )
                        }
                    }
                }
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "playEffect tick", ex, KSLError.EFFECT_TICK_FAILED)
                stop()
                return@Runnable
            }
            tick = if (tick > Long.MAX_VALUE - interval) spec.durationTicks else tick + interval
        }, 0L, interval)

        plugin.trackTask(scriptName, task.taskId)
        activeByScript.computeIfAbsent(scriptName) { ConcurrentHashMap.newKeySet() }.add(task.taskId)
        return task.taskId
    }

    /** Cancels an effect only when it belongs to the requesting script. */
    fun stop(scriptName: String, taskId: Int): Boolean {
        val ids = activeByScript[scriptName] ?: return false
        if (!ids.remove(taskId)) return false
        Bukkit.getScheduler().cancelTask(taskId)
        plugin.untrackTask(scriptName, taskId)
        if (ids.isEmpty()) activeByScript.remove(scriptName, ids)
        return true
    }

    /** Called before the script's generic task cleanup during reload/unload. */
    fun cleanupForScript(scriptName: String) {
        val ids = activeByScript.remove(scriptName) ?: return
        ids.forEach { taskId ->
            Bukkit.getScheduler().cancelTask(taskId)
            plugin.untrackTask(scriptName, taskId)
        }
    }
}
