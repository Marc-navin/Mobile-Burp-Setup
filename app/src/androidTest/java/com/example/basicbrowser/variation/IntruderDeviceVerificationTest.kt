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
    }

    @Test
    fun testClusterBombAttack_onDevice_cartesianProduct_independentPayloads() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedPaths = CopyOnWriteArrayList<String>()
        val serverLatch = CountDownLatch(6)

        val serverThread = Thread {
            try {
                while (!server.isClosed && receivedPaths.size < 6) {
                    val clientSocket = server.accept()
                    val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                    val requestLine = reader.readLine()
                    if (requestLine != null) {
                        val path = requestLine.split(" ").getOrNull(1) ?: ""
                        receivedPaths.add(path)
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

        val config = VariationConfig(
            attackType = AttackType.CLUSTER_BOMB,
            baseRequest = baseReq,
            clusterBombPayloads = mapOf(
                insertionPoints[0].id to listOf("banana", "orange"),
                insertionPoints[1].id to listOf("produce", "fresh", "market")
            ),
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
        assertTrue("Server should receive 6 requests", serverLatch.await(10, TimeUnit.SECONDS))
        server.close()

        assertEquals(6, results.size)
        assertEquals(6, receivedPaths.size)

        // Verify Cartesian combinations: 2 x 3 = 6
        val expected = listOf(
            "/search?term=banana&cat=produce",
            "/search?term=banana&cat=fresh",
            "/search?term=banana&cat=market",
            "/search?term=orange&cat=produce",
            "/search?term=orange&cat=fresh",
            "/search?term=orange&cat=market"
        )
        assertEquals(expected, receivedPaths.toList())
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
