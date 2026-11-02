package com.example.basicbrowser.util

import java.net.URLEncoder

/**
 * Pure JVM-testable utilities for URL normalization and search-query resolution.
 */
object UrlUtils {

    private const val GOOGLE_SEARCH_PREFIX = "https://www.google.com/search?q="

    /**
     * Resolves a user input string into a valid URL or search URL.
     *
     * Rules:
     * 1. Empty or whitespace-only inputs return an empty string.
     * 2. Inputs containing spaces or lacking a dot ('.') are routed to Google Search with UTF-8 encoding.
     * 3. Conservative handling of duplicate leading schemes (e.g. "https://https://" -> "https://").
     * 4. URLs without an explicit scheme default to "https://".
     * 5. Normalizes "https://google.com" and "http://google.com" to "https://www.google.com"
     *    to preserve existing application behavior and prevent WebView redirect loops.
     */
    fun getUrlOrSearch(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""

        // If the query contains whitespace or does not contain a domain dot, route to search
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            return "$GOOGLE_SEARCH_PREFIX$encoded"
        }

        // Conservatively clean redundant identical leading schemes
        var cleaned = trimmed
        while (cleaned.startsWith("https://https://", ignoreCase = true)) {
            cleaned = cleaned.substring("https://".length)
        }
        while (cleaned.startsWith("http://http://", ignoreCase = true)) {
            cleaned = cleaned.substring("http://".length)
        }

        var finalUrl = cleaned
        if (!finalUrl.startsWith("http://", ignoreCase = true) && !finalUrl.startsWith("https://", ignoreCase = true)) {
            finalUrl = "https://$finalUrl"
        }

        // Prevent google.com redirect loop (preserves existing app behavior)
        if (finalUrl.equals("https://google.com", ignoreCase = true) ||
            finalUrl.equals("http://google.com", ignoreCase = true)
        ) {
            finalUrl = "https://www.google.com"
        }

        return finalUrl
    }
}

