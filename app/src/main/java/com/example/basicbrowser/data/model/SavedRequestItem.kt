package com.example.basicbrowser.data.model

import com.example.basicbrowser.proxy.intercept.HttpRequestData

/**
 * Completely self-contained persisted request/response record.
 * Retains complete header and body data independently of HttpHistory retention and cache eviction.
 */
data class SavedRequestItem(
    val id: String,
    val method: String,
    val url: String,
    val requestHeaders: List<String>,
    val requestBody: ByteArray? = null,
    val statusCode: Int? = null,
    val responseHeaders: List<String> = emptyList(),
    val responseBody: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String = "",
    val highlightColor: HighlightColor = HighlightColor.NONE,
    val savedAt: Long = System.currentTimeMillis()
) {
    fun toHttpRequestData(): HttpRequestData {
        return HttpRequestData(
            method = method,
            url = url,
            httpVersion = "HTTP/1.1",
            headers = requestHeaders.filterNot { it.startsWith("HTTP/") || it.contains("HTTP/1.") },
            body = requestBody
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SavedRequestItem

        if (id != other.id) return false
        if (method != other.method) return false
        if (url != other.url) return false
        if (requestHeaders != other.requestHeaders) return false
        if (requestBody != null) {
            if (other.requestBody == null) return false
            if (!requestBody.contentEquals(other.requestBody)) return false
        } else if (other.requestBody != null) return false
        if (statusCode != other.statusCode) return false
        if (responseHeaders != other.responseHeaders) return false
        if (responseBody != null) {
            if (other.responseBody == null) return false
            if (!responseBody.contentEquals(other.responseBody)) return false
        } else if (other.responseBody != null) return false
        if (timestamp != other.timestamp) return false
        if (note != other.note) return false
        if (highlightColor != other.highlightColor) return false
        if (savedAt != other.savedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + (requestBody?.contentHashCode() ?: 0)
        result = 31 * result + (statusCode ?: 0)
        result = 31 * result + responseHeaders.hashCode()
        result = 31 * result + (responseBody?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + note.hashCode()
        result = 31 * result + highlightColor.hashCode()
        result = 31 * result + savedAt.hashCode()
        return result
    }
}

