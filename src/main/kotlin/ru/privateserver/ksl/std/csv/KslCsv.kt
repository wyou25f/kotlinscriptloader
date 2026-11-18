@file:Suppress("unused")

package ru.privateserver.ksl.std.csv

import ru.privateserver.ksl.storage.KSLAtomicFileWriter
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Parser, writer and resource limits for an RFC 4180-style CSV document. */
data class KslCsvOptions(
    val delimiter: Char = ',',
    val quote: Char = '"',
    val hasHeader: Boolean = true,
    val trimUnquoted: Boolean = false,
    val skipEmptyLines: Boolean = true,
    val lineSeparator: String = "\r\n",
    val maxRows: Int = 100_000,
    val maxColumns: Int = 256,
    val maxFieldCharacters: Int = 1_048_576,
    val maxTotalCharacters: Int = 16_777_216
) {
    init {
        require(delimiter != quote && delimiter != '\r' && delimiter != '\n') {
            "CSV delimiter must differ from quote and cannot be a line break"
        }
        require(quote != '\r' && quote != '\n') { "CSV quote cannot be a line break" }
        require(lineSeparator == "\n" || lineSeparator == "\r\n") {
            "CSV lineSeparator must be LF or CRLF"
        }
        require(maxRows in 0..1_000_000) { "maxRows must be in 0..1,000,000" }
        require(maxColumns in 1..4096) { "maxColumns must be in 1..4096" }
        require(maxFieldCharacters in 1..16_777_216) {
            "maxFieldCharacters must be in 1..16,777,216"
        }
        require(maxTotalCharacters in 1..64 * 1024 * 1024) {
            "maxTotalCharacters must be in 1..64 Mi characters"
        }
    }
}

/** Mutable view over one table row, with name- and index-based typed accessors. */
class KslCsvRow internal constructor(
    private val headers: List<String>,
    private val values: MutableList<String>
) {
    val size: Int get() = headers.size
    val valuesSnapshot: List<String> get() = values.toList()

    operator fun get(column: String): String = values[indexOf(column)]
    operator fun get(index: Int): String = values[index]

    operator fun set(column: String, value: Any?) {
        values[indexOf(column)] = cell(value)
    }

    operator fun set(index: Int, value: Any?) {
        values[index] = cell(value)
    }

    fun getString(column: String, default: String = ""): String =
        get(column).ifEmpty { default }

    fun getInt(column: String, default: Int = 0): Int =
        get(column).toIntOrNull() ?: default

    fun requireInt(column: String): Int =
        get(column).toIntOrNull() ?: throw IllegalArgumentException("CSV column '$column' is not a valid Int")

    fun getLong(column: String, default: Long = 0L): Long =
        get(column).toLongOrNull() ?: default

    fun requireLong(column: String): Long =
        get(column).toLongOrNull() ?: throw IllegalArgumentException("CSV column '$column' is not a valid Long")

    fun getDouble(column: String, default: Double = 0.0): Double =
        get(column).toDoubleOrNull()?.takeIf { it.isFinite() } ?: default

    fun requireDouble(column: String): Double =
        get(column).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("CSV column '$column' is not a finite Double")

    fun getBoolean(column: String, default: Boolean = false): Boolean =
        when (get(column).trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }

    fun requireBoolean(column: String): Boolean =
        when (get(column).trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> throw IllegalArgumentException("CSV column '$column' is not a valid Boolean")
        }

    fun getDecimal(column: String, default: BigDecimal = BigDecimal.ZERO): BigDecimal =
        get(column).toBigDecimalOrNull() ?: default

    fun requireDecimal(column: String): BigDecimal =
        get(column).toBigDecimalOrNull()
            ?: throw IllegalArgumentException("CSV column '$column' is not a valid Decimal")

    fun getUuid(column: String, default: UUID? = null): UUID? =
        runCatching { UUID.fromString(get(column)) }.getOrNull() ?: default

    fun requireUuid(column: String): UUID =
        runCatching { UUID.fromString(get(column)) }.getOrElse {
            throw IllegalArgumentException("CSV column '$column' is not a valid UUID", it)
        }

    fun getLocalDate(column: String, default: LocalDate? = null): LocalDate? =
        runCatching { LocalDate.parse(get(column)) }.getOrNull() ?: default

    fun requireLocalDate(column: String): LocalDate =
        runCatching { LocalDate.parse(get(column)) }.getOrElse {
            throw IllegalArgumentException("CSV column '$column' is not an ISO-8601 date", it)
        }

    fun getInstant(column: String, default: Instant? = null): Instant? =
        runCatching { Instant.parse(get(column)) }.getOrNull() ?: default

    fun requireInstant(column: String): Instant =
        runCatching { Instant.parse(get(column)) }.getOrElse {
            throw IllegalArgumentException("CSV column '$column' is not an ISO-8601 instant", it)
        }

    fun requireNotBlank(column: String): String =
        get(column).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("CSV column '$column' must not be blank")

    fun asMap(): Map<String, String> =
        headers.indices.associate { headers[it] to values[it] }

    private fun indexOf(column: String): Int =
        headers.indexOf(column).takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Unknown CSV column '$column'; available: ${headers.joinToString()}")

    private fun cell(value: Any?): String = when (value) {
        null -> ""
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        else -> value.toString()
    }
}

