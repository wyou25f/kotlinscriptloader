package ru.privateserver.ksl

object KSLPersistCodec {

    fun encode(value: Any?): String? = when (value) {
        null -> "n:"
        is String -> "s:$value"
        is Int -> "i:$value"
        is Long -> "l:$value"
        is Double -> "d:$value"
        is Boolean -> "b:$value"
        else -> null
    }

    fun decode(raw: String?): Any? {
        if (raw == null || raw.length < 2 || raw[1] != ':') return null
        val type = raw[0]
        val body = raw.substring(2)
        return when (type) {
            'n' -> null
            's' -> body
            'i' -> body.toIntOrNull()
            'l' -> body.toLongOrNull()
            'd' -> body.toDoubleOrNull()
            'b' -> body.toBooleanStrictOrNull()
            else -> null
        }
    }
}
