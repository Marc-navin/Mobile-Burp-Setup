package com.example.basicbrowser.proxy

import com.example.basicbrowser.proxy.tls.TlsInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Unit tests for aggregate chunked response capture limits.
 *
 * Verifies that:
 * 1. Capture memory is capped strictly at MAX_CAPTURE_SIZE (1 MB) across ALL chunks.
 * 2. Complete response payload and framing are fully preserved and forwarded to the browser.
 * 3. Truncation flags are accurately set.
 * 4. Trailers and chunk boundaries remain completely intact.
 * 5. Both HttpProxyHandler and TlsInterceptor enforce identical aggregate capture semantics.
 */
class AggregateChunkedCaptureTest {

    companion object {
        const val MAX_CAPTURE_SIZE = 1024 * 1024L // 1 MB = 1,048,576 bytes
    }

    /**
     * An on-the-fly streaming InputStream that generates deterministic chunked HTTP data
     * of arbitrary size (1 MB, 5 MB, 10 MB, 25 MB) without holding payload arrays in heap memory.
     */
    private class GeneratingChunkedInputStream(
        private val totalPayloadBytes: Long,
        private val chunkSize: Int,
        private val trailers: List<String> = emptyList(),
        private val payloadFillByte: Byte = 'X'.code.toByte()
    ) : InputStream() {

        private var remainingPayload = totalPayloadBytes
        private var currentBuffer: ByteArray? = null
        private var currentBufferPos = 0
        private var phase = Phase.CHUNK_HEADER

        private enum class Phase {
            CHUNK_HEADER,
            CHUNK_DATA,
            CHUNK_CRLF,
            TERMINAL_CHUNK,
            TRAILERS,
            TERMINAL_CRLF,
            TERMINAL_DONE,
            DONE
        }

        private var chunkDataRemaining = 0

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n == -1) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (phase == Phase.DONE) return -1
            if (len == 0) return 0

            var bytesWritten = 0

            while (bytesWritten < len && phase != Phase.DONE) {
                // If we have a buffered string header or trailer to drain
                val buf = currentBuffer
                if (buf != null && currentBufferPos < buf.size) {
                    val toCopy = minOf(len - bytesWritten, buf.size - currentBufferPos)
                    System.arraycopy(buf, currentBufferPos, b, off + bytesWritten, toCopy)
                    currentBufferPos += toCopy
                    bytesWritten += toCopy
                    if (currentBufferPos >= buf.size) {
                        currentBuffer = null
                        currentBufferPos = 0
                    }
                    continue
                }

                when (phase) {
                    Phase.CHUNK_HEADER -> {
                        if (remainingPayload > 0) {
                            val thisChunk = minOf(remainingPayload, chunkSize.toLong()).toInt()
                            chunkDataRemaining = thisChunk
                            val hexHeader = "${Integer.toHexString(thisChunk)}\r\n".toByteArray(Charsets.US_ASCII)
                            currentBuffer = hexHeader
                            currentBufferPos = 0
                            phase = Phase.CHUNK_DATA
                        } else {
                            phase = Phase.TERMINAL_CHUNK
                        }
                    }
                    Phase.CHUNK_DATA -> {
                        if (chunkDataRemaining > 0) {
                            val toProduce = minOf(len - bytesWritten, chunkDataRemaining)
                            for (i in 0 until toProduce) {
                                b[off + bytesWritten + i] = payloadFillByte
                            }
                            bytesWritten += toProduce
                            chunkDataRemaining -= toProduce
                            remainingPayload -= toProduce
                            if (chunkDataRemaining == 0) {
                                phase = Phase.CHUNK_CRLF
                            }
                        } else {
                            phase = Phase.CHUNK_CRLF
                        }
                    }
                    Phase.CHUNK_CRLF -> {
                        currentBuffer = "\r\n".toByteArray(Charsets.US_ASCII)
                        currentBufferPos = 0
                        phase = if (remainingPayload > 0) Phase.CHUNK_HEADER else Phase.TERMINAL_CHUNK
                    }
                    Phase.TERMINAL_CHUNK -> {
                        currentBuffer = "0\r\n".toByteArray(Charsets.US_ASCII)
                        currentBufferPos = 0
                        phase = if (trailers.isNotEmpty()) Phase.TRAILERS else Phase.TERMINAL_CRLF
                    }
                    Phase.TRAILERS -> {
                        val sb = StringBuilder()
                        for (trailer in trailers) {
                            sb.append(trailer).append("\r\n")
                        }
                        currentBuffer = sb.toString().toByteArray(Charsets.US_ASCII)
                        currentBufferPos = 0
                        phase = Phase.TERMINAL_CRLF
                    }
                    Phase.TERMINAL_CRLF -> {
                        currentBuffer = "\r\n".toByteArray(Charsets.US_ASCII)
                        currentBufferPos = 0
                        phase = Phase.TERMINAL_DONE
                    }
                    Phase.TERMINAL_DONE -> {
                        phase = Phase.DONE
                    }
                    Phase.DONE -> break
                }
            }

