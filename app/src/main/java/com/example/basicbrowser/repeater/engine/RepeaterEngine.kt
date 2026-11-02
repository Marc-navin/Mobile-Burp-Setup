package com.example.basicbrowser.repeater.engine

import android.util.Log
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.repeater.model.RepeaterResponse
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Dedicated execution engine for Repeater requests.
 *
 * Responsibilities:
 * - Executes HTTP and HTTPS requests directly to destination servers without routing
 *   through the local proxy or Intercept queue.
 * - Enforces strict upstream TLS certificate-chain and hostname verification (HTTPS).
 * - Enforces explicit connection and read timeouts.
 * - Supports cancellation by closing active sockets immediately.
 * - Normalizes request framing (Host header, Content-Length, Transfer-Encoding).
 * - Limits response body captures to 1 MB with accurate truncation tracking.
 * - Detects binary responses to avoid unnecessary memory allocations and text decoding.
 * - Enforces single-execution concurrency (one request active at a time).
 */
class RepeaterEngine {

    companion object {
        private const val TAG = "RepeaterEngine"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_CAPTURE_SIZE = 1024 * 1024L // 1 MB
        private const val BUFFER_SIZE = 8192

        @Volatile
        private var instance: RepeaterEngine? = null

        fun getInstance(): RepeaterEngine {
            return instance ?: synchronized(this) {
                instance ?: RepeaterEngine().also { instance = it }
            }
        }
    }

    private val isExecuting = AtomicBoolean(false)

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var isCancelled = false

    /**
     * Executes the given HTTP request synchronously on the calling thread.
     * Must be called from a background thread.
     *
     * @throws IllegalStateException if another request is currently running.
     * @throws IOException on network or connection errors.
     */
    @Throws(IOException::class)
    fun execute(request: HttpRequestData): RepeaterResponse {
        if (!isExecuting.compareAndSet(false, true)) {
            throw IllegalStateException("Another Repeater execution is already in progress.")
        }

        isCancelled = false
        val startTime = System.currentTimeMillis()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        try {
            // 1. Parse target URI/URL
            val parsedDestination = parseDestination(request.url)
            val isHttps = parsedDestination.scheme.equals("https", ignoreCase = true)
            val host = parsedDestination.host
            val port = parsedDestination.port
            val relativePath = parsedDestination.pathAndQuery

            // 2. Normalize headers and framing
            val normalizedHeaders = normalizeRequestHeaders(
                rawHeaders = request.headers,
                host = host,
                port = port,
                scheme = parsedDestination.scheme,
                body = request.body,
                method = request.method
            )

            // 3. Connect to destination
            val baseSocket = Socket()
            rawSocket = baseSocket
            activeSocket = baseSocket

            baseSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            baseSocket.soTimeout = READ_TIMEOUT_MS

            val transportSocket: Socket = if (isHttps) {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val secureSocket = sslFactory.createSocket(
                    baseSocket,
                    host,
                    port,
                    /* autoClose = */ true
                ) as SSLSocket
                sslSocket = secureSocket
                activeSocket = secureSocket

                secureSocket.useClientMode = true
                val sslParams = secureSocket.sslParameters
                sslParams.endpointIdentificationAlgorithm = "HTTPS"
                secureSocket.sslParameters = sslParams

                secureSocket.startHandshake()
                secureSocket
            } else {
                baseSocket
            }

            if (isCancelled) {
                throw IOException("Request was cancelled by user.")
            }

            val socketIn = BufferedInputStream(transportSocket.getInputStream())
            val socketOut = transportSocket.getOutputStream()

            // 4. Send Request Line & Headers
            val requestLine = "${request.method.trim()} $relativePath ${request.httpVersion.trim()}\r\n"
            socketOut.write(requestLine.toByteArray(StandardCharsets.US_ASCII))

            for (header in normalizedHeaders) {
                socketOut.write("$header\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            socketOut.write("\r\n".toByteArray(StandardCharsets.US_ASCII))

            // 5. Send Body if present
            if (request.body != null && request.body.isNotEmpty()) {
                socketOut.write(request.body)
            }
            socketOut.flush()

            if (isCancelled) {
                throw IOException("Request was cancelled by user.")
            }

            // 6. Read Response Status Line
            val statusLine = readLine(socketIn) ?: run {
                throw IOException("Destination server closed connection before sending status line.")
            }

            val statusParts = statusLine.split(" ", limit = 3)
            val statusCode = if (statusParts.size >= 2) statusParts[1].toIntOrNull() else null

            // 7. Read Response Headers
            val responseHeaders = mutableListOf<String>()
            var responseContentLength: Long = -1
            var isChunked = false

            while (true) {
                val headerLine = readLine(socketIn) ?: break
                if (headerLine.isEmpty()) break
                responseHeaders.add(headerLine)

                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    responseContentLength = headerLine.substringAfter(":").trim().toLongOrNull() ?: -1
                }
                if (headerLine.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    headerLine.contains("chunked", ignoreCase = true)
                ) {
                    isChunked = true
                }
            }

            // 8. Read Response Body
            var totalBodyBytesRead = 0L
            var bodyTruncated = false
            val bodyStream = ByteArrayOutputStream()

            val hasBody = (responseContentLength > 0 || isChunked || responseContentLength == -1L) &&
                statusCode != 204 && statusCode != 304 && !request.method.equals("HEAD", ignoreCase = true)

            if (hasBody) {
                if (responseContentLength > 0) {
                    val result = readFixedLengthBody(socketIn, responseContentLength, bodyStream)
                    totalBodyBytesRead = result.totalBytesRead
                    bodyTruncated = result.isTruncated
                } else if (isChunked) {
                    val result = readChunkedBody(socketIn, bodyStream)
                    totalBodyBytesRead = result.totalBytesRead
                    bodyTruncated = result.isTruncated
                } else {
                    val result = readUntilEofBody(socketIn, bodyStream)
                    totalBodyBytesRead = result.totalBytesRead
                    bodyTruncated = result.isTruncated
                }
            }

            val capturedBody = bodyStream.toByteArray()
            val elapsedTimeMs = System.currentTimeMillis() - startTime
            val isBinary = if (capturedBody.isNotEmpty()) isBinaryPayload(capturedBody, responseHeaders) else false
            val binarySummary = if (isBinary) analyzeBinarySummary(capturedBody, responseHeaders) else ""

            return RepeaterResponse(
                statusCode = statusCode,
                statusLine = statusLine,
                headers = responseHeaders,
                body = if (capturedBody.isNotEmpty()) capturedBody else null,
                isBodyTruncated = bodyTruncated,
                elapsedTimeMs = elapsedTimeMs,
                responseSizeBytes = totalBodyBytesRead,
                isBinary = isBinary,
                binarySummary = binarySummary
            )

        } finally {
            closeQuietly(sslSocket)
            closeQuietly(rawSocket)
            activeSocket = null
            isExecuting.set(false)
        }
    }

    /**
     * Cancels the currently executing request, closing the active socket immediately.
     */
    fun cancelActiveRequest() {
        isCancelled = true
        activeSocket?.let {
            closeQuietly(it)
        }
    }

    fun isBusy(): Boolean = isExecuting.get()

    // ------------------------------------------------------------------ //
    //  Request Normalization & Destination Parsing
    // ------------------------------------------------------------------ //

    data class ParsedDestination(
        val scheme: String,
        val host: String,
        val port: Int,
        val pathAndQuery: String
    )

    fun parseDestination(rawUrl: String): ParsedDestination {
        val trimmed = rawUrl.trim()
        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "http://$trimmed"
        } else {
            trimmed
        }

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            val url = URL(withScheme)
            URI(url.protocol, url.userInfo, url.host, url.port, url.path, url.query, url.ref)
        }

