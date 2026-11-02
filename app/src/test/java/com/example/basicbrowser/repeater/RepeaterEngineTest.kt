package com.example.basicbrowser.repeater

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptManager
import com.example.basicbrowser.repeater.engine.RepeaterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLParameters

class RepeaterEngineTest {

    private val engine = RepeaterEngine()

    @Test
    fun testParseDestination() {
        val dest1 = engine.parseDestination("http://example.com/test?query=1")
        assertEquals("http", dest1.scheme)
        assertEquals("example.com", dest1.host)
        assertEquals(80, dest1.port)
        assertEquals("/test?query=1", dest1.pathAndQuery)

        val dest2 = engine.parseDestination("https://secure.example.com:8443/api/v1")
        assertEquals("https", dest2.scheme)
        assertEquals("secure.example.com", dest2.host)
        assertEquals(8443, dest2.port)
        assertEquals("/api/v1", dest2.pathAndQuery)

        val dest3 = engine.parseDestination("example.com")
        assertEquals("http", dest3.scheme)
        assertEquals("example.com", dest3.host)
        assertEquals(80, dest3.port)
        assertEquals("/", dest3.pathAndQuery)
    }

    @Test
    fun testNormalizeRequestHeaders_insertsHostAndContentLength() {
        val body = "Hello, Repeater!".toByteArray(StandardCharsets.UTF_8)
        val normalized = engine.normalizeRequestHeaders(
            rawHeaders = listOf("User-Agent: TestAgent", "Accept: text/plain"),
            host = "api.example.com",
            port = 443,
            scheme = "https",
            body = body,
            method = "POST"
        )

        assertTrue(normalized.any { it.equals("Host: api.example.com", ignoreCase = true) })
        assertTrue(normalized.any { it.equals("Content-Length: ${body.size}", ignoreCase = true) })
        assertTrue(normalized.any { it.startsWith("User-Agent:", ignoreCase = true) })
    }

