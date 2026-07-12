registerCommand("summonboss", permission = "ksl.boss.summon") { player, args ->
    val boss = spawnCustomEntity(
        player.location,
        """TYPE:ZOMBIE NAME:"<red><bold>Огненный Босс</bold></red>" HP:200 AI:TRUE"""
    ) {
        equip("HAND:DIAMOND_SWORD HELMET:NETHERITE_HELMET")
    }

    boss.playEffect(Particle.FLAME, follow = true) {
        circle(radius = 2.0, stepDegrees = 15.0)
        durationTicks = 20L * 60 * 5
        intervalTicks = 2L
    }

    boss.behavior {
        onTick(period = 20L) { entity ->
            entity.getNearbyEntities(3.0, 3.0, 3.0)
                .filterIsInstance<Player>()
                .forEach { nearby -> nearby.damage(2.0, entity) }
        }

        onDeath { entity, killer ->
            broadcastMM("<gold>${entity.name} <yellow>был повержен игроком <white>${killer?.name ?: "неизвестным"}</white>!")
        }
    }

    player.sendRichMessage("<green>Босс призван! Аура следует за ним и держится 5 минут либо до его смерти.")
}

registerCommand("dna") { player, args ->
    player.location.playEffect(Particle.SOUL_FIRE_FLAME) {
        helix(radius = 1.5, turns = 3.0, height = 3.0)
        durationTicks = 60L
        intervalTicks = 1L
    }
}

registerCommand("beam") { player, args ->
    val target = player.getTargetEntity(30)
    if (target == null) {
        player.sendRichMessage("<red>Некуда стрелять — посмотри на цель.")
        return@registerCommand
    }
    player.eyeLocation.playEffect(Particle.END_ROD) {
        lineTo({ target.location }, points = 25)
        durationTicks = 40L
        intervalTicks = 1L
    }
}
