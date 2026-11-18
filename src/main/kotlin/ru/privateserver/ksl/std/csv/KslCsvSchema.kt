@file:Suppress("unused")

package ru.privateserver.ksl.std.csv

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

enum class KslCsvType {
    TEXT, INT, LONG, DECIMAL, DOUBLE, BOOLEAN, UUID, DATE, INSTANT;
    internal fun accepts(value: String): Boolean = when (this) {
        TEXT -> true
        INT -> value.toIntOrNull() != null
        LONG -> value.toLongOrNull() != null
        DECIMAL -> value.toBigDecimalOrNull() != null
        DOUBLE -> value.toDoubleOrNull()?.isFinite() == true
        BOOLEAN -> value.trim().lowercase(Locale.ROOT) in setOf("true", "false", "1", "0", "yes", "no", "on", "off")
        UUID -> runCatching { UUID.fromString(value) }.isSuccess
        DATE -> runCatching { LocalDate.parse(value) }.isSuccess
        INSTANT -> runCatching { Instant.parse(value) }.isSuccess
    }
}

data class KslCsvColumn(
    val name: String,
    val type: KslCsvType = KslCsvType.TEXT,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val trim: Boolean = false,
    val allowedValues: Set<String> = emptySet()
) {
    init {
        require(name.isNotBlank()) { "CSV schema column name must not be blank" }
        require(defaultValue == null || type.accepts(defaultValue)) {
            "Default value for '$name' is not a valid " + type.name
        }
        require(allowedValues.none { it.isBlank() }) { "CSV allowed values for '$name' cannot contain blanks" }
        require(allowedValues.all(type::accepts)) { "CSV allowed values for '$name' must match " + type.name }
        require(defaultValue == null || allowedValues.isEmpty() || defaultValue in allowedValues) {
            "Default value for '$name' is not in the allowed values"
        }
    }
}

data class KslCsvIssue(val rowNumber: Int?, val column: String, val code: String, val message: String)

data class KslCsvValidationResult(
    val issues: List<KslCsvIssue>,
    val checkedRows: Int,
    val issuesTruncated: Boolean = false
) {
    val isValid: Boolean get() = issues.isEmpty()
    val errorCount: Int get() = issues.size
}

data class KslCsvNormalizationResult(val table: KslCsvTable, val validation: KslCsvValidationResult) {
    val isValid: Boolean get() = validation.isValid
}

class KslCsvSchema(
    columns: List<KslCsvColumn>,
    val allowAdditionalColumns: Boolean = false,
    val maxIssues: Int = 1_000
) {
    val columns: List<KslCsvColumn> = columns.toList()

    init {
        require(this.columns.isNotEmpty()) { "CSV schema must contain at least one column" }
        require(this.columns.map { it.name }.distinct().size == this.columns.size) { "CSV schema column names must be unique" }
        require(maxIssues in 1..10_000) { "maxIssues must be in 1..10,000" }
    }

    fun validate(table: KslCsvTable): KslCsvValidationResult {
        val issues = mutableListOf<KslCsvIssue>()
        var truncated = false
        fun report(issue: KslCsvIssue) {
            if (issues.size < maxIssues) issues += issue else truncated = true
        }

        val expected = columns.mapTo(linkedSetOf()) { it.name }
        val actual = table.columns.toSet()
        columns.filterNot { it.name in actual }.forEach { column ->
            report(KslCsvIssue(null, column.name, "missing_column", "Required schema column '" + column.name + "' is missing"))
        }
        if (!allowAdditionalColumns) {
            table.columns.filterNot { it in expected }.forEach { extra ->
                report(KslCsvIssue(null, extra, "unexpected_column", "Column '$extra' is not declared by this schema"))
            }
        }
        columns.filter { it.name in actual }.forEach { column ->
            for (index in 0 until table.rowCount) {
                val raw = table[index][column.name]
                val value = if (column.trim) raw.trim() else raw
                if (value.isBlank()) {
                    if (column.required) report(KslCsvIssue(index + 1, column.name, "required", "Value for '" + column.name + "' is required"))
                    continue
                }
                if (!column.type.accepts(value)) {
                    report(KslCsvIssue(index + 1, column.name, "invalid_type", "Value '$value' is not a valid " + column.type.name))
                    continue
                }
                if (column.allowedValues.isNotEmpty() && value !in column.allowedValues) {
                    report(KslCsvIssue(index + 1, column.name, "not_allowed", "Value '$value' is not allowed for '" + column.name + "'"))
                }
            }
        }
        return KslCsvValidationResult(issues.toList(), table.rowCount, truncated)
    }

    fun normalize(table: KslCsvTable): KslCsvNormalizationResult {
        val working = table.copy()
        columns.forEach { column ->
            if (!working.containsColumn(column.name)) working.addColumn(column.name, column.defaultValue ?: "")
        }
        columns.forEach { column ->
            if (column.trim || column.defaultValue != null) {
                working.updateRows({ true }) {
                    val current = get(column.name)
                    val value = if (column.trim) current.trim() else current
                    this[column.name] = if (value.isEmpty() && column.defaultValue != null) column.defaultValue else value
                }
            }
        }
        val schemaNames = columns.map { it.name }.toSet()
        val orderedNames = columns.map { it.name } + working.columns.filterNot { it in schemaNames }
        val ordered = working.select(*orderedNames.toTypedArray())
        return KslCsvNormalizationResult(ordered, validate(ordered))
    }
}

