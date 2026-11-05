package ru.privateserver.ksl

// Decides whether a console command may be run by a script. Kept separate from the plugin so the rules can be tested.
object KSLCommandPolicy {

    // "/minecraft:OP Steve" -> "op". Slashes, any whitespace after the name and a namespace prefix must not hide a command.
    fun commandName(command: String): String =
        command.trim().trimStart('/').takeWhile { !it.isWhitespace() }.lowercase().substringAfter(':')

    fun isBlocked(command: String, blocklist: Set<String>): Boolean = commandName(command) in blocklist
}
