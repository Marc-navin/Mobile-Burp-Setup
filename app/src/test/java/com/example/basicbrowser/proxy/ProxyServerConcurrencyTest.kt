package com.example.basicbrowser.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ProxyServerConcurrencyTest {

    @Test
    fun testOverloadRejectionSends503AndClosesSocket() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort

        val client = Socket("127.0.0.1", port)
        val accepted = server.accept()

        val rejectionHandler = ProxyServer.OverloadRejectionHandler()
        val task = ProxyServer.ConnectionTask(accepted) {
            // task logic
        }

        val executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1)
        )

        rejectionHandler.rejectedExecution(task, executor)

        // Read response received by client
        val clientIn = client.getInputStream()
        val buffer = ByteArray(1024)
        val read = clientIn.read(buffer)
        assertTrue(read > 0)
        val response = String(buffer, 0, read)

        assertTrue(response.contains("503 Service Unavailable"))
        assertTrue(response.contains("Proxy connection limit reached"))

        // Accepted socket should now be closed by the rejection handler
        assertTrue(accepted.isClosed)

        client.close()
        server.close()
        executor.shutdown()
    }

    @Test
    fun testLifecycleStartAndStop() {
        val proxy = ProxyServer.getInstance("127.0.0.1", 8089)
        proxy.start(coreWorkers = 2, maxWorkers = 4, queueCapacity = 8)
        assertTrue(proxy.isRunning())

        proxy.stop()
        assertTrue(!proxy.isRunning())
        assertEquals(0, proxy.getActiveConnectionCount())
    }
}
