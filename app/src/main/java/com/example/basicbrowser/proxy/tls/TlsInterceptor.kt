package com.example.basicbrowser.proxy.tls

import android.util.Log
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptAction
import com.example.basicbrowser.proxy.intercept.InterceptManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Performs HTTPS TLS interception (MITM) for the local proxy.
 *
 * ## Flow
 *
 * After [com.example.basicbrowser.proxy.ConnectHandler] has:
 * 1. Received `CONNECT host:port` from the WebView
 * 2. Opened a raw TCP connection to the destination
 * 3. Replied with `200 Connection Established`
 *
 * This interceptor:
 * 1. Wraps the **client socket** in an [SSLSocket] in **server mode**,
 *    presenting a per-host certificate signed by the Development CA.
 * 2. Wraps the **upstream socket** in an [SSLSocket] in **client mode**,
 *    using the Android system default trust store.
 * 3. Reads decrypted HTTP requests from the browser, forwards them to
 *    the upstream server, reads the response, and forwards it back.
 * 4. Records intercepted requests/responses in [HttpHistoryRepository].
 *
 * ## Security
 *
 * - **Browser-side**: Uses a per-host certificate from
 *   [CertificateGenerator], signed by the Development CA.
 *   The WebView trusts this via [DevCaSslErrorHandler].
 * - **Upstream-side**: Uses the system default [SSLSocketFactory],
 *   which validates the real server certificate against the Android
 *   system trust store. No trust-all TrustManagers are used. Hostname
 *   verification is NOT bypassed.
 * - Private keys are never logged or exposed.
 * - This interceptor is scoped to this development browser only.
 *
 * ## Keep-Alive
 *
 * The relay loop supports HTTP/1.1 keep-alive: after forwarding one
 * request-response pair, it loops to handle the next request on the
 * same TLS connection, until either side closes the connection.
 */
object TlsInterceptor {

    private const val TAG = "TlsInterceptor"
    private const val BUFFER_SIZE = 8192
    private const val MAX_CAPTURE_SIZE = 1024 * 1024L // 1 MB
    internal const val KEEP_ALIVE_IDLE_TIMEOUT_MS = 10_000
    private const val ACTIVE_READ_TIMEOUT_MS = 30_000

    private val historyRepo = HttpHistoryRepository.getInstance()