/**
 * An editable table abstraction for scripts and addons.
 *
 * Header names are validated once; rows keep a fixed width and missing cells are padded
 * with empty strings. All public collection getters return snapshots.
 */
class KslCsvTable(columns: List<String>) {
    private val headers = columns.toMutableList()
    private val data = mutableListOf<MutableList<String>>()

    init {
        validateHeaders(headers)
    }

    val columns: List<String> get() = headers.toList()
    val headersSnapshot: List<String> get() = columns
    val rowCount: Int get() = data.size
    val columnCount: Int get() = headers.size
    val size: Int get() = data.size
    val isEmpty: Boolean get() = data.isEmpty()
    val isNotEmpty: Boolean get() = data.isNotEmpty()

    val rows: List<KslCsvRow>
        get() = data.map { KslCsvRow(headers, it) }

    operator fun get(index: Int): KslCsvRow = row(index)
    operator fun get(column: String): List<String> = column(column)

    fun row(index: Int): KslCsvRow = KslCsvRow(headers, data[index])

    fun column(name: String): List<String> {
        val index = columnIndex(name)
        return data.map { it[index] }
    }

    fun column(index: Int): List<String> {
        require(index in headers.indices) { "CSV column index $index is out of bounds" }
        return data.map { it[index] }
    }

    fun containsColumn(name: String): Boolean = name in headers
    fun indexOfColumn(name: String): Int = headers.indexOf(name)

    fun renameColumn(oldName: String, newName: String): Boolean {
        val index = headers.indexOf(oldName)
        if (index < 0) return false
        require(newName.isNotBlank()) { "CSV column name must not be blank" }
        require(newName == oldName || newName !in headers) { "Duplicate CSV column '$newName'" }
        headers[index] = newName
        return true
    }

    fun append(vararg values: Any?): KslCsvRow = addRow(values.toList())

    /** Adds a row by position, padding omitted cells with empty strings. */
    fun addRow(vararg values: Any?): KslCsvRow = addRow(values.toList())

    fun addRow(values: List<Any?>): KslCsvRow {
        require(values.size <= headers.size) {
            "CSV row has ${values.size} values, but the table has only ${headers.size} columns"
        }
        val row = MutableList(headers.size) { index ->
            values.getOrNull(index)?.let(::cell) ?: ""
        }
        data += row
        return KslCsvRow(headers, row)
    }

