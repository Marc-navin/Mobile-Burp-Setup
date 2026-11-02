package com.example.basicbrowser.variation.engine

import android.util.Log
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.repeater.engine.RepeaterEngine
import com.example.basicbrowser.variation.model.TargetSummary
import com.example.basicbrowser.variation.model.VariationConfig
import com.example.basicbrowser.variation.model.VariationResult
import com.example.basicbrowser.variation.model.VariationSubstitution
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Dedicated execution engine for Request Variation Lab.
 *
 * Guarantees:
 * - Sequential execution only (1 worker).
 * - Enforces hard count limit (<= 500 requests).
 * - Strict upstream TLS certificate-chain and hostname validation.
 * - Streams and discards response bodies immediately (zero body memory retention).
 * - Immediate socket closure on Stop/Cancel.
 * - Enforces inter-request delay.
 * - Does not follow redirects.
 */
class VariationEngine {

    companion object {
        private const val TAG = "VariationEngine"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 8192
        const val MAX_STREAM_READ_BYTES = 50 * 1024 * 1024L // 50 MB safety ceiling for stream counting
        const val MAX_LINE_LENGTH = 16 * 1024 // 16 KB max HTTP line length
        const val MAX_HEADER_COUNT = 200 // Max header lines
    }

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private val pauseLock = java.lang.Object()

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var activeWorkerThread: Thread? = null

    /**
     * Executes the variation job sequentially.
     * Must be invoked from a background worker thread.
     *
     * @param config The validated variation configuration.
     * @param target Validated target endpoint summary.
     * @param onProgress Callback invoked upon completion of each request.
     * @param onFinished Callback invoked when the entire job ends (completed or stopped).
     */
    fun runJob(
        config: VariationConfig,
        target: TargetSummary,
        onProgress: (VariationResult, completed: Int, total: Int, errors: Int) -> Unit,
        onFinished: (completed: Int, total: Int, errors: Int, stopped: Boolean) -> Unit
    ) {
        if (!isRunning.compareAndSet(false, true)) {
            throw IllegalStateException("A Variation job is already running.")
        }

        isPaused.set(false)
        isStopped.set(false)
        activeWorkerThread = Thread.currentThread()

        val generatedRequests = IntruderRequestGenerator.generateRequests(config)
        val totalToRun = generatedRequests.size
        var completed = 0
        var errors = 0

        try {
            for (i in 0 until totalToRun) {
                // Check for stop condition before starting next request
                if (isStopped.get()) break

                // Check for pause condition
                synchronized(pauseLock) {
                    while (isPaused.get() && !isStopped.get()) {
                        try {
                            pauseLock.wait()
                        } catch (_: InterruptedException) {
                            isStopped.set(true)
                            break
                        }
                    }
                }

                if (isStopped.get()) break

                val genReq = generatedRequests[i]
                val result = executeSingleRequest(
                    index = i + 1,
                    payload = genReq.payloadSummary,
                    request = genReq.request,
                    target = target
                )

                completed++
                if (result.errorMessage != null || (result.statusCode != null && result.statusCode >= 400)) {
                    errors++
                }

                onProgress(result, completed, totalToRun, errors)

                // Enforce inter-request delay (if not stopped or last request)
                if (i < totalToRun - 1 && !isStopped.get() && config.delayMs > 0) {
                    try {
                        Thread.sleep(config.delayMs)
                    } catch (_: InterruptedException) {
                        isStopped.set(true)
                        break
                    }
                }
            }
        } finally {
            val stopped = isStopped.get()
            activeWorkerThread = null
            isRunning.set(false)
            isPaused.set(false)
            onFinished(completed, totalToRun, errors, stopped)
        }
    }

