package com.example.basicbrowser.util

import java.net.URI

/**
 * Extracts and normalizes links from HTML response bodies.
 */
object ResponseLinkExtractor {

    private val LINK_REGEX = Regex("""(?i)\b(?:href|src|action)\s*=\s*["']([^"']+)["']""")

    /**
     * Extracts and resolves unique URLs found in HTML response body.
     *
     * @param baseUrl The base URL of the original request
     * @param responseHeaders List of response headers
     * @param responseBody Raw response body bytes
     * @return List of unique, resolved absolute URLs
     */
    fun extractLinks(
        baseUrl: String,
        responseHeaders: List<String>,
        responseBody: ByteArray?
    ): List<String> {
        if (responseBody == null || responseBody.isEmpty()) return emptyList()

        val text = try {
            String(responseBody, Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }

        val baseUri = try {
            URI(baseUrl)
        } catch (_: Exception) {
            null
        }

        val links = LinkedHashSet<String>()
        val matches = LINK_REGEX.findAll(text)

        for (match in matches) {
            val rawLink = match.groupValues[1].trim()
            if (rawLink.isEmpty() ||
                rawLink.startsWith("#") ||
                rawLink.startsWith("javascript:", ignoreCase = true) ||
                rawLink.startsWith("data:", ignoreCase = true) ||
                rawLink.startsWith("mailto:", ignoreCase = true) ||
                rawLink.startsWith("tel:", ignoreCase = true)
            ) {
                continue
            }

            val resolved = try {
                if (baseUri != null) {
                    baseUri.resolve(rawLink).toString()
                } else {
                    rawLink
                }
            } catch (_: Exception) {
                rawLink
            }

            if (resolved.startsWith("http://", ignoreCase = true) ||
                resolved.startsWith("https://", ignoreCase = true)
            ) {
                links.add(resolved)
            }
        }

        return links.toList()
    }
}