    fun addRow(values: Map<String, Any?>): KslCsvRow {
        val unknown = values.keys - headers.toSet()
        require(unknown.isEmpty()) { "Unknown CSV columns: ${unknown.joinToString()}" }
        val row = headers.map { column -> values[column]?.let(::cell) ?: "" }.toMutableList()
        data += row
        return KslCsvRow(headers, row)
    }

    fun addRow(vararg values: Pair<String, Any?>): KslCsvRow {
        val map = linkedMapOf<String, Any?>()
        values.forEach { (key, value) ->
            require(!map.containsKey(key)) { "CSV row contains duplicate column '$key'" }
            map[key] = value
        }
        return addRow(map)
    }

    fun insertRow(index: Int, values: List<Any?>): KslCsvRow {
        require(index in 0..data.size) { "CSV row index $index is out of bounds" }
        require(values.size <= headers.size) {
            "CSV row has ${values.size} values, but the table has only ${headers.size} columns"
        }
        val row = MutableList(headers.size) { column ->
            values.getOrNull(column)?.let(::cell) ?: ""
        }
        data.add(index, row)
        return KslCsvRow(headers, row)
    }

    fun removeRow(index: Int): List<String> = data.removeAt(index).toList()

    fun firstOrNull(): KslCsvRow? = data.firstOrNull()?.let { KslCsvRow(headers, it) }
    fun lastOrNull(): KslCsvRow? = data.lastOrNull()?.let { KslCsvRow(headers, it) }
    fun first(): KslCsvRow = firstOrNull() ?: throw NoSuchElementException("CSV table is empty")
    fun last(): KslCsvRow = lastOrNull() ?: throw NoSuchElementException("CSV table is empty")

    fun takeRows(count: Int): KslCsvTable {
        require(count >= 0) { "Row count must not be negative" }
        return fromRows(headers, data.take(count))
    }

    fun dropRows(count: Int): KslCsvTable {
        require(count >= 0) { "Row count must not be negative" }
        return fromRows(headers, data.drop(count))
    }

    fun removeRows(predicate: (KslCsvRow) -> Boolean): Int {
        val before = data.size
        data.removeAll { predicate(KslCsvRow(headers, it)) }
        return before - data.size
    }

    fun clearRows() = data.clear()

    fun addColumn(name: String, defaultValue: Any? = null) {
        validateNewColumn(name)
        headers += name
        val value = cell(defaultValue)
        data.forEach { it += value }
    }

    fun removeColumn(name: String): Boolean {
        val index = headers.indexOf(name)
        if (index < 0) return false
        headers.removeAt(index)
        data.forEach { it.removeAt(index) }
        return true
    }

    fun findFirst(column: String, value: String): KslCsvRow? {
        val index = columnIndex(column)
        val row = data.firstOrNull { it[index] == value } ?: return null
        return KslCsvRow(headers, row)
    }

    fun findAll(column: String, value: String): List<KslCsvRow> {
        val index = columnIndex(column)
        return data.filter { it[index] == value }.map { KslCsvRow(headers, it) }
    }

    fun upsertBy(column: String, values: Map<String, Any?>): KslCsvRow {
        val keyIndex = columnIndex(column)
        require(column in values) { "upsertBy requires the key column '$column' in values" }
        val unknown = values.keys - headers.toSet()
        require(unknown.isEmpty()) { "Unknown CSV columns: " + unknown.joinToString() }
        val keyValue = values[column]?.let(::cell) ?: ""
        val existing = data.firstOrNull { it[keyIndex] == keyValue }
        if (existing == null) return addRow(values)
        val row = KslCsvRow(headers, existing)
        values.forEach { (name, value) -> row[name] = value }
        return row
    }

    fun updateRows(
        predicate: (KslCsvRow) -> Boolean,
        transform: KslCsvRow.() -> Unit
    ): Int {
        var changed = 0
        data.forEach { values ->
            val row = KslCsvRow(headers, values)
            if (predicate(row)) {
                row.transform()
                changed++
            }
        }
        return changed
    }