class KslCsvSchemaBuilder internal constructor(
    private val allowAdditionalColumns: Boolean,
    private val maxIssues: Int
) {
    private val definitions = mutableListOf<KslCsvColumn>()

    fun column(
        name: String,
        type: KslCsvType = KslCsvType.TEXT,
        required: Boolean = false,
        defaultValue: Any? = null,
        trim: Boolean = false,
        allowedValues: Set<String> = emptySet()
    ) {
        val encoded = when (defaultValue) {
            null -> null
            is BigDecimal -> defaultValue.stripTrailingZeros().toPlainString()
            else -> defaultValue.toString()
        }
        definitions += KslCsvColumn(name, type, required, encoded, trim, allowedValues.toSet())
    }

    fun text(name: String, required: Boolean = false, defaultValue: String? = null, trim: Boolean = false) =
        column(name, KslCsvType.TEXT, required, defaultValue, trim)
    fun int(name: String, required: Boolean = false, defaultValue: Int? = null) =
        column(name, KslCsvType.INT, required, defaultValue)
    fun long(name: String, required: Boolean = false, defaultValue: Long? = null) =
        column(name, KslCsvType.LONG, required, defaultValue)
    fun decimal(name: String, required: Boolean = false, defaultValue: BigDecimal? = null) =
        column(name, KslCsvType.DECIMAL, required, defaultValue)
    fun double(name: String, required: Boolean = false, defaultValue: Double? = null) =
        column(name, KslCsvType.DOUBLE, required, defaultValue)
    fun bool(name: String, required: Boolean = false, defaultValue: Boolean? = null) =
        column(name, KslCsvType.BOOLEAN, required, defaultValue)
    fun uuid(name: String, required: Boolean = false, defaultValue: UUID? = null) =
        column(name, KslCsvType.UUID, required, defaultValue)
    fun date(name: String, required: Boolean = false, defaultValue: LocalDate? = null) =
        column(name, KslCsvType.DATE, required, defaultValue)
    fun instant(name: String, required: Boolean = false, defaultValue: Instant? = null) =
        column(name, KslCsvType.INSTANT, required, defaultValue)

    internal fun build(): KslCsvSchema = KslCsvSchema(definitions, allowAdditionalColumns, maxIssues)
}

fun csvSchema(
    allowAdditionalColumns: Boolean = false,
    maxIssues: Int = 1_000,
    block: KslCsvSchemaBuilder.() -> Unit
): KslCsvSchema = KslCsvSchemaBuilder(allowAdditionalColumns, maxIssues).apply(block).build()
