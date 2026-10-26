package ru.privateserver.ksl

import java.io.File
import java.util.logging.Logger

object KSLScriptOrder {

    private val requiresRegex = Regex("""^//\s*@requires:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private const val HEADER_LINES_SCANNED = 20

    fun sort(files: Array<File>, logger: Logger): List<File> {
        val byName = files.associateBy { it.nameWithoutExtension }
        val deps = HashMap<String, List<String>>()

        files.forEach { file ->
            val required = readRequires(file)
            val resolved = required.filter { name ->
                if (name !in byName) {
                    logger.warning("[${file.nameWithoutExtension}] @requires: '$name' не найден среди скриптов — зависимость проигнорирована")
                    false
                } else true
            }
            deps[file.nameWithoutExtension] = resolved
        }

        val originalOrder = files.map { it.nameWithoutExtension }
        val orderIndex = originalOrder.withIndex().associate { (i, name) -> name to i }

        val inDegree = HashMap<String, Int>()
        val dependents = HashMap<String, MutableList<String>>()
        originalOrder.forEach { name -> inDegree[name] = 0 }
        deps.forEach { (script, requires) ->
            requires.forEach { dep ->
                inDegree[script] = (inDegree[script] ?: 0) + 1
                dependents.computeIfAbsent(dep) { mutableListOf() }.add(script)
            }
        }

        val result = mutableListOf<String>()
        val available = originalOrder.filter { inDegree[it] == 0 }.toMutableList()

        while (available.isNotEmpty()) {
            available.sortBy { orderIndex.getValue(it) }
            val next = available.removeAt(0)
            result.add(next)
            dependents[next]?.forEach { dependent ->
                inDegree[dependent] = (inDegree[dependent] ?: 1) - 1
                if (inDegree[dependent] == 0) available.add(dependent)
            }
        }

        if (result.size != originalOrder.size) {
            val stuck = originalOrder - result.toSet()
            logger.warning("Обнаружена циклическая зависимость @requires среди скриптов: ${stuck.joinToString(", ")} — загружаю их в исходном порядке")
            result.addAll(stuck.sortedBy { orderIndex.getValue(it) })
        }

        return result.map { byName.getValue(it) }
    }

    private fun readRequires(file: File): List<String> {
        return try {
            file.bufferedReader().useLines { lines ->
                lines.take(HEADER_LINES_SCANNED)
                    .mapNotNull { line -> requiresRegex.find(line.trim())?.groupValues?.get(1) }
                    .map { it.split(",") }.flatten()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        } catch (ex: Exception) {
            emptyList()
        }
    }
}