    fun filterRows(predicate: (KslCsvRow) -> Boolean): KslCsvTable =
        KslCsvTable(headers).also { target ->
            data.filter { predicate(KslCsvRow(headers, it)) }.forEach { target.addRow(it) }
        }

    fun select(vararg columns: String): KslCsvTable {
        require(columns.isNotEmpty()) { "select requires at least one CSV column" }
        val indices = columns.map(::columnIndex)
        return KslCsvTable(columns.toList()).also { target ->
            data.forEach { row -> target.addRow(indices.map { row[it] }) }
        }
    }

    fun distinctBy(column: String): KslCsvTable {
        val index = columnIndex(column)
        val seen = HashSet<String>()
        return KslCsvTable(headers).also { target ->
            data.forEach { row -> if (seen.add(row[index])) target.addRow(row) }
        }
    }

    fun groupRowsBy(column: String): Map<String, KslCsvTable> {
        val index = columnIndex(column)
        val groups = linkedMapOf<String, KslCsvTable>()
        data.forEach { row ->
            val key = row[index]
            groups.getOrPut(key) { KslCsvTable(headers) }.addRow(row)
        }
        return groups
    }

    fun sortedBy(
        column: String,
        comparator: Comparator<String> = naturalOrder(),
        descending: Boolean = false
    ): KslCsvTable = copy().also { it.sortBy(column, comparator, descending) }

    fun <T> mapRows(transform: (KslCsvRow) -> T): List<T> =
        data.map { transform(KslCsvRow(headers, it)) }

    fun sortBy(
        column: String,
        comparator: Comparator<String> = naturalOrder(),
        descending: Boolean = false
    ) {
        val index = columnIndex(column)
        data.sortWith { left, right ->
            val result = comparator.compare(left[index], right[index])
            if (descending) -result else result
        }
    }

    fun toMaps(): List<Map<String, String>> =
        data.map { row -> headers.indices.associate { headers[it] to row[it] } }

    fun copy(): KslCsvTable =
        KslCsvTable(headers).also { target ->
            data.forEach { target.data += it.toMutableList() }
        }

    internal fun rawRows(): List<List<String>> = data.map { it.toList() }

    private fun columnIndex(name: String): Int =
        headers.indexOf(name).takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Unknown CSV column '$name'; available: ${headers.joinToString()}")

    private fun validateNewColumn(name: String) {
        require(name.isNotBlank()) { "CSV column name must not be blank" }
        require(name !in headers) { "Duplicate CSV column '$name'" }
        require(headers.size < 4096) { "CSV table cannot exceed 4096 columns" }
    }

    private fun validateHeaders(values: List<String>) {
        require(values.size <= 4096) { "CSV table cannot exceed 4096 columns" }
        require(values.all { it.isNotBlank() }) { "CSV column names must not be blank" }
        require(values.distinct().size == values.size) { "CSV column names must be unique" }
    }

    private fun cell(value: Any?): String = when (value) {
        null -> ""
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        else -> value.toString()
    }

    companion object {
        fun fromMaps(columns: List<String>, rows: Iterable<Map<String, Any?>>): KslCsvTable =
            KslCsvTable(columns).also { table -> rows.forEach { table.addRow(it) } }

        fun fromRows(columns: List<String>, rows: Iterable<List<Any?>>): KslCsvTable =
            KslCsvTable(columns).also { table -> rows.forEach { table.addRow(it) } }
    }
}

object KslCsvCodec {
    private data class ParsedField(val value: String, val quoted: Boolean)

