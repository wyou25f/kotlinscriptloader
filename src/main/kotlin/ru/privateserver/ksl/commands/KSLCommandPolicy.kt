package ru.privateserver.ksl

// Decides whether a console command may be run by a script. Kept separate from the plugin so the rules can be tested.
object KSLCommandPolicy {

    // "/minecraft:OP Steve" -> "op". Slashes, any whitespace after the name and a namespace prefix must not hide a command.
    fun commandName(command: String): String =
        command.trim().trimStart('/').takeWhile { !it.isWhitespace() }.lowercase().substringAfter(':')

    fun isBlocked(command: String, blocklist: Set<String>): Boolean = commandName(command) in blocklist

    /**
     * Sandboxed scripts receive no console authority by default. An explicit allow-list
     * can grant only named root commands; the global deny-list always takes precedence.
     */
    fun isAllowedForScript(
        command: String,
        sandboxed: Boolean,
        blocklist: Set<String>,
        sandboxAllowlist: Set<String>
    ): Boolean {
        val name = commandName(command)
        if (name.isBlank() || name in blocklist) return false
        return !sandboxed || name in sandboxAllowlist
    }
}
