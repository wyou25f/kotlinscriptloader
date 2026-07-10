package ru.privateserver.ksl

import java.io.File

object KSLSandboxRules {

    val forbidden = listOf(
        "java.lang.Runtime",
        "Runtime.getRuntime",
        "Runtime.exec",
        "java.lang.ProcessBuilder",
        "ProcessBuilder(",
        "java.lang.reflect.",
        "Class.forName",
        ".getDeclaredMethod",
        ".getDeclaredField",
        ".getDeclaredConstructor",
        "ClassLoader",
        "System.exit",
        "System.halt",
        "sun.misc.Unsafe",
        "jdk.internal.misc.Unsafe",
        "java.rmi.",
        "java.net.Socket",
        "java.net.ServerSocket",
        "javax.script.",
        "org.objectweb.asm.",
        "javassist.",
        "net.bytebuddy."
    )

    data class Violation(val line: Int, val pattern: String)

    fun scan(file: File): List<Violation> {
        val violations = mutableListOf<Violation>()
        val lines = file.readLines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
            for (pattern in forbidden) {
                if (line.contains(pattern)) {
                    violations += Violation(index + 1, pattern)
                    break
                }
            }
        }
        return violations
    }
}
