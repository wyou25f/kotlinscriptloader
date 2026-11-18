package ru.privateserver.ksl.database.postember

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class KSLPostemberClientTest {
    private fun withServer(
        response: (String) -> List<String>,
        block: (Int, AtomicReference<String?>) -> Unit
    ) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val receivedSql = AtomicReference<String?>()
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                        val writer = PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                        writer.println("PostemberDB/0.1 ready; send one SQL statement per line")
                        val sql = reader.readLine()
                        receivedSql.set(sql)
                        response(sql).forEach(writer::println)
                        writer.flush()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            thread.start()
            try {
                block(server.localPort, receivedSql)
            } finally {
                thread.join(3_000)
                assertFalse(thread.isAlive, "fake PostemberDB server did not finish")
                failure.get()?.let { throw AssertionError("fake server failed", it) }
            }
        }
    }

    @Test
    fun `query decodes current PostemberDB tabular response`() {
        withServer(
            response = {
                assertEquals("SELECT * FROM users", it)
                listOf("OK 2 row(s)", "id\tname", "1\tAda", "2\tLinus")
            }
        ) { port, received ->
            KSLPostemberClient(port = port).use { client ->
                val result = client.query("SELECT * FROM users")
                assertEquals(listOf("id", "name"), result.columns)
                assertEquals(listOf(listOf("1", "Ada"), listOf("2", "Linus")), result.rows)
                assertEquals("2 row(s)", result.message)
                assertEquals(0, result.affectedRows)
                assertEquals("SELECT * FROM users", received.get())
            }
        }
    }

    @Test
    fun `execute reads inserted row count`() {
        withServer(
            response = { listOf("OK 1 row inserted") }
        ) { port, _ ->
            KSLPostemberClient(port = port).use { client ->
                val result = client.execute("INSERT INTO users VALUES (1, 'Ada')")
                assertEquals(1, result.affectedRows)
                assertTrue(result.columns.isEmpty())
            }
        }
    }

    @Test
    fun `server errors are exposed as Postember exceptions`() {
        withServer(
            response = { listOf("ERROR SQL parse error: invalid value") }
        ) { port, _ ->
            val error = assertThrows(KSLPostemberException::class.java) {
                KSLPostemberClient(port = port).execute("SELECT * FROM missing")
            }
            assertTrue(error.message!!.contains("SQL parse error"))
        }
    }

    @Test
    fun `client rejects non loopback targets and multiline SQL`() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLPostemberClient(host = "192.0.2.10").execute("SELECT * FROM users")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLPostemberClient().execute("SELECT * FROM users\nQUIT")
        }
    }

    @Test
    fun `client enforces response row limit`() {
        withServer(
            response = { listOf("OK 2 row(s)", "id", "1", "2") }
        ) { port, _ ->
            assertThrows(java.io.IOException::class.java) {
                KSLPostemberClient(port = port, maxRows = 1).query("SELECT * FROM users")
            }
        }
    }

    @Test
    fun `closed client rejects further requests`() {
        val client = KSLPostemberClient()
        client.close()
        assertThrows(IllegalStateException::class.java) {
            client.execute("SELECT * FROM users")
        }
    }
}