    /** Parses RFC 4180-style CSV, including escaped quotes and newlines inside quoted fields. */
    @JvmStatic
    @JvmOverloads
    fun parse(source: String, options: KslCsvOptions = KslCsvOptions()): KslCsvTable {
        val text = source.removePrefix("\uFEFF")
        require(text.length <= options.maxTotalCharacters) {
            "CSV input exceeds ${options.maxTotalCharacters} characters"
        }

        val records = mutableListOf<List<ParsedField>>()
        val fields = mutableListOf<ParsedField>()
        val field = StringBuilder()
        var inQuotes = false
        var afterQuote = false
        var fieldQuoted = false
        var recordTouched = false
        var index = 0

        fun append(value: String) {
            require(field.length + value.length <= options.maxFieldCharacters) {
                "CSV field exceeds ${options.maxFieldCharacters} characters"
            }
            field.append(value)
        }

        fun endField() {
            val raw = field.toString()
            val value = if (!fieldQuoted && options.trimUnquoted) raw.trim() else raw
            fields += ParsedField(value, fieldQuoted)
            require(fields.size <= options.maxColumns) {
                "CSV record exceeds ${options.maxColumns} columns"
            }
            field.setLength(0)
            fieldQuoted = false
            afterQuote = false
        }

        fun endRecord() {
            endField()
            if (!(options.skipEmptyLines && !recordTouched)) {
                val maxRecords = options.maxRows + if (options.hasHeader) 1 else 0
                require(records.size < maxRecords) { "CSV input exceeds ${options.maxRows} data rows" }
                records += fields.toList()
            }
            fields.clear()
            recordTouched = false
        }

        fun newlineLength(at: Int): Int =
            if (text[at] == '\r' && at + 1 < text.length && text[at + 1] == '\n') 2 else 1

        while (index < text.length) {
            val ch = text[index]
            if (inQuotes) {
                when {
                    ch == options.quote -> {
                        if (index + 1 < text.length && text[index + 1] == options.quote) {
                            append(options.quote.toString())
                            index += 2
                        } else {
                            inQuotes = false
                            afterQuote = true
                            index++
                        }
                    }
                    ch == '\r' || ch == '\n' -> {
                        val length = newlineLength(index)
                        append(text.substring(index, index + length))
                        index += length
                    }
                    else -> {
                        append(ch.toString())
                        index++
                    }
                }
                continue
            }

            if (afterQuote) {
                when {
                    ch == options.delimiter -> {
                        endField()
                        recordTouched = true
                        index++
                    }
                    ch == '\r' || ch == '\n' -> {
                        endRecord()
                        index += newlineLength(index)
                    }
                    options.trimUnquoted && (ch == ' ' || ch == '\t') -> index++
                    else -> throw IllegalArgumentException(
                        "Unexpected character '$ch' after a closing CSV quote at index $index"
                    )
                }
                continue
            }

            when {
                ch == options.delimiter -> {
                    endField()
                    recordTouched = true
                    index++
                }
                ch == '\r' || ch == '\n' -> {
                    endRecord()
                    index += newlineLength(index)
                }
                ch == options.quote -> {
                    val onlyPaddingBeforeQuote = options.trimUnquoted &&
                        field.all { it == ' ' || it == '\t' }
                    require(field.isEmpty() || onlyPaddingBeforeQuote) {
                        "Unexpected quote inside an unquoted CSV field at index $index"
                    }
                    if (onlyPaddingBeforeQuote) field.setLength(0)
                    inQuotes = true
                    fieldQuoted = true
                    recordTouched = true
                    index++
                }
                else -> {
                    append(ch.toString())
                    recordTouched = true
                    index++
                }
            }
        }

        require(!inQuotes) { "CSV input ends inside a quoted field" }
        if (recordTouched || fields.isNotEmpty() || field.isNotEmpty() || afterQuote || fieldQuoted) {
            endRecord()
        }

        if (records.isEmpty()) return KslCsvTable(emptyList())

        val headers: List<String>
        val dataRows: List<List<String>>
        if (options.hasHeader) {
            headers = records.first().map { it.value }
            require(headers.isNotEmpty()) { "CSV header must contain at least one column" }
            require(headers.all { it.isNotBlank() }) { "CSV header contains a blank column name" }
            require(headers.distinct().size == headers.size) { "CSV header contains duplicate column names" }
            dataRows = records.drop(1).map { it.map(ParsedField::value) }
        } else {
            val width = records.maxOfOrNull { it.size } ?: 0
            headers = (1..width).map { "column$it" }
            dataRows = records.map { it.map(ParsedField::value) }
        }

        val table = KslCsvTable(headers)
        dataRows.forEachIndexed { rowIndex, values ->
            require(values.size <= headers.size) {
                "CSV row ${rowIndex + 1} has ${values.size} columns; the header has ${headers.size}"
            }
            table.addRow(values)
        }
        return table
    }

