package com.example.basicbrowser.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URL

import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptAction
import com.example.basicbrowser.proxy.intercept.InterceptManager
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Handles a single HTTP proxy request from a client socket.
 *
 * Responsibilities:
 * - Parse the HTTP request line and headers
 * - Determine the destination host and port
 * - Open a connection to the destination server
 * - Forward the HTTP request (converting absolute URI to relative)
 * - Relay the response back to the client
 * - Log method, host, port, path, and response status
 * - Record history of request and response
 *
 * This class is intentionally stateless — one instance per connection.
 * Future stages will hook into this pipeline for history, interception,
 * Repeater, and Intruder.
 */
class HttpProxyHandler(private val clientSocket: Socket) {

    companion object {
        private const val TAG = "PROXY"
        private const val BUFFER_SIZE = 8192
        const val MAX_CAPTURE_SIZE = 1024 * 1024L // 1MB
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Read a single line (terminated by \r\n or \n) from the input stream.
         * Returns null on EOF.
         */
        internal fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    // Strip trailing \r if present
                    if (sb.isNotEmpty() && sb.last() == '\r') {
                        sb.deleteCharAt(sb.length - 1)
                    }
                    return sb.toString()
                }
                sb.append(b.toChar())
            }
        }

        /** 
         * Transfer exactly [length] bytes from [input] to [output]. 
         * Optionally records up to MAX_CAPTURE_SIZE bytes into [captureStream].
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
         * Transfer chunked-encoded data, relaying chunk framing as-is. 
         * Correctly consumes and relays all trailer headers and terminating empty line (RFC 7230 §4.1).
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
                } catch (e: NumberFormatException) {
                    break
                }

                if (chunkSize == 0L) {
                    // Terminal chunk — read and relay trailing headers and terminating empty line
                    while (true) {
                        val trailerLine = readLine(input) ?: break
                        output.write("$trailerLine\r\n".toByteArray())
                        if (trailerLine.isEmpty()) break
                    }
                    break
                }

                // Transfer the chunk data with aggregate capture tracking
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
                
                // Each chunk is followed by CRLF
                readLine(input) // consume \r\n after chunk data
                output.write("\r\n".toByteArray())
            }
            output.flush()
            return truncated
        }

        /** 
         * Transfer all remaining data until EOF. 
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
    }

    private val historyRepo = HttpHistoryRepository.getInstance()

    /**
     * Process the proxied HTTP request end-to-end.
     * Called from a worker thread — never from the UI thread.
     */
    fun handle() {
        var serverSocket: Socket? = null
        try {
            clientSocket.soTimeout = READ_TIMEOUT_MS
            val clientIn = BufferedInputStream(clientSocket.getInputStream())
            val clientOut = clientSocket.getOutputStream()

            // --- 1. Read the request line -----------------------------------
            val requestLine = readLine(clientIn) ?: run {
                Log.w(TAG, "Empty request — no request line received")
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                Log.w(TAG, "Malformed request line: $requestLine")
                sendError(clientOut, 400, "Bad Request")
                return
            }

            val method = parts[0]
            val rawUri = parts[1]
            val httpVersion = parts[2]

            // --- 2. Read client headers -------------------------------------
            val requestHeaders = mutableListOf<String>()
            var contentLength: Long = -1
            var reqIsChunked = false
            while (true) {
                val headerLine = readLine(clientIn) ?: break
                if (headerLine.isEmpty()) break  // End of headers
                requestHeaders.add(headerLine)
                // Track Content-Length for request body forwarding
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substringAfter(":").trim().toLongOrNull() ?: -1
                }
                if (headerLine.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                    headerLine.contains("chunked", ignoreCase = true)
                ) {
                    reqIsChunked = true
                }
            }

            // --- 3. Handle HTTPS CONNECT requests ---------------------------
            if (method.equals("CONNECT", ignoreCase = true)) {
                ConnectHandler(clientSocket, clientIn, clientOut).handle(rawUri, httpVersion, requestHeaders)
                return
            }

            // --- 4. Parse destination from the absolute URI -----------------
            val url: URL
            try {
                url = URL(rawUri)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot parse URI: $rawUri")
                sendError(clientOut, 400, "Bad Request — invalid URI")
                return
            }

            var destHost = url.host
            var destPort = if (url.port != -1) url.port else 80
            // Convert absolute URI → relative path for the upstream request
            var relativePath = if (url.path.isNullOrEmpty()) "/" else url.path +
                (if (url.query != null) "?${url.query}" else "")

            Log.i(TAG, "$method $rawUri")
            Log.i(TAG, "Connecting to $destHost:$destPort")

            var historyItem = HttpHistoryItem(
                method = method,
                url = rawUri,
                requestHeaders = listOf(requestLine) + requestHeaders
            )
            historyRepo.addOrUpdate(historyItem)

            var finalMethod = method
            var finalRawUri = rawUri
            var finalHttpVersion = httpVersion
            var finalHeaders: List<String> = requestHeaders
            var finalBody: ByteArray? = null
            var finalBodyTruncated = false

            // --- 4B. Check Intercept Mode -----------------------------------
            val interceptManager = InterceptManager.getInstance()
            if (interceptManager.isEnabled()) {
                val (bodyBytes, truncated) = readRequestBody(clientIn, contentLength, reqIsChunked)
                finalBody = bodyBytes.takeIf { it.isNotEmpty() }
                finalBodyTruncated = truncated

                val reqData = HttpRequestData(
                    method = method,
                    url = rawUri,
                    httpVersion = httpVersion,
                    headers = requestHeaders,
                    body = finalBody,
                    isBodyTruncated = finalBodyTruncated
                )

                val action = interceptManager.interceptOrPass(
                    id = historyItem.id,
                    request = reqData,
                    isHttps = false,
                    hostname = destHost
                )

                when (action) {
                    is InterceptAction.Drop -> {
                        Log.i(TAG, "HTTP request dropped by user: $method $rawUri")
                        sendError(clientOut, 403, "Forbidden — request dropped by interceptor")
                        historyRepo.addOrUpdate(
                            historyItem.copy(
                                statusCode = 403,
                                responseHeaders = listOf("HTTP/1.1 403 Forbidden", "Content-Type: text/html", "X-Intercept-Status: Dropped")
                            )
                        )
                        return
                    }
                    is InterceptAction.Forward -> {
                        val forwardedReq = action.request
                        finalMethod = forwardedReq.method
                        finalRawUri = forwardedReq.url
                        finalHttpVersion = forwardedReq.httpVersion
                        finalHeaders = forwardedReq.headers
                        finalBody = forwardedReq.body
                        finalBodyTruncated = forwardedReq.isBodyTruncated

                        try {
                            val newUrl = URL(finalRawUri)
                            destHost = newUrl.host
                            destPort = if (newUrl.port != -1) newUrl.port else 80
                            relativePath = if (newUrl.path.isNullOrEmpty()) "/" else newUrl.path +
                                (if (newUrl.query != null) "?${newUrl.query}" else "")
                        } catch (e: Exception) {
                            Log.w(TAG, "Cannot re-parse edited URI: $finalRawUri, keeping original host: $destHost")
                        }

                        historyItem = historyItem.copy(
                            method = finalMethod,
                            url = finalRawUri,
                            requestHeaders = listOf("$finalMethod $relativePath $finalHttpVersion") + finalHeaders,
                            requestBody = finalBody,
                            requestBodyTruncated = finalBodyTruncated
                        )
                        historyRepo.addOrUpdate(historyItem)
                    }
                }
            }

            // --- 4C. Connect to the destination server with explicit timeout -
            val targetSocket = Socket()
            try {
                targetSocket.connect(java.net.InetSocketAddress(destHost, destPort), CONNECT_TIMEOUT_MS)
                targetSocket.soTimeout = READ_TIMEOUT_MS
                serverSocket = targetSocket
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Connection timed out to $destHost:$destPort")
                sendError(clientOut, 504, "Gateway Timeout — connection timed out to $destHost:$destPort")
                closeQuietly(targetSocket)
                return
            } catch (e: IOException) {
                Log.e(TAG, "Cannot connect to $destHost:$destPort — ${e.message}")
                sendError(clientOut, 502, "Bad Gateway — cannot reach $destHost:$destPort")
                closeQuietly(targetSocket)
                return
            }

            val serverIn = BufferedInputStream(serverSocket.getInputStream())
            val serverOut = serverSocket.getOutputStream()

            // --- 5. Forward the request to the destination ------------------
            // Write reconstructed request line with relative path
            serverOut.write("$finalMethod $relativePath $finalHttpVersion\r\n".toByteArray())
            for (header in finalHeaders) {
                serverOut.write("$header\r\n".toByteArray())
            }
            serverOut.write("\r\n".toByteArray())

            // Forward request body (buffered if intercepted, or streamed if unchanged)
            if (finalBody != null) {
                serverOut.write(finalBody)
                serverOut.flush()
            } else if (contentLength > 0 || reqIsChunked) {
                val initialCap = if (contentLength > 0) minOf(contentLength, MAX_CAPTURE_SIZE).toInt() else 1024
                val reqBodyStream = ByteArrayOutputStream(initialCap)
                val reqTruncated = if (contentLength > 0) {
                    transfer(clientIn, serverOut, contentLength, reqBodyStream)
                } else {
                    transferChunked(clientIn, serverOut, reqBodyStream)
                }
                serverOut.flush()
                val captured = reqBodyStream.toByteArray()
                historyItem = historyItem.copy(
                    requestBody = captured.takeIf { it.isNotEmpty() },
                    requestBodyTruncated = reqTruncated
                )
                historyRepo.addOrUpdate(historyItem)
            } else {
                serverOut.flush()
            }

            // --- 6. Read and relay the response -----------------------------
            // Read the response status line
            val statusLine = readLine(serverIn) ?: run {
                Log.w(TAG, "No response from $destHost:$destPort")
                sendError(clientOut, 502, "Bad Gateway — no response")
                return
            }

            // Parse status code for logging
            val statusParts = statusLine.split(" ", limit = 3)
            val statusCode = if (statusParts.size >= 2) statusParts[1].toIntOrNull() else null
            Log.i(TAG, "Response: $statusCode")

            // Write status line to client
            clientOut.write("$statusLine\r\n".toByteArray())

            // Read and forward response headers, track Content-Length
            val responseHeaders = mutableListOf<String>()
            var responseContentLength: Long = -1
            var isChunked = false
            while (true) {
                val headerLine = readLine(serverIn) ?: break
                clientOut.write("$headerLine\r\n".toByteArray())
                if (headerLine.isEmpty()) break  // End of headers
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
            clientOut.flush()

            historyItem = historyItem.copy(
                statusCode = statusCode,
                responseHeaders = listOf(statusLine) + responseHeaders
            )
            historyRepo.addOrUpdate(historyItem)

            // Forward response body if applicable
            val hasBody = (responseContentLength > 0 || isChunked || responseContentLength == -1L) &&
                statusCode != 204 && statusCode != 304
            if (hasBody) {
                val initialCap = if (responseContentLength > 0) {
                    minOf(responseContentLength, MAX_CAPTURE_SIZE).toInt()
                } else {
                    minOf(MAX_CAPTURE_SIZE.toInt(), 16384)
                }
                val resBodyStream = ByteArrayOutputStream(initialCap)
                val resTruncated = if (responseContentLength > 0) {
                    transfer(serverIn, clientOut, responseContentLength, resBodyStream)
                } else if (isChunked) {
                    transferChunked(serverIn, clientOut, resBodyStream)
                } else {
                    transferUntilEof(serverIn, clientOut, resBodyStream)
                }
                clientOut.flush()

                val captured = resBodyStream.toByteArray()
                historyItem = historyItem.copy(
                    responseBody = captured.takeIf { it.isNotEmpty() },
                    responseBodyTruncated = resTruncated
                )
                historyRepo.addOrUpdate(historyItem)
            }

        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Socket read timed out: ${e.message}")
        } catch (e: IOException) {
            if (clientSocket.isClosed) {
                Log.d(TAG, "Client disconnected")
            } else {
                Log.e(TAG, "Error handling HTTP request: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error handling HTTP request", e)
        } finally {
            closeQuietly(serverSocket)
            closeQuietly(clientSocket)
        }
    }



    /**
     * Reads the incoming request body into memory, bounded by [MAX_CAPTURE_SIZE] (1 MB).
     * Supports both Content-Length and chunked transfer encodings.
     */
    private fun readRequestBody(
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
                    readLine(input) // CRLF after last chunk
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

    /** Send a minimal HTTP error response to the client. */
    private fun sendError(output: OutputStream, code: Int, message: String) {
        try {
            val body = "<html><body><h1>$code $message</h1></body></html>"
            val response = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
            output.write(response.toByteArray())
            output.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send error response", e)
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
            // ignore
        }
    }
}
