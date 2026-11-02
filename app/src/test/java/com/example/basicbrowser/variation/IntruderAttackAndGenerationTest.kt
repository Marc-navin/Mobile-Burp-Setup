package com.example.basicbrowser.variation

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.engine.IntruderRequestGenerator
import com.example.basicbrowser.variation.engine.VariationEngine
import com.example.basicbrowser.variation.model.AttackType
import com.example.basicbrowser.variation.model.MarkerLocation
import com.example.basicbrowser.variation.model.VariationConfig
import com.example.basicbrowser.variation.model.VariationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IntruderAttackAndGenerationTest {

    // =========================================================================
    // 1. Sniper Attack Tests
    // =========================================================================

    @Test
    fun testSniper_singleInsertionPoint_generatesExactRequests() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/api?user=§admin§",
            headers = listOf("Accept: application/json")
        )
        val payloads = listOf("alice", "bob", "charlie", "david", "eve")

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads
        )

        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(1, points.size)
        assertEquals("admin", points[0].baselineValue)
        assertEquals(MarkerLocation.URL, points[0].location)

        val total = IntruderRequestGenerator.calculateTotalRequests(
            AttackType.SNIPER,
            points,
            payloads,
            emptyMap()
        )
        assertEquals(5, total)

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(5, requests.size)

        for (i in payloads.indices) {
            val genReq = requests[i]
            assertEquals(i + 1, genReq.index)
            assertEquals("http://example.com/api?user=${payloads[i]}", genReq.request.url)
            assertEquals(AttackType.SNIPER, genReq.attackType)
            assertEquals(points[0], genReq.activeInsertionPoint)
        }
    }

    @Test
    fun testSniper_multipleInsertionPoints_testsEachIndividually() {
        val baseRequest = HttpRequestData(
            method = "POST",
            url = "http://example.com/item/§42§?action=§view§",
            headers = listOf("X-Auth-Token: §secret§"),
            body = "{\"comment\": \"§hello§\"}".toByteArray(StandardCharsets.UTF_8)
        )
        val payloads = listOf("P1", "P2")

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads
        )

        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(4, points.size) // URL path, URL query, Header, Body

        // Formula: 4 positions * 2 payloads = 8 requests
        val total = IntruderRequestGenerator.calculateTotalRequests(
            AttackType.SNIPER,
            points,
            payloads,
            emptyMap()
        )
        assertEquals(8, total)

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(8, requests.size)
    }

    @Test
    fun testSniper_preservesOriginalValuesAtUntestedPositions() {
        val baseRequest = HttpRequestData(
            method = "POST",
            url = "http://example.com/api?first=§1§&second=§2§",
            headers = listOf("X-Third: §3§"),
            body = "fourth=§4§".toByteArray(StandardCharsets.UTF_8)
        )
        val payloads = listOf("PAYLOAD_X")

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads
        )

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(4, requests.size)

        // Request 1: Position 0 tested, Points 1, 2, 3 keep baseline ("2", "3", "4")
        assertEquals("http://example.com/api?first=PAYLOAD_X&second=2", requests[0].request.url)
        assertEquals("X-Third: 3", requests[0].request.headers[0])
        assertEquals("fourth=4", String(requests[0].request.body!!, StandardCharsets.UTF_8))

        // Request 2: Position 1 tested, Points 0, 2, 3 keep baseline ("1", "3", "4")
        assertEquals("http://example.com/api?first=1&second=PAYLOAD_X", requests[1].request.url)
        assertEquals("X-Third: 3", requests[1].request.headers[0])
        assertEquals("fourth=4", String(requests[1].request.body!!, StandardCharsets.UTF_8))

        // Request 3: Position 2 tested, Points 0, 1, 3 keep baseline ("1", "2", "4")
        assertEquals("http://example.com/api?first=1&second=2", requests[2].request.url)
        assertEquals("X-Third: PAYLOAD_X", requests[2].request.headers[0])
        assertEquals("fourth=4", String(requests[2].request.body!!, StandardCharsets.UTF_8))

        // Request 4: Position 3 tested, Points 0, 1, 2 keep baseline ("1", "2", "3")
        assertEquals("http://example.com/api?first=1&second=2", requests[3].request.url)
        assertEquals("X-Third: 3", requests[3].request.headers[0])
        assertEquals("fourth=PAYLOAD_X", String(requests[3].request.body!!, StandardCharsets.UTF_8))
    }

    @Test
    fun testSniper_exactRequestCountFormula() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/search?a=§1§&b=§2§&c=§3§",
            headers = emptyList()
        )
        val payloads = (1..5).map { "payload_$it" }

        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(3, points.size)

        // 3 insertion points * 5 payloads = 15 requests
        val total = IntruderRequestGenerator.calculateTotalRequests(
            AttackType.SNIPER,
            points,
            payloads,
            emptyMap()
        )
        assertEquals(15, total)

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads
        )
        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(15, requests.size)
    }

    // =========================================================================
    // 2. Cluster Bomb Attack Tests
    // =========================================================================

    @Test
    fun testClusterBomb_twoPositionsTwoPayloadsEach_generatesFourRequests() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/login?user=§admin§&role=§guest§",
            headers = emptyList()
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(2, points.size)

        val payloadMap = mapOf(
            points[0].id to listOf("user1", "user2"),
            points[1].id to listOf("roleA", "roleB")
        )

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = payloadMap
        )

        // 2 * 2 = 4 requests
        val total = IntruderRequestGenerator.calculateTotalRequests(
            AttackType.CLUSTER_BOMB,
            points,
            emptyList(),
            payloadMap
        )
        assertEquals(4, total)

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(4, requests.size)

        val expectedUrls = listOf(
            "http://example.com/login?user=user1&role=roleA",
            "http://example.com/login?user=user1&role=roleB",
            "http://example.com/login?user=user2&role=roleA",
            "http://example.com/login?user=user2&role=roleB"
        )

        for (i in expectedUrls.indices) {
            assertEquals(expectedUrls[i], requests[i].request.url)
            assertEquals(AttackType.CLUSTER_BOMB, requests[i].attackType)
        }
    }

    @Test
    fun testClusterBomb_threePositionsDifferentPayloadCounts_generatesCartesianProduct() {
        val baseRequest = HttpRequestData(
            method = "POST",
            url = "http://example.com/query?p1=§val1§",
            headers = listOf("X-P2: §val2§"),
            body = "p3=§val3§".toByteArray(StandardCharsets.UTF_8)
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(3, points.size)

        // Position 1: 5 payloads
        // Position 2: 4 payloads
        // Position 3: 3 payloads
        // Total = 5 * 4 * 3 = 60 requests
        val p1 = (1..5).map { "P1_$it" }
        val p2 = (1..4).map { "P2_$it" }
        val p3 = (1..3).map { "P3_$it" }

        val payloadMap = mapOf(
            points[0].id to p1,
            points[1].id to p2,
            points[2].id to p3
        )

        val total = IntruderRequestGenerator.calculateTotalRequests(
            AttackType.CLUSTER_BOMB,
            points,
            emptyList(),
            payloadMap
        )
        assertEquals(60, total)

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = payloadMap
        )

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(60, requests.size)
    }

    @Test
    fun testClusterBomb_noMissingAndNoDuplicateCombinations() {
        val p1 = listOf("A", "B")
        val p2 = listOf("1", "2", "3")
        val p3 = listOf("X", "Y")

        val combinations = IntruderRequestGenerator.cartesianProduct(listOf(p1, p2, p3))
        assertEquals(12, combinations.size)

        val uniqueTuples = combinations.map { it.joinToString(",") }.toSet()
        assertEquals("Must not contain duplicate tuples", 12, uniqueTuples.size)

        // Verify specific expected tuple exists
        assertTrue(combinations.contains(listOf("A", "1", "X")))
        assertTrue(combinations.contains(listOf("B", "3", "Y")))
    }

    @Test
    fun testClusterBomb_correctMarkerToPayloadMapping() {
        val baseRequest = HttpRequestData(
            method = "POST",
            url = "http://example.com/test?u=§USER§",
            headers = listOf("Authorization: Bearer §TOKEN§"),
            body = "data=§PAYLOAD§".toByteArray(StandardCharsets.UTF_8)
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        val payloadMap = mapOf(
            points[0].id to listOf("alice"),
            points[1].id to listOf("tok999"),
            points[2].id to listOf("binary_data")
        )

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = payloadMap
        )

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(1, requests.size)

        val req = requests[0].request
        assertEquals("http://example.com/test?u=alice", req.url)
        assertEquals("Authorization: Bearer tok999", req.headers[0])
        assertEquals("data=binary_data", String(req.body!!, StandardCharsets.UTF_8))
    }

    // =========================================================================
    // 3. Validation & Edge Case Tests
    // =========================================================================

    @Test
    fun testValidation_noInsertionPoints_throwsException() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/plain/url?id=123",
            headers = listOf("Accept: */*")
        )
        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = mapOf("pos_0" to listOf("a", "b"))
        )

        try {
            config.validate()
            fail("Expected IllegalArgumentException when no insertion points are marked")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No insertion points marked"))
        }
    }

    @Test
    fun testValidation_emptyPayloadList_sniperThrowsException() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/api?id=§10§",
            headers = emptyList()
        )
        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = emptyList()
        )

        try {
            config.validate()
            fail("Expected IllegalArgumentException when Sniper payload list is empty")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Payload list must not be empty"))
        }
    }

    @Test
    fun testValidation_emptyPayloadList_clusterBombThrowsException() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/api?a=§1§&b=§2§",
            headers = emptyList()
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)

        // Only position 0 has payloads, position 1 is empty
        val payloadMap = mapOf(
            points[0].id to listOf("val1", "val2")
        )

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = payloadMap
        )

        try {
            config.validate()
            fail("Expected IllegalArgumentException when a Cluster Bomb position has no payloads")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("has no payloads assigned"))
        }
    }

    @Test
    fun testValidation_moreThanFiveHundredRequests_blockedWithoutTruncation() {
        // 2 positions with 25 payloads each in Cluster Bomb -> 25 * 25 = 625 requests (> 500)
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/search?q1=§a§&q2=§b§",
            headers = emptyList()
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        val p = (1..25).map { "item$it" }
        val payloadMap = mapOf(
            points[0].id to p,
            points[1].id to p
        )

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = payloadMap
        )

        val total = IntruderRequestGenerator.calculateTotalRequests(
            AttackType.CLUSTER_BOMB,
            points,
            emptyList(),
            payloadMap
        )
        assertEquals(625, total)

        try {
            config.validate()
            fail("Expected validation failure when request count exceeds 500")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds maximum safety limit of 500"))
        }

        try {
            IntruderRequestGenerator.generateRequests(config)
            fail("Expected generateRequests to block execution when exceeding 500")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds maximum safety limit of 500"))
        }
    }

    @Test
    fun testValidation_malformedAndUnclosedMarkers_handledSafely() {
        // One properly closed marker and one unclosed marker
        val textWithUnclosed = "http://example.com/api?valid=§closed§&broken=§unclosed"
        val pairs = IntruderRequestGenerator.countMarkerPairs(textWithUnclosed)
        assertEquals(1, pairs)

        val baseRequest = HttpRequestData(method = "GET", url = textWithUnclosed, headers = emptyList())
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(1, points.size)
        assertEquals("closed", points[0].baselineValue)

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = listOf("SUBSTITUTE")
        )
        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(1, requests.size)
        // The closed marker is replaced, the unclosed marker remains literal text
        assertEquals("http://example.com/api?valid=SUBSTITUTE&broken=§unclosed", requests[0].request.url)
    }

    @Test
    fun testValidation_specialCharactersAndPayloadWithMarkerChar() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://example.com/test?q=§param§",
            headers = emptyList()
        )
        // Payload itself contains the § character and quote/ampersand
        val maliciousPayload = "val' OR '§'='§&foo=bar"

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = listOf(maliciousPayload)
        )

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(1, requests.size)
        // Must be substituted cleanly without causing infinite marker scanning
        assertEquals("http://example.com/test?q=$maliciousPayload", requests[0].request.url)
    }

    @Test
    fun testValidation_markersInUrlHeadersAndBodySimultaneously() {
        val baseRequest = HttpRequestData(
            method = "POST",
            url = "http://example.com/api/v1/§user§?sort=§asc§",
            headers = listOf("Authorization: §token§", "X-Custom: §headerVal§"),
            body = "{\"key1\": \"§v1§\", \"key2\": §v2§}".toByteArray(StandardCharsets.UTF_8)
        )

        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(6, points.size)
        assertEquals(MarkerLocation.URL, points[0].location)
        assertEquals(MarkerLocation.URL, points[1].location)
        assertEquals(MarkerLocation.HEADER, points[2].location)
        assertEquals(MarkerLocation.HEADER, points[3].location)
        assertEquals(MarkerLocation.BODY, points[4].location)
        assertEquals(MarkerLocation.BODY, points[5].location)

        // Sniper with 1 payload: 6 requests
        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = listOf("TEST")
        )
        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(6, requests.size)

        // Check Position 0 substituted, others keep baseline
        assertEquals("http://example.com/api/v1/TEST?sort=asc", requests[0].request.url)
        assertEquals("Authorization: token", requests[0].request.headers[0])
        assertEquals("X-Custom: headerVal", requests[0].request.headers[1])
        assertEquals("{\"key1\": \"v1\", \"key2\": v2}", String(requests[0].request.body!!, StandardCharsets.UTF_8))

        // Check Position 5 (Body second param) substituted, others keep baseline
        assertEquals("http://example.com/api/v1/user?sort=asc", requests[5].request.url)
        assertEquals("{\"key1\": \"v1\", \"key2\": TEST}", String(requests[5].request.body!!, StandardCharsets.UTF_8))
    }

    // =========================================================================
    // 4. Execution Engine Wire Test (Sniper & Cluster Bomb)
    // =========================================================================

    @Test
    fun testExecutionEngine_sendsExactGeneratedRequestSet_sniper() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedRequests = Collections.synchronizedList(ArrayList<String>())
        val count = 4 // 2 positions * 2 payloads = 4 requests

        val serverThread = Thread {
            for (i in 0 until count) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val reqLine = reader.readLine() ?: ""
                receivedRequests.add(reqLine)
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

        val engine = VariationEngine()
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/api?pos1=§A§&pos2=§B§",
            headers = emptyList()
        )
        val payloads = listOf("x", "y")

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads,
            delayMs = 0L
        )
        val target = config.validate()

        val results = mutableListOf<VariationResult>()
        val latch = CountDownLatch(1)

        engine.runJob(
            config = config,
            target = target,
            onProgress = { res, _, _, _ -> results.add(res) },
            onFinished = { _, _, _, _ -> latch.countDown() }
        )

        assertTrue("Job must complete", latch.await(5, TimeUnit.SECONDS))
        assertEquals(4, results.size)
        assertEquals(4, receivedRequests.size)

        // Request 1: Pos 1 tested with "x", Pos 2 keeps "B"
        assertTrue(receivedRequests[0].contains("GET /api?pos1=x&pos2=B HTTP/1.1"))
        // Request 2: Pos 1 tested with "y", Pos 2 keeps "B"
        assertTrue(receivedRequests[1].contains("GET /api?pos1=y&pos2=B HTTP/1.1"))
        // Request 3: Pos 2 tested with "x", Pos 1 keeps "A"
        assertTrue(receivedRequests[2].contains("GET /api?pos1=A&pos2=x HTTP/1.1"))
        // Request 4: Pos 2 tested with "y", Pos 1 keeps "A"
        assertTrue(receivedRequests[3].contains("GET /api?pos1=A&pos2=y HTTP/1.1"))
    }

    @Test
    fun testExecutionEngine_sendsExactGeneratedRequestSet_clusterBomb() {
        val server = ServerSocket(0)
        val port = server.localPort
        val receivedRequests = Collections.synchronizedList(ArrayList<String>())
        val count = 4 // 2 positions * 2 payloads = 4 requests (Cartesian product)

        val serverThread = Thread {
            for (i in 0 until count) {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val reqLine = reader.readLine() ?: ""
                receivedRequests.add(reqLine)
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

        val engine = VariationEngine()
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:$port/cb?u=§admin§&p=§pass§",
            headers = emptyList()
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        val payloadMap = mapOf(
            points[0].id to listOf("user1", "user2"),
            points[1].id to listOf("pwdA", "pwdB")
        )

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = payloadMap,
            delayMs = 0L
        )
        val target = config.validate()

        val results = mutableListOf<VariationResult>()
        val latch = CountDownLatch(1)

        engine.runJob(
            config = config,
            target = target,
            onProgress = { res, _, _, _ -> results.add(res) },
            onFinished = { _, _, _, _ -> latch.countDown() }
        )

        assertTrue("Job must complete", latch.await(5, TimeUnit.SECONDS))
        assertEquals(4, results.size)
        assertEquals(4, receivedRequests.size)

        // Cartesian combinations
        assertTrue(receivedRequests[0].contains("GET /cb?u=user1&p=pwdA HTTP/1.1"))
        assertTrue(receivedRequests[1].contains("GET /cb?u=user1&p=pwdB HTTP/1.1"))
        assertTrue(receivedRequests[2].contains("GET /cb?u=user2&p=pwdA HTTP/1.1"))
        assertTrue(receivedRequests[3].contains("GET /cb?u=user2&p=pwdB HTTP/1.1"))
    }
}

