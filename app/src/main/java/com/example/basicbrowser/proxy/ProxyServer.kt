package com.example.basicbrowser.proxy

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.example.basicbrowser.proxy.intercept.InterceptManager

/**
 * A local TCP proxy server that listens on 127.0.0.1:8080.
 *
 * Uses a bounded ThreadPoolExecutor (max 16 workers) to ensure memory
 * and thread efficiency on mobile devices. If overloaded, incoming
 * connections receive a fast HTTP 503 Service Unavailable response
 * without blocking the accept loop or silently dropping requests.
 *
 * All network I/O runs on background threads so the Android UI thread
 * is never blocked.
 */
class ProxyServer private constructor(
    private val host: String = "127.0.0.1",
    private val port: Int = 8080
) {

    companion object {
        private const val TAG = "ProxyServer"

        // Concurrency bounds for mobile efficiency
        const val DEFAULT_CORE_WORKERS = 4
        const val DEFAULT_MAX_WORKERS = 16
        const val DEFAULT_QUEUE_CAPACITY = 32
        const val KEEP_ALIVE_SECONDS = 60L

        @Volatile
        private var instance: ProxyServer? = null

        /** Returns the singleton ProxyServer, creating it if needed. */
        fun getInstance(host: String = "127.0.0.1", port: Int = 8080): ProxyServer {
            return instance ?: synchronized(this) {
                instance ?: ProxyServer(host, port).also { instance = it }
            }
        }
    }

    /** Signals whether the accept-loop should keep running. */
    private val isRunning = AtomicBoolean(false)

    /** The listening socket; null when the server is stopped. */
    private var serverSocket: ServerSocket? = null

    /**
     * Single-threaded executor that runs the accept-loop.
     * Using a dedicated thread keeps the main thread free.
     */
    private var acceptExecutor: ThreadPoolExecutor? = null

    /**
     * Bounded thread pool for handling client connections.
     */
    private var connectionExecutor: ThreadPoolExecutor? = null

    /**
     * Tracks currently open client sockets so all connections can be
     * closed immediately upon server shutdown without leaking.
     */
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Starts the proxy server. Safe to call from any thread (including
     * the UI thread) because the actual socket work is dispatched to
     * a background executor.
     */
    fun start(
        coreWorkers: Int = DEFAULT_CORE_WORKERS,
        maxWorkers: Int = DEFAULT_MAX_WORKERS,
        queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
    ) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "start() called but proxy is already running")
            return
        }

        val threadId = AtomicInteger(1)
        acceptExecutor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { r ->
            Thread(r, "proxy-accept").apply { isDaemon = true }
        }

        connectionExecutor = ThreadPoolExecutor(
            coreWorkers,
            maxWorkers,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(queueCapacity),
            { r ->
                Thread(r, "proxy-conn-${threadId.getAndIncrement()}").apply { isDaemon = true }
            },
            OverloadRejectionHandler()
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        acceptExecutor?.execute { acceptLoop() }
    }

    /**
     * Stops the proxy server and releases all resources.
     * Waits briefly for the accept thread to finish so the port
     * is fully released before returning.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            Log.w(TAG, "stop() called but proxy is not running")
            return
        }

        Log.i(TAG, "Stopping proxy server…")

        // 1. Close the server socket first to unblock accept()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        // 1B. Release any paused requests in InterceptManager so waiting worker threads unblock
        InterceptManager.getInstance().releaseAll("proxy_stopped")

        // 2. Close all active client sockets so blocked threads unblock immediately
        val openSockets = activeSockets.toList()
        for (socket in openSockets) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
        activeSockets.clear()

        // 3. Shut down executors
        acceptExecutor?.shutdown()
        try {
            acceptExecutor?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        acceptExecutor?.shutdownNow()

        connectionExecutor?.shutdownNow()
        try {
            connectionExecutor?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }

        acceptExecutor = null
        connectionExecutor = null

        Log.i(TAG, "Proxy server stopped")
    }

    /** Returns `true` when the accept-loop is active. */
    fun isRunning(): Boolean = isRunning.get()

    /** Returns the number of currently active connections. For testing/diagnostics. */
    fun getActiveConnectionCount(): Int = activeSockets.size

    // ------------------------------------------------------------------ //
    //  Internal
    // ------------------------------------------------------------------ //

    /**
     * Binds to [host]:[port] and loops accepting connections until
     * [isRunning] is flipped to `false` or the socket is closed.
     */
    private fun acceptLoop() {
        try {
            val bindAddress = InetAddress.getByName(host)
            val socket = ServerSocket()
            socket.reuseAddress = true  // Allow quick rebind after restart
            socket.bind(InetSocketAddress(bindAddress, port))
            serverSocket = socket
            Log.i(TAG, "Proxy server started on $host:$port")

            while (isRunning.get()) {
                try {
                    val clientSocket: Socket = socket.accept()
                    Log.i(
                        TAG,
                        "Accepted connection from ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                    )
                    activeSockets.add(clientSocket)

                    val task = ConnectionTask(clientSocket) {
                        try {
                            handleConnection(clientSocket)
                        } finally {
                            activeSockets.remove(clientSocket)
                        }
                    }

                    connectionExecutor?.execute(task)
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error accepting connection", e)
                    }
                    // If isRunning is false the socket was intentionally
                    // closed in stop(), so the exception is expected.
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start proxy server on $host:$port", e)
            isRunning.set(false)
        }
    }

    /**
     * Delegates connection handling to [HttpProxyHandler] which parses
     * the HTTP request, forwards it upstream, and relays the response.
     */
    private fun handleConnection(clientSocket: Socket) {
        try {
            HttpProxyHandler(clientSocket).handle()
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in connection handler", e)
            try {
                clientSocket.close()
            } catch (_: IOException) {
                // ignore
            }
        }
    }

    /**
     * Runnable wrapper that preserves the [clientSocket] reference so that
     * [OverloadRejectionHandler] can safely respond with an HTTP 503 error
     * if the queue is full.
     */
    class ConnectionTask(
        val clientSocket: Socket,
        private val block: () -> Unit
    ) : Runnable {
        override fun run() {
            block()
        }
    }

    /**
     * Handles rejected tasks when the connection pool and queue are saturated.
     * Writes an explicit HTTP 503 response and closes the socket immediately.
     * Does NOT block the accept loop and does NOT silently discard the connection.
     */
    class OverloadRejectionHandler : RejectedExecutionHandler {
        override fun rejectedExecution(r: Runnable?, executor: ThreadPoolExecutor?) {
            Log.w(TAG, "Proxy overloaded — rejecting connection with 503")
            if (r is ConnectionTask) {
                val socket = r.clientSocket
                try {
                    if (!socket.isClosed) {
                        val body = "<html><body><h1>503 Service Unavailable</h1><p>Proxy connection limit reached. Please retry.</p></body></html>"
                        val response = "HTTP/1.1 503 Service Unavailable\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: ${body.length}\r\n" +
                            "Retry-After: 2\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            body
                        val out = socket.getOutputStream()
                        out.write(response.toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error sending 503 to rejected client: ${e.message}")
                } finally {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }
}
