package com.example.basicbrowser.variation

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.data.VariationRepository
import com.example.basicbrowser.variation.engine.VariationEngine
import com.example.basicbrowser.variation.model.TargetSummary
import com.example.basicbrowser.variation.model.VariationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class VariationEngineTest {

    private val engine = VariationEngine()

    @Test
    fun testSequentialExecution_transmitsSubstitutedPayloadsInOrder() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedQueries = Collections.synchronizedList(ArrayList<String>())
        val count = 3

        val serverThread = Thread {
            for (i in 0 until count) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val reqLine = reader.readLine() ?: ""
                receivedQueries.add(reqLine)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                writer.flush()
                client.close()
            }
            server.close()
        }
        serverThread.start()

        val payloads = listOf("alice", "bob", "charlie")
        val config = VariationConfig(
            baseRequest = HttpRequestData(
                method = "GET",
                url = "http://127.0.0.1:$port/user?name=§placeholder§",
                headers = emptyList()
            ),
            payloads = payloads,
            delayMs = 0L,
            maxRequests = 10
        )
        val target = config.validate()

        val results = mutableListOf<com.example.basicbrowser.variation.model.VariationResult>()
        val latch = CountDownLatch(1)

        engine.runJob(
            config = config,
            target = target,
            onProgress = { result, _, _, _ -> results.add(result) },
            onFinished = { _, _, _, _ -> latch.countDown() }
        )

        assertTrue("Execution should finish", latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, results.size)
        assertEquals(3, receivedQueries.size)
        assertTrue(receivedQueries[0].contains("/user?name=alice"))
        assertTrue(receivedQueries[1].contains("/user?name=bob"))
        assertTrue(receivedQueries[2].contains("/user?name=charlie"))

        for (res in results) {
            assertEquals(200, res.statusCode)
            assertEquals(2L, res.responseSizeBytes)
            assertNull(res.errorMessage)
        }
    }

    @Test
    fun testDelayEnforcement_pacesSequentialRequests() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            for (i in 0 until 3) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                writer.flush()
                client.close()
            }
            server.close()
        }
        serverThread.start()

        val delayMs = 100L
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/test", headers = emptyList()),
            payloads = listOf("1", "2", "3"),
            delayMs = delayMs,
            maxRequests = 3
        )
        val target = config.validate()

        val start = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        engine.runJob(config, target, { _, _, _, _ -> }, { _, _, _, _ -> latch.countDown() })

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val totalElapsed = System.currentTimeMillis() - start
        // Expect at least 2 * 100ms = 200ms of delay
        assertTrue("Total elapsed ($totalElapsed ms) should reflect pacing delay", totalElapsed >= 180)
    }

    @Test
    fun testFiniteExecution_respectsMaxRequestsCap() {
        val server = ServerSocket(0)
        val port = server.localPort
        val count = 2

        val serverThread = Thread {
            for (i in 0 until count) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                writer.flush()
                client.close()
            }
            server.close()
        }
        serverThread.start()

        // 5 payloads but maxRequests is 2
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/item", headers = emptyList()),
            payloads = listOf("a", "b", "c", "d", "e"),
            delayMs = 0L,
            maxRequests = 2
        )
        val target = config.validate()
        assertEquals(2, target.plannedRequestCount)

        val results = mutableListOf<com.example.basicbrowser.variation.model.VariationResult>()
        val latch = CountDownLatch(1)
        engine.runJob(config, target, { r, _, _, _ -> results.add(r) }, { _, _, _, _ -> latch.countDown() })

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(2, results.size)
    }

    @Test
    fun testStop_abortsExecutionImmediately() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverConnected = CountDownLatch(1)

        val serverThread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            serverConnected.countDown()
            // Hang without sending response until stopped
            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {}
            client.close()
            server.close()
        }
        serverThread.start()

        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/slow", headers = emptyList()),
            payloads = (1..10).map { it.toString() },
            delayMs = 100L,
            maxRequests = 10
        )
        val target = config.validate()

        val latch = CountDownLatch(1)
        var wasStopped = false

        val runner = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { _, _, _, _ -> },
                onFinished = { _, _, _, stopped ->
                    wasStopped = stopped
                    latch.countDown()
                }
            )
        }
        runner.start()

        // Wait until client has connected to the server, then call stop
        assertTrue("Server should receive connection", serverConnected.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        engine.stop()

        assertTrue("Job should finish promptly after stop", latch.await(3, TimeUnit.SECONDS))
        assertTrue(wasStopped)
    }

    @Test
    fun testPauseAndResume() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            for (i in 0 until 3) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                writer.flush()
                client.close()
            }
            server.close()
        }
        serverThread.start()

        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/p", headers = emptyList()),
            payloads = listOf("1", "2", "3"),
            delayMs = 50L,
            maxRequests = 3
        )
        val target = config.validate()

        val results = mutableListOf<com.example.basicbrowser.variation.model.VariationResult>()
        val latch = CountDownLatch(1)

        val runner = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { r, comp, _, _ ->
                    results.add(r)
                    if (comp == 1) {
                        engine.pause()
                    }
                },
                onFinished = { _, _, _, _ -> latch.countDown() }
            )
        }
        runner.start()

        // Give time for first request to complete and pause to engage
        Thread.sleep(150)
        assertEquals("Should be paused after 1 request", 1, results.size)

        // Resume execution
        engine.resume()
        assertTrue("Should complete after resume", latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, results.size)
    }

    @Test
    fun testDrainAndCountLargeBody_withoutRetainingInMemory() {
        val server = ServerSocket(0)
        val port = server.localPort
        val bodySize = 2 * 1024 * 1024 // 2 MB body

        val serverThread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: $bodySize\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            val chunk = ByteArray(8192) { 'X'.code.toByte() }
            var written = 0
            while (written < bodySize) {
                val toWrite = minOf(chunk.size, bodySize - written)
                out.write(chunk, 0, toWrite)
                written += toWrite
            }
            out.flush()
            client.close()
            server.close()
        }
        serverThread.start()

        val target = TargetSummary(
            scheme = "http",
            host = "127.0.0.1",
            port = port,
            path = "/large",
            plannedRequestCount = 1,
            delayMs = 0L
        )

        val result = engine.executeSingleRequest(
            index = 1,
            payload = "test",
            request = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/large", headers = emptyList()),
            target = target
        )

        assertEquals(200, result.statusCode)
        assertEquals(bodySize.toLong(), result.responseSizeBytes)
        assertNull(result.errorMessage)
    }

    @Test
    fun testDrainChunkedResponseBody() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            // Chunk 1: 5 bytes
            out.write("5\r\nHello\r\n".toByteArray(StandardCharsets.US_ASCII))
            // Chunk 2: 6 bytes
            out.write("6\r\n World\r\n".toByteArray(StandardCharsets.US_ASCII))
            // End chunk
            out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            client.close()
            server.close()
        }
        serverThread.start()

        val target = TargetSummary("http", "127.0.0.1", port, "/chunked", 1, 0L)
        val result = engine.executeSingleRequest(
            index = 1,
            payload = "chunk_test",
            request = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/chunked", headers = emptyList()),
            target = target
        )

        assertEquals(200, result.statusCode)
        assertEquals(11L, result.responseSizeBytes)
    }

    @Test
    fun testRepositoryResultEviction_capsAtMaxStoredResults() {
        val repo = VariationRepository(engine = engine, maxStoredResults = 3)
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = Thread {
            for (i in 0 until 5) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                writer.flush()
                client.close()
            }
            server.close()
        }
        serverThread.start()

        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/cap", headers = emptyList()),
            payloads = listOf("1", "2", "3", "4", "5"),
            delayMs = 0L,
            maxRequests = 5
        )
        repo.setBaseRequest(config.baseRequest)
        repo.requestAuthorization(config)
        repo.startConfirmedJob()

        // Wait until all 5 complete
        val startWait = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWait < 5000) {
            val state = repo.jobState.value
            if (state is com.example.basicbrowser.variation.model.VariationJobState.Completed) break
            Thread.sleep(50)
        }

        val results = repo.results.value
        assertEquals("Should be capped at maxStoredResults of 3", 3, results.size)
        // Check that older items 1 and 2 were evicted, keeping 3, 4, 5
        assertEquals("3", results[0].payload)
        assertEquals("4", results[1].payload)
        assertEquals("5", results[2].payload)
    }

    @Test
    fun testExecuteSingleRequest_rejectsUntrustedSelfSignedTlsCertificate() {
        // Generate untrusted self-signed certificate for 127.0.0.1
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        val now = Date(System.currentTimeMillis() - 60_000)
        val notAfter = Date(System.currentTimeMillis() + 3600_000)
        val subject = X500Name("CN=127.0.0.1")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(1),
            now,
            notAfter,
            subject,
            keyPair.public
        )
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        val serverKs = KeyStore.getInstance("PKCS12")
        serverKs.load(null, null)
        serverKs.setKeyEntry("key", keyPair.private, "pass".toCharArray(), arrayOf<Certificate>(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(serverKs, "pass".toCharArray())
        val serverCtx = SSLContext.getInstance("TLS")
        serverCtx.init(kmf.keyManagers, null, SecureRandom())

        val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverPort = serverSocket.localPort

        val serverThread = Thread {
            try {
                val client = serverSocket.accept()
                client.getInputStream().read()
                client.close()
            } catch (_: Exception) {}
            serverSocket.close()
        }
        serverThread.start()

        val target = TargetSummary(
            scheme = "https",
            host = "127.0.0.1",
            port = serverPort,
            path = "/secure-test",
            plannedRequestCount = 1,
            delayMs = 0L
        )

        val result = engine.executeSingleRequest(
            index = 1,
            payload = "tls_test",
            request = HttpRequestData(method = "GET", url = "https://127.0.0.1:$serverPort/secure-test", headers = emptyList()),
            target = target
        )

        assertNull("Status code must be null when TLS validation fails", result.statusCode)
        assertNotNull("Error message must be present when TLS fails", result.errorMessage)
        val err = result.errorMessage!!.lowercase()
        assertTrue("Error should indicate TLS/certificate rejection: ${result.errorMessage}",
            err.contains("cert") || err.contains("ssl") || err.contains("handshake") || err.contains("trust") || err.contains("path")
        )
    }

    @Test
    fun testStop_duringInterRequestDelay_abortsPromptly() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            writer.flush()
            client.close()
            server.close()
        }
        serverThread.start()

        // Configure high delay (4000 ms) so that if stop() didn't interrupt sleep, it would take 4 seconds
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/delay", headers = emptyList()),
            payloads = listOf("1", "2"),
            delayMs = 4000L,
            maxRequests = 2
        )
        val target = config.validate()

        val latch = CountDownLatch(1)
        var wasStopped = false
        val firstRequestCompleted = CountDownLatch(1)

        val runner = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { _, completed, _, _ ->
                    if (completed == 1) {
                        firstRequestCompleted.countDown()
                    }
                },
                onFinished = { _, _, _, stopped ->
                    wasStopped = stopped
                    latch.countDown()
                }
            )
        }
        runner.start()

        assertTrue("First request should complete", firstRequestCompleted.await(5, TimeUnit.SECONDS))
        val stopTriggerTime = System.currentTimeMillis()
        engine.stop()

        assertTrue("Job should stop promptly during inter-request delay without waiting 4s", latch.await(1, TimeUnit.SECONDS))
        val stopDuration = System.currentTimeMillis() - stopTriggerTime
        assertTrue("Stop duration should be < 1000ms, was $stopDuration ms", stopDuration < 1000)
        assertTrue(wasStopped)
    }

    @Test
    fun testStop_whilePaused_terminatesCleanly() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            writer.flush()
            client.close()
            server.close()
        }
        serverThread.start()

        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/pause_stop", headers = emptyList()),
            payloads = listOf("1", "2", "3"),
            delayMs = 50L,
            maxRequests = 3
        )
        val target = config.validate()

        val latch = CountDownLatch(1)
        var wasStopped = false
        val pausedLatch = CountDownLatch(1)

        val runner = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { _, completed, _, _ ->
                    if (completed == 1) {
                        engine.pause()
                        pausedLatch.countDown()
                    }
                },
                onFinished = { _, _, _, stopped ->
                    wasStopped = stopped
                    latch.countDown()
                }
            )
        }
        runner.start()

        assertTrue(pausedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        // Call stop while engine is paused
        engine.stop()

        assertTrue("Job should terminate promptly when stopped while paused", latch.await(2, TimeUnit.SECONDS))
        assertTrue(wasStopped)
    }

    @Test
    fun testExecuteSingleRequest_doesNotFollowRedirects() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 302 Found\r\nLocation: /redirected_target\r\nContent-Length: 7\r\nConnection: close\r\n\r\nRedirect")
            writer.flush()
            client.close()
            server.close()
        }
        serverThread.start()

        val target = TargetSummary("http", "127.0.0.1", port, "/login", 1, 0L)
        val result = engine.executeSingleRequest(
            index = 1,
            payload = "redir_test",
            request = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/login", headers = emptyList()),
            target = target
        )

        assertEquals("Should preserve raw 302 redirect code without following", 302, result.statusCode)
        assertEquals(7L, result.responseSizeBytes)
        assertNull(result.errorMessage)
    }

    @Test
    fun testExecuteSingleRequest_rejectsLineExceedingLimit() {
        val server = ServerSocket(0)
        val port = server.localPort

        val serverThread = Thread {
            val client = server.accept()
            val out = client.getOutputStream()
            // Send HTTP response status line exceeding MAX_LINE_LENGTH (16KB)
            out.write("HTTP/1.1 200 ".toByteArray(StandardCharsets.US_ASCII))
            val hugeLine = ByteArray(20 * 1024) { 'A'.code.toByte() }
            out.write(hugeLine)
            out.write("\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            client.close()
            server.close()
        }
        serverThread.start()

        val target = TargetSummary("http", "127.0.0.1", port, "/huge_line", 1, 0L)
        val result = engine.executeSingleRequest(
            index = 1,
            payload = "huge",
            request = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/huge_line", headers = emptyList()),
            target = target
        )

        assertNull("Status code should be null when line length limit is breached", result.statusCode)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("exceeded maximum limit", ignoreCase = true))
    }

    @Test
    fun testExecuteSingleRequest_rejectsHostnameMismatchTlsCertificate() {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        val now = Date(System.currentTimeMillis() - 60_000)
        val notAfter = Date(System.currentTimeMillis() + 3600_000)
        val subject = X500Name("CN=strictly-different-host.com")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(1),
            now,
            notAfter,
            subject,
            keyPair.public
        )
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        val serverKs = KeyStore.getInstance("PKCS12")
        serverKs.load(null, null)
        serverKs.setKeyEntry("key", keyPair.private, "pass".toCharArray(), arrayOf<Certificate>(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(serverKs, "pass".toCharArray())
        val serverCtx = SSLContext.getInstance("TLS")
        serverCtx.init(kmf.keyManagers, null, SecureRandom())

        val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverPort = serverSocket.localPort

        val serverThread = Thread {
            try {
                val client = serverSocket.accept()
                client.getInputStream().read()
                client.close()
            } catch (_: Exception) {}
            serverSocket.close()
        }
        serverThread.start()

        // Connecting to 127.0.0.1, but cert is only valid for 'strictly-different-host.com'
        val target = TargetSummary(
            scheme = "https",
            host = "127.0.0.1",
            port = serverPort,
            path = "/mismatch",
            plannedRequestCount = 1,
            delayMs = 0L
        )

        val result = engine.executeSingleRequest(
            index = 1,
            payload = "test_mismatch",
            request = HttpRequestData(method = "GET", url = "https://127.0.0.1:$serverPort/mismatch", headers = emptyList()),
            target = target
        )

        assertNull("Status code must be null when TLS hostname check fails", result.statusCode)
        assertNotNull("Error message must indicate failure", result.errorMessage)
    }

    @Test
    fun testExecuteSingleRequest_enforcesFiftyMegabyteLimit_andMarksTruncated() {
        val server = ServerSocket(0)
        val port = server.localPort
        val declaredSize = VariationEngine.MAX_STREAM_READ_BYTES + 1024 * 1024 // 51 MB

        val serverThread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: $declaredSize\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            val chunk = ByteArray(64 * 1024) { 'Z'.code.toByte() }
            var written = 0L
            try {
                while (written < declaredSize) {
                    val toWrite = minOf(chunk.size.toLong(), declaredSize - written).toInt()
                    out.write(chunk, 0, toWrite)
                    written += toWrite
                }
                out.flush()
            } catch (_: Exception) {}
            client.close()
            server.close()
        }
        serverThread.start()

        val target = TargetSummary("http", "127.0.0.1", port, "/oversized", 1, 0L)
        val result = engine.executeSingleRequest(
            index = 1,
            payload = "oversized",
            request = HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/oversized", headers = emptyList()),
            target = target
        )

        assertEquals(200, result.statusCode)
        assertEquals("Response size must be capped at 50 MB", VariationEngine.MAX_STREAM_READ_BYTES, result.responseSizeBytes)
        assertTrue("isTruncated must be true", result.isTruncated)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("truncated", ignoreCase = true))
    }

    @Test
    fun testExactPayloadPreservation_fromConfigThroughConstructionAndResultDisplay() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedPaths = Collections.synchronizedList(ArrayList<String>())
        val receivedHeaders = Collections.synchronizedList(ArrayList<String>())
        val count = 3

        val serverThread = Thread {
            for (i in 0 until count) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val reqLine = reader.readLine() ?: ""
                val path = reqLine.split(" ").getOrNull(1) ?: ""
                receivedPaths.add(path)

                var customHeaderVal = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("X-Test-Payload:", ignoreCase = true)) {
                        customHeaderVal = line.substringAfter(":").trim()
                    }
                }
                receivedHeaders.add(customHeaderVal)

                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                writer.flush()
                client.close()
            }
            server.close()
        }
        serverThread.start()

        val expectedPayloads = listOf("alpha", "beta", "gamma")
        val config = VariationConfig(
            baseRequest = HttpRequestData(
                method = "GET",
                url = "http://127.0.0.1:$port/test?q=§param§",
                headers = listOf("X-Test-Payload: fixed-header")
            ),
            payloads = expectedPayloads,
            delayMs = 0L,
            maxRequests = 5
        )
        val target = config.validate()

        val results = mutableListOf<com.example.basicbrowser.variation.model.VariationResult>()
        val latch = CountDownLatch(1)

        engine.runJob(
            config = config,
            target = target,
            onProgress = { result, _, _, _ -> results.add(result) },
            onFinished = { _, _, _, _ -> latch.countDown() }
        )

        assertTrue("Execution should finish", latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, results.size)
        assertEquals(3, receivedPaths.size)
        assertEquals(3, receivedHeaders.size)

        for (i in expectedPayloads.indices) {
            val expected = expectedPayloads[i]

            // 1. Verify exact wire transmission
            assertEquals("/test?q=$expected", receivedPaths[i])
            assertEquals("fixed-header", receivedHeaders[i])
            assertFalse("Path must not have 'by' prefix", receivedPaths[i].contains("by$expected"))
            assertFalse("Path must not have 'yy' suffix", receivedPaths[i].contains("${expected}yy"))

            // 2. Verify exact result object payload
            val res = results[i]
            assertEquals(expected, res.payload)
            assertEquals(expected, res.displayPayload)
            assertEquals(200, res.statusCode)
            assertNull(res.errorMessage)
        }
    }
}

