package ru.privateserver.ksl

object KSLTemplateParser {

    private val tokenRegex = Regex("""([A-Za-z_]+):("[^"]*"|\S+)""")

    fun parse(template: String): Map<String, String> {
        return tokenRegex.findAll(template).associate { match ->
            val key = match.groupValues[1].uppercase()
            var value = match.groupValues[2]
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }
    }
}