    /** Writes a standards-compliant CSV document, escaping quotes, delimiters and line breaks. */
    @JvmStatic
    @JvmOverloads
    fun encode(table: KslCsvTable, options: KslCsvOptions = KslCsvOptions()): String {
        require(table.columnCount <= options.maxColumns) {
            "CSV table exceeds ${options.maxColumns} columns"
        }
        require(table.rowCount <= options.maxRows) {
            "CSV table exceeds ${options.maxRows} rows"
        }
        val output = StringBuilder()
        var recordsWritten = 0

        fun appendRecord(values: List<String>) {
            if (recordsWritten > 0) output.append(options.lineSeparator)
            values.forEachIndexed { columnIndex, value ->
                if (columnIndex > 0) output.append(options.delimiter)
                val mustQuote = value.contains(options.delimiter) ||
                    value.contains(options.quote) ||
                    value.contains('\r') || value.contains('\n') ||
                    value.firstOrNull()?.isWhitespace() == true ||
                    value.lastOrNull()?.isWhitespace() == true
                if (mustQuote) {
                    output.append(options.quote)
                    value.forEach { ch ->
                        if (ch == options.quote) output.append(options.quote)
                        output.append(ch)
                    }
                    output.append(options.quote)
                } else {
                    output.append(value)
                }
            }
            recordsWritten++
            require(output.length <= options.maxTotalCharacters) {
                "Encoded CSV exceeds ${options.maxTotalCharacters} characters"
            }
        }

        if (options.hasHeader && table.columnCount > 0) appendRecord(table.columns)
        table.rawRows().forEach(::appendRecord)
        return output.toString()
    }
}

object KslCsvFiles {
    const val DEFAULT_MAX_FILE_BYTES: Long = 32L * 1024L * 1024L

    @JvmStatic
    @JvmOverloads
    fun read(
        file: File,
        options: KslCsvOptions = KslCsvOptions(),
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
    ): KslCsvTable? {
        require(maxFileBytes in 0L..256L * 1024L * 1024L) {
            "maxFileBytes must be in 0..256 MiB"
        }
        if (!file.exists()) return null
        require(file.isFile) { "CSV path is not a regular file: $file" }
        require(file.length() <= maxFileBytes) {
            "CSV file exceeds $maxFileBytes bytes"
        }
        val text = file.readText(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        return try {
            KslCsvCodec.parse(text, options)
        } catch (error: Exception) {
            throw IllegalArgumentException("Could not parse CSV file '${file.name}': ${error.message}", error)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun write(file: File, table: KslCsvTable, options: KslCsvOptions = KslCsvOptions()) {
        try {
            KSLAtomicFileWriter.writeText(file, KslCsvCodec.encode(table, options))
        } catch (error: Exception) {
            throw IOException("Could not write CSV file '${file.name}': ${error.message}", error)
        }
    }
}

/** Creates an in-memory CSV table with a compact DSL. */
fun csvTable(vararg columns: String, block: KslCsvTable.() -> Unit = {}): KslCsvTable =
    KslCsvTable(columns.toList()).apply(block)

/** Parses CSV text using the supplied dialect and safety limits. */
fun String.parseCsv(options: KslCsvOptions = KslCsvOptions()): KslCsvTable =
    KslCsvCodec.parse(this, options)

/** Encodes this table to CSV text. */
fun KslCsvTable.toCsv(options: KslCsvOptions = KslCsvOptions()): String =
    KslCsvCodec.encode(this, options)
