package com.example.basicbrowser.variation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.data.VariationRepository
import com.example.basicbrowser.variation.engine.IntruderRequestGenerator
import com.example.basicbrowser.variation.engine.VariationEngine
import com.example.basicbrowser.variation.model.AttackType
import com.example.basicbrowser.variation.model.VariationConfig
import com.example.basicbrowser.variation.model.VariationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IntruderDeviceVerificationTest {

    @Test
    fun testSniperAttack_onDevice_generatesExpectedRequests_preservesBaseline() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedPaths = CopyOnWriteArrayList<String>()
        val serverLatch = CountDownLatch(4)

        val serverThread = Thread {
            try {
                while (!server.isClosed && receivedPaths.size < 4) {
                    val clientSocket = server.accept()
                    val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                    val requestLine = reader.readLine()
                    if (requestLine != null) {
                        val path = requestLine.split(" ").getOrNull(1) ?: ""
                        receivedPaths.add(path)
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                        }
                    }
                    val writer = OutputStreamWriter(clientSocket.getOutputStream())
                    writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    writer.flush()
                    clientSocket.close()
                    serverLatch.countDown()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.start()

        val engine = VariationEngine()
        val baseReq = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/api?user=§alice§&role=§guest§",
            headers = listOf("Host: 127.0.0.1:$port", "User-Agent: DeviceTest"),
            body = ByteArray(0),
            httpVersion = "HTTP/1.1"
        )
        val config = VariationConfig(
            attackType = AttackType.SNIPER,
            baseRequest = baseReq,
            sniperPayloads = listOf("val1", "val2"),
            delayMs = 10L
        )
        val target = config.validate()

        val results = CopyOnWriteArrayList<VariationResult>()
        val jobLatch = CountDownLatch(1)

        val worker = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { res, _, _, _ -> results.add(res) },
                onFinished = { _, _, _, _ -> jobLatch.countDown() }
            )
        }
        worker.start()

        assertTrue("Job should complete within 10 seconds", jobLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Server should receive 4 requests", serverLatch.await(10, TimeUnit.SECONDS))
        server.close()

        assertEquals(4, results.size)
        assertEquals(4, receivedPaths.size)

        // Verify Sniper Baseline Preservation:
        // Position 1 tested (user replaced, role keeps baseline 'guest')
        assertEquals("/api?user=val1&role=guest", receivedPaths[0])
        assertEquals("/api?user=val2&role=guest", receivedPaths[1])

        // Position 2 tested (role replaced, user keeps baseline 'alice')
        assertEquals("/api?user=alice&role=val1", receivedPaths[2])
        assertEquals("/api?user=alice&role=val2", receivedPaths[3])

        // Check result status codes
        results.forEach { assertEquals(200, it.statusCode) }

        // Verify positionPayloads metadata
        assertEquals("val1", results[0].positionPayloads.values.first())
        assertEquals("guest (baseline)", results[0].positionPayloads.values.last())
        assertEquals("val2", results[1].positionPayloads.values.first())
        assertEquals("guest (baseline)", results[1].positionPayloads.values.last())
        assertEquals("alice (baseline)", results[2].positionPayloads.values.first())
        assertEquals("val1", results[2].positionPayloads.values.last())
        assertEquals("alice (baseline)", results[3].positionPayloads.values.first())
        assertEquals("val2", results[3].positionPayloads.values.last())
    }

    @Test
    fun testBatteringRamAttack_onDevice_bothPositionsReceiveSamePayload() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedPaths = CopyOnWriteArrayList<String>()
        val serverLatch = CountDownLatch(2)

        val serverThread = Thread {
            try {
                while (!server.isClosed && receivedPaths.size < 2) {
                    val clientSocket = server.accept()
                    val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                    val requestLine = reader.readLine()
                    if (requestLine != null) {
                        val path = requestLine.split(" ").getOrNull(1) ?: ""
                        receivedPaths.add(path)
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                        }
                    }
                    val writer = OutputStreamWriter(clientSocket.getOutputStream())
                    writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    writer.flush()
                    clientSocket.close()
                    serverLatch.countDown()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.start()

        val engine = VariationEngine()
        val baseReq = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/api?user=§alice§&role=§guest§",
            headers = listOf("Host: 127.0.0.1:$port"),
            body = ByteArray(0),
            httpVersion = "HTTP/1.1"
        )
        val config = VariationConfig(
            attackType = AttackType.BATTERING_RAM,
            baseRequest = baseReq,
            batteringRamPayloads = listOf("val1", "val2"),
            delayMs = 10L
        )
        val target = config.validate()

        val results = CopyOnWriteArrayList<VariationResult>()
        val jobLatch = CountDownLatch(1)

        val worker = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { res, _, _, _ -> results.add(res) },
                onFinished = { _, _, _, _ -> jobLatch.countDown() }
            )
        }
        worker.start()

        assertTrue("Job should complete within 10 seconds", jobLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Server should receive 2 requests", serverLatch.await(10, TimeUnit.SECONDS))
        server.close()

        assertEquals(2, results.size)
        assertEquals(2, receivedPaths.size)

        // Verify Battering Ram: both positions receive the same payload simultaneously
        assertEquals("/api?user=val1&role=val1", receivedPaths[0])
        assertEquals("/api?user=val2&role=val2", receivedPaths[1])

        assertEquals("val1", results[0].positionPayloads.values.first())
        assertEquals("val1", results[0].positionPayloads.values.last())
        assertEquals("val2", results[1].positionPayloads.values.first())
        assertEquals("val2", results[1].positionPayloads.values.last())
    }

    @Test
    fun testPitchforkAttack_onDevice_lockstepAdvance_stopsAtShortestSet() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedPaths = CopyOnWriteArrayList<String>()
        val serverLatch = CountDownLatch(2)

        val serverThread = Thread {
            try {
                while (!server.isClosed && receivedPaths.size < 2) {
                    val clientSocket = server.accept()
                    val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                    val requestLine = reader.readLine()
                    if (requestLine != null) {
                        val path = requestLine.split(" ").getOrNull(1) ?: ""
                        receivedPaths.add(path)
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                        }
                    }
                    val writer = OutputStreamWriter(clientSocket.getOutputStream())
                    writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    writer.flush()
                    clientSocket.close()
                    serverLatch.countDown()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.start()

        val engine = VariationEngine()
        val baseReq = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/api?user=§alice§&role=§guest§",
            headers = listOf("Host: 127.0.0.1:$port"),
            body = ByteArray(0),
            httpVersion = "HTTP/1.1"
        )
        val insertionPoints = IntruderRequestGenerator.parseInsertionPoints(baseReq)
        assertEquals(2, insertionPoints.size)

        // Set 1 has 3 items, Set 2 has 2 items -> expected count = min(3, 2) = 2
        val config = VariationConfig(
            attackType = AttackType.PITCHFORK,
            baseRequest = baseReq,
            pitchforkPayloads = mapOf(
                insertionPoints[0].id to listOf("u1", "u2", "u3"),
                insertionPoints[1].id to listOf("r1", "r2")
            ),
            delayMs = 10L
        )
        val target = config.validate()
        assertEquals(2, target.plannedRequestCount)

        val results = CopyOnWriteArrayList<VariationResult>()
        val jobLatch = CountDownLatch(1)

        val worker = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { res, _, _, _ -> results.add(res) },
                onFinished = { _, _, _, _ -> jobLatch.countDown() }
            )
        }
        worker.start()

        assertTrue("Job should complete within 10 seconds", jobLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Server should receive 2 requests", serverLatch.await(10, TimeUnit.SECONDS))
        server.close()

        assertEquals(2, results.size)
        assertEquals(2, receivedPaths.size)

        // Verify Lockstep advancing: stopped at shortest set (2)
        assertEquals("/api?user=u1&role=r1", receivedPaths[0])
        assertEquals("/api?user=u2&role=r2", receivedPaths[1])

        assertEquals("u1", results[0].positionPayloads.values.first())
        assertEquals("r1", results[0].positionPayloads.values.last())
        assertEquals("u2", results[1].positionPayloads.values.first())
        assertEquals("r2", results[1].positionPayloads.values.last())
    }

    @Test
    fun testClusterBombAttack_onDevice_cartesianProduct_independentPayloads() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedPaths = CopyOnWriteArrayList<String>()
        val serverLatch = CountDownLatch(4)

        val serverThread = Thread {
            try {
                while (!server.isClosed && receivedPaths.size < 4) {
                    val clientSocket = server.accept()
                    val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                    val requestLine = reader.readLine()
                    if (requestLine != null) {
                        val path = requestLine.split(" ").getOrNull(1) ?: ""
                        receivedPaths.add(path)
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                        }
                    }
                    val writer = OutputStreamWriter(clientSocket.getOutputStream())
                    writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    writer.flush()
                    clientSocket.close()
                    serverLatch.countDown()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.start()

        val engine = VariationEngine()
        val baseReq = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/search?term=§apple§&cat=§fruits§",
            headers = listOf("Host: 127.0.0.1:$port"),
            body = ByteArray(0),
            httpVersion = "HTTP/1.1"
        )
        val insertionPoints = IntruderRequestGenerator.parseInsertionPoints(baseReq)
        assertEquals(2, insertionPoints.size)

        // 2 positions, 2 values in each set -> expected request count = 4
        val config = VariationConfig(
            attackType = AttackType.CLUSTER_BOMB,
            baseRequest = baseReq,
            clusterBombPayloads = mapOf(
                insertionPoints[0].id to listOf("v1", "v2"),
                insertionPoints[1].id to listOf("w1", "w2")
            ),
            delayMs = 10L
        )
        val target = config.validate()
        assertEquals(4, target.plannedRequestCount)

        val results = CopyOnWriteArrayList<VariationResult>()
        val jobLatch = CountDownLatch(1)

        val worker = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { res, _, _, _ -> results.add(res) },
                onFinished = { _, _, _, _ -> jobLatch.countDown() }
            )
        }
        worker.start()

        assertTrue("Job should complete within 10 seconds", jobLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Server should receive 4 requests", serverLatch.await(10, TimeUnit.SECONDS))
        server.close()

        assertEquals(4, results.size)
        assertEquals(4, receivedPaths.size)

        // Verify Cartesian combinations: 2 x 2 = 4
        val expected = listOf(
            "/search?term=v1&cat=w1",
            "/search?term=v1&cat=w2",
            "/search?term=v2&cat=w1",
            "/search?term=v2&cat=w2"
        )
        assertEquals(expected, receivedPaths.toList())
    }

    @Test
    fun testExecutionLifecycle_pauseResumeStop_onDevice() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedCount = java.util.concurrent.atomic.AtomicInteger(0)

        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val clientSocket = server.accept()
                    receivedCount.incrementAndGet()
                    val writer = OutputStreamWriter(clientSocket.getOutputStream())
                    writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    writer.flush()
                    clientSocket.close()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.start()

        val engine = VariationEngine()
        val baseReq = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/api?item=§val§",
            headers = listOf("Host: 127.0.0.1:$port"),
            body = ByteArray(0),
            httpVersion = "HTTP/1.1"
        )
        // 10 payloads with 150ms delay between requests
        val config = VariationConfig(
            attackType = AttackType.SNIPER,
            baseRequest = baseReq,
            sniperPayloads = (1..10).map { "item$it" },
            delayMs = 150L
        )
        val target = config.validate()
        val results = CopyOnWriteArrayList<VariationResult>()
        val finishedLatch = CountDownLatch(1)

        val worker = Thread {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { res, _, _, _ -> results.add(res) },
                onFinished = { _, _, _, _ -> finishedLatch.countDown() }
            )
        }
        worker.start()

        // Allow 2 requests to complete
        Thread.sleep(250)
        assertTrue(results.size in 1..4)

        // 1. Pause
        engine.pause()
        val countAtPause = results.size
        Thread.sleep(300)
        // Verify no additional requests are made while paused
        assertEquals(countAtPause, results.size)

        // 2. Resume
        engine.resume()
        Thread.sleep(300)
        // Verify requests continued after resume
        assertTrue(results.size > countAtPause)

        // 3. Stop
        engine.stop()
        finishedLatch.await(5, TimeUnit.SECONDS)
        val finalCount = results.size
        assertTrue(finalCount < 10) // Must have stopped before finishing all 10

        Thread.sleep(300)
        // Verify strictly no further requests sent after stop
        assertEquals(finalCount, results.size)
        server.close()
    }

    @Test
    fun testSafety_unresolvedMarkersBlocked_onDevice() {
        val engine = VariationEngine()
        val dummyTarget = com.example.basicbrowser.variation.model.TargetSummary(
            scheme = "http",
            host = "127.0.0.1",
            port = 8080,
            path = "/api",
            plannedRequestCount = 1,
            delayMs = 0L
        )
        val reqWithMarker = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:8080/api?q=§unresolved§",
            headers = emptyList()
        )

        val result = engine.executeSingleRequest(
            index = 1,
            payload = "test",
            request = reqWithMarker,
            target = dummyTarget
        )

        assertEquals("Error: Unresolved payload marker (§) in request", result.errorMessage)
        assertEquals(null, result.statusCode)
    }

    @Test
    fun testSafetyCapEnforcement_onDevice_over500Requests_blocksExecution() {
        val baseReq = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:8080/test?p1=§a§&p2=§b§",
            headers = listOf("Host: 127.0.0.1:8080"),
            body = ByteArray(0),
            httpVersion = "HTTP/1.1"
        )
        val insertionPoints = IntruderRequestGenerator.parseInsertionPoints(baseReq)
        // 25 x 25 = 625 requests
        val payloads25 = (1..25).map { "item$it" }
        val clusterBombMap = mapOf(
            insertionPoints[0].id to payloads25,
            insertionPoints[1].id to payloads25
        )

        val calculatedCount = IntruderRequestGenerator.calculateTotalRequests(
            attackType = AttackType.CLUSTER_BOMB,
            insertionPoints = insertionPoints,
            sniperPayloads = emptyList(),
            clusterBombPayloads = clusterBombMap
        )
        assertEquals(625, calculatedCount)
        assertTrue(calculatedCount > IntruderRequestGenerator.HARD_CAP_MAX_REQUESTS)

        val config = VariationConfig(
            attackType = AttackType.CLUSTER_BOMB,
            baseRequest = baseReq,
            clusterBombPayloads = clusterBombMap
        )

        try {
            config.validate()
            fail("Config validation must throw IllegalArgumentException when count exceeds 500")
        } catch (e: IllegalArgumentException) {
            assertTrue("Error message must mention safety limit", e.message?.contains("500") == true)
        }

        try {
            IntruderRequestGenerator.generateRequests(config)
            fail("IntruderRequestGenerator must throw IllegalArgumentException when >500")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("500") == true)
        }
    }

    @Test
    fun testAttackTypePersistence_onDevice_variationRepository() {
        val repository = VariationRepository()
        assertEquals(AttackType.SNIPER, repository.selectedAttackType.value)

        repository.setAttackType(AttackType.CLUSTER_BOMB)
        assertEquals(AttackType.CLUSTER_BOMB, repository.selectedAttackType.value)

        repository.setAttackType(AttackType.SNIPER)
        assertEquals(AttackType.SNIPER, repository.selectedAttackType.value)
    }
}
