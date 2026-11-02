package com.example.basicbrowser.variation

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.model.TargetSummary
import com.example.basicbrowser.variation.model.VariationConfig
import com.example.basicbrowser.variation.model.VariationResult
import com.example.basicbrowser.variation.model.VariationSubstitution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class VariationModelAndSubstitutionTest {

    @Test
    fun testMarkerCounting() {
        assertEquals(0, VariationSubstitution.countMarkers("http://example.com/test"))
        assertEquals(1, VariationSubstitution.countMarkers("http://example.com/test?id=§123§"))
        assertEquals(2, VariationSubstitution.countMarkers("http://example.com/§test§?id=§123§"))
        // Unclosed marker
        assertEquals(1, VariationSubstitution.countMarkers("http://example.com/§test§?id=§123"))
    }

    @Test
    fun testHasMarkers() {
        val reqNoMarkers = HttpRequestData(
            method = "GET",
            url = "http://example.com/api",
            headers = listOf("Accept: */*"),
            body = null
        )
        assertFalse(VariationSubstitution.hasMarkers(reqNoMarkers))

        val reqUrlMarker = reqNoMarkers.copy(url = "http://example.com/api?user=§admin§")
        assertTrue(VariationSubstitution.hasMarkers(reqUrlMarker))

        val reqHeaderMarker = reqNoMarkers.copy(headers = listOf("Authorization: Bearer §token§"))
        assertTrue(VariationSubstitution.hasMarkers(reqHeaderMarker))

        val reqBodyMarker = reqNoMarkers.copy(
            method = "POST",
            body = "{\"key\": \"§secret§\"}".toByteArray(StandardCharsets.UTF_8)
        )
        assertTrue(VariationSubstitution.hasMarkers(reqBodyMarker))
    }

    @Test
    fun testSubstituteInText() {
        val template = "SELECT * FROM users WHERE id = §1§ AND status = '§active§'"
        val substituted = VariationSubstitution.substituteInText(template, "test_val")
        assertEquals("SELECT * FROM users WHERE id = test_val AND status = 'test_val'", substituted)

        // Text without markers is untouched
        val plain = "Normal text without markers"
        assertEquals(plain, VariationSubstitution.substituteInText(plain, "anything"))

        // Unclosed marker appended literally
        val unclosed = "Prefix §marker with no end"
        assertEquals("Prefix §marker with no end", VariationSubstitution.substituteInText(unclosed, "payload"))
    }

    @Test
    fun testPayloadLengthTruncation() {
        val longPayload = "A".repeat(2000)
        val text = "Value: §test§"
        val substituted = VariationSubstitution.substituteInText(text, longPayload)
        // Hard limit is MAX_PAYLOAD_LENGTH (1024)
        assertEquals("Value: " + "A".repeat(VariationSubstitution.MAX_PAYLOAD_LENGTH), substituted)
    }

    @Test
    fun testApplyPayloadAcrossUrlHeadersAndBody() {
        val baseReq = HttpRequestData(
            method = "POST",
            url = "http://example.com/search?q=§query§",
            headers = listOf("X-Custom-Header: §header_val§", "Content-Type: text/plain"),
            body = "payload_in_body=§body_val§".toByteArray(StandardCharsets.UTF_8)
        )

        val applied = VariationSubstitution.applyPayload(baseReq, "FUZZ")
        assertEquals("http://example.com/search?q=FUZZ", applied.url)
        assertEquals("X-Custom-Header: FUZZ", applied.headers[0])
        assertEquals("Content-Type: text/plain", applied.headers[1])
        assertNotNull(applied.body)
        assertEquals("payload_in_body=FUZZ", String(applied.body!!, StandardCharsets.UTF_8))
    }

    @Test
    fun testTruncateForDisplay() {
        val shortStr = "Hello"
        assertEquals("Hello", VariationSubstitution.truncateForDisplay(shortStr, 10))

        val longStr = "1234567890ABCDEF"
        assertEquals("1234567...", VariationSubstitution.truncateForDisplay(longStr, 10))
    }

    @Test
    fun testVariationResultDisplayProperties() {
        val longPayload = "P".repeat(200)
        val longError = "E".repeat(300)
        val result = VariationResult(
            index = 1,
            payload = longPayload,
            statusCode = 500,
            responseSizeBytes = 1024,
            elapsedTimeMs = 45,
            errorMessage = longError
        )

        assertTrue(result.displayPayload.endsWith("..."))
        assertTrue(result.displayPayload.length <= VariationSubstitution.MAX_DISPLAY_PAYLOAD_LENGTH)
        assertTrue(result.displayErrorMessage!!.endsWith("..."))
        assertTrue(result.displayErrorMessage!!.length <= VariationSubstitution.MAX_ERROR_MESSAGE_LENGTH)
    }

    @Test
    fun testVariationConfigValidation_success() {
        val config = VariationConfig(
            baseRequest = HttpRequestData(
                method = "GET",
                url = "https://test.local:8443/api/check?param=1",
                headers = emptyList()
            ),
            payloads = listOf("val1", "val2", "val3"),
            delayMs = 150L,
            maxRequests = 100
        )

        val summary = config.validate()
        assertEquals("https", summary.scheme)
        assertEquals("test.local", summary.host)
        assertEquals(8443, summary.port)
        assertEquals("/api/check", summary.path)
        assertEquals(3, summary.plannedRequestCount)
        assertEquals(150L, summary.delayMs)
    }

    @Test
    fun testVariationConfigValidation_missingPayloadsFails() {
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://example.com/", headers = emptyList()),
            payloads = emptyList()
        )
        try {
            config.validate()
            fail("Expected IllegalArgumentException for empty payloads")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Payload list must not be empty"))
        }
    }

    @Test
    fun testVariationConfigValidation_invalidDelayFails() {
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://example.com/", headers = emptyList()),
            payloads = listOf("test"),
            delayMs = 10000L // Exceeds MAX_DELAY_MS (5000L)
        )
        try {
            config.validate()
            fail("Expected IllegalArgumentException for excessive delay")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Delay must be between"))
        }
    }

    @Test
    fun testVariationConfigValidation_unsupportedSchemeFails() {
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "ftp://example.com/file", headers = emptyList()),
            payloads = listOf("test")
        )
        try {
            config.validate()
            fail("Expected IllegalArgumentException for unsupported scheme")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported scheme"))
        }
    }

    @Test
    fun testVariationConfigValidation_hardCapMaxRequestsEnforced() {
        val largeList = (1..600).map { "item$it" }
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://example.com/", headers = emptyList()),
            payloads = largeList,
            maxRequests = 500
        )
        val summary = config.validate()
        assertEquals(500, summary.plannedRequestCount)
    }

    @Test
    fun testVariationConfigValidation_markerInHostFails() {
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://§dynamic_host§:8080/path", headers = emptyList()),
            payloads = listOf("val")
        )
        try {
            config.validate()
            fail("Expected validation failure when marker is in host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cannot contain position markers"))
        }
    }

    @Test
    fun testVariationConfigValidation_markerInSchemeFails() {
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "§scheme§://example.com/path", headers = emptyList()),
            payloads = listOf("val")
        )
        try {
            config.validate()
            fail("Expected validation failure when marker is in scheme")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("scheme"))
        }
    }

    @Test
    fun testVariationConfigValidation_invalidPortFails() {
        val config = VariationConfig(
            baseRequest = HttpRequestData(method = "GET", url = "http://example.com:99999/path", headers = emptyList()),
            payloads = listOf("val")
        )
        try {
            config.validate()
            fail("Expected validation failure for out-of-range port")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Port must be between 1 and 65535") || e.message!!.contains("Invalid target URL format"))
        }
    }

    @Test
    fun testExactPayloadPreservation_withoutExtraneousPrefixOrSuffix() {
        val baseRequest = HttpRequestData(
            method = "GET",
            url = "http://127.0.0.1:8888/test?q=§param§",
            headers = listOf("X-Test: §param§")
        )
        val testPayloads = listOf("alpha", "beta", "gamma")

        for (p in testPayloads) {
            val applied = VariationSubstitution.applyPayload(baseRequest, p)
            assertEquals("http://127.0.0.1:8888/test?q=$p", applied.url)
            assertEquals("X-Test: $p", applied.headers[0])
            assertFalse("URL must not contain accidental prefixes like 'by'", applied.url.contains("by$p"))
            assertFalse("URL must not contain accidental suffixes like 'yy'", applied.url.contains("${p}yy"))

            val result = VariationResult(
                index = 1,
                payload = p,
                statusCode = 200,
                responseSizeBytes = 10L,
                elapsedTimeMs = 5L
            )
            assertEquals(p, result.payload)
            assertEquals(p, result.displayPayload)
        }
    }
}
