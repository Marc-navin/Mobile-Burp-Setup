package com.example.basicbrowser.variation.engine

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.model.VariationSubstitution
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Metadata identifying the upstream target server.
 */
data class TargetMetadata(
    val scheme: String, // "http" or "https"
    val host: String,
    val port: Int,
    val isExplicit: Boolean = true
)

/**
 * Position of a §...§ marker within a raw HTTP request template.
 */
data class MarkerPosition(
    val id: String,
    val index: Int, // 0-based index
    val rawStartIndex: Int,
    val rawEndIndex: Int,
    val baselineValue: String,
    val contextSnippet: String
)

/**
 * Result of validating markers in a raw HTTP template.
 */
data class MarkerValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val positions: List<MarkerPosition>
)

/**
 * Result of parsing a raw HTTP request.
 */
data class ParsedRawHttpRequest(
    val method: String,
    val pathAndQuery: String,
    val httpVersion: String,
    val headers: List<String>,
    val body: ByteArray?,
    val target: TargetMetadata?,
    val validationErrors: List<String> = emptyList(),
    val markerPositions: List<MarkerPosition> = emptyList()
) {
    val isValid: Boolean get() = validationErrors.isEmpty()
}

/**
 * Bi-directional converter, parser, serializer, and marker manager for raw HTTP requests.
 *
 * Guarantees:
 * - Preserves the raw HTTP template as the source of truth.
 * - Preserves exact header ordering, formatting, and body content.
 * - Accurate target scheme and port resolution (never assumes HTTPS if ambiguous).
 * - Burp-style § marker parsing, wrapping, clearing, auto-detection, and syntax validation.
 */
object RawHttpConverter {

    const val MARKER = VariationSubstitution.MARKER // "§"
    private const val DEFAULT_HTTP_VERSION = "HTTP/1.1"

    /**
     * Serializes an [HttpRequestData] and optional target metadata to RFC 7230 wire format.
     * Preserves header ordering, casing, and body bytes.
     */
    fun serializeToRawHttp(
        request: HttpRequestData,
        targetScheme: String? = null,
        targetPort: Int? = null
    ): String {
        val sb = StringBuilder()

        // 1. Resolve path and query
        val rawUrl = request.url.trim()
        val pathAndQuery = extractPathAndQuery(rawUrl)

        // 2. Request line: METHOD PATH VERSION
        val method = if (request.method.isNotBlank()) request.method.trim().uppercase() else "GET"
        val httpVersion = if (request.httpVersion.isNotBlank()) request.httpVersion.trim() else DEFAULT_HTTP_VERSION
        sb.append(method).append(" ").append(pathAndQuery).append(" ").append(httpVersion).append("\r\n")

        // 3. Headers
        val hasHostHeader = request.headers.any { it.trim().startsWith("Host:", ignoreCase = true) }
        val inferredHost = extractHostFromUrl(rawUrl)

        // If Host header is missing from headers list, synthesize it from URL if available
        if (!hasHostHeader && !inferredHost.isNullOrBlank()) {
            val hostHeaderVal = formatHostHeaderValue(inferredHost, targetScheme, targetPort)
            sb.append("Host: ").append(hostHeaderVal).append("\r\n")
        }

        for (header in request.headers) {
            val trimmed = header.trim()
            if (trimmed.isNotEmpty()) {
                sb.append(trimmed).append("\r\n")
            }
        }

        // 4. Header-Body delimiter
        sb.append("\r\n")

        // 5. Request Body
        if (request.body != null && request.body.isNotEmpty()) {
            sb.append(String(request.body, StandardCharsets.UTF_8))
        }

        return sb.toString()
    }