    /**
     * Performs TLS interception on an established CONNECT tunnel.
     *
     * This method blocks the calling thread until the connection is
     * closed by either side.
     *
     * @param clientSocket The raw TCP socket connected to the WebView.
     * @param clientIn The [BufferedInputStream] from the client socket
     *   (may contain bytes already buffered by the CONNECT handler).
     * @param clientOut The [OutputStream] to the client socket.
     * @param upstreamSocket The raw TCP socket connected to the
     *   destination server.
     * @param hostname The destination hostname (e.g. "example.com").
     */
    fun intercept(
        clientSocket: Socket,
        clientIn: BufferedInputStream,
        clientOut: OutputStream,
        upstreamSocket: Socket,
        hostname: String
    ) {
        var browserSslSocket: SSLSocket? = null
        var upstreamSslSocket: SSLSocket? = null

        try {
            // 1. Generate (or retrieve cached) per-host certificate
            val hostCert = CertificateGenerator.getOrCreate(hostname)
            val serverSslContext = CertificateGenerator.createSslContext(hostCert)

            // 2. Browser-side TLS (server mode)
            //    Wrap the client socket so we present our per-host cert
            browserSslSocket = serverSslContext.socketFactory.createSocket(
                clientSocket,
                clientSocket.inetAddress?.hostAddress,
                clientSocket.port,
                /* autoClose = */ false
            ) as SSLSocket
            browserSslSocket.useClientMode = false
            browserSslSocket.startHandshake()
            Log.i(TAG, "Browser-side TLS handshake completed for '$hostname'")

            // 3. Upstream-side TLS (client mode)
            //    Use the system default SSLSocketFactory which validates
            //    the real server's certificate against the Android trust store.
            //    Configure HTTPS endpoint identification algorithm to enforce
            //    strict RFC 2818/6125 hostname verification.
            val systemSslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            upstreamSslSocket = systemSslFactory.createSocket(
                upstreamSocket,
                hostname,
                upstreamSocket.port,
                /* autoClose = */ false
            ) as SSLSocket
            upstreamSslSocket.useClientMode = true

            val sslParams = upstreamSslSocket.sslParameters
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            upstreamSslSocket.sslParameters = sslParams

            upstreamSslSocket.startHandshake()
            Log.i(TAG, "Upstream TLS handshake completed for '$hostname' (hostname verification enabled)")

            // 4. HTTP relay loop (supports keep-alive)
            val browserIn = BufferedInputStream(browserSslSocket.inputStream)
            val browserOut = browserSslSocket.outputStream
            val upstreamIn = BufferedInputStream(upstreamSslSocket.inputStream)
            val upstreamOut = upstreamSslSocket.outputStream

            relayLoop(
                browserSslSocket,
                upstreamSslSocket,
                browserIn,
                browserOut,
                upstreamIn,
                upstreamOut,
                hostname
            )

        } catch (e: javax.net.ssl.SSLHandshakeException) {
            Log.w(TAG, "TLS handshake failed for '$hostname': ${e.message}")
        } catch (e: javax.net.ssl.SSLException) {
            Log.w(TAG, "TLS error for '$hostname': ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Socket timed out for '$hostname': ${e.message}")
        } catch (e: IOException) {
            // Normal — connection closed by browser or server
            Log.d(TAG, "Connection closed for '$hostname': ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error intercepting '$hostname'", e)
        } finally {
            closeQuietly(browserSslSocket)
            closeQuietly(upstreamSslSocket)
            closeQuietly(clientSocket)
            closeQuietly(upstreamSocket)
            Log.d(TAG, "Interception ended for '$hostname'")
        }
    }

    // ------------------------------------------------------------------ //
    //  HTTP Relay
    // ------------------------------------------------------------------ //

    /**
     * Reads HTTP requests from the browser and relays them to the
     * upstream server, then reads the upstream response and relays
     * it back. Loops for HTTP/1.1 keep-alive connections.
     */
    private fun relayLoop(
        browserSocket: SSLSocket,
        upstreamSocket: SSLSocket,
        browserIn: BufferedInputStream,
        browserOut: OutputStream,
        upstreamIn: BufferedInputStream,
        upstreamOut: OutputStream,
        hostname: String
    ) {
        while (true) {
            // Set idle keep-alive timeout when waiting for a new request
            browserSocket.soTimeout = KEEP_ALIVE_IDLE_TIMEOUT_MS

            // --- Read request line ---
            val requestLine = try {
                readLine(browserIn)
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "Keep-alive idle connection timed out for $hostname")
                break
            } ?: break

            if (requestLine.isEmpty()) continue

            // An active request has begun — switch to active transfer timeout
            browserSocket.soTimeout = ACTIVE_READ_TIMEOUT_MS
            upstreamSocket.soTimeout = ACTIVE_READ_TIMEOUT_MS

            // --- Read request headers ---
            val requestHeaders = mutableListOf<String>()
            var reqContentLength: Long = -1
            var reqIsChunked = false
            var clientConnectionClose = false
            while (true) {
                val headerLine = readLine(browserIn) ?: break
                if (headerLine.isEmpty()) break
                requestHeaders.add(headerLine)
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    reqContentLength = headerLine.substringAfter(":").trim().toLongOrNull() ?: -1
                }
                if (headerLine.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    headerLine.contains("chunked", ignoreCase = true)
                ) {
                    reqIsChunked = true
                }
                if (headerLine.startsWith("Connection:", ignoreCase = true)) {
                    if (headerLine.contains("close", ignoreCase = true)) {
                        clientConnectionClose = true
                    }
                }
            }

