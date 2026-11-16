import ru.privateserver.ksl.api.*

/*
 * IntelliJ smoke test for the KSL script definition.
 * Hover over public functions/extensions to inspect KDoc.
 * Ctrl/Cmd-click API and extension names to navigate to declarations.
 * Try completion after "api.", "player?.", "world." and "location?."
 */

val api = kslOrNull
if (api != null) {
    api.scripts().forEach { script ->
        println("${script.id}: ${script.loaded}")
    }
}

val shortLabel = "KotlinScriptLoader".truncateCodePoints(12)
val ticks = 5.secondsToTicks()

// Scheduler completion smoke checks: type after scheduler., handle. and task.
val scheduled = scheduler.schedule(delay = java.time.Duration.ofSeconds(2)) { task ->
    println("task #${task.execution} owned by ${task.owner}")
}
val repeating = scheduler.repeat(
    interval = java.time.Duration.ofSeconds(10),
    mode = ru.privateserver.ksl.api.KSLTaskRepeatMode.FIXED_DELAY
) { task ->
    println("repeat #${task.execution}")
}
val detailedTask = scheduler.schedule(
    KSLTaskSpec(owner = "sample", delay = java.time.Duration.ofSeconds(3)) { }
)
scheduled.cancel()
repeating.cancel()
detailedTask.cancel()

val player = Bukkit.getOnlinePlayers().firstOrNull()
player?.tellRich("<green>KSL completion is available")
val canManage = player?.hasAnyPermission("ksl.admin", "ksl.manage") ?: false
val inventoryFull = player?.isInventoryFull() ?: false

Bukkit.getWorld("world")?.let { world ->
    val block = world.blockAt(0, 64, 0)
    val nearby = world.entitiesInBox(Location(world, 0.0, 64.0, 0.0), 8.0)
    val shifted = Location(world, 0.0, 64.0, 0.0).offset(y = 1.0)
    val inside = shifted.isInside(
        Location(world, -8.0, 0.0, -8.0),
        Location(world, 8.0, 128.0, 8.0)
    )
}

/* Sound, particles and mob-builder completion smoke checks. */
val clickSound = sound(Sound.UI_BUTTON_CLICK) {
    volume(0.7)
    pitchRange(0.9, 1.1)
    category(SoundCategory.PLAYERS)
}
val clickTimeline = soundSequence {
    at(0, Sound.BLOCK_NOTE_BLOCK_HARP)
    after(4, Sound.BLOCK_NOTE_BLOCK_PLING)
    then("ui.button.click")
}

if (false && player != null) {
    clickSound.playTo(player)
    player.location.particles(Particle.DUST) {
        amount(3)
        every(2)
        durationTicks = 40
        ring(1.0, 24)
        color("#55CCFF")
        origin(y = 1.0)
        spin(4.0)
    }
    val mobPreview = mob(player.location, EntityType.ZOMBIE) {
        name("<red>Preview")
        maxHealth(40.0)
        damage(5.0)
        speed(1.1)
        target(player)
        mainHand(item(Material.IRON_SWORD))
    }
    mobPreview.behavior { onTick(20) { it.isGlowing = true } }
}


// Custom scheduler engine registration and discovery for addon authors.
api.availableTaskSchedulers()
if (api != null) {
    val registration = api.registerTaskSchedulerFactory("sample-addon.engine") { environment ->
        KSLOfficialTaskScheduler(environment.officialWorkers)
    }
    val addonScheduler = api.createTaskScheduler("sample-addon.engine")
    addonScheduler.close()
    registration.close()
}
