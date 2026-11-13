package ru.example.format

import org.bukkit.entity.Player

/** A small extension package that can be imported by KSL scripts. */
fun Player.shortServerLabel(): String = "Игрок: $name"

/** Formats a numeric balance without depending on a particular economy service. */
fun Long.coinLabel(): String = "$this монет"
