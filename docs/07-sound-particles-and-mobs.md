# Звук, частицы и мобы

KSL объединяет игровые эффекты в короткие DSL-вызовы. Звуки, повторяющиеся эффекты и массовый спавн используют задачи владельца-скрипта: при перезагрузке или выгрузке KSL удалит связанные задачи.

## Звуковой движок

Создай переиспользуемое описание звука один раз. Громкость, высота тона и категория задаются цепочкой или функциями builder:

```kotlin
val click = sound(Sound.UI_BUTTON_CLICK) {
    volume(0.7)
    pitchRange(0.95, 1.05)
    category(SoundCategory.PLAYERS)
}

click.playTo(player)
click.playAt(player.location)
click.playNearby(player.location, radius = 24.0)
```

Вместо enum можно использовать строковый ключ: `sound("entity.player.levelup")`. Если namespace не указан, автоматически добавляется `minecraft:`. Для пользовательских ресурс-паков указывай свой namespace. Функция `music(...)` создаёт такой же preset, но по умолчанию использует категорию `SoundCategory.MUSIC` (её можно переопределить внутри builder).

- `playAt(location)` — слышат игроки в мире в пределах обычной дистанции Minecraft.
- `playTo(player, at)` — звук слышит только указанный игрок, но позиция по-прежнему влияет на затухание.
- `playNearby(location, radius)` — проигрывает звук игрокам в заданном радиусе.
- `repeatAt`, `repeatTo`, `repeatNearby` — повторяют звук. `times = -1` означает бесконечный повтор; вернувшийся ID можно отменить через `cancelTask(id)`.
- `pitchRange(min, max)` — выбирает случайную высоту тона для каждого воспроизведения.

Все значения проверяются: громкость 0..16, pitch 0..2, радиус неотрицательный. Методы воспроизведения вызывай в главном потоке сервера; для событий из async-потока используй `runSync { ... }`.

### Последовательности

Последовательность задаёт звуки относительно начала таймлайна. `at` использует абсолютный тик, `after` — задержку от предыдущего звука, `then` — следующий тик:

```kotlin
val intro = soundSequence {
    at(0L, Sound.BLOCK_NOTE_BLOCK_HARP) {
        volume(0.8)
        pitch(1.0)
    }
    after(4L, Sound.BLOCK_NOTE_BLOCK_PLING) {
        pitch(1.2)
    }
    then("ui.button.click")
}

intro.playTo(player)
intro.playAt(player.location, repeats = 3, gapTicks = 20L)
```

Вся последовательность работает как одна отменяемая KSL-задача. Количество повторов ограничено 1..1000, чтобы случайная конфигурация не создавала бесконечный цикл.

## Движок частиц

Существующий `playEffect` поддерживает формы и пользовательские shape-фабрики. Новые короткие алиасы — `particle` и `particles`:

```kotlin
val effectId = player.location.particles(Particle.DUST) {
    durationTicks = 100L
    every(2L)
    amount(3)
    ring(radius = 1.4, points = 32)
    color("#55CCFF", size = 1.2f)
    origin(y = 1.0)
    spin(3.0)
    onEnd { player.msg("<gray>Эффект завершён") }
}

// У сущности: follow=true переносит форму вместе с ней.
// entity.particle(Particle.FLAME, follow = true) { helix(0.8, 2.0, 2.5) }
```

Формы: `point`, `circle`, `ring`, `arc`, `square`, `box`, `cube`, `sphere`, `cylinder`, `cone`, `heart`, `helix` и `spiral`. Можно задать `origin`, статическое вращение `rotate` и вращение по тикам `spin`. Для частиц с дополнительными данными есть `color`/`dust`, `transition`, `block`, `item` и `particleData`.

KSL сохраняет ограничения на число точек, количество частиц на эффект и общий бюджет эмиссии за тик. Числа координат и смещения проверяются на конечность, а формы, возвращающие слишком много точек, останавливаются с ошибкой скрипта. ID эффекта можно передать в `stopEffect(id)`, `stopParticles(id)` или `cancelTask(id)`.

## Мобы и сущности

Можно передать строковый шаблон, как раньше, или тип Bukkit напрямую:

```kotlin
val boss = mob(location, EntityType.ZOMBIE) {
    name("<dark_red><bold>Страж")
    maxHealth(120.0)
    damage(12.0)
    speed(1.25)
    armor(8.0)
    target(player)
    mainHand(item(Material.NETHERITE_SWORD))
    helmet(item(Material.NETHERITE_HELMET))
    potion(PotionEffectType.FIRE_RESISTANCE, seconds = -1)
    glow()
    persistent()
    drops(item(Material.GOLD_INGOT, amount = 3), exp = 25)
}

boss.behavior {
    onTick(period = 20L) { mob ->
        if (mob.health < 40.0) mob.isGlowing = true
    }
    onDeath { mob, killer ->
        killer?.msg("<gold>Страж повержен")
    }
}
```

Доступные инструменты builder: MiniMessage-имя, текущее/максимальное здоровье, лечение, цель и сброс цели, постоянство, гравитация, коллизия, подбор предметов, огонь, скорость/вектор, телепортация, все слоты экипировки с шансом выпадения, эффекты зелий в секундах или тиках, а также исходные настройки AI и навигации Paper Pathfinder.

- `mob(location, EntityType, ...)` и `spawnMob(...)` создают одну сущность.
- `mobs(location, EntityType, amount, perTick, intervalTicks, ...)` и `spawnMobs(...)` создают группу постепенно, не спавня весь массив за один тик.
- Старые overloads с шаблонами `"TYPE:ZOMBIE;HP:40;AI:true"` остаются доступными.
- `behavior { onTick; onDeath; onDamaged; onAttack }` управляет событиями конкретной сущности.

Мобы и их обработчики привязаны к скрипту. По умолчанию созданные KSL-сущности удаляются при выгрузке скрипта; флаг `removeOnReload = false` сохраняет саму сущность, но зарегистрированные обработчики скрипта всё равно очищаются. Управление сущностями, путь и воспроизведение частиц/звука выполняй на основном потоке сервера.
