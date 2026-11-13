package ru.privateserver.ksl

/**
 * Immutable-snapshot cache for named SQL queries loaded from a script YAML file.
 * Replacing the snapshot is atomic, so DB worker threads never observe a half-refresh.
 */
internal class KSLYamlSqlQueryCache {
    @Volatile
    private var queries: Map<String, String> = emptyMap()

    fun replace(values: Map<String, String>) {
        queries = values.filter { (path, sql) -> path.isNotBlank() && sql.isNotBlank() }.toMap()
    }

    fun get(path: String): String? = queries[path]

    fun size(): Int = queries.size
}
