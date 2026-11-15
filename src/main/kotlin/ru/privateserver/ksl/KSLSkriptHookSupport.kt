package ru.privateserver.ksl

data class KSLSkriptDiagnostics(
    val installed: Boolean,
    val enabled: Boolean,
    val version: String?,
    val variablesApi: Boolean,
    val commandsApi: Boolean,
    val scriptIntrospection: Boolean
) {
    val isReady: Boolean get() = enabled && variablesApi && commandsApi && scriptIntrospection
}

internal object KSLSkriptNames {
    fun variableName(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "Skript variable name cannot be blank" }
        require('\n' !in value && '\r' !in value) { "Skript variable name cannot contain line breaks" }
        return if (value.startsWith("{") && value.endsWith("}")) value.substring(1, value.length - 1) else value
    }

    fun commandLabel(input: String): String? {
        val value = input.trim().removePrefix("/")
        if (value.isEmpty() || '\n' in value || '\r' in value) return null
        return value.substringBefore(' ').substringBefore('\t').lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    fun scriptPath(input: String): String? {
        val value = input.trim().replace('\\', '/').removePrefix("./")
        if (value.isBlank() || '\n' in value || '\r' in value || value.any(Char::isWhitespace)) return null
        val segments = value.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return if (value.endsWith(".sk", ignoreCase = true)) value else "$value.sk"
    }
}