    /**
     * Parses a raw HTTP string into structured components, validating syntax and markers.
     *
     * @param rawText Full raw HTTP request text.
     * @param defaultScheme Preserved scheme from imported request or user selection (e.g. "https" or "http").
     * @param defaultPort Preserved port (e.g. 443, 80, 8080).
     * @param defaultHost Preserved host (e.g. "example.com").
     */
    fun parseRawHttp(
        rawText: String,
        defaultScheme: String? = null,
        defaultPort: Int? = null,
        defaultHost: String? = null
    ): ParsedRawHttpRequest {
        val errors = mutableListOf<String>()

        if (rawText.isBlank()) {
            return ParsedRawHttpRequest(
                method = "GET",
                pathAndQuery = "/",
                httpVersion = DEFAULT_HTTP_VERSION,
                headers = emptyList(),
                body = null,
                target = null,
                validationErrors = listOf("Request template is empty.")
            )
        }

        // 1. Separate header section and body
        val delimiterIndex = findHeaderBodyDelimiter(rawText)
        val headerSection: String
        val bodySection: String

        if (delimiterIndex != -1) {
            headerSection = rawText.substring(0, delimiterIndex)
            val bodyStartIndex = if (rawText.startsWith("\r\n\r\n", delimiterIndex)) {
                delimiterIndex + 4
            } else if (rawText.startsWith("\n\n", delimiterIndex)) {
                delimiterIndex + 2
            } else if (rawText.startsWith("\r\n\n", delimiterIndex) || rawText.startsWith("\n\r\n", delimiterIndex)) {
                delimiterIndex + 3
            } else {
                delimiterIndex + 2
            }
            bodySection = if (bodyStartIndex < rawText.length) rawText.substring(bodyStartIndex) else ""
        } else {
            headerSection = rawText
            bodySection = ""
        }

        // 2. Parse Request Line
        val headerLines = headerSection.lines()
        val firstLine = headerLines.firstOrNull()?.trim() ?: ""

        if (firstLine.isEmpty()) {
            errors.add("Missing HTTP request line.")
        }

        val requestLineTokens = firstLine.split("\\s+".toRegex(), limit = 3)
        val method = if (requestLineTokens.isNotEmpty() && requestLineTokens[0].isNotBlank()) {
            requestLineTokens[0].trim()
        } else {
            "GET"
        }

        val requestTarget = if (requestLineTokens.size >= 2) {
            requestLineTokens[1].trim()
        } else {
            "/"
        }

        val httpVersion = if (requestLineTokens.size >= 3 && requestLineTokens[2].isNotBlank()) {
            requestLineTokens[2].trim()
        } else {
            DEFAULT_HTTP_VERSION
        }

        // Method validation
        if (method.contains(MARKER)) {
            errors.add("HTTP Method cannot contain payload markers (§).")
        }
        if (httpVersion.contains(MARKER)) {
            errors.add("HTTP Version cannot contain payload markers (§).")
        }

        // 3. Parse Headers (lines 1 until delimiter)
        val headers = mutableListOf<String>()
        var hostHeaderValue: String? = null

        for (i in 1 until headerLines.size) {
            val line = headerLines[i].trimEnd('\r', ' ')
            if (line.isNotEmpty()) {
                headers.add(line)
                if (line.startsWith("Host:", ignoreCase = true)) {
                    hostHeaderValue = line.substringAfter(":").trim()
                }
            }
        }

        // 4. Validate Host header
        if (hostHeaderValue != null && hostHeaderValue.contains(MARKER)) {
            errors.add("Host header cannot contain payload markers (§). Target host must remain fixed.")
        }

        // 5. Target Scheme & Port Resolution
        // Rule 1: Do not automatically assume HTTPS if ambiguous.
        // Rule 2: Preserve original scheme & port when provided.
        var resolvedScheme: String? = defaultScheme?.lowercase()?.trim()
        var resolvedHost: String? = defaultHost?.trim()
        var resolvedPort: Int? = defaultPort

        // Check if request line target is an absolute URL (e.g. GET https://target.com/path)
        if (requestTarget.startsWith("http://", ignoreCase = true)) {
            val uri = try { URI(requestTarget.replace(MARKER, "")) } catch (_: Exception) { null }
            if (uri != null) {
                resolvedScheme = "http"
                if (uri.host != null) resolvedHost = uri.host
                if (uri.port != -1) resolvedPort = uri.port
            }
        } else if (requestTarget.startsWith("https://", ignoreCase = true)) {
            val uri = try { URI(requestTarget.replace(MARKER, "")) } catch (_: Exception) { null }
            if (uri != null) {
                resolvedScheme = "https"
                if (uri.host != null) resolvedHost = uri.host
                if (uri.port != -1) resolvedPort = uri.port
            }
        }

        // If host not resolved from target line, resolve from Host header
        if (resolvedHost.isNullOrBlank() && !hostHeaderValue.isNullOrBlank()) {
            val strippedHostHeader = hostHeaderValue.replace(MARKER, "").trim()
            if (strippedHostHeader.contains(":")) {
                val hostPart = strippedHostHeader.substringBeforeLast(":").trim(' ', '[', ']')
                val portPart = strippedHostHeader.substringAfterLast(":").toIntOrNull()
                resolvedHost = hostPart
                if (resolvedPort == null && portPart != null) {
                    resolvedPort = portPart
                }
            } else {
                resolvedHost = strippedHostHeader
            }
        }

        // Scheme validation
        if (resolvedScheme.isNullOrBlank()) {
            errors.add("Target scheme (HTTP or HTTPS) is required and cannot be determined ambiguously.")
        } else if (resolvedScheme != "http" && resolvedScheme != "https") {
            errors.add("Invalid scheme '$resolvedScheme'. Only HTTP and HTTPS are supported.")
        }

        // Host validation
        if (resolvedHost.isNullOrBlank()) {
            errors.add("Target host is required (specify in Host header or target metadata).")
        }

        // Port validation
        if (resolvedPort == null) {
            resolvedPort = if (resolvedScheme == "https") 443 else 80
        } else if (resolvedPort !in 1..65535) {
            errors.add("Invalid port $resolvedPort. Must be between 1 and 65535.")
        }

        val targetMetadata = if (resolvedScheme != null && !resolvedHost.isNullOrBlank() && resolvedPort in 1..65535) {
            TargetMetadata(
                scheme = resolvedScheme,
                host = resolvedHost,
                port = resolvedPort,
                isExplicit = defaultScheme != null
            )
        } else {
            null
        }

        // 6. Path and Query
        val pathAndQuery = extractPathAndQuery(requestTarget)

        // 7. Body
        val bodyBytes = if (bodySection.isNotEmpty()) {
            bodySection.toByteArray(StandardCharsets.UTF_8)
        } else {
            null
        }

        // 8. Marker validation
        val markerResult = validateMarkers(rawText)
        if (!markerResult.isValid) {
            errors.addAll(markerResult.errors)
        }

        return ParsedRawHttpRequest(
            method = method,
            pathAndQuery = pathAndQuery,
            httpVersion = httpVersion,
            headers = headers,
            body = bodyBytes,
            target = targetMetadata,
            validationErrors = errors,
            markerPositions = markerResult.positions
        )
    }

