# STD, цветные сообщения и миксины KSL

Это дополнение к основным книгам KSL: здесь собраны новые цветные методы, сокращения, KSL Mixins, обновление скриптового движка и защитные custom enchantments.

## Цветные сообщения

KSL принимает MiniMessage и legacy-коды Bukkit/Spigot. Поддерживаются `&a`, `§l`, `&#55CCFF` и шестизначная форма `&x&5&5&C&C&F&F`. Обычные амперсанды, которые не образуют код цвета, остаются текстом.

```kotlin
onEvent<PlayerJoinEvent> {
    player.msg("<gradient:#55CCFF:#AA55FF>Добро пожаловать, </gradient><white>" + player.name)
    player.ab("<gray>Приятной игры")
}

registerCommand("notice") { sender, _ ->
    sender.msg("&#55CCFFЦветное сообщение <bold>с MiniMessage</bold>")
}
```

Новые короткие методы:

| Метод | Назначение |
|---|---|
| `sender.msg(text)` | Отправить MiniMessage/legacy текст игроку, командному отправителю или консоли |
| `sender.sendMM(text)` | Альтернативное имя `msg` |
| `player.ab(text)` | ActionBar |
| `player.actionBar(text)` | Полное имя ActionBar |
| `player.t(title, subtitle)` | Короткий Title |
| `player.title(title, subtitle)` | Полное имя Title |
| `mm(text)` / `text.mm()` | Строка в Adventure Component |
| `broadcastRich(text)` | Форматированная рассылка |
| `text.colors()` | Legacy-коды в `§`-формат |
| `text.mmMarkup()` | Legacy-коды в MiniMessage-теги |

Доступны и расширенные методы: `parseMM`, `sendRichMessage`, `sendActionBarMessage`, `showRichTitle`, `broadcastMM`, `toMiniMessageComponent`, `toMiniMessageMarkup`, `colorizeLegacy` и `stripLegacyColors`. Старые методы сохранены. Для пользовательского текста, в котором markup не нужно интерпретировать, используй `toTextComponent()`.

## KSL Mixins

KSL Mixins — это скриптовая композиция поведения. Они позволяют собрать несколько событий, периодических действий и одноразовых задач в один именованный объект, а затем установить его в текущий скрипт. Все обработчики и задачи принадлежат этому скрипту и снимаются при reload/unload.

```kotlin
val joinBehavior = mixin("join-behavior") {
    description("Общие реакции на вход игрока")

    on<PlayerJoinEvent> {
        player.msg("<green>С возвращением, " + player.name + "!")
    }

    every(periodTicks = 20L) {
        onlinePlayers().forEach { online ->
            online.ab("<gray>Статус сервера")
        }
    }

    after(delayTicks = 5L) {
        // Инициализация после применения поведения.
    }
}

val installed = useMixin(joinBehavior)
```

Builder поддерживает `description(text)`, `on<Event> { ... }`, `every(periodTicks, initialDelayTicks) { ... }`, `after(delayTicks) { ... }` и `install { ... }`. `applyMixin(mixin)` — полное имя `useMixin`. Повторное применение одного объекта к тому же контексту возвращает `false`. Миксин нельзя установить в контекст другого скрипта.

**Важно:** это KSL Mixins — композиция событий и задач уровня скриптов, а не Sponge Mixin. Это не подмена байткода Minecraft и не `@Inject`/`@Overwrite`; такие bytecode-инъекции нельзя безопасно гарантировать из обычного hot-reload Bukkit/Paper-плагина.

## Обновление движка скриптов

`/ksl reload changed` сравнивает SHA-256 содержимого `.kts` файлов, а не только время изменения и размер. Это помогает не пропустить изменение, когда timestamp и размер остались прежними. Проверка sandbox и компиляция используют один снимок исходника. Успешная сборка запоминает fingerprint именно скомпилированного снимка; если файл меняется во время компиляции, следующий reload заметит новую версию.

## Защитные зачарования брони

Для KSL Custom Enchantments добавлен `onDamageTaken`. Он вызывается для каждой надетой части брони с этим зачарованием, когда игрок получает урон от сущности. Контекст включает `player`, `attacker`, `item`, `slot`, `level` и исходный `event`.

```kotlin
val ward = customEnchant("ward") {
    name("<aqua>Защита стража")
    maxLevel(3)
    targets(Material.IRON_HELMET, Material.IRON_CHESTPLATE)

    onDamageTaken {
        event.damage = (event.damage - level * 0.5).coerceAtLeast(0.0)
    }
}

val helmet = item(Material.IRON_HELMET) {
    customEnchant(ward, 2)
}
```

`slot` указывает `FEET`, `LEGS`, `CHEST` или `HEAD`. Здесь изменяется текущий `EntityDamageByEntityEvent`; обычные `onHit`, `onUse`, `onBreak` и `onKill` работают как раньше.

## Совместимость и версия API

Существующие имена методов не удалялись. Новые сокращения добавлены поверх прежних вызовов. Публичная версия KSL API повышена до 9.