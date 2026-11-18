package ru.privateserver.ksl

// Formatting of script problems for the console and chat: short, deduplicated, never a wall of red text.
object KSLReport {

    // "строка 1: X" three times becomes one line marked (×3); a long list is cut with a pointer to /ksl errors.
    fun compactErrors(errors: List<String>, maxLines: Int = 5): List<String> {
        val counts = LinkedHashMap<String, Int>()
        errors.forEach { text -> counts.merge(text, 1) { a, b -> a + b } }
        val lines = counts.map { (text, count) -> if (count > 1) "$text (×$count)" else text }
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines) + "… и ещё ${lines.size - maxLines}: /ksl errors"
    }
}