        val scheme = uri.scheme?.lowercase() ?: "http"
        val host = uri.host ?: throw IllegalArgumentException("Invalid URL: missing host in '$rawUrl'")
        val port = if (uri.port != -1) {
            uri.port
        } else if (scheme == "https") {
            443
        } else {
            80
        }

        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
        val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
        val pathAndQuery = path + query

        return ParsedDestination(scheme, host, port, pathAndQuery)
    }

    /**
     * Normalizes headers for sending directly to an origin server:
     * - Ensures Host header is present.
     * - Recalculates Content-Length based on body size.
     * - Strips chunked Transfer-Encoding unless explicitly managed.
     * - Strips proxy-specific headers (Proxy-Connection, etc.).
     */
    fun normalizeRequestHeaders(
        rawHeaders: List<String>,
        host: String,
        port: Int,
        scheme: String,
        body: ByteArray?,
        method: String
    ): List<String> {
        val filtered = mutableListOf<String>()
        var hostHeaderPresent = false
        val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val defaultHostVal = if (isDefaultPort) host else "$host:$port"

        for (header in rawHeaders) {
            val trimmed = header.trim()
            if (trimmed.isEmpty()) continue

            // Exclude request line if mistakenly passed in headers list
            if (trimmed.startsWith("GET ") || trimmed.startsWith("POST ") ||
                trimmed.startsWith("PUT ") || trimmed.startsWith("DELETE ") ||
                trimmed.startsWith("HEAD ") || trimmed.startsWith("OPTIONS ") ||
                trimmed.startsWith("PATCH ")
            ) {
                continue
            }

            if (trimmed.startsWith("Host:", ignoreCase = true)) {
                hostHeaderPresent = true
                filtered.add(trimmed)
                continue
            }

            // Strip framing headers to replace them with normalized values
            if (trimmed.startsWith("Content-Length:", ignoreCase = true)) continue
            if (trimmed.startsWith("Transfer-Encoding:", ignoreCase = true)) continue
            if (trimmed.startsWith("Proxy-Connection:", ignoreCase = true)) continue

            filtered.add(trimmed)
        }

        if (!hostHeaderPresent) {
            filtered.add(0, "Host: $defaultHostVal")
        }

        // Add correct Content-Length
        val upperMethod = method.uppercase()
        if (body != null && body.isNotEmpty()) {
            filtered.add("Content-Length: ${body.size}")
        } else if (upperMethod == "POST" || upperMethod == "PUT" || upperMethod == "PATCH") {
            filtered.add("Content-Length: 0")
        }

        return filtered
    }

    // ------------------------------------------------------------------ //
    //  Stream Reading Helpers
    // ------------------------------------------------------------------ //

    private data class ReadResult(val totalBytesRead: Long, val isTruncated: Boolean)

    private fun readFixedLengthBody(
        input: InputStream,
        contentLength: Long,
        captureStream: ByteArrayOutputStream
    ): ReadResult {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = contentLength
        var totalRead = 0L
        var truncated = false

        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val n = input.read(buffer, 0, toRead)
            if (n == -1) break

            totalRead += n
            remaining -= n

            if (captureStream.size() + n <= MAX_CAPTURE_SIZE) {
                captureStream.write(buffer, 0, n)
            } else if (captureStream.size() < MAX_CAPTURE_SIZE) {
                val allowed = (MAX_CAPTURE_SIZE - captureStream.size()).toInt()
                captureStream.write(buffer, 0, allowed)
                truncated = true
            } else {
                truncated = true
            }
        }

        return ReadResult(totalRead, truncated)
    }

    private fun readChunkedBody(
        input: InputStream,
        captureStream: ByteArrayOutputStream
    ): ReadResult {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalRead = 0L
        var truncated = false

        while (true) {
            val sizeLine = readLine(input) ?: break
            val trimmedSize = sizeLine.trim().split(";")[0]
            val chunkSize = try {
                trimmedSize.toInt(16)
            } catch (_: Exception) {
                break
            }

            if (chunkSize == 0) {
                // Consume trailing headers / CRLF
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }

            var chunkRemaining = chunkSize
            while (chunkRemaining > 0) {
                val toRead = minOf(chunkRemaining, buffer.size)
                val n = input.read(buffer, 0, toRead)
                if (n == -1) break

                totalRead += n
                chunkRemaining -= n

                if (captureStream.size() + n <= MAX_CAPTURE_SIZE) {
                    captureStream.write(buffer, 0, n)
                } else if (captureStream.size() < MAX_CAPTURE_SIZE) {
                    val allowed = (MAX_CAPTURE_SIZE - captureStream.size()).toInt()
                    captureStream.write(buffer, 0, allowed)
                    truncated = true
                } else {
                    truncated = true
                }
            }

            // Consume chunk CRLF
            readLine(input)
        }

        return ReadResult(totalRead, truncated)
    }

    private fun readUntilEofBody(
        input: InputStream,
        captureStream: ByteArrayOutputStream
    ): ReadResult {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalRead = 0L
        var truncated = false

        while (true) {
            val n = input.read(buffer)
            if (n == -1) break

            totalRead += n
            if (captureStream.size() + n <= MAX_CAPTURE_SIZE) {
                captureStream.write(buffer, 0, n)
            } else if (captureStream.size() < MAX_CAPTURE_SIZE) {
                val allowed = (MAX_CAPTURE_SIZE - captureStream.size()).toInt()
                captureStream.write(buffer, 0, allowed)
                truncated = true
            } else {
                truncated = true
            }
        }

        return ReadResult(totalRead, truncated)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') {
                    sb.deleteCharAt(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    // ------------------------------------------------------------------ //
    //  Binary Data Analysis
    // ------------------------------------------------------------------ //

    private fun isBinaryPayload(bytes: ByteArray, headers: List<String>): Boolean {
        val ct = headers.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.lowercase() ?: ""

        if (ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") ||
            ct.startsWith("font/") || ct.contains("application/octet-stream") ||
            ct.contains("application/pdf") || ct.contains("zip") || ct.contains("gzip")
        ) {
            return true
        }

        // Magic bytes
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()) return true // PNG
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true // JPEG
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return true // ZIP
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) return true // GZIP
        if (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte()) return true // PDF

        // Scan first 512 bytes for nulls or control characters
        val sampleLength = minOf(bytes.size, 512)
        var controlCount = 0
        for (i in 0 until sampleLength) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 32 && b != 9 && b != 10 && b != 13) {
                controlCount++
            }
        }
        return controlCount > (sampleLength / 10)
    }

    private fun analyzeBinarySummary(bytes: ByteArray, headers: List<String>): String {
        val ct = headers.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: "binary"
        return "Binary Data ($ct, ${bytes.size} bytes)"
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {}
    }
}

