package com.example.basicbrowser.proxy.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class TlsInterceptorTest {

    // ------------------------------------------------------------------ //
    //  Chunked Framing Tests
    // ------------------------------------------------------------------ //

    @Test
    fun testTransferChunkedWithoutTrailers() {
        // Chunked stream: "5\r\nhello\r\n0\r\n\r\n"
        val inputData = "5\r\nhello\r\n0\r\n\r\n"
        val input = ByteArrayInputStream(inputData.toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertFalse(truncated)
        assertEquals("5\r\nhello\r\n0\r\n\r\n", output.toString(Charsets.US_ASCII.name()))
        assertEquals("hello", capture.toString(Charsets.US_ASCII.name()))
        // Crucial: All bytes consumed, nothing left in input stream
        assertEquals(-1, input.read())
    }

    @Test
    fun testTransferChunkedWithSingleTrailer() {
        val inputData = "5\r\nhello\r\n0\r\nExpires: Wed, 21 Oct 2026 07:28:00 GMT\r\n\r\n"
        val input = ByteArrayInputStream(inputData.toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertFalse(truncated)
        assertEquals(inputData, output.toString(Charsets.US_ASCII.name()))
        assertEquals("hello", capture.toString(Charsets.US_ASCII.name()))
        assertEquals(-1, input.read())
    }

    @Test
    fun testTransferChunkedWithMultipleTrailers() {
        val inputData = "4\r\nWiki\r\n0\r\nTrailer-A: ValA\r\nTrailer-B: ValB\r\n\r\n"
        val input = ByteArrayInputStream(inputData.toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertFalse(truncated)
        assertEquals(inputData, output.toString(Charsets.US_ASCII.name()))
        assertEquals("Wiki", capture.toString(Charsets.US_ASCII.name()))
        assertEquals(-1, input.read())
    }

    @Test
    fun testSequentialResponsesAfterChunkedFirstResponse() {
        // Simulates upstream response 1 (chunked) immediately followed by response 2 (content-length)
        val response1 = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nfirst\r\n0\r\n\r\n"
        val response2 = "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecond"
        val streamData = response1 + response2

        val input = ByteArrayInputStream(streamData.toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()

        // 1. Parse response 1
        val statusLine1 = TlsInterceptor.readLine(input)
        assertEquals("HTTP/1.1 200 OK", statusLine1)
        val headers1 = mutableListOf<String>()
        while (true) {
            val line = TlsInterceptor.readLine(input) ?: break
            if (line.isEmpty()) break
            headers1.add(line)
        }
        assertTrue(headers1.any { it.contains("chunked", ignoreCase = true) })
        TlsInterceptor.transferChunked(input, output)

        // 2. Parse response 2 - Must NOT be empty, must be the exact status line!
        // (Previously, unconsumed CRLF from response 1 caused statusLine2 to be "")
        val statusLine2 = TlsInterceptor.readLine(input)
        assertEquals("HTTP/1.1 200 OK", statusLine2)

        val headers2 = mutableListOf<String>()
        while (true) {
            val line = TlsInterceptor.readLine(input) ?: break
            if (line.isEmpty()) break
            headers2.add(line)
        }
        assertTrue(headers2.any { it.startsWith("Content-Length: 6", ignoreCase = true) })

        val body2 = ByteArrayOutputStream()
        TlsInterceptor.transfer(input, body2, 6)
        assertEquals("second", body2.toString(Charsets.US_ASCII.name()))
    }

    // ------------------------------------------------------------------ //
    //  Leading CRLF and Status-Line Robustness (RFC 7230 §3.5)
    // ------------------------------------------------------------------ //

    @Test
    fun testReadLineIgnoresLeadingEmptyLines() {
        // Stream with 2 empty lines before status line
        val streamData = "\r\n\r\nHTTP/1.1 304 Not Modified\r\n\r\n"
        val input = ByteArrayInputStream(streamData.toByteArray(Charsets.US_ASCII))

        var statusLine: String? = null
        var emptyCount = 0
        while (emptyCount < 5) {
            val line = TlsInterceptor.readLine(input) ?: break
            if (line.isNotEmpty()) {
                statusLine = line
                break
            }
            emptyCount++
        }

        assertEquals(2, emptyCount)
        assertEquals("HTTP/1.1 304 Not Modified", statusLine)
    }

    // ------------------------------------------------------------------ //
    //  Connection Lifecycle & Framing Checks
    // ------------------------------------------------------------------ //

    @Test
    fun testReadLineEofReturnsNull() {
        val input = ByteArrayInputStream(ByteArray(0))
        val line = TlsInterceptor.readLine(input)
        assertEquals(null, line)
    }

    @Test
    fun testReadRequestBodyChunkedWithTrailers() {
        val requestBody = "5\r\nhello\r\n0\r\nChecksum: xyz\r\n\r\n"
        val input = ByteArrayInputStream(requestBody.toByteArray(Charsets.US_ASCII))

        val (bytes, truncated) = TlsInterceptor.readRequestBody(input, contentLength = -1, isChunked = true)

        assertFalse(truncated)
        assertEquals("hello", String(bytes, Charsets.US_ASCII))
        // Verify input stream fully consumed past trailers
        assertEquals(-1, input.read())
    }

    @Test
    fun testKeepAliveIdleTimeoutIsTenSeconds() {
        assertEquals(10_000, TlsInterceptor.KEEP_ALIVE_IDLE_TIMEOUT_MS)
    }
}

