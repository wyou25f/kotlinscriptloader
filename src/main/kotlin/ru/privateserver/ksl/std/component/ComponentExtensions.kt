package ru.privateserver.ksl.std.component

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.privateserver.ksl.std.text.toMiniMessageMarkup

private val miniMessage: MiniMessage = MiniMessage.miniMessage()

/** Creates a literal text component. Markup characters are not interpreted. */
fun String.toTextComponent(): Component = Component.text(this)
/** Parses MiniMessage and supported legacy colors into an Adventure component. */
fun String.toMiniMessageComponent(): Component = miniMessage.deserialize(toMiniMessageMarkup())
/** Short alias for [toMiniMessageComponent]. */
fun String.mm(): Component = toMiniMessageComponent()
/** Appends literal text to this component. */
fun Component.appendText(text: String): Component = append(Component.text(text))
/** Parses and appends rich MiniMessage/legacy text. */
fun Component.appendRich(text: String): Component = append(text.toMiniMessageComponent())
/** Returns visible text without color or decoration metadata. */
fun Component.plainText(): String = PlainTextComponentSerializer.plainText().serialize(this)
/** Serializes an Adventure component into MiniMessage markup. */
fun Component.toMiniMessageString(): String = miniMessage.serialize(this)