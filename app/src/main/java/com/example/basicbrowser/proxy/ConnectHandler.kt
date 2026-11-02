package com.example.basicbrowser.proxy

import android.util.Log
import com.example.basicbrowser.proxy.tls.CertificateAuthority
import com.example.basicbrowser.proxy.tls.TlsInterceptor
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Functional interface for DNS address resolution.
 * Allows deterministic unit testing and custom address resolution strategies.
 */
fun interface DnsResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): Array<InetAddress>
}

/**
 * Functional interface for creating and connecting upstream client sockets.
 * Ensures sockets that fail to connect are closed immediately without leaking.
 */
fun interface SocketConnector {
    @Throws(IOException::class)
    fun connect(address: InetAddress, port: Int, timeoutMs: Int): Socket
}

/**
 * Handles HTTP CONNECT requests for HTTPS interception.
 *
 * When the browser issues an HTTPS request via the proxy:
 * 1. Browser sends `CONNECT host:port HTTP/1.1`
 * 2. ConnectHandler connects to `host:port` via TCP with bounded multi-address fallback
 * 3. On success, ConnectHandler replies `HTTP/1.1 200 Connection Established`
 * 4. ConnectHandler delegates to [TlsInterceptor] which:
 *    - Presents a per-host certificate signed by the Development CA to the browser
 *    - Opens a new TLS connection to the real upstream server
 *    - Relays decrypted HTTP requests/responses between browser and server
 *    - Records intercepted traffic to HttpHistoryRepository
 *
 * ## Security
 * - Per-host certificates are signed by the Development CA (Step 3A)
 * - The browser trusts these via DevCaSslErrorHandler (Step 3B)
 * - Upstream TLS uses the Android system trust store (no trust-all)
 * - Strict RFC 2818/6125 hostname verification is enforced
 * - No certificate pinning bypass is implemented
 * - This is scoped to this development browser only
 *
 * ## DNS & Connection Limits
 * - DNS resolution uses synchronous `InetAddress.getAllByName(destHost)` (or injected [DnsResolver]).
 *   *Note on Android*: Synchronous libc `getaddrinfo` cannot be interrupted or cancelled
 *   from managed code; its execution time depends on system DNS resolver timeouts.
 * - Connection attempts use a per-address timeout of at most 3 seconds.
 * - An aggregate connection deadline of at most 10 seconds is enforced across all candidate addresses.
 * - Sockets from failed connection attempts are closed immediately.
 */
