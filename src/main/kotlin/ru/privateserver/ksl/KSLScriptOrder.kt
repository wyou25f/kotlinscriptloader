package ru.privateserver.ksl

import java.io.File
import java.util.logging.Logger

data class KSLScriptDependencyInfo(
    val scriptId: String,
    val declared: List<String>,
    val resolved: List<String>,
    val missing: List<String>
)

// Orders scripts so that every script loads after the ones it names in "// @requires: a, b".
// A script is identified by its folder-relative id without the extension, e.g. "economy/shop".
object KSLScriptOrder {

    private val requiresRegex = Regex("""^//\s*@requires:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private const val HEADER_LINES_SCANNED = 20

    // "shop" also matches "economy/shop" when no other script is called shop, so old @requires lines keep working
    // after scripts are moved into folders.
    fun resolve(requirement: String, ids: Collection<String>): String? {
        val wanted = requirement.trim().removeSuffix(".kts").replace('\\', '/')
        if (wanted in ids) return wanted
        return ids.filter { it.substringAfterLast('/') == wanted }.singleOrNull()
    }

    // Pure part of the sort: ids and their (already resolved) requirements in, load order out.
    // Scripts without dependencies keep their given order; a cycle falls back to that order with a warning.
    fun sortIds(ids: List<String>, requires: Map<String, List<String>>, warn: (String) -> Unit): List<String> {
        val orderIndex = ids.withIndex().associate { (i, id) -> id to i }
        val inDegree = HashMap<String, Int>()
        val dependents = HashMap<String, MutableList<String>>()
        ids.forEach { inDegree[it] = 0 }
        requires.forEach { (script, deps) ->
            deps.forEach { dep ->
                inDegree[script] = (inDegree[script] ?: 0) + 1
                dependents.computeIfAbsent(dep) { mutableListOf() }.add(script)
            }
        }

        val result = mutableListOf<String>()
        val available = ids.filter { inDegree[it] == 0 }.toMutableList()
        while (available.isNotEmpty()) {
            available.sortBy { orderIndex.getValue(it) }
            val next = available.removeAt(0)
            result.add(next)
            dependents[next]?.forEach { dependent ->
                inDegree[dependent] = (inDegree[dependent] ?: 1) - 1
                if (inDegree[dependent] == 0) available.add(dependent)
            }
        }

        if (result.size != ids.size) {
            val stuck = ids - result.toSet()
            warn("${KSLError.SCRIPT_REQUIRES_CYCLE.tag} Обнаружена циклическая зависимость @requires среди скриптов: ${stuck.joinToString(", ")} — загружаю их в исходном порядке")
            result.addAll(stuck.sortedBy { orderIndex.getValue(it) })
        }
        return result
    }

    // Flat folder: the id is the file name.
    fun sort(files: Array<File>, logger: Logger): List<File> = sort(files.toList(), { it.nameWithoutExtension }, logger)

    /** Returns declared, resolved and missing script dependencies without changing load state. */
    fun inspect(files: List<File>, idOf: (File) -> String): List<KSLScriptDependencyInfo> {
        val ids = files.map(idOf)
        return files.map { file ->
            val id = idOf(file)
            val declared = readRequires(file).distinct()
            val resolved = declared.mapNotNull { resolve(it, ids) }.distinct()
            val missing = declared.filter { resolve(it, ids) == null }
            KSLScriptDependencyInfo(id, declared, resolved, missing)
        }.sortedBy { it.scriptId }
    }

    fun sort(files: List<File>, idOf: (File) -> String, logger: Logger): List<File> {
        val byId = files.associateBy(idOf)
        val ids = files.map(idOf)
        val requires = inspect(files, idOf).associate { info ->
            info.scriptId to info.resolved.also { _ ->
                info.missing.forEach { name ->
                    logger.warning("[${info.scriptId}] ${KSLError.SCRIPT_REQUIRES_MISSING.tag} @requires: '$name' не найден среди скриптов — зависимость проигнорирована")
                }
            }
        }
        return sortIds(ids, requires) { logger.warning(it) }.map { byId.getValue(it) }
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
