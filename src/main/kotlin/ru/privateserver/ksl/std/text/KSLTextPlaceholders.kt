package ru.privateserver.ksl

// Custom @name@ tokens for message()/messageList()/richMessage(), next to the built-in @player@, @world@ and so on.
object KSLTextPlaceholders {

    // Handled by the context itself; a custom token with one of these names would never be reached.
    val reserved = setOf("player", "world", "online", "maxplayers", "health", "ping")

    private val token = Regex("@([A-Za-z0-9_]+)@")

    // Tokens the lookup does not know are left as they are, so the "unmatched placeholder" warning can still report them.
    fun apply(text: String, lookup: (String) -> String?): String {
        if (text.indexOf('@') < 0) return text
        return token.replace(text) { match -> lookup(match.groupValues[1].lowercase()) ?: match.value }
    }
}