    /**
     * Converts a [ParsedRawHttpRequest] to an [HttpRequestData] instance.
     * Throws [IllegalArgumentException] if validation errors exist.
     */
    fun toHttpRequestData(parsed: ParsedRawHttpRequest): HttpRequestData {
        require(parsed.isValid) {
            "Cannot construct HttpRequestData from invalid raw HTTP: ${parsed.validationErrors.joinToString("; ")}"
        }
        val target = parsed.target ?: throw IllegalArgumentException("Missing target metadata.")
        val fullUrl = buildFullUrl(target.scheme, target.host, target.port, parsed.pathAndQuery)

        return HttpRequestData(
            method = parsed.method,
            url = fullUrl,
            httpVersion = parsed.httpVersion,
            headers = parsed.headers,
            body = parsed.body
        )
    }

    // ------------------------------------------------------------------ //
    //  Marker Management & Auto-Detection
    // ------------------------------------------------------------------ //

    /**
     * Wraps the selected text range in [text] with §...§ markers.
     * If no text is selected (start == end), inserts §§ at cursor and positions cursor between them.
     */
    fun addMarker(text: String, selectionStart: Int, selectionEnd: Int): Pair<String, IntRange> {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)

        return if (start < end) {
            // Text is selected -> wrap selection
            val prefix = text.substring(0, start)
            val selected = text.substring(start, end)
            val suffix = text.substring(end)
            val newText = "$prefix$MARKER$selected$MARKER$suffix"
            val newRange = IntRange(start, end + 2) // selection covers marked region
            Pair(newText, newRange)
        } else {
            // No selection -> insert empty §§ at cursor and place cursor in middle
            val prefix = text.substring(0, start)
            val suffix = text.substring(start)
            val newText = "$prefix$MARKER$MARKER$suffix"
            val newRange = IntRange(start + 1, start + 1) // cursor between markers
            Pair(newText, newRange)
        }
    }

    /**
     * Strips all § markers from the raw template, leaving baseline text intact.
     */
    fun clearMarkers(text: String): String {
        return text.replace(MARKER, "")
    }

    /**
     * Removes the marker pair at [targetIndex] (0-based), retaining its baseline text.
     */
    fun removeMarkerAtIndex(rawText: String, targetIndex: Int): String {
        val result = validateMarkers(rawText)
        if (targetIndex !in result.positions.indices) return rawText
        val target = result.positions[targetIndex]
        val prefix = rawText.substring(0, target.rawStartIndex)
        val baseline = target.baselineValue
        val suffix = rawText.substring(target.rawEndIndex)
        return "$prefix$baseline$suffix"
    }

    /**
     * Validates that markers in [rawText] are well-formed and extracts their positions.
     */
    fun validateMarkers(rawText: String): MarkerValidationResult {
        val errors = mutableListOf<String>()
        val positions = mutableListOf<MarkerPosition>()

        // Check for odd number of markers (unclosed marker)
        val markerCount = rawText.count { it.toString() == MARKER }
        if (markerCount % 2 != 0) {
            errors.add("Unclosed payload marker ($MARKER) detected. Every opening $MARKER must have a matching closing $MARKER.")
        }

        // Extract pairs
        var searchIndex = 0
        var pairIndex = 0

        while (searchIndex < rawText.length) {
            val start = rawText.indexOf(MARKER, searchIndex)
            if (start == -1) break
            val end = rawText.indexOf(MARKER, start + 1)
            if (end == -1) break // unclosed remainder

            val baseline = rawText.substring(start + 1, end)
            val snippetStart = maxOf(0, start - 15)
            val snippetEnd = minOf(rawText.length, end + 1 + 15)
            val snippet = rawText.substring(snippetStart, snippetEnd).replace("\r", "").replace("\n", " ")

            positions.add(
                MarkerPosition(
                    id = "pos_$pairIndex",
                    index = pairIndex,
                    rawStartIndex = start,
                    rawEndIndex = end + 1,
                    baselineValue = baseline,
                    contextSnippet = snippet
                )
            )
            pairIndex++
            searchIndex = end + 1
        }

        return MarkerValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            positions = positions
        )
    }

    /**
     * Heuristically auto-detects candidate parameters (query params, cookies, JSON attributes)
     * and wraps their baseline values in §...§ markers.
     */
    fun autoDetectMarkers(rawText: String): String {
        if (rawText.isBlank()) return rawText

        // Strip existing markers first so we don't produce nested §
        val cleaned = clearMarkers(rawText)
        val delimiterIndex = findHeaderBodyDelimiter(cleaned)

        val headerSection: String
        val bodySection: String
        val delimiter: String

        if (delimiterIndex != -1) {
            headerSection = cleaned.substring(0, delimiterIndex)
            delimiter = if (cleaned.startsWith("\r\n\r\n", delimiterIndex)) "\r\n\r\n" else "\n\n"
            bodySection = cleaned.substring(delimiterIndex + delimiter.length)
        } else {
            headerSection = cleaned
            delimiter = "\r\n\r\n"
            bodySection = ""
        }

        val lines = headerSection.lines().toMutableList()
        if (lines.isNotEmpty()) {
            // 1. Mark query parameters in request line: ?id=123&action=view -> ?id=§123§&action=§view§
            val reqLine = lines[0]
            val markedReqLine = reqLine.replace("([?&][a-zA-Z0-9_.-]+=)([^&\\s]+)".toRegex()) { match ->
                "${match.groupValues[1]}$MARKER${match.groupValues[2]}$MARKER"
            }
            lines[0] = markedReqLine

            // 2. Mark cookies in Cookie headers: Cookie: session=abc; user=guest -> Cookie: session=§abc§; user=§guest§
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.startsWith("Cookie:", ignoreCase = true)) {
                    lines[i] = line.replace("([=])([^;\\r\\n\\s]+)".toRegex()) { match ->
                        "=$MARKER${match.groupValues[2]}$MARKER"
                    }
                }
            }
        }

        // 3. Mark body content: JSON or form-urlencoded
        val markedBody = if (bodySection.isNotBlank()) {
            val trimmedBody = bodySection.trim()
            if (trimmedBody.startsWith("{") || trimmedBody.startsWith("[")) {
                // JSON: mark string, number, and boolean values
                // "key": "value" -> "key": "§value§"
                var b = bodySection.replace("(\"[a-zA-Z0-9_.-]+\"\\s*:\\s*\")([^\"]*)(\")".toRegex()) { match ->
                    "${match.groupValues[1]}$MARKER${match.groupValues[2]}$MARKER${match.groupValues[3]}"
                }
                // "key": 123 -> "key": §123§
                b = b.replace("(\"[a-zA-Z0-9_.-]+\"\\s*:\\s*)([0-9.]+|true|false)(?=[,\\s\\r\\n}\\]])".toRegex()) { match ->
                    "${match.groupValues[1]}$MARKER${match.groupValues[2]}$MARKER"
                }
                b
            } else if (bodySection.contains("=")) {
                // Form urlencoded: a=1&b=2 -> a=§1§&b=§2§
                bodySection.replace("(^|[&])([a-zA-Z0-9_.-]+=)([^&\\s]+)".toRegex()) { match ->
                    "${match.groupValues[1]}${match.groupValues[2]}$MARKER${match.groupValues[3]}$MARKER"
                }
            } else {
                bodySection
            }
        } else {
            bodySection
        }

        val markedHeaders = lines.joinToString("\n")
        return if (markedBody.isNotEmpty()) {
            "$markedHeaders$delimiter$markedBody"
        } else {
            markedHeaders
        }
    }

    /**
     * Substitutes sequential values into the raw HTTP template, replacing each §...§ pair.
     * Preserves exact wire formatting, line breaks, header order, and non-marked characters.
     */
    fun substituteRawMarkers(rawTemplate: String, values: List<String>): String {
        if (!rawTemplate.contains(MARKER) || values.isEmpty()) {
            return clearMarkers(rawTemplate)
        }

        val sb = StringBuilder()
        var valIdx = 0
        var i = 0

        while (i < rawTemplate.length) {
            val start = rawTemplate.indexOf(MARKER, i)
            if (start == -1) {
                sb.append(rawTemplate.substring(i))
                break
            }
            sb.append(rawTemplate.substring(i, start))
            val end = rawTemplate.indexOf(MARKER, start + 1)
            if (end == -1) {
                // Unclosed marker at end
                sb.append(rawTemplate.substring(start))
                break
            }

            if (valIdx < values.size) {
                val payload = values[valIdx++]
                val safePayload = if (payload.length > VariationSubstitution.MAX_PAYLOAD_LENGTH) {
                    payload.substring(0, VariationSubstitution.MAX_PAYLOAD_LENGTH)
                } else {
                    payload
                }
                sb.append(safePayload)
            } else {
                // If fewer values than markers, keep baseline
                sb.append(rawTemplate.substring(start + 1, end))
            }
            i = end + 1
        }

        return sb.toString()
    }

    // ------------------------------------------------------------------ //
    //  Helper Utilities
    // ------------------------------------------------------------------ //

    private fun findHeaderBodyDelimiter(text: String): Int {
        val crlfcrlf = text.indexOf("\r\n\r\n")
        val lflf = text.indexOf("\n\n")
        return when {
            crlfcrlf != -1 && lflf != -1 -> minOf(crlfcrlf, lflf)
            crlfcrlf != -1 -> crlfcrlf
            lflf != -1 -> lflf
            else -> -1
        }
    }

    private fun extractPathAndQuery(targetOrUrl: String): String {
        val trimmed = targetOrUrl.trim()
        if (trimmed.isEmpty()) return "/"
        if (trimmed == "*") return "*"

        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val stripped = trimmed.replace(MARKER, "")
            return try {
                val uri = URI(stripped)
                val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
                val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                path + query
            } catch (_: Exception) {
                val afterScheme = trimmed.substringAfter("://")
                val slashIdx = afterScheme.indexOf('/')
                if (slashIdx != -1) afterScheme.substring(slashIdx) else "/"
            }
        }

        return if (!trimmed.startsWith("/")) "/$trimmed" else trimmed
    }

    private fun extractHostFromUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.contains("://")) return null
        return try {
            val uri = URI(trimmed.replace(MARKER, ""))
            uri.host
        } catch (_: Exception) {
            val withoutScheme = trimmed.substringAfter("://")
            val hostPart = withoutScheme.substringBefore("/").substringBefore("?")
            if (hostPart.contains(":")) hostPart.substringBefore(":") else hostPart
        }
    }

    private fun formatHostHeaderValue(host: String, scheme: String?, port: Int?): String {
        val isDefaultPort = (scheme == "http" && (port == null || port == 80)) ||
                (scheme == "https" && (port == null || port == 443))
        return if (isDefaultPort || port == null) host else "$host:$port"
    }

    private fun buildFullUrl(scheme: String, host: String, port: Int, pathAndQuery: String): String {
        val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val hostPort = if (isDefaultPort) host else "$host:$port"
        val normalizedPath = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
        return "$scheme://$hostPort$normalizedPath"
    }
}
