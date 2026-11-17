# CSV standard library

KSL 1.2.2 includes a bounded CSV parser/writer and an editable table model. Script files live inside `scripts/`; the standalone `KslCsvFiles` API also accepts a Java `File` for addons.

## Build a table in memory

```kotlin
val users = csvTable("id", "name", "coins", "active") {
    addRow(1, "Alice", 120, true)
    addRow(2, "Bob", 75, false)
    addRow(
        "id" to 3,
        "name" to "Riley",
        "coins" to 90,
        "active" to true
    )
}

users.addColumn("rank", "new")
users[0]["coins"] = 150
users.sortBy("coins", descending = true)

val activeUsers = users.filterRows { getBoolean("active") }
val publicView = activeUsers.select("id", "name", "rank")
```

## Parse and encode text

```kotlin
val table = csvText.parseCsv()
val encoded = table.toCsv()

val semicolon = csvText.parseCsv(
    KslCsvOptions(
        delimiter = ';',
        hasHeader = true,
        trimUnquoted = true
    )
)
```

The parser follows the common RFC 4180 quoting rules: delimiters, quotes and line breaks inside quoted fields are preserved; embedded quotes are escaped by doubling them. It accepts CRLF and LF records and a UTF-8 BOM. It rejects malformed quotes, duplicate/blank headers and rows wider than the header. Short rows are padded with empty cells.

## Read, write and edit script files

Files are stored under `scripts/`. Paths are canonicalized so a script cannot escape that directory through `../` or a symbolic link.

```kotlin
val rows = loadCsv("data/users.csv")
    ?: csvTable("id", "name", "active")

val updated = editCsv("data/users.csv", listOf("id", "name", "active")) {
    addRow(42, "Example", true)
    val index = column("id").indexOf("42")
    if (index >= 0) this[index]["active"] = false
}

check(saveCsv("data/users.csv", updated))
```

## Typed row access

```kotlin
val user = rows.first()
val id = user.requireInt("id")
val name = user.getString("name", default = "Unknown")
val active = user.getBoolean("active")
val score = user.getDouble("score", default = 0.0)
val record: Map<String, String> = user.asMap()
```

Accessors include `getString`, `getInt`, `requireInt`, `getLong`, `requireLong`, `getDouble`, `requireDouble`, `getBoolean` and `requireBoolean`. Table helpers include `append`, `addRow`, `insertRow`, `removeRow`, `addColumn`, `removeColumn`, `column`, `findFirst`, `filterRows`, `select`, `mapRows`, `sortBy`, `toMaps` and `copy`.

## Limits and options

`KslCsvOptions` defaults to comma delimiter, double-quote escaping, a header row, CRLF output and skipping physically empty lines. Default safety limits are 100,000 data rows, 256 columns, 1 MiB per field and 16 Mi characters total input/output. Script file reads are limited to 32 MiB. Limits can be lowered or raised explicitly within the hard caps.

## Public API and addons

`ru.privateserver.ksl.std.csv.*` is available to KSL scripts by default. Addons can import `ru.privateserver.ksl.api.*` and use `KSLCSVTable`, `KSLCSVRow`, `KSLCSVOptions`, plus the `KSLAPI.csvTable/parseCsv/readCsv/writeCsv` extensions. API compatibility is 14.
