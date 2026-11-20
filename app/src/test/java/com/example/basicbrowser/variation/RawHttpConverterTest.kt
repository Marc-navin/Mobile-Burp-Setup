package com.example.basicbrowser.variation

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.engine.RawHttpConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RawHttpConverterTest {

    @Test
    fun testSerializeToRawHttpPreservesHeadersAndBody() {
        val request = HttpRequestData(
            method = "POST",
            url = "https://example.com:8443/api/v1/auth?step=1",
            httpVersion = "HTTP/1.1",
            headers = listOf(
                "Host: example.com:8443",
                "User-Agent: TestAgent/1.0",
                "Content-Type: application/json",
                "Cookie: session=xyz123"
            ),
            body = "{\"user\":\"admin\",\"pass\":\"secret\"}".toByteArray(StandardCharsets.UTF_8)
        )

        val raw = RawHttpConverter.serializeToRawHttp(request, targetScheme = "https", targetPort = 8443)

        val lines = raw.lines()
        assertEquals("POST /api/v1/auth?step=1 HTTP/1.1", lines[0].trimEnd('\r'))
        assertEquals("Host: example.com:8443", lines[1].trimEnd('\r'))
        assertEquals("User-Agent: TestAgent/1.0", lines[2].trimEnd('\r'))
        assertEquals("Content-Type: application/json", lines[3].trimEnd('\r'))
        assertEquals("Cookie: session=xyz123", lines[4].trimEnd('\r'))
        assertTrue(raw.contains("\r\n\r\n{\"user\":\"admin\",\"pass\":\"secret\"}"))
    }

    @Test
    fun testParseRawHttpValid() {
        val raw = "POST /login HTTP/1.1\r\nHost: target.internal:9000\r\nContent-Type: application/json\r\n\r\n{\"user\":\"§admin§\"}"
        val parsed = RawHttpConverter.parseRawHttp(raw, defaultScheme = "http", defaultPort = 9000)

        assertTrue(parsed.isValid)
        assertEquals("POST", parsed.method)
        assertEquals("/login", parsed.pathAndQuery)
        assertEquals("HTTP/1.1", parsed.httpVersion)
        assertEquals(2, parsed.headers.size)
        assertEquals("Host: target.internal:9000", parsed.headers[0])
        assertNotNull(parsed.target)
        assertEquals("http", parsed.target?.scheme)
        assertEquals("target.internal", parsed.target?.host)
        assertEquals(9000, parsed.target?.port)
        assertNotNull(parsed.body)
        assertEquals("{\"user\":\"§admin§\"}", String(parsed.body!!, StandardCharsets.UTF_8))
        assertEquals(1, parsed.markerPositions.size)
        assertEquals("admin", parsed.markerPositions[0].baselineValue)
    }

    @Test
    fun testAmbiguousSchemeNotAssumedHttps() {
        val raw = "GET /test HTTP/1.1\r\nHost: test.example.com\r\n\r\n"
        // No scheme provided
        val parsed = RawHttpConverter.parseRawHttp(raw, defaultScheme = null)

        assertFalse(parsed.isValid)
        assertTrue(parsed.validationErrors.any { it.contains("Target scheme", ignoreCase = true) })
    }

    @Test
    fun testHostHeaderCannotContainMarkers() {
        val raw = "GET /test HTTP/1.1\r\nHost: §evil.com§\r\n\r\n"
        val parsed = RawHttpConverter.parseRawHttp(raw, defaultScheme = "https", defaultPort = 443)

        assertFalse(parsed.isValid)
        assertTrue(parsed.validationErrors.any { it.contains("Host header cannot contain", ignoreCase = true) })
    }

    @Test
    fun testMarkerValidationDetectsUnclosedMarker() {
        val unclosed = "GET /api?id=§123 HTTP/1.1\r\nHost: example.com\r\n\r\n"
        val result = RawHttpConverter.validateMarkers(unclosed)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Unclosed payload marker", ignoreCase = true) })
    }

    @Test
    fun testAddMarkerWithSelection() {
        val text = "POST /search?q=banana HTTP/1.1"
        val start = text.indexOf("banana")
        val end = start + "banana".length

        val (newText, newRange) = RawHttpConverter.addMarker(text, start, end)
        assertEquals("POST /search?q=§banana§ HTTP/1.1", newText)
        assertEquals("§banana§", newText.substring(newRange.first, newRange.last))
    }

    @Test
    fun testAddMarkerWithoutSelectionInsertsEmptyPair() {
        val text = "POST /api/test HTTP/1.1"
        val cursor = text.indexOf("/test")

        val (newText, newRange) = RawHttpConverter.addMarker(text, cursor, cursor)
        assertEquals("POST /api§§/test HTTP/1.1", newText)
        // Cursor placed inside markers
        assertEquals(cursor + 1, newRange.first)
        assertEquals(cursor + 1, newRange.last)
    }

    @Test
    fun testClearMarkers() {
        val text = "POST /§api§/login HTTP/1.1\r\nCookie: s=§abc§\r\n\r\n{\"name\":\"§admin§\"}"
        val cleared = RawHttpConverter.clearMarkers(text)
        assertEquals("POST /api/login HTTP/1.1\r\nCookie: s=abc\r\n\r\n{\"name\":\"admin\"}", cleared)
    }

    @Test
    fun testRemoveMarkerAtIndex() {
        val text = "GET /api?a=§1§&b=§2§&c=§3§ HTTP/1.1"
        // Remove index 1 (b=§2§ -> b=2)
        val result = RawHttpConverter.removeMarkerAtIndex(text, 1)
        assertEquals("GET /api?a=§1§&b=2&c=§3§ HTTP/1.1", result)
    }

    @Test
    fun testAutoDetectMarkers() {
        val raw = "GET /api?id=100&action=edit HTTP/1.1\r\nHost: example.com\r\nCookie: session=secret123\r\n\r\n{\"username\":\"admin\",\"role\":\"tester\"}"
        val auto = RawHttpConverter.autoDetectMarkers(raw)

        assertTrue(auto.contains("?id=§100§&action=§edit§"))
        assertTrue(auto.contains("session=§secret123§"))
        assertTrue(auto.contains("\"username\":\"§admin§\""))
        assertTrue(auto.contains("\"role\":\"§tester§\""))
    }

    @Test
    fun testSubstituteRawMarkersPreservesExactFormatting() {
        val template = "POST /api HTTP/1.1\r\nHost: example.com\r\nX-Custom: §header_val§\r\n\r\n{\"id\":§1§,\"name\":\"§user§\"}"
        val substituted = RawHttpConverter.substituteRawMarkers(
            template,
            listOf("CUSTOM_TOKEN", "999", "superadmin")
        )

        val expected = "POST /api HTTP/1.1\r\nHost: example.com\r\nX-Custom: CUSTOM_TOKEN\r\n\r\n{\"id\":999,\"name\":\"superadmin\"}"
        assertEquals(expected, substituted)
    }
}
