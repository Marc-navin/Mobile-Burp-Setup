package com.example.basicbrowser.variation.model

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import java.nio.charset.StandardCharsets

/**
 * Utility for handling position markers (§...§) and parameter substitution
 * across URL, headers, and request body.
 */
object VariationSubstitution {

    const val MARKER = "§"
    const val MAX_PAYLOAD_LENGTH = 1024
    const val MAX_DISPLAY_PAYLOAD_LENGTH = 100
    const val MAX_ERROR_MESSAGE_LENGTH = 200

    /**
     * Extracts marked template positions or returns count of marker pairs.
     */
    fun countMarkers(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(MARKER, i)
            if (start == -1) break
            val end = text.indexOf(MARKER, start + 1)
            if (end == -1) break
            count++
            i = end + 1
        }
        return count
    }

    /**
     * Checks if the given request contains at least one marked position (§...§)
     * in its URL, headers, or UTF-8 decoded body.
     */
    fun hasMarkers(request: HttpRequestData): Boolean {
        if (countMarkers(request.url) > 0) return true
        for (header in request.headers) {
            if (countMarkers(header) > 0) return true
        }
        if (request.body != null && request.body.isNotEmpty()) {
            val bodyStr = String(request.body, StandardCharsets.UTF_8)
            if (countMarkers(bodyStr) > 0) return true
        }
        return false
    }

    /**
     * Substitutes all occurrences of §...§ with the specified [payload] in [text].
     * If no markers exist, the text is returned unmodified.
     */
    fun substituteInText(text: String, payload: String): String {
        if (!text.contains(MARKER)) return text

        val safePayload = if (payload.length > MAX_PAYLOAD_LENGTH) {
            payload.substring(0, MAX_PAYLOAD_LENGTH)
        } else {
            payload
        }

        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(MARKER, i)
            if (start == -1) {
                sb.append(text.substring(i))
                break
            }
            sb.append(text.substring(i, start))
            val end = text.indexOf(MARKER, start + 1)
            if (end == -1) {
                // Unclosed marker, append remainder as literal
                sb.append(text.substring(start))
                break
            }
            // Replace marker section with payload
            sb.append(safePayload)
            i = end + 1
        }
        return sb.toString()
    }

    /**
     * Generates a substituted [HttpRequestData] for a given [payload].
     */
    fun applyPayload(baseRequest: HttpRequestData, payload: String): HttpRequestData {
        val newUrl = substituteInText(baseRequest.url, payload)
        val newHeaders = baseRequest.headers.map { substituteInText(it, payload) }

        val newBody = if (baseRequest.body != null && baseRequest.body.isNotEmpty()) {
            val bodyStr = String(baseRequest.body, StandardCharsets.UTF_8)
            val substitutedBody = substituteInText(bodyStr, payload)
            substitutedBody.toByteArray(StandardCharsets.UTF_8)
        } else {
            baseRequest.body
        }

        return baseRequest.copy(
            url = newUrl,
            headers = newHeaders,
            body = newBody
        )
    }

    /**
     * Truncates a string safely for display.
     */
    fun truncateForDisplay(text: String?, maxLength: Int): String {
        if (text == null) return ""
        return if (text.length > maxLength) {
            text.take(maxLength - 3) + "..."
        } else {
            text
        }
    }
}

