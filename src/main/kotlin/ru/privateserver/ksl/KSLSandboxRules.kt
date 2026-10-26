package ru.privateserver.ksl

import java.io.File

// Static source filter for accidental dangerous calls. It is NOT an isolation boundary:
// scripts run in-process with the plugin's rights and can still build names at runtime
// or use the DSL itself (executeConsole, dbExecute, ...).
object KSLSandboxRules {

    // No whitespace in patterns: the source is whitespace-stripped before matching.
    val forbidden = listOf(
        "java.lang.Runtime",
        "Runtime.getRuntime",
        "Runtime.exec",
        "java.lang.Process",
        "ProcessBuilder",
        "ProcessHandle",
        "java.lang.reflect.",
        "java.lang.invoke.",
        "MethodHandles",
        "kotlin.reflect.full.",
        "kotlin.reflect.jvm.",
        "Class.forName",
        ".getDeclaredMethod",
        ".getDeclaredField",
        ".getDeclaredConstructor",
        ".getMethod(",
        ".getField(",
        ".getConstructor(",
        "ClassLoader",
        "classLoader",
        "System.exit",
        "System.halt",
        "System.load",
        "sun.misc.",
        "sun.reflect.",
        "jdk.internal.",
        "com.sun.",
        "java.lang.instrument.",
        "javax.tools.",
        "java.rmi.",
        "java.net.Socket",
        "java.net.ServerSocket",
        "javax.script.",
        "kotlin.script.experimental.",
        "org.jetbrains.kotlin.",
        "org.objectweb.asm.",
        "javassist.",
        "net.bytebuddy."
    )

    data class Violation(val line: Int, val pattern: String)

    fun scan(file: File): List<Violation> = scanSource(file.readText())

    fun scanSource(source: String): List<Violation> {
        val (text, lineOf) = normalize(source)
        return forbidden
            .mapNotNull { pattern -> text.indexOf(pattern).takeIf { it >= 0 }?.let { Violation(lineOf[it], pattern) } }
            .sortedBy { it.line }
    }

    // Drops comments (nesting-aware, string-aware), whitespace and backticks so that
    // `/* x */ Runtime.getRuntime()`, "Runtime\n.getRuntime()" and Runtime.`getRuntime`() all match.
    // String contents are kept on purpose: "${Runtime.getRuntime()}" is executable code.
    private fun normalize(src: String): Pair<String, IntArray> {
        val out = StringBuilder(src.length)
        val lineOf = IntArray(src.length + 1)
        var line = 1
        var i = 0
        var blockDepth = 0
        var inLineComment = false
        var quote = "" // "\"", "\"\"\"" or "'"; empty means plain code

        fun emit(c: Char) {
            if (c.isWhitespace() || c == '`') return
            out.append(c)
            lineOf[out.length - 1] = line
        }

        while (i < src.length) {
            val c = src[i]
            val next = src.getOrNull(i + 1)
            if (c == '\n') {
                line++
                inLineComment = false
                // Only raw strings span lines; resync if a regular literal is left open.
                if (quote == "\"" || quote == "'") quote = ""
                i++
                continue
            }
            when {
                inLineComment -> i++
                blockDepth > 0 -> when {
                    c == '/' && next == '*' -> { blockDepth++; i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; i += 2 }
                    else -> i++
                }
                quote == "\"\"\"" -> {
                    if (src.startsWith("\"\"\"", i)) { repeat(3) { emit('"') }; quote = ""; i += 3 }
                    else { emit(c); i++ }
                }
                quote.isNotEmpty() -> when {
                    c == '\\' -> { emit(c); next?.let { if (it != '\n') emit(it) }; i += if (next == null || next == '\n') 1 else 2 }
                    c == quote[0] -> { emit(c); quote = ""; i++ }
                    else -> { emit(c); i++ }
                }
                c == '/' && next == '/' -> { inLineComment = true; i += 2 }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                src.startsWith("\"\"\"", i) -> { repeat(3) { emit('"') }; quote = "\"\"\""; i += 3 }
                c == '"' -> { emit(c); quote = "\""; i++ }
                c == '\'' -> { emit(c); quote = "'"; i++ }
                else -> { emit(c); i++ }
            }
        }
        return out.toString() to lineOf
    }
}
