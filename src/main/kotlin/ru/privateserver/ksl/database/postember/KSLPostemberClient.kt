package ru.privateserver.ksl.database.postember

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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

    private data class Channel(
        val socket: Socket,
        val reader: BoundedLineReader,
        val output: BufferedOutputStream
    )

    @Volatile
    private var channel: Channel? = null

    init {
        require(host.isNotBlank() && host == host.trim()) { "PostemberDB host must not be blank or padded" }
        require(port in 1..65_535) { "PostemberDB port must be in 1..65535" }
        require(connectTimeoutMillis in 1..60_000) { "connectTimeoutMillis must be in 1..60000" }
        require(readTimeoutMillis in 1..120_000) { "readTimeoutMillis must be in 1..120000" }
        require(maxResponseBytes in 128..16_777_216) { "maxResponseBytes must be in 128..16777216" }
        require(maxRows in 1..100_000) { "maxRows must be in 1..100000" }
    }

    /**
     * Execute one SQL statement using the current PostemberDB line protocol.
     *
     * A client owns one loopback TCP connection and serializes requests on it.
     * Reusing the socket avoids a TCP handshake for every script query. The server
     * processes one line at a time, so this deliberately does not pipeline requests.
     * SQL errors keep the connection alive; transport/protocol errors discard it.
     */
    @Synchronized
    @Throws(IOException::class)
    fun execute(sql: String): KSLPostemberResult {
        check(!closed.get()) { "PostemberDB client is closed" }
        require(sql.isNotBlank()) { "SQL statement must not be blank" }
        require('\n' !in sql && '\r' !in sql) { "PostemberDB protocol accepts one SQL statement per line" }
        require(sql.toByteArray(StandardCharsets.UTF_8).size <= MAX_SQL_BYTES) {
            "SQL statement exceeds $MAX_SQL_BYTES UTF-8 bytes"
        }

        val active = connectIfNeeded()
        return try {
            exchange(active, sql)
        } catch (error: KSLPostemberException) {
            // A rejected SQL statement is a complete protocol response. Keep the
            // session reusable instead of paying for a new TCP connection.
            throw error
        } catch (error: IOException) {
            disconnect(active)
            throw error
        }
    }

    private fun connectIfNeeded(): Channel {
        channel?.let { current ->
            if (!current.socket.isClosed && current.socket.isConnected) return current
            disconnect(current)
        }
        check(!closed.get()) { "PostemberDB client is closed" }

        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) {
            "PostemberDB development protocol is unauthenticated; KSL only permits loopback connections"
        }

        var lastError: IOException? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
                socket.soTimeout = readTimeoutMillis
                socket.tcpNoDelay = true

                val reader = BoundedLineReader(
                    BufferedInputStream(socket.getInputStream()),
                    maxResponseBytes
                )
                val greeting = reader.readLine()
                if (!greeting.startsWith("PostemberDB/")) {
                    throw IOException("Unexpected PostemberDB greeting")
                }

                return Channel(
                    socket = socket,
                    reader = reader,
                    output = BufferedOutputStream(socket.getOutputStream())
                ).also { channel = it }
            } catch (error: IOException) {
                lastError = error
                runCatching { socket.close() }.onFailure(error::addSuppressed)
            }
        }
        throw lastError ?: IOException("Could not connect to PostemberDB at $host:$port")
    }

    private fun exchange(connection: Channel, sql: String): KSLPostemberResult {
        connection.output.write(sql.toByteArray(StandardCharsets.UTF_8))
        connection.output.write(10)
        connection.output.flush()

        // The configured limit applies to each SQL response, not the lifetime
        // total of a long-lived connection.
        connection.reader.resetBudget()
        val response = connection.reader.readLine()
        if (response.startsWith("ERROR ")) {
            throw KSLPostemberException(response.removePrefix("ERROR "))
        }
        if (!response.startsWith("OK ")) throw IOException("Malformed PostemberDB response")

        val message = response.removePrefix("OK ")
        val rowCountText = ROW_COUNT.matchEntire(message)?.groupValues?.get(1)
        if (rowCountText != null) {
            val rowCount = rowCountText.toIntOrNull()
                ?: throw IOException("PostemberDB row count exceeds the supported integer range")
            if (rowCount > maxRows) {
                throw IOException("PostemberDB returned $rowCount rows; configured limit is $maxRows")
            }
            val columns = protocolFields(connection.reader.readLine())
            if (columns.any { it.isEmpty() }) throw IOException("PostemberDB returned an invalid column header")
            val rows = ArrayList<List<String>>(rowCount)
            repeat(rowCount) {
                val fields = protocolFields(connection.reader.readLine())
                if (fields.size != columns.size) {
                    throw IOException("PostemberDB row has ${fields.size} fields; expected ${columns.size}")
                }
                rows += fields
            }
            return KSLPostemberResult(message, columns, rows, 0)
        }

        val insertedText = INSERT_COUNT.matchEntire(message)?.groupValues?.get(1)
        val affected = if (insertedText != null) {
            insertedText.toIntOrNull()
                ?: throw IOException("PostemberDB affected-row count exceeds the supported integer range")
        } else {
            if (message.endsWith(" row(s)") || message.endsWith(" row inserted")) {
                throw IOException("Malformed PostemberDB row count")
            }
            0
        }
        return KSLPostemberResult(message, emptyList(), emptyList(), affected)
    }

    private fun disconnect(connection: Channel) {
        if (channel === connection) channel = null
        runCatching { connection.socket.close() }
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

    private fun protocolFields(line: String): List<String> {
        val fields = line.split('\t')
        // The upstream Rust server currently appends a tab after every field, including the last one.
        return if (fields.lastOrNull() == "") fields.dropLast(1) else fields
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel?.let(::disconnect)
    }

    private class BoundedLineReader(
        private val input: BufferedInputStream,
        private val maximumBytes: Int
    ) {
        private var consumed = 0

        fun resetBudget() {
            consumed = 0
        }

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