    /**
     * Executes a single HTTP/HTTPS request directly to the upstream destination.
     * Streams and discards the response body to measure its size without retaining it.
     */
    fun executeSingleRequest(
        index: Int,
        payload: String,
        request: HttpRequestData,
        target: TargetSummary
    ): VariationResult {
        if (isStopped.get()) {
            return VariationResult(
                index = index,
                payload = payload,
                statusCode = null,
                responseSizeBytes = 0L,
                elapsedTimeMs = 0L,
                errorMessage = "Job stopped"
            )
        }
        val startTime = System.currentTimeMillis()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        try {
            val isHttps = target.scheme.equals("https", ignoreCase = true)
            val host = target.host
            val port = target.port

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
                // Strict TLS hostname verification
                sslParams.endpointIdentificationAlgorithm = "HTTPS"
                secureSocket.sslParameters = sslParams

                secureSocket.startHandshake()
                secureSocket
            } else {
                baseSocket
            }

            if (isStopped.get()) {
                throw IOException("Job was stopped.")
            }

            val socketIn = BufferedInputStream(transportSocket.getInputStream())
            val socketOut = transportSocket.getOutputStream()

            // Normalize headers
            val repeaterHelper = RepeaterEngine.getInstance()
            val normalizedHeaders = repeaterHelper.normalizeRequestHeaders(
                rawHeaders = request.headers,
                host = host,
                port = port,
                scheme = target.scheme,
                body = request.body,
                method = request.method
            )

            // Parse path and query
            val parsedDest = repeaterHelper.parseDestination(request.url)
            val pathAndQuery = parsedDest.pathAndQuery

            // Write request
            val requestLine = "${request.method.trim()} $pathAndQuery ${request.httpVersion.trim()}\r\n"
            socketOut.write(requestLine.toByteArray(StandardCharsets.US_ASCII))
            for (header in normalizedHeaders) {
                socketOut.write("$header\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            socketOut.write("\r\n".toByteArray(StandardCharsets.US_ASCII))

            if (request.body != null && request.body.isNotEmpty()) {
                socketOut.write(request.body)
            }
            socketOut.flush()

            if (isStopped.get()) {
                throw IOException("Job was stopped.")
            }

            // Read Status Line
            val statusLine = readLine(socketIn) ?: throw IOException("Server closed connection prematurely.")
            val statusParts = statusLine.split(" ", limit = 3)
            val statusCode = if (statusParts.size >= 2) statusParts[1].toIntOrNull() else null

            // Read Headers
            var responseContentLength: Long = -1
            var isChunked = false
            var headerCount = 0

            while (true) {
                val headerLine = readLine(socketIn) ?: break
                if (headerLine.isEmpty()) break
                headerCount++
                if (headerCount > MAX_HEADER_COUNT) {
                    throw IOException("HTTP response headers exceeded limit of $MAX_HEADER_COUNT.")
                }

                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    responseContentLength = headerLine.substringAfter(":").trim().toLongOrNull() ?: -1
                }
                if (headerLine.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    headerLine.contains("chunked", ignoreCase = true)
                ) {
                    isChunked = true
                }
            }

            // Stream and discard response body — strictly memory bounded
            var totalBodyBytesRead = 0L
            var bodyTruncated = false
            val hasBody = (responseContentLength > 0 || isChunked || responseContentLength == -1L) &&
                statusCode != 204 && statusCode != 304 && !request.method.equals("HEAD", ignoreCase = true)

            if (hasBody) {
                val drainResult = drainAndCountBody(
                    inputStream = socketIn,
                    contentLength = responseContentLength,
                    isChunked = isChunked
                )
                totalBodyBytesRead = drainResult.bytesCounted
                bodyTruncated = drainResult.isTruncated
            }

            val elapsed = System.currentTimeMillis() - startTime
            return VariationResult(
                index = index,
                payload = payload,
                statusCode = statusCode,
                responseSizeBytes = totalBodyBytesRead,
                elapsedTimeMs = elapsed,
                errorMessage = if (bodyTruncated) "Response truncated at 50 MB limit" else null,
                isTruncated = bodyTruncated
            )

        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            val msg = if (isStopped.get()) "Job stopped" else (e.message ?: e.javaClass.simpleName)
            return VariationResult(
                index = index,
                payload = payload,
                statusCode = null,
                responseSizeBytes = 0L,
                elapsedTimeMs = elapsed,
                errorMessage = msg
            )
        } finally {
            closeQuietly(sslSocket)
            closeQuietly(rawSocket)
            activeSocket = null
        }
    }

