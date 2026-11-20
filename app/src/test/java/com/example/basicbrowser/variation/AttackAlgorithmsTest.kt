package com.example.basicbrowser.variation

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.engine.IntruderRequestGenerator
import com.example.basicbrowser.variation.engine.RawHttpConverter
import com.example.basicbrowser.variation.model.AttackType
import com.example.basicbrowser.variation.model.VariationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class AttackAlgorithmsTest {

    // =========================================================================
    // 1. Battering Ram Algorithm Tests
    // =========================================================================

    @Test
    fun testBatteringRam_allPositionsReplacedSimultaneouslyWithSamePayload() {
        val rawTemplate = "POST /api/user/§id§ HTTP/1.1\r\nHost: example.com\r\nX-Token: §token§\r\n\r\n{\"key\":\"§bodyKey§\"}"
        val parsed = RawHttpConverter.parseRawHttp(rawTemplate, defaultScheme = "https", defaultPort = 443)
        val baseRequest = RawHttpConverter.toHttpRequestData(parsed)
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)

        assertEquals(3, points.size) // URL, Header, Body

        val payloads = listOf("FUZZ1", "FUZZ2", "FUZZ3")
        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.BATTERING_RAM,
            batteringRamPayloads = payloads,
            rawTemplate = rawTemplate
        )

        val total = IntruderRequestGenerator.calculateTotalRequests(
            attackType = AttackType.BATTERING_RAM,
            insertionPoints = points,
            sniperPayloads = emptyList(),
            clusterBombPayloads = emptyMap(),
            batteringRamPayloads = payloads
        )
        // Battering Ram total equals payload set size
        assertEquals(3, total)

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(3, requests.size)

        for (i in payloads.indices) {
            val req = requests[i]
            val p = payloads[i]
            assertEquals(i + 1, req.index)
            assertEquals(AttackType.BATTERING_RAM, req.attackType)
            assertEquals(p, req.payloadSummary)

            // Verify all positions have the SAME payload
            assertTrue(req.request.url.contains("/api/user/$p"))
            assertTrue(req.request.headers.contains("X-Token: $p"))
            assertEquals("{\"key\":\"$p\"}", String(req.request.body!!, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun testBatteringRam_emptyPayloads_returnsZeroAndFailsValidation() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "https://example.com/api?q=§val§",
            headers = emptyList()
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)

        val total = IntruderRequestGenerator.calculateTotalRequests(
            attackType = AttackType.BATTERING_RAM,
            insertionPoints = points,
            sniperPayloads = emptyList(),
            clusterBombPayloads = emptyMap(),
            batteringRamPayloads = emptyList()
        )
        assertEquals(0, total)

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.BATTERING_RAM,
            batteringRamPayloads = emptyList()
        )
        try {
            config.validate()
            fail("Expected validation failure for empty payloads in Battering Ram")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Payload list must not be empty"))
        }
    }

    // =========================================================================
    // 2. Pitchfork Algorithm Tests
    // =========================================================================

    @Test
    fun testPitchfork_equalLengthSets_advancesInLockstep() {
        val rawTemplate = "POST /login HTTP/1.1\r\nHost: example.com\r\n\r\nuser=§user§&pass=§pass§&role=§role§"
        val parsed = RawHttpConverter.parseRawHttp(rawTemplate, defaultScheme = "https", defaultPort = 443)
        val baseRequest = RawHttpConverter.toHttpRequestData(parsed)
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)

        assertEquals(3, points.size)

        val users = listOf("admin", "alice", "bob")
        val passes = listOf("p@ss1", "p@ss2", "p@ss3")
        val roles = listOf("superuser", "user", "guest")

        val payloadMap = mapOf(
            points[0].id to users,
            points[1].id to passes,
            points[2].id to roles
        )

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.PITCHFORK,
            pitchforkPayloads = payloadMap,
            rawTemplate = rawTemplate
        )

        val total = IntruderRequestGenerator.calculateTotalRequests(
            attackType = AttackType.PITCHFORK,
            insertionPoints = points,
            sniperPayloads = emptyList(),
            clusterBombPayloads = emptyMap(),
            pitchforkPayloads = payloadMap
        )
        assertEquals(3, total)

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(3, requests.size)

        for (i in 0 until 3) {
            val req = requests[i]
            assertEquals(i + 1, req.index)
            assertEquals(AttackType.PITCHFORK, req.attackType)

            val body = String(req.request.body!!, StandardCharsets.UTF_8)
            assertEquals("user=${users[i]}&pass=${passes[i]}&role=${roles[i]}", body)
        }
    }

    @Test
    fun testPitchfork_differentLengthSets_stopsAtShortestSet() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "https://example.com/search?a=§1§&b=§2§&c=§3§",
            headers = emptyList()
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        assertEquals(3, points.size)

        // Set 1 has 5 items, Set 2 has 2 items, Set 3 has 4 items
        val set1 = listOf("a1", "a2", "a3", "a4", "a5")
        val set2 = listOf("b1", "b2") // Shortest: 2
        val set3 = listOf("c1", "c2", "c3", "c4")

        val payloadMap = mapOf(
            points[0].id to set1,
            points[1].id to set2,
            points[2].id to set3
        )

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.PITCHFORK,
            pitchforkPayloads = payloadMap
        )

        val total = IntruderRequestGenerator.calculateTotalRequests(
            attackType = AttackType.PITCHFORK,
            insertionPoints = points,
            sniperPayloads = emptyList(),
            clusterBombPayloads = emptyMap(),
            pitchforkPayloads = payloadMap
        )
        // Must equal length of shortest set (2)
        assertEquals(2, total)

        val requests = IntruderRequestGenerator.generateRequests(config)
        assertEquals(2, requests.size)

        // Request 1: a1, b1, c1
        assertEquals("https://example.com/search?a=a1&b=b1&c=c1", requests[0].request.url)
        // Request 2: a2, b2, c2
        assertEquals("https://example.com/search?a=a2&b=b2&c=c2", requests[1].request.url)
    }

    @Test
    fun testPitchfork_emptySetInAnyPosition_returnsZeroAndFailsValidation() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "https://example.com/api?x=§1§&y=§2§",
            headers = emptyList()
        )
        val points = IntruderRequestGenerator.parseInsertionPoints(baseRequest)

        val payloadMap = mapOf(
            points[0].id to listOf("p1", "p2"),
            points[1].id to emptyList<String>() // Empty set
        )

        val total = IntruderRequestGenerator.calculateTotalRequests(
            attackType = AttackType.PITCHFORK,
            insertionPoints = points,
            sniperPayloads = emptyList(),
            clusterBombPayloads = emptyMap(),
            pitchforkPayloads = payloadMap
        )
        assertEquals(0, total)

        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.PITCHFORK,
            pitchforkPayloads = payloadMap
        )
        try {
            config.validate()
            fail("Expected validation failure when Pitchfork has an empty set")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("has no payloads assigned") || e.message!!.contains("produces 0 requests"))
        }
    }

    // =========================================================================
    // 3. Request Count Calculation & Overflow Safety
    // =========================================================================

    @Test
    fun testCalculateTotalRequests_preventsIntegerOverflow() {
        val dummyPoints = (0 until 4).map { idx ->
            com.example.basicbrowser.variation.model.InsertionPoint(
                id = "pos_$idx",
                index = idx,
                location = com.example.basicbrowser.variation.model.MarkerLocation.BODY,
                baselineValue = "val",
                description = "pos $idx"
            )
        }

        // 4 sets with 10,000 items each in Cluster Bomb -> 10,000^4 = 10^16 (> Long.MAX_VALUE or Int.MAX_VALUE)
        val largeList = (1..1000).map { "item$it" }
        val hugeMap = dummyPoints.associate { it.id to largeList }

        val total = IntruderRequestGenerator.calculateTotalRequests(
            attackType = AttackType.CLUSTER_BOMB,
            insertionPoints = dummyPoints,
            sniperPayloads = emptyList(),
            clusterBombPayloads = hugeMap
        )

        // Capped cleanly without throwing ArithmeticException
        assertEquals(Int.MAX_VALUE, total)
    }

    // =========================================================================
    // 4. Threshold & Confirmation Verification
    // =========================================================================

    @Test
    fun testSafetyThreshold_belowFiveHundred_validatesWithoutWarning() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "https://example.com/api?id=§1§",
            headers = emptyList()
        )
        val payloads = (1..100).map { "$it" }
        val config = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads
        )

        val summary = config.validate()
        assertEquals(100, summary.plannedRequestCount)
        assertFalse(summary.exceedsThreshold)
    }

    @Test
    fun testSafetyThreshold_aboveFiveHundredWithConfirmation_validatesWithWarning() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "https://example.com/api?id=§1§",
            headers = emptyList()
        )
        // 600 payloads (> 500)
        val payloads = (1..600).map { "$it" }

        // Without confirmation: blocked
        val unconfirmedConfig = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads
        )
        try {
            unconfirmedConfig.validate()
            fail("Expected validation failure when exceeding 500 without confirmation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds maximum safety limit of 500"))
        }

        // With user confirmation: accepted, flags exceedsThreshold
        val confirmedConfig = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = payloads,
            userConfirmedExceedsThreshold = true
        )
        val summary = confirmedConfig.validate()
        assertEquals(600, summary.plannedRequestCount)
        assertTrue(summary.exceedsThreshold)

        // Does NOT truncate requests
        val requests = IntruderRequestGenerator.generateRequests(confirmedConfig)
        assertEquals(600, requests.size)
    }

    // =========================================================================
    // 5. Stage 3 Execution & Metadata Integration Tests
    // =========================================================================

    @Test
    fun testGeneratedRequests_populatePositionPayloadsAndRawText() {
        val rawTemplate = "GET /user?name=§user§&role=§role§ HTTP/1.1\r\nHost: target.local\r\n\r\n"
        val parsed = RawHttpConverter.parseRawHttp(rawTemplate, defaultScheme = "https", defaultPort = 443)
        val baseRequest = RawHttpConverter.toHttpRequestData(parsed)

        // 1. Sniper
        val sniperConfig = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.SNIPER,
            sniperPayloads = listOf("admin"),
            rawTemplate = rawTemplate
        )
        val sniperReqs = IntruderRequestGenerator.generateRequests(sniperConfig)
        assertEquals(2, sniperReqs.size) // 2 positions * 1 payload
        assertEquals("admin", sniperReqs[0].positionPayloads.values.first())
        assertTrue(sniperReqs[0].rawRequestText!!.contains("/user?name=admin&role=role"))

        // 2. Battering Ram
        val ramConfig = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.BATTERING_RAM,
            batteringRamPayloads = listOf("sharedVal"),
            rawTemplate = rawTemplate
        )
        val ramReqs = IntruderRequestGenerator.generateRequests(ramConfig)
        assertEquals(1, ramReqs.size)
        assertEquals("sharedVal", ramReqs[0].positionPayloads.values.first())
        assertEquals("sharedVal", ramReqs[0].positionPayloads.values.last())
        assertTrue(ramReqs[0].rawRequestText!!.contains("/user?name=sharedVal&role=sharedVal"))

        // 3. Pitchfork
        val pitchforkMap = mapOf(
            "pos_0" to listOf("u1", "u2"),
            "pos_1" to listOf("r1", "r2")
        )
        val pfConfig = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.PITCHFORK,
            pitchforkPayloads = pitchforkMap,
            rawTemplate = rawTemplate
        )
        val pfReqs = IntruderRequestGenerator.generateRequests(pfConfig)
        assertEquals(2, pfReqs.size)
        assertTrue(pfReqs[0].rawRequestText!!.contains("/user?name=u1&role=r1"))
        assertTrue(pfReqs[1].rawRequestText!!.contains("/user?name=u2&role=r2"))

        // 4. Cluster Bomb
        val cbConfig = VariationConfig(
            baseRequest = baseRequest,
            attackType = AttackType.CLUSTER_BOMB,
            clusterBombPayloads = pitchforkMap,
            rawTemplate = rawTemplate
        )
        val cbReqs = IntruderRequestGenerator.generateRequests(cbConfig)
        assertEquals(4, cbReqs.size) // 2x2 combinations
        assertTrue(cbReqs[0].rawRequestText!!.contains("/user?name=u1&role=r1"))
        assertTrue(cbReqs[1].rawRequestText!!.contains("/user?name=u1&role=r2"))
        assertTrue(cbReqs[2].rawRequestText!!.contains("/user?name=u2&role=r1"))
        assertTrue(cbReqs[3].rawRequestText!!.contains("/user?name=u2&role=r2"))
    }

    @Test
    fun testVariationEngine_blocksUnresolvedMarkers() {
        val engine = com.example.basicbrowser.variation.engine.VariationEngine()
        val dummyTarget = com.example.basicbrowser.variation.model.TargetSummary(
            scheme = "https",
            host = "127.0.0.1",
            port = 443,
            path = "/api",
            plannedRequestCount = 1,
            delayMs = 0L
        )
        val reqWithMarker = HttpRequestData(
            method = "GET",
            url = "https://127.0.0.1/api?query=§unresolved§",
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
}
