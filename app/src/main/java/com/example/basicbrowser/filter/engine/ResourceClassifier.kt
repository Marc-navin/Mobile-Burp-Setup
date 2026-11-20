package com.example.basicbrowser.filter.engine

import com.example.basicbrowser.filter.model.ContentTypeCategory
import java.net.URI
import java.util.Locale

/**
 * Classifies HTTP requests and responses into Burp-style content categories.
 *
 * Implements two-stage classification:
 * 1. Early request-stage classification (URL extension + request Accept header)
 *    used by Interception filtering before upstream responses exist.
 * 2. Accurate response-stage classification (actual Content-Type header + URL extension fallback)
 *    used by History filtering.
 */
object ResourceClassifier {

    /**
     * Accurately classifies a response using its Content-Type header and URL extension fallback.
     */
    fun classifyResponse(contentTypeHeader: String?, url: String): ContentTypeCategory {
        val ct = contentTypeHeader?.lowercase(Locale.ROOT)?.trim() ?: ""
        val mime = ct.substringBefore(";").trim()

        // 1. Direct MIME match (Images checked first to ensure image/svg+xml is classified as IMAGES)
        when {
            mime.startsWith("image/") -> return ContentTypeCategory.IMAGES
            mime == "text/html" || mime == "application/xhtml+xml" -> return ContentTypeCategory.HTML
            mime == "application/json" || mime == "text/json" || mime.endsWith("+json") -> return ContentTypeCategory.JSON
            mime == "application/xml" || mime == "text/xml" || mime.endsWith("+xml") -> return ContentTypeCategory.XML
            mime == "text/plain" -> return ContentTypeCategory.PLAIN_TEXT
            mime == "text/css" -> return ContentTypeCategory.CSS
            mime == "application/javascript" || mime == "text/javascript" ||
                    mime == "application/x-javascript" || mime == "text/ecmascript" -> return ContentTypeCategory.JAVASCRIPT
            mime.startsWith("font/") || mime.startsWith("application/font-") ||
                    mime.startsWith("application/x-font-") || mime == "application/vnd.ms-fontobject" -> return ContentTypeCategory.FONTS
            mime.startsWith("audio/") -> return ContentTypeCategory.AUDIO
            mime.startsWith("video/") -> return ContentTypeCategory.VIDEO
        }

        // 2. Extension fallback if Content-Type is generic (e.g. octet-stream, text/plain, or missing)
        val ext = extractExtension(url)
        return classifyByExtension(ext) ?: when {
            mime.startsWith("text/") -> ContentTypeCategory.PLAIN_TEXT
            mime.isNotEmpty() -> ContentTypeCategory.OTHER_BINARY
            else -> ContentTypeCategory.OTHER_BINARY
        }
    }

    /**
     * Early classification of a request before upstream response is received.
     *
     * Used by Intercept filtering. When uncertain, returns null to avoid
     * accidentally filtering or blocking legitimate traffic.
     */
    fun classifyRequestEarly(url: String, requestHeaders: List<String>): ContentTypeCategory? {
        // 1. URL extension has highest early confidence
        val ext = extractExtension(url)
        val fromExt = classifyByExtension(ext)
        if (fromExt != null) {
            return fromExt
        }

        // 2. Check Accept header
        val acceptHeader = requestHeaders.find {
            it.startsWith("Accept:", ignoreCase = true)
        }?.substringAfter(":")?.trim()?.lowercase(Locale.ROOT) ?: ""

        if (acceptHeader.isNotEmpty()) {
            // If browser accepts text/html, it is a document / navigation request, NEVER filter as image/css/js
            if (acceptHeader.contains("text/html")) {
                return ContentTypeCategory.HTML
            }

            // Strictly primary image requests (e.g., <img src="...">)
            if (acceptHeader.startsWith("image/") ||
                (acceptHeader.contains("image/") && !acceptHeader.contains("application/json") && !acceptHeader.contains("text/"))) {
                return ContentTypeCategory.IMAGES
            }

            // Strictly CSS request (<link rel="stylesheet">)
            if (acceptHeader.startsWith("text/css")) {
                return ContentTypeCategory.CSS
            }

            // JavaScript request (<script src="...">)
            if (acceptHeader.startsWith("*/*") && ext == "js") {
                return ContentTypeCategory.JAVASCRIPT
            }
        }

        // Uncertain — return null so caller defaults to allowing the request through
        return null
    }

    fun extractExtension(url: String): String {
        return try {
            val path = if (url.contains("://")) {
                URI(url).path ?: ""
            } else {
                url.substringBefore("?").substringBefore("#")
            }
            val lastSlash = path.lastIndexOf('/')
            val filename = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
            val clean = filename.substringBefore("?").substringBefore("#")
            val dotIdx = clean.lastIndexOf('.')
            if (dotIdx >= 0 && dotIdx < clean.length - 1) {
                clean.substring(dotIdx + 1).lowercase(Locale.ROOT)
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun classifyByExtension(ext: String): ContentTypeCategory? {
        return when (ext) {
            "html", "htm", "xhtml" -> ContentTypeCategory.HTML
            "json" -> ContentTypeCategory.JSON
            "xml", "xsl", "xsd" -> ContentTypeCategory.XML
            "txt", "log", "md", "csv" -> ContentTypeCategory.PLAIN_TEXT
            "jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "bmp", "tif", "tiff", "avif" -> ContentTypeCategory.IMAGES
            "css" -> ContentTypeCategory.CSS
            "js", "mjs", "cjs", "jsx", "ts" -> ContentTypeCategory.JAVASCRIPT
            "woff", "woff2", "ttf", "otf", "eot" -> ContentTypeCategory.FONTS
            "mp3", "wav", "ogg", "m4a", "aac", "flac" -> ContentTypeCategory.AUDIO
            "mp4", "webm", "avi", "mov", "mkv", "m4v", "flv" -> ContentTypeCategory.VIDEO
            "pdf", "zip", "tar", "gz", "rar", "7z", "bin", "exe", "apk" -> ContentTypeCategory.OTHER_BINARY
            else -> null
        }
    }
}