            // Parse method and URL for logging/history
            val parts = requestLine.split(" ", limit = 3)
            val method = if (parts.isNotEmpty()) parts[0] else "?"
            val path = if (parts.size > 1) parts[1] else "/"
            val httpVersion = if (parts.size > 2) parts[2] else "HTTP/1.1"
            val fullUrl = "https://$hostname$path"

            val isHttp10 = httpVersion.equals("HTTP/1.0", ignoreCase = true)
            // HTTP/1.0 defaults to close unless keep-alive is explicitly requested
            if (isHttp10 && !requestHeaders.any { it.startsWith("Connection:", ignoreCase = true) && it.contains("keep-alive", ignoreCase = true) }) {
                clientConnectionClose = true
            }

            Log.i(TAG, "$method $fullUrl ($httpVersion)")

            // Create history item
            var historyItem = HttpHistoryItem(
                method = method,
                url = fullUrl,
                requestHeaders = listOf(requestLine) + requestHeaders
            )
            historyRepo.addOrUpdate(historyItem)

            var finalMethod = method
            var finalPath = path
            var finalHttpVersion = if (parts.size > 2) parts[2] else "HTTP/1.1"
            var finalHeaders: List<String> = requestHeaders
            var finalBody: ByteArray? = null
            var finalBodyTruncated = false

            // --- Check Intercept Mode ---------------------------------------
            val interceptManager = InterceptManager.getInstance()
            if (interceptManager.isEnabled()) {
                val (bodyBytes, truncated) = readRequestBody(browserIn, reqContentLength, reqIsChunked)
                finalBody = bodyBytes.takeIf { it.isNotEmpty() }
                finalBodyTruncated = truncated

                val reqData = HttpRequestData(
                    method = method,
                    url = fullUrl,
                    httpVersion = finalHttpVersion,
                    headers = requestHeaders,
                    body = finalBody,
                    isBodyTruncated = finalBodyTruncated
                )

                val action = interceptManager.interceptOrPass(
                    id = historyItem.id,
                    request = reqData,
                    isHttps = true,
                    hostname = hostname
                )

                when (action) {
                    is InterceptAction.Drop -> {
                        Log.i(TAG, "HTTPS request dropped by user: $method $fullUrl")
                        val dropResponse = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: 32\r\nConnection: close\r\n\r\nRequest dropped by interceptor\r\n"
                        try {
                            browserOut.write(dropResponse.toByteArray())
                            browserOut.flush()
                        } catch (_: IOException) {}
                        historyRepo.addOrUpdate(
                            historyItem.copy(
                                statusCode = 403,
                                responseHeaders = listOf("HTTP/1.1 403 Forbidden", "Content-Type: text/plain", "X-Intercept-Status: Dropped")
                            )
                        )
                        break // End keep-alive connection
                    }
                    is InterceptAction.Forward -> {
                        val forwardedReq = action.request
                        finalMethod = forwardedReq.method
                        finalPath = try {
                            val parsed = java.net.URI(forwardedReq.url)
                            val p = if (parsed.rawPath.isNullOrEmpty()) "/" else parsed.rawPath
                            if (parsed.rawQuery != null) "$p?${parsed.rawQuery}" else p
                        } catch (_: Exception) {
                            if (forwardedReq.url.startsWith("/")) forwardedReq.url else path
                        }
                        finalHttpVersion = forwardedReq.httpVersion
                        finalHeaders = forwardedReq.headers
                        finalBody = forwardedReq.body
                        finalBodyTruncated = forwardedReq.isBodyTruncated

                        historyItem = historyItem.copy(
                            method = finalMethod,
                            url = forwardedReq.url,
                            requestHeaders = listOf("$finalMethod $finalPath $finalHttpVersion") + finalHeaders,
                            requestBody = finalBody,
                            requestBodyTruncated = finalBodyTruncated
                        )
                        historyRepo.addOrUpdate(historyItem)
                    }
                }
            }