class ConnectHandler(
    private val clientSocket: Socket,
    private val clientIn: BufferedInputStream,
    private val clientOut: OutputStream,
    private val dnsResolver: DnsResolver = DnsResolver { host -> InetAddress.getAllByName(host) },
    private val socketConnector: SocketConnector = SocketConnector { addr, port, timeout ->
        val s = Socket()
        try {
            s.connect(InetSocketAddress(addr, port), timeout)
            s
        } catch (e: Exception) {
            try { s.close() } catch (_: IOException) {}
            throw e
        }
    },
    private val caReadinessChecker: () -> Boolean = {
        if (CertificateAuthority.isInitialized()) true
        else CertificateAuthority.awaitInitialization(15_000)
    }
) {

    companion object {
        private const val TAG = "PROXY"
        const val PER_ADDRESS_CONNECT_TIMEOUT_MS = 3_000
        const val AGGREGATE_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_HTTPS_PORT = 443

        /**
         * Parses an authority string like "example.com:443" or "[::1]:443" into host and port.
         * Returns null if the format is invalid or port is out of range.
         */
        fun parseAuthority(authority: String, defaultPort: Int = DEFAULT_HTTPS_PORT): Pair<String, Int>? {
            val trimmed = authority.trim()
            if (trimmed.isEmpty()) return null

            return try {
                if (trimmed.startsWith("[")) {
                    // IPv6 literal, e.g. [2001:db8::1]:443 or [2001:db8::1]
                    val closeBracket = trimmed.indexOf(']')
                    if (closeBracket == -1) return null
                    val host = trimmed.substring(1, closeBracket)
                    val rest = trimmed.substring(closeBracket + 1)
                    val port = if (rest.startsWith(":")) {
                        rest.substring(1).toInt()
                    } else {
                        defaultPort
                    }
                    if (port in 1..65535) Pair(host, port) else null
                } else {
                    // IPv4 or hostname, e.g. example.com:443 or example.com
                    val colonIdx = trimmed.lastIndexOf(':')
                    if (colonIdx != -1) {
                        val host = trimmed.substring(0, colonIdx)
                        val portStr = trimmed.substring(colonIdx + 1)
                        val port = portStr.toInt()
                        if (host.isNotEmpty() && port in 1..65535) Pair(host, port) else null
                    } else {
                        if (trimmed.isNotEmpty()) Pair(trimmed, defaultPort) else null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Handles the CONNECT request end-to-end.
     *
     * @param authority The destination authority from the request line (e.g. "example.com:443").
     * @param httpVersion The HTTP version from the request line (e.g. "HTTP/1.1").
     * @param headers The list of request headers already parsed, or null if to be read from stream.
     */
    fun handle(authority: String, httpVersion: String, headers: List<String> = emptyList()) {
        val target = parseAuthority(authority)
        if (target == null) {
            Log.w(TAG, "Invalid CONNECT authority syntax: $authority")
            sendError(clientOut, 400, "Bad Request — invalid authority '$authority'")
            return
        }

        val (destHost, destPort) = target
        Log.i(TAG, "CONNECT $destHost:$destPort")

        // 1. Resolve destination addresses via DNS
        val dnsStartTime = System.currentTimeMillis()
        val addresses: Array<InetAddress> = try {
            dnsResolver.resolve(destHost)
        } catch (e: UnknownHostException) {
            val dnsElapsed = System.currentTimeMillis() - dnsStartTime
            Log.w(TAG, "DNS resolution failed for $destHost in ${dnsElapsed}ms: ${e.message}")
            sendError(clientOut, 502, "Bad Gateway — DNS resolution failed for $destHost")
            return
        }
        val dnsElapsed = System.currentTimeMillis() - dnsStartTime
        Log.i(TAG, "DNS resolved $destHost to ${addresses.size} address(es) in ${dnsElapsed}ms")

        if (addresses.isEmpty()) {
            Log.w(TAG, "DNS resolved 0 addresses for $destHost")
            sendError(clientOut, 502, "Bad Gateway — No IP addresses found for $destHost")
            return
        }

        // 2. Establish TCP connection with bounded per-address timeout and aggregate deadline
        val connectStartTime = System.currentTimeMillis()
        var connectedSocket: Socket? = null
        var lastException: Exception? = null

        for ((index, address) in addresses.withIndex()) {
            // Check if client socket closed while negotiating upstream
            if (clientSocket.isClosed) {
                Log.i(TAG, "Client socket closed before upstream connection to $destHost:$destPort")
                return
            }

            val elapsedTotal = System.currentTimeMillis() - connectStartTime
            val remainingAggregateMs = AGGREGATE_CONNECT_TIMEOUT_MS - elapsedTotal
            if (remainingAggregateMs <= 0) {
                Log.w(TAG, "Aggregate connect timeout reached (${elapsedTotal}ms) for $destHost:$destPort")
                if (lastException == null) {
                    lastException = SocketTimeoutException("Aggregate connect deadline reached ($elapsedTotal ms)")
                }
                break
            }

            val timeoutForAttempt = minOf(PER_ADDRESS_CONNECT_TIMEOUT_MS.toLong(), remainingAggregateMs).toInt()
            val attemptStart = System.currentTimeMillis()
            val family = if (address is java.net.Inet6Address) "IPv6" else "IPv4"
            Log.i(TAG, "Connecting to $destHost via $address ($family, attempt ${index + 1}/${addresses.size}, timeout=${timeoutForAttempt}ms)")

            try {
                val s = socketConnector.connect(address, destPort, timeoutForAttempt)
                val attemptDuration = System.currentTimeMillis() - attemptStart
                Log.i(TAG, "Connected to $destHost via $address ($family) in ${attemptDuration}ms")
                connectedSocket = s
                break
            } catch (e: Exception) {
                val attemptDuration = System.currentTimeMillis() - attemptStart
                Log.w(TAG, "Attempt to $destHost via $address ($family) failed in ${attemptDuration}ms: ${e.javaClass.simpleName} (${e.message})")
                lastException = e
            }
        }

        if (connectedSocket == null) {
            val totalConnectTime = System.currentTimeMillis() - connectStartTime
            Log.w(TAG, "All connection attempts failed for $destHost:$destPort in ${totalConnectTime}ms: ${lastException?.message}")
            when (lastException) {
                is SocketTimeoutException -> {
                    sendError(clientOut, 504, "Gateway Timeout — Connection timed out to $destHost:$destPort")
                }
                is ConnectException -> {
                    sendError(clientOut, 502, "Bad Gateway — Connection refused by $destHost:$destPort")
                }
                else -> {
                    sendError(clientOut, 502, "Bad Gateway — ${lastException?.message ?: "Connection failed"}")
                }
            }
            return
        }

        val serverSocket = connectedSocket

        // 3. Verify TLS CA readiness before replying to client
        val caReady = caReadinessChecker()
        if (!caReady) {
            Log.w(TAG, "CA not initialized — cannot intercept TLS for $destHost:$destPort")
            sendError(clientOut, 502, "Bad Gateway — TLS interception unavailable (CA not ready)")
            closeQuietly(serverSocket)
            closeQuietly(clientSocket)
            return
        }

        // Reply with 200 Connection Established
        try {
            val response = "$httpVersion 200 Connection Established\r\n" +
                    "Proxy-Agent: BasicBrowser-Proxy/1.0\r\n" +
                    "\r\n"
            clientOut.write(response.toByteArray(Charsets.US_ASCII))
            clientOut.flush()
            Log.i(TAG, "CONNECT established to $destHost:$destPort")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send 200 Connection Established to client: ${e.message}")
            closeQuietly(serverSocket)
            return
        }

        // 4. TLS interception — terminate TLS on both sides and relay HTTP
        TlsInterceptor.intercept(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            upstreamSocket = serverSocket,
            hostname = destHost
        )
    }

    /**
     * Sends an HTTP error response to the client.
     */
    private fun sendError(output: OutputStream, statusCode: Int, reason: String) {
        try {
            val body = "<html><body><h1>$statusCode $reason</h1></body></html>"
            val response = "HTTP/1.1 $statusCode $reason\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    body
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }
}
