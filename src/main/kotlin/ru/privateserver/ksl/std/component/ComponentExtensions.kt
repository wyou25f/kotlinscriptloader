package ru.privateserver.ksl.std.component

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

private val miniMessage: MiniMessage = MiniMessage.miniMessage()

/** Creates a literal text component. Markup characters are not interpreted. */
fun String.toTextComponent(): Component = Component.text(this)

/** Parses MiniMessage markup into an Adventure component. */
fun String.toMiniMessageComponent(): Component = miniMessage.deserialize(this)

/** Appends literal text to this component and returns a new component tree. */
fun Component.appendText(text: String): Component = append(Component.text(text))