            return if (bytesWritten == 0 && phase == Phase.DONE) -1 else bytesWritten
        }
    }

    /**
     * An OutputStream that counts forwarded bytes and verifies chunk framing integrity.
     */
    private class ValidatingChunkedOutputStream : OutputStream() {
        var totalBytesReceived = 0L
        private val frameBuffer = ByteArrayOutputStream(64)
        private var parsingState = State.READING_SIZE
        private var currentChunkSize = 0L
        private var currentChunkRemaining = 0L
        var totalPayloadBytesUnframed = 0L
        var terminalChunkEncountered = false

        private enum class State {
            READING_SIZE,
            READING_DATA,
            READING_CRLF,
            DONE
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            totalBytesReceived += len
            var pos = off
            val end = off + len

            while (pos < end) {
                when (parsingState) {
                    State.READING_SIZE -> {
                        val ch = b[pos++]
                        frameBuffer.write(ch.toInt())
                        if (ch == '\n'.code.toByte()) {
                            val line = frameBuffer.toString(Charsets.US_ASCII.name()).trim()
                            frameBuffer.reset()
                            val size = line.split(";")[0].toLong(16)
                            if (size == 0L) {
                                terminalChunkEncountered = true
                                parsingState = State.DONE
                            } else {
                                currentChunkSize = size
                                currentChunkRemaining = size
                                parsingState = State.READING_DATA
                            }
                        }
                    }
                    State.READING_DATA -> {
                        val avail = end - pos
                        val toConsume = minOf(avail.toLong(), currentChunkRemaining).toInt()
                        pos += toConsume
                        currentChunkRemaining -= toConsume
                        totalPayloadBytesUnframed += toConsume
                        if (currentChunkRemaining == 0L) {
                            parsingState = State.READING_CRLF
                        }
                    }
                    State.READING_CRLF -> {
                        val ch = b[pos++]
                        if (ch == '\n'.code.toByte()) {
                            parsingState = State.READING_SIZE
                        }
                    }
                    State.DONE -> {
                        // Trailer bytes or terminal CRLF
                        pos++
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Tests: TlsInterceptor (HTTPS)
    // ------------------------------------------------------------------ //

    @Test
    fun testTlsInterceptor_1MbChunkedResponse() {
        val totalBytes = 1024 * 1024L // Exactly 1 MB
        val chunkSize = 65536 // 64 KB chunks
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        // When exactly reaching MAX_CAPTURE_SIZE, truncated is set to true
        assertTrue(truncated)
    }

    @Test
    fun testTlsInterceptor_5MbChunkedResponse() {
        val totalBytes = 5 * 1024 * 1024L // 5 MB
        val chunkSize = 65536 // 64 KB chunks
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        // Forwarded: full 5 MB reached the output with valid chunk framing
        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)

        // Capture: STRICTLY capped at 1 MB
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        assertTrue(truncated)
    }

    @Test
    fun testTlsInterceptor_10MbChunkedResponse() {
        val totalBytes = 10 * 1024 * 1024L // 10 MB
        val chunkSize = 65536
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        assertTrue(truncated)
    }

    @Test
    fun testTlsInterceptor_25MbChunkedResponse() {
        val totalBytes = 25 * 1024 * 1024L // 25 MB
        val chunkSize = 65536
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        // Complete 25 MB payload streamed to output without corrupting framing
        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)

        // Capture strictly capped at 1 MB
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        assertTrue(truncated)
    }

    @Test
    fun testTlsInterceptor_MultipleSmallChunksExceeding1Mb() {
        val totalBytes = 1_500_000L // 1.5 MB
        val chunkSize = 1500 // Small 1.5 KB chunks (1000 chunks)
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        assertTrue(truncated)
    }

    @Test
    fun testTlsInterceptor_SingleChunkLargerThanCaptureLimit() {
        val totalBytes = 2 * 1024 * 1024L // 2 MB
        val chunkSize = (2 * 1024 * 1024) // 1 single huge chunk of 2 MB
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        assertTrue(truncated)
    }

    @Test
    fun testTlsInterceptor_ChunkTrailersPreservedAndConsumed() {
        val trailers = listOf("X-Checksum: sha256-abc123", "X-Sign: sig-xyz789")
        val input = GeneratingChunkedInputStream(1024, 512, trailers)
        val output = ByteArrayOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = TlsInterceptor.transferChunked(input, output, capture)

        assertFalse(truncated)
        assertEquals(1024, capture.size())
        val outputStr = output.toString(Charsets.US_ASCII.name())
        assertTrue("Output should contain trailer 1", outputStr.contains("X-Checksum: sha256-abc123\r\n"))
        assertTrue("Output should contain trailer 2", outputStr.contains("X-Sign: sig-xyz789\r\n"))
        assertEquals("Ends with \\r\\n\\r\\n", "\r\n\r\n", outputStr.takeLast(4))
        assertEquals("Input stream should be completely consumed", -1, input.read())
    }

    @Test
    fun testTlsInterceptor_FixedLengthCaptureRegression() {
        // Below limit (500 KB) -> not truncated
        val belowBytes = 500 * 1024
        val in1 = ByteArrayInputStream(ByteArray(belowBytes) { 0x41 })
        val out1 = ByteArrayOutputStream()
        val cap1 = ByteArrayOutputStream()
        val trunc1 = TlsInterceptor.transfer(in1, out1, belowBytes.toLong(), cap1)
        assertFalse(trunc1)
        assertEquals(belowBytes, cap1.size())
        assertEquals(belowBytes, out1.size())

        // Above limit (3 MB) -> truncated at 1 MB, output gets full 3 MB
        val aboveBytes = 3 * 1024 * 1024
        val in2 = ByteArrayInputStream(ByteArray(aboveBytes) { 0x42 })
        val out2 = ByteArrayOutputStream()
        val cap2 = ByteArrayOutputStream()
        val trunc2 = TlsInterceptor.transfer(in2, out2, aboveBytes.toLong(), cap2)
        assertTrue(trunc2)
        assertEquals(MAX_CAPTURE_SIZE, cap2.size().toLong())
        assertEquals(aboveBytes, out2.size())
    }

    // ------------------------------------------------------------------ //
    //  Tests: HttpProxyHandler (HTTP Forwarding)
    // ------------------------------------------------------------------ //

    @Test
    fun testHttpProxyHandler_5MbChunkedResponse() {
        val totalBytes = 5 * 1024 * 1024L // 5 MB
        val chunkSize = 65536
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = HttpProxyHandler.transferChunked(input, output, capture)

        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        assertTrue(truncated)
    }

    @Test
    fun testHttpProxyHandler_MultipleSmallChunks() {
        val totalBytes = 2_000_000L // 2 MB
        val chunkSize = 2000
        val input = GeneratingChunkedInputStream(totalBytes, chunkSize)
        val output = ValidatingChunkedOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = HttpProxyHandler.transferChunked(input, output, capture)

        assertEquals(totalBytes, output.totalPayloadBytesUnframed)
        assertTrue(output.terminalChunkEncountered)
        assertEquals(MAX_CAPTURE_SIZE, capture.size().toLong())
        assertTrue(truncated)
    }

    @Test
    fun testHttpProxyHandler_ChunkTrailersPreserved() {
        val trailers = listOf("Trailer-One: 123", "Trailer-Two: 456")
        val input = GeneratingChunkedInputStream(2048, 1024, trailers)
        val output = ByteArrayOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = HttpProxyHandler.transferChunked(input, output, capture)

        assertFalse(truncated)
        assertEquals(2048, capture.size())
        val outputStr = output.toString(Charsets.US_ASCII.name())
        assertTrue(outputStr.contains("Trailer-One: 123\r\n"))
        assertTrue(outputStr.contains("Trailer-Two: 456\r\n"))
        assertEquals(-1, input.read())
    }
}
