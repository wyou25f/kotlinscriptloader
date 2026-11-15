# Предметы и собственные зачарования

KSL предоставляет компактный DSL для сборки предметов и отдельную систему KSL Custom Enchantments.

## Быстрый конструктор предметов

Пример:

    val sword = item(Material.DIAMOND_SWORD) {
        name("<aqua><bold>Ледяной клинок")
        lore("<gray>Оружие древнего мага", "<dark_aqua>Особое зачарование: Мороз")
        enchant(Enchantment.SHARPNESS, 5, unsafe = true)
        unbreakable()
        hideAttributes()
    }

Старый вызов item(material, name, lore, amount) остаётся доступен. Builder также поддерживает plainName, addLore, modelData, amount, flags, hideEnchants, hideUnbreakable, hideAll и customEnchant.

Большинство методов ItemStack возвращают клон предмета, поэтому исходный объект не меняется:

    val upgraded = item
        .withName("<gold>Сокровище")
        .withAddedLore("Найдено в подземелье")
        .enchant(Enchantment.UNBREAKING, 3)

Доступны короткие алиасы amount, enchant, unenchant, isOf, isSimilarTo, withFlags и withoutFlags.

## KSL Custom Enchantments

Это зачарования KSL, а не записи реестра ванильных org.bukkit.enchantments.Enchantment. Они хранят уровень в PersistentDataContainer предмета и запускают обработчики событий KSL.

Пример:

    import org.bukkit.Sound

    val frost = customEnchant("frost") {
        name("<aqua>Мороз")
        maxLevel(3)
        targets(Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)
        conflicts("flame")

        onHit {
            if (target is LivingEntity) {
                target.addPotionEffect(PotionEffect(PotionEffectType.SLOW, 40 * level, 0))
            }
            attacker.world.playSound(target.location, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.4f)
        }

        onKill {
            player.health = (player.health + 1.0 * level).coerceAtMost(player.maxHealth)
        }
    }

    val sword = item(Material.DIAMOND_SWORD) {
        name("<aqua><bold>Ледяной клинок")
        lore("<gray>Клинок, сковывающий врагов")
        customEnchant(frost, 2)
    }

### Триггеры

| Обработчик | Контекст |
|---|---|
| onHit | attacker, target, victim, item, level, event |
| onUse | player, item, level, action, clickedBlock, event |
| onBreak | player, block, item, level, event |
| onKill | player, target, item, level, event |

onHit работает для прямого удара игрока и снаряда, у которого игрок является владельцем. В onBreak и onKill проверяется предмет в главной руке. Обработчики выполняются на основном потоке Bukkit; тяжёлые вычисления не следует выполнять прямо в событии.

Зачарования скрипта автоматически снимаются из реестра при reload/unload. Уже созданные предметы сохраняют ключ и уровень; если скрипт снова зарегистрирует зачарование с тем же id, оно продолжит распознаваться. Если скрипт выгружен и не загружен обратно, данные сохраняются, но обработчики не вызываются.

Уровень ограничен диапазоном 1–255, предметы проверяются по targets, несовместимые ключи задаются через conflicts. По умолчанию зачарование разрешено на любом материале. Id локален для скрипта; для зачарования другого скрипта указывай полное имя script-name:id.

### Операции с предметом

    val stronger = sword
        .withCustomEnchant(frost, 3)
        .withEnchantment(Enchantment.UNBREAKING, 3)

    val level = stronger.customEnchantLevel(frost)
    val hasFrost = stronger.hasCustomEnchant(frost)
    val plain = stronger.withoutCustomEnchant(frost)

Методы withCustomEnchant, withoutCustomEnchant, withEnchantment и другие with... возвращают копию. Для изменения текущего предмета присвой результат обратно переменной.

Собственные зачарования отображаются в lore. Если требуется нестандартный текст уровня или визуальный формат, lore можно настроить после вызова builder.

Для зачарований брони доступен дополнительный обработчик `onDamageTaken`. Он вызывается при получении игроком урона от сущности для каждой подходящей надетой части брони; контекст содержит `player`, `attacker`, `item`, `slot`, `level` и `event`. Через `event.damage` можно изменить текущий урон. Пример с `onDamageTaken` приведён в [справочнике STD и миксинов](08-standard-library-mixins-and-messages.md).

## Границы совместимости

KSL Custom Enchantments не появятся в списке ванильных зачарований на столе зачарования и не будут автоматически поддерживаться сторонними плагинами, ожидающими Bukkit Enchantment. Для стандартных зачарований используй enchant(Enchantment, level).

Система не подменяет NBT/PDC API: customEnchant предназначен для игровых эффектов, а modifyNBT, modifyPersistentNBT и modifyComponents — для низкоуровневых данных и компонентов предметов. См. также NBT API (05-nbt-api.md).
