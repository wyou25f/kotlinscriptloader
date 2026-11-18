package ru.privateserver.ksl.database.postember

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental client for the PostemberDB development TCP protocol.
 *
 * The upstream server is currently in-memory, unauthenticated and line-oriented.
 * This client therefore only connects to loopback addresses and is not a production
 * persistence or remote-database replacement.
 */
class KSLPostemberClient(
    val host: String = "127.0.0.1",
    val port: Int = 7432,
    val connectTimeoutMillis: Int = 2_000,
    val readTimeoutMillis: Int = 5_000,
    val maxResponseBytes: Int = 1_048_576,
    val maxRows: Int = 10_000
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    init {
        require(host.isNotBlank() && host == host.trim()) { "PostemberDB host must not be blank or padded" }
        require(port in 1..65_535) { "PostemberDB port must be in 1..65535" }
        require(connectTimeoutMillis in 1..60_000) { "connectTimeoutMillis must be in 1..60000" }
        require(readTimeoutMillis in 1..120_000) { "readTimeoutMillis must be in 1..120000" }
        require(maxResponseBytes in 128..16_777_216) { "maxResponseBytes must be in 128..16777216" }
        require(maxRows in 1..100_000) { "maxRows must be in 1..100000" }
    }

    /** Execute one SQL statement using the current PostemberDB line protocol. */
    @Throws(IOException::class)
    fun execute(sql: String): KSLPostemberResult {
        check(!closed.get()) { "PostemberDB client is closed" }
        require(sql.isNotBlank()) { "SQL statement must not be blank" }
        require('\n' !in sql && '\r' !in sql) { "PostemberDB protocol accepts one SQL statement per line" }
        require(sql.toByteArray(StandardCharsets.UTF_8).size <= MAX_SQL_BYTES) {
            "SQL statement exceeds $MAX_SQL_BYTES UTF-8 bytes"
        }

        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) {
            "PostemberDB development protocol is unauthenticated; KSL only permits loopback connections"
        }

        var lastError: IOException? = null
        for (address in addresses) {
            try {
                return exchange(address, sql)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("Could not connect to PostemberDB at $host:$port")
    }

    /** Execute a SELECT statement and require a tabular response. */
    @Throws(IOException::class)
    fun query(sql: String): KSLPostemberResult {
        val result = execute(sql)
        check(result.columns.isNotEmpty()) {
            "PostemberDB did not return a tabular result; only SELECT * is currently supported"
        }
        return result
    }

    private fun exchange(address: InetAddress, sql: String): KSLPostemberResult {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val reader = BoundedLineReader(input, maxResponseBytes)

            val greeting = reader.readLine()
            if (!greeting.startsWith("PostemberDB/")) throw IOException("Unexpected PostemberDB greeting")

            output.write(sql.toByteArray(StandardCharsets.UTF_8))
            output.write(10)
            output.flush()

            val response = reader.readLine()
            if (response.startsWith("ERROR ")) throw KSLPostemberException(response.removePrefix("ERROR "))
            if (!response.startsWith("OK ")) throw IOException("Malformed PostemberDB response")

            val message = response.removePrefix("OK ")
            val rowCount = ROW_COUNT.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull()
            if (rowCount != null) {
                if (rowCount > maxRows) {
                    throw IOException("PostemberDB returned $rowCount rows; configured limit is $maxRows")
                }
                val columns = protocolFields(reader.readLine())
                if (columns.any { it.isEmpty() }) throw IOException("PostemberDB returned an invalid column header")
                val rows = ArrayList<List<String>>(rowCount)
                repeat(rowCount) {
                    val fields = protocolFields(reader.readLine())
                    if (fields.size != columns.size) {
                        throw IOException("PostemberDB row has ${fields.size} fields; expected ${columns.size}")
                    }
                    rows += fields
                }
                return KSLPostemberResult(message, columns, rows, 0)
            }

            val affected = INSERT_COUNT.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return KSLPostemberResult(message, emptyList(), emptyList(), affected)
        }
    }

    private fun protocolFields(line: String): List<String> {
        val fields = line.split('\t')
        // The upstream Rust server currently appends a tab after every field, including the last one.
        return if (fields.lastOrNull() == "") fields.dropLast(1) else fields
    }

    override fun close() {
        closed.set(true)
    }

    private class BoundedLineReader(
        private val input: BufferedInputStream,
        private val maximumBytes: Int
    ) {
        private var consumed = 0

        fun readLine(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) {
                    if (bytes.size() == 0) throw EOFException(
                        "PostemberDB closed the connection before completing its response"
                    )
                    break
                }
                consumed++
                if (consumed > maximumBytes) throw IOException("PostemberDB response exceeds $maximumBytes bytes")
                if (value == 10) break
                if (bytes.size() >= maximumBytes) throw IOException("PostemberDB response line is too large")
                bytes.write(value)
            }
            return String(bytes.toByteArray(), StandardCharsets.UTF_8).removeSuffix("\r")
        }
    }

    companion object {
        private const val MAX_SQL_BYTES = 16_384
        private val ROW_COUNT = Regex("^(\\d+) row\\(s\\)$")
        private val INSERT_COUNT = Regex("^(\\d+) row inserted$")
    }
}

/** Detached, text-valued result returned by the PostemberDB prototype protocol. */
data class KSLPostemberResult(
    val message: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val affectedRows: Int
)

/** Error reported by the PostemberDB server for a rejected SQL statement. */
class KSLPostemberException(message: String) : IOException(message)