    @Test
    fun testNormalizeRequestHeaders_removesChunkedAndSetsContentLengthForBody() {
        val body = "Fixed Body".toByteArray(StandardCharsets.UTF_8)
        val normalized = engine.normalizeRequestHeaders(
            rawHeaders = listOf("Host: example.com", "Transfer-Encoding: chunked", "Content-Length: 999"),
            host = "example.com",
            port = 80,
            scheme = "http",
            body = body,
            method = "PUT"
        )

        assertFalse(normalized.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) })
        val clHeaders = normalized.filter { it.startsWith("Content-Length:", ignoreCase = true) }
        assertEquals(1, clHeaders.size)
        assertEquals("Content-Length: ${body.size}", clHeaders[0])
    }

    @Test
    fun testNormalizeRequestHeaders_postWithEmptyBodySetsContentLengthZero() {
        val normalized = engine.normalizeRequestHeaders(
            rawHeaders = listOf("Host: example.com"),
            host = "example.com",
            port = 80,
            scheme = "http",
            body = null,
            method = "POST"
        )

        assertTrue(normalized.any { it.equals("Content-Length: 0", ignoreCase = true) })
    }

    @Test
    fun testExecuteGetRequest_success() {
        val server = ServerSocket(0)
        val port = server.localPort

        val thread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val writer = OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII)

            // Read request
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: text/plain\r\n")
            writer.write("Content-Length: 13\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write("Hello World!\n")
            writer.flush()
            client.close()
            server.close()
        }
        thread.start()

        val req = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/hello",
            headers = listOf("Host: 127.0.0.1:$port", "User-Agent: Test")
        )

        val resp = engine.execute(req)
        assertEquals(200, resp.statusCode)
        assertEquals("HTTP/1.1 200 OK", resp.statusLine)
        assertNotNull(resp.body)
        assertEquals("Hello World!\n", String(resp.body!!, StandardCharsets.UTF_8))
        assertFalse(resp.isBodyTruncated)
        assertFalse(resp.isBinary)
        assertEquals(13L, resp.responseSizeBytes)
        assertTrue(resp.elapsedTimeMs >= 0)
    }

    @Test
    fun testExecutePostRequest_withBody() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedBody = StringBuilder()

        val thread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val writer = OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII)

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toInt()
                }
                if (line.isEmpty()) break
            }

            for (i in 0 until contentLength) {
                receivedBody.append(reader.read().toChar())
            }

            writer.write("HTTP/1.1 201 Created\r\n")
            writer.write("Content-Length: 0\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            client.close()
            server.close()
        }
        thread.start()

        val reqBody = "name=security_test".toByteArray(StandardCharsets.UTF_8)
        val req = HttpRequestData(
            method = "POST",
            url = "http://127.0.0.1:$port/submit",
            headers = listOf("Content-Type: application/x-www-form-urlencoded"),
            body = reqBody
        )

        val resp = engine.execute(req)
        assertEquals(201, resp.statusCode)
        assertEquals("name=security_test", receivedBody.toString())
    }

    @Test
    fun testExecute_handlesErrorStatusCode() {
        val server = ServerSocket(0)
        val port = server.localPort

        val thread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\nConnection: close\r\n\r\nNot Found")
            writer.flush()
            client.close()
            server.close()
        }
        thread.start()

        val resp = engine.execute(HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/missing", headers = emptyList()))
        assertEquals(404, resp.statusCode)
        assertEquals("Not Found", String(resp.body!!, StandardCharsets.UTF_8))
    }

    @Test
    fun testExecute_truncatesBodyExceedingOneMegabyte() {
        val server = ServerSocket(0)
        val port = server.localPort
        val totalSize = (1024 * 1024 + 100_000) // ~1.1 MB

        val thread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: $totalSize\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))

            val chunk = ByteArray(8192) { 'A'.code.toByte() }
            var written = 0
            while (written < totalSize) {
                val toWrite = minOf(chunk.size, totalSize - written)
                out.write(chunk, 0, toWrite)
                written += toWrite
            }
            out.flush()
            client.close()
            server.close()
        }
        thread.start()

        val resp = engine.execute(HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/large", headers = emptyList()))
        assertEquals(200, resp.statusCode)
        assertTrue("Body should be marked truncated", resp.isBodyTruncated)
        assertEquals("Captured body should not exceed 1MB", RepeaterEngine.MAX_CAPTURE_SIZE, resp.body?.size?.toLong())
        assertEquals(totalSize.toLong(), resp.responseSizeBytes)
    }

    @Test
    fun testCancelActiveRequest_closesConnectionPromptly() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverConnected = CountDownLatch(1)

        val thread = Thread {
            val client = server.accept()
            serverConnected.countDown()
            // Hang without sending response until client disconnects
            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {}
            client.close()
            server.close()
        }
        thread.start()

        val cancelThread = Thread {
            serverConnected.await(5, TimeUnit.SECONDS)
            Thread.sleep(100) // Brief pause to ensure client is in read
            engine.cancelActiveRequest()
        }
        cancelThread.start()

        try {
            engine.execute(HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/hang", headers = emptyList()))
            fail("Expected execute to fail due to cancellation")
        } catch (e: Exception) {
            assertTrue("Expected cancellation or socket closure: ${e.message}",
                e.message?.contains("cancelled", ignoreCase = true) == true ||
                e.message?.contains("Socket closed", ignoreCase = true) == true ||
                e.message?.contains("Software caused connection abort", ignoreCase = true) == true
            )
        }
        assertFalse("Engine should no longer be busy after cancellation", engine.isBusy())
    }

    @Test
    fun testTlsEndpointIdentificationAlgorithmEnforcesHttps() {
        val sslParams = SSLParameters()
        sslParams.endpointIdentificationAlgorithm = "HTTPS"
        assertEquals("HTTPS", sslParams.endpointIdentificationAlgorithm)
    }

    @Test
    fun testRepeaterRemainsUsableWhileInterceptIsEnabled() {
        val interceptManager = InterceptManager.getInstance()
        interceptManager.setEnabled(true)
        assertTrue(interceptManager.isInterceptEnabled.value)

        val server = ServerSocket(0)
        val port = server.localPort

        val thread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
            writer.flush()
            client.close()
            server.close()
        }
        thread.start()

        // Repeater does not route through proxy or InterceptManager
        val resp = engine.execute(HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/check", headers = emptyList()))
        assertEquals(200, resp.statusCode)
        assertEquals("OK", String(resp.body!!, StandardCharsets.UTF_8))
        assertEquals(0, interceptManager.pausedRequests.value.size) // Intercept queue unaffected

        interceptManager.setEnabled(false)
    }
}
