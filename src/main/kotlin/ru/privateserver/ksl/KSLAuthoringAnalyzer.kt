package ru.privateserver.ksl

/**
 * Conservative source-level checks for patterns that commonly stall the Paper main thread
 * or leave resources outside KSL's script lifecycle. These are warnings, never compile errors.
 */
internal object KSLAuthoringAnalyzer {
    data class Diagnostic(
        val code: String,
        val line: Int,
        val column: Int,
        val message: String
    )

    private data class Rule(val code: String, val pattern: Regex, val message: String)

    private val rules = listOf(
        Rule(
            "blocking-sleep",
            Regex("""\bThread\s*\.\s*sleep\s*\("""),
            "Thread.sleep blocks the current thread. It can stall script loading or synchronous callbacks; use KSL scheduling/worker APIs."
        ),
        Rule(
            "blocking-coroutine",
            Regex("""\brunBlocking\s*\{"""),
            "runBlocking blocks the current thread. Prefer suspending work on a worker and hand Bukkit operations back to the server thread."
        ),
        Rule(
            "unbounded-loop",
            Regex("""\bwhile\s*\(\s*true\s*\)\s*\{"""),
            "An unconditional loop can prevent the current callback from returning. Use a repeating KSL task with a bounded interval."
        ),
        Rule(
            "unmanaged-thread",
            Regex("""\bThread\s*(?:\(|\{)|\bExecutors\s*\.\s*new(?:SingleThreadExecutor|FixedThreadPool|CachedThreadPool|ScheduledThreadPool)"""),
            "A manually created thread/executor is not owned by the script lifecycle. Prefer KSL workers or register an explicit cleanup."
        ),
        Rule(
            "unmanaged-bukkit-task",
            Regex("""\bBukkit\s*\.\s*getScheduler\s*\(\s*\)\s*\.\s*runTask(?:Later|Timer|TimerLater)?(?:Asynchronously)?\s*\("""),
            "Direct Bukkit scheduler tasks are not automatically owned by the script. Prefer KSL after/repeat APIs or register cleanup."
        ),
        Rule(
            "blocking-addon-reload",
            Regex("""\b(?:KSL\s*\.\s*api|api)\s*\.\s*reloadScript\s*\("""),
            "KSLAPI.reloadScript blocks until compilation and swap finish. Use reloadScriptAsync from addon callbacks."
        )
    )

    fun analyze(source: String): List<Diagnostic> {
        if (source.isBlank()) return emptyList()
        val code = maskCommentsAndStrings(source)
        val diagnostics = mutableListOf<Diagnostic>()
        val seen = HashSet<Pair<String, Int>>()
        rules.forEach { rule ->
            rule.pattern.findAll(code).forEach { match ->
                if (seen.add(rule.code to match.range.first)) {
                    val before = code.substring(0, match.range.first)
                    val line = before.count { it == '\n' } + 1
                    val lineStart = before.lastIndexOf('\n') + 1
                    diagnostics += Diagnostic(rule.code, line, match.range.first - lineStart + 1, rule.message)
                }
            }
        }
        return diagnostics.sortedWith(compareBy<Diagnostic> { it.line }.thenBy { it.column })
    }

    private fun maskCommentsAndStrings(source: String): String {
        val result = source.toCharArray()
        var index = 0
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            if (current == '/' && next == '/') {
                val start = index
                while (index < source.length && source[index] != '\n') index++
                blank(result, start, index)
            } else if (current == '/' && next == '*') {
                val start = index
                index += 2
                var depth = 1
                while (index < source.length && depth > 0) {
                    when {
                        source[index] == '/' && source.getOrNull(index + 1) == '*' -> {
                            depth++
                            index += 2
                        }
                        source[index] == '*' && source.getOrNull(index + 1) == '/' -> {
                            depth--
                            index += 2
                        }
                        else -> index++
                    }
                }
                blank(result, start, index)
            } else if (current == '"' || current == '\'') {
                val start = index
                val triple = current == '"' && source.startsWith("\"\"\"", index)
                val quoteWidth = if (triple) 3 else 1
                index += quoteWidth
                var escaped = false
                while (index < source.length) {
                    if (triple && source.startsWith("\"\"\"", index)) {
                        index += 3
                        break
                    }
                    val c = source[index]
                    if (!triple && !escaped && c == current) {
                        index++
                        break
                    }
                    if (!triple && !escaped && c == '\\') escaped = true else escaped = false
                    index++
                }
                blank(result, start, index)
            } else {
                index++
            }
        }
        return String(result)
    }

    private fun blank(chars: CharArray, start: Int, end: Int) {
        for (i in start until end) if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' '
    }
}