            // --- Forward request to upstream ---
            upstreamOut.write("$finalMethod $finalPath $finalHttpVersion\r\n".toByteArray())
            for (header in finalHeaders) {
                upstreamOut.write("$header\r\n".toByteArray())
            }
            upstreamOut.write("\r\n".toByteArray())

            // Forward request body (buffered if intercepted, or streamed if unchanged)
            if (finalBody != null) {
                upstreamOut.write(finalBody)
                upstreamOut.flush()
            } else if (reqContentLength > 0 || reqIsChunked) {
                val initialCap = if (reqContentLength > 0) {
                    minOf(reqContentLength, MAX_CAPTURE_SIZE).toInt()
                } else {
                    minOf(MAX_CAPTURE_SIZE.toInt(), 16384)
                }
                val reqBodyCapture = ByteArrayOutputStream(initialCap)
                val reqTruncated = if (reqContentLength > 0) {
                    transfer(browserIn, upstreamOut, reqContentLength, reqBodyCapture)
                } else {
                    transferChunked(browserIn, upstreamOut, reqBodyCapture)
                }
                upstreamOut.flush()

                val captured = reqBodyCapture.toByteArray()
                historyItem = historyItem.copy(
                    requestBody = captured.takeIf { it.isNotEmpty() },
                    requestBodyTruncated = reqTruncated
                )
                historyRepo.addOrUpdate(historyItem)
            } else {
                upstreamOut.flush()
            }

            // --- Read response from upstream ---
            // RFC 7230 §3.5: Robustness tolerance to skip bounded leading empty lines between messages
            var statusLine: String? = null
            var emptyLineCount = 0
            while (emptyLineCount < 5) {
                val line = readLine(upstreamIn) ?: break
                if (line.isNotEmpty()) {
                    statusLine = line
                    break
                }
                emptyLineCount++
            }
            if (statusLine == null) break

            val statusParts = statusLine.split(" ", limit = 3)
            val statusCode = if (statusParts.size >= 2) statusParts[1].toIntOrNull() else null
            Log.i(TAG, "Response: $statusCode for $fullUrl")

            // Forward status line to browser
            browserOut.write("$statusLine\r\n".toByteArray())