    data class DrainResult(val bytesCounted: Long, val isTruncated: Boolean)

    /**
     * Drains the response body in reusable 8KB buffers, counting total bytes,
     * without retaining any body bytes in heap memory.
     */
    private fun drainAndCountBody(
        inputStream: InputStream,
        contentLength: Long,
        isChunked: Boolean
    ): DrainResult {
        var bytesCounted = 0L
        var isTruncated = false
        val buffer = ByteArray(BUFFER_SIZE)

        if (contentLength > 0) {
            var remaining = contentLength
            while (remaining > 0 && bytesCounted < MAX_STREAM_READ_BYTES) {
                if (isStopped.get()) break
                val maxAllowed = MAX_STREAM_READ_BYTES - bytesCounted
                val toRead = minOf(buffer.size.toLong(), minOf(remaining, maxAllowed)).toInt()
                val read = inputStream.read(buffer, 0, toRead)
                if (read == -1) break
                bytesCounted += read
                remaining -= read
            }
            if (remaining > 0 && bytesCounted >= MAX_STREAM_READ_BYTES) {
                isTruncated = true
            }
        } else if (isChunked) {
            // Read chunked frames and discard
            while (bytesCounted < MAX_STREAM_READ_BYTES) {
                if (isStopped.get()) break
                val chunkSizeLine = readLine(inputStream) ?: break
                val chunkSizeStr = chunkSizeLine.trim().split(";")[0]
                val chunkSize = chunkSizeStr.toLongOrNull(16) ?: break
                if (chunkSize <= 0) {
                    readLine(inputStream) // trailing CRLF
                    break
                }
                var chunkRemaining = chunkSize
                while (chunkRemaining > 0 && bytesCounted < MAX_STREAM_READ_BYTES) {
                    if (isStopped.get()) break
                    val maxAllowed = MAX_STREAM_READ_BYTES - bytesCounted
                    val toRead = minOf(buffer.size.toLong(), minOf(chunkRemaining, maxAllowed)).toInt()
                    val read = inputStream.read(buffer, 0, toRead)
                    if (read == -1) break
                    bytesCounted += read
                    chunkRemaining -= read
                }
                readLine(inputStream) // chunk trailing CRLF
            }
            if (bytesCounted >= MAX_STREAM_READ_BYTES) {
                isTruncated = true
            }
        } else {
            // Read until EOF
            while (bytesCounted < MAX_STREAM_READ_BYTES) {
                if (isStopped.get()) break
                val maxAllowed = minOf(buffer.size.toLong(), MAX_STREAM_READ_BYTES - bytesCounted).toInt()
                val read = inputStream.read(buffer, 0, maxAllowed)
                if (read == -1) break
                bytesCounted += read
            }
            if (bytesCounted >= MAX_STREAM_READ_BYTES) {
                isTruncated = true
            }
        }
        return DrainResult(bytesCounted, isTruncated)
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
    }

    fun stop() {
        isStopped.set(true)
        isPaused.set(false)
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        activeSocket?.let { closeQuietly(it) }
        activeWorkerThread?.interrupt()
    }

    fun isBusy(): Boolean = isRunning.get()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                sb.append(b.toChar())
                if (sb.length > MAX_LINE_LENGTH) {
                    throw IOException("HTTP line exceeded maximum limit of $MAX_LINE_LENGTH characters.")
                }
            }
        }
        return sb.toString()
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {}
    }
}