            // Read and forward response headers
            val responseHeaders = mutableListOf<String>()
            var resContentLength: Long = -1
            var resIsChunked = false
            var upstreamConnectionClose = isHttp10 // Default close for HTTP/1.0
            while (true) {
                val headerLine = readLine(upstreamIn) ?: break
                browserOut.write("$headerLine\r\n".toByteArray())
                if (headerLine.isEmpty()) break
                responseHeaders.add(headerLine)
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    resContentLength = headerLine.substringAfter(":").trim().toLongOrNull() ?: -1
                }
                if (headerLine.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    headerLine.contains("chunked", ignoreCase = true)
                ) {
                    resIsChunked = true
                }
                if (headerLine.startsWith("Connection:", ignoreCase = true)) {
                    if (headerLine.contains("close", ignoreCase = true)) {
                        upstreamConnectionClose = true
                    } else if (headerLine.contains("keep-alive", ignoreCase = true) && isHttp10) {
                        upstreamConnectionClose = false
                    }
                }
            }
            browserOut.flush()

            historyItem = historyItem.copy(
                statusCode = statusCode,
                responseHeaders = listOf(statusLine) + responseHeaders
            )
            historyRepo.addOrUpdate(historyItem)

            val connectionClose = clientConnectionClose || upstreamConnectionClose

            // Forward response body if applicable
            val isHeadRequest = finalMethod.equals("HEAD", ignoreCase = true)
            val hasResponseBody = (resContentLength > 0 || resIsChunked || connectionClose || resContentLength == -1L) &&
                statusCode != 204 && statusCode != 304 && !isHeadRequest
            if (hasResponseBody) {
                val initialCap = if (resContentLength > 0) {
                    minOf(resContentLength, MAX_CAPTURE_SIZE).toInt()
                } else {
                    minOf(MAX_CAPTURE_SIZE.toInt(), 16384)
                }
                val resBodyCapture = ByteArrayOutputStream(initialCap)
                val resTruncated = if (resContentLength > 0) {
                    transfer(upstreamIn, browserOut, resContentLength, resBodyCapture)
                } else if (resIsChunked) {
                    transferChunked(upstreamIn, browserOut, resBodyCapture)
                } else {
                    transferUntilEof(upstreamIn, browserOut, resBodyCapture)
                }
                browserOut.flush()

                val captured = resBodyCapture.toByteArray()
                historyItem = historyItem.copy(
                    responseBody = captured.takeIf { it.isNotEmpty() },
                    responseBodyTruncated = resTruncated
                )
                historyRepo.addOrUpdate(historyItem)
            }

            // Terminate keep-alive loop if either side requested close or response was unparseable
            if (connectionClose || statusCode == null) {
                Log.d(TAG, "Relay loop ending (clientClose=$clientConnectionClose, upstreamClose=$upstreamConnectionClose, statusCode=$statusCode) for $hostname")
                break
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Stream Helpers (matching HttpProxyHandler patterns)
    // ------------------------------------------------------------------ //

    /**
     * Reads a single CRLF- or LF-terminated line from the stream.
     * Returns null on EOF.
     */
    internal fun readLine(input: InputStream): String? {
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

    /**
     * Transfers exactly [length] bytes from [input] to [output].
     * Optionally captures up to [MAX_CAPTURE_SIZE] bytes.
     * Returns true if capture was truncated.
     */
    internal fun transfer(
        input: InputStream,
        output: OutputStream,
        length: Long,
        captureStream: ByteArrayOutputStream? = null
    ): Boolean {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        var captured = 0L
        var truncated = false

        while (remaining > 0) {
            val toRead = minOf(remaining.toInt(), buffer.size)
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)

            if (captureStream != null && !truncated) {
                if (captured + read <= MAX_CAPTURE_SIZE) {
                    captureStream.write(buffer, 0, read)
                    captured += read
                } else {
                    val allowed = (MAX_CAPTURE_SIZE - captured).toInt()
                    if (allowed > 0) captureStream.write(buffer, 0, allowed)
                    truncated = true
                }
            }

            remaining -= read
        }
        return truncated
    }

    /**
     * Transfers chunked-encoded data, relaying chunk framing as-is.
     * Correctly consumes and relays all trailer headers and the final empty line terminating
     * the chunked section (RFC 7230 §4.1).
     * Enforces a shared aggregate capture limit across ALL chunks (max [MAX_CAPTURE_SIZE] bytes).
     * Returns true if capture was truncated.
     */
    internal fun transferChunked(
        input: InputStream,
        output: OutputStream,
        captureStream: ByteArrayOutputStream? = null
    ): Boolean {
        var totalCaptured = 0L
        var truncated = false
        val buffer = ByteArray(BUFFER_SIZE)

        while (true) {
            val chunkSizeLine = readLine(input) ?: break
            output.write("$chunkSizeLine\r\n".toByteArray())

            val chunkSize = try {
                chunkSizeLine.trim().split(";")[0].toLong(16)
            } catch (_: NumberFormatException) {
                break
            }

            if (chunkSize == 0L) {
                // Terminal chunk: read and relay trailer headers (if any) and terminating CRLF
                while (true) {
                    val trailerLine = readLine(input) ?: break
                    output.write("$trailerLine\r\n".toByteArray())
                    if (trailerLine.isEmpty()) break
                }
                break
            }

            // Transfer chunk data with aggregate capture tracking
            var chunkRemaining = chunkSize
            while (chunkRemaining > 0) {
                val toRead = minOf(chunkRemaining.toInt(), buffer.size)
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                output.write(buffer, 0, read)

                if (captureStream != null && !truncated) {
                    val remainingAllowed = MAX_CAPTURE_SIZE - totalCaptured
                    if (read <= remainingAllowed) {
                        captureStream.write(buffer, 0, read)
                        totalCaptured += read
                        if (totalCaptured >= MAX_CAPTURE_SIZE) {
                            truncated = true
                        }
                    } else {
                        if (remainingAllowed > 0) {
                            captureStream.write(buffer, 0, remainingAllowed.toInt())
                            totalCaptured += remainingAllowed
                        }
                        truncated = true
                    }
                } else if (captureStream != null) {
                    truncated = true
                }

                chunkRemaining -= read
            }

            // Each chunk data is followed by CRLF
            readLine(input)
            output.write("\r\n".toByteArray())
        }
        output.flush()
        return truncated
    }

    /**
     * Transfers all remaining data until EOF.
     * Returns true if capture was truncated.
     */
    internal fun transferUntilEof(
        input: InputStream,
        output: OutputStream,
        captureStream: ByteArrayOutputStream? = null
    ): Boolean {
        val buffer = ByteArray(BUFFER_SIZE)
        var captured = 0L
        var truncated = false

        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)

            if (captureStream != null && !truncated) {
                if (captured + read <= MAX_CAPTURE_SIZE) {
                    captureStream.write(buffer, 0, read)
                    captured += read
                } else {
                    val allowed = (MAX_CAPTURE_SIZE - captured).toInt()
                    if (allowed > 0) captureStream.write(buffer, 0, allowed)
                    truncated = true
                }
            }
        }
        return truncated
    }

    /**
     * Reads the incoming request body into memory, bounded by [MAX_CAPTURE_SIZE] (1 MB).
     * Supports both Content-Length and chunked transfer encodings.
     */
    internal fun readRequestBody(
        input: InputStream,
        contentLength: Long,
        isChunked: Boolean
    ): Pair<ByteArray, Boolean> {
        if (contentLength <= 0 && !isChunked) {
            return Pair(ByteArray(0), false)
        }
        val captureStream = ByteArrayOutputStream(
            if (contentLength > 0) minOf(contentLength, MAX_CAPTURE_SIZE).toInt() else 1024
        )
        var truncated = false

        if (contentLength > 0) {
            var remaining = contentLength
            val buffer = ByteArray(BUFFER_SIZE)
            var captured = 0L
            while (remaining > 0) {
                val toRead = minOf(remaining.toInt(), buffer.size)
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                if (captured + read <= MAX_CAPTURE_SIZE) {
                    captureStream.write(buffer, 0, read)
                    captured += read
                } else if (!truncated) {
                    val allowed = (MAX_CAPTURE_SIZE - captured).toInt()
                    if (allowed > 0) captureStream.write(buffer, 0, allowed)
                    truncated = true
                }
                remaining -= read
            }
        } else if (isChunked) {
            var captured = 0L
            while (true) {
                val chunkSizeLine = readLine(input) ?: break
                val chunkSize = try {
                    chunkSizeLine.trim().split(";")[0].toLong(16)
                } catch (_: NumberFormatException) {
                    break
                }
                if (chunkSize == 0L) {
                    while (true) {
                        val trailerLine = readLine(input) ?: break
                        if (trailerLine.isEmpty()) break
                    }
                    break
                }
                var chunkRemaining = chunkSize
                val buffer = ByteArray(BUFFER_SIZE)
                while (chunkRemaining > 0) {
                    val toRead = minOf(chunkRemaining.toInt(), buffer.size)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    if (captured + read <= MAX_CAPTURE_SIZE) {
                        captureStream.write(buffer, 0, read)
                        captured += read
                    } else if (!truncated) {
                        val allowed = (MAX_CAPTURE_SIZE - captured).toInt()
                        if (allowed > 0) captureStream.write(buffer, 0, allowed)
                        truncated = true
                    }
                    chunkRemaining -= read
                }
                readLine(input) // CRLF after chunk data
            }
        }
        return Pair(captureStream.toByteArray(), truncated)
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }
}
