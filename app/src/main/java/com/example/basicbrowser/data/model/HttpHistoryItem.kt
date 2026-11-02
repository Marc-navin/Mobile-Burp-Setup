package com.example.basicbrowser.data.model

import java.util.UUID

data class HttpHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val method: String,
    val url: String,
    val requestHeaders: List<String>,
    val requestBody: ByteArray? = null,
    val requestBodyTruncated: Boolean = false,
    
    val statusCode: Int? = null,
    val responseHeaders: List<String> = emptyList(),
    val responseBody: ByteArray? = null,
    val responseBodyTruncated: Boolean = false,
    
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HttpHistoryItem

        if (id != other.id) return false
        if (method != other.method) return false
        if (url != other.url) return false
        if (requestHeaders != other.requestHeaders) return false
        if (requestBody != null) {
            if (other.requestBody == null) return false
            if (!requestBody.contentEquals(other.requestBody)) return false
        } else if (other.requestBody != null) return false
        if (requestBodyTruncated != other.requestBodyTruncated) return false
        if (statusCode != other.statusCode) return false
        if (responseHeaders != other.responseHeaders) return false
        if (responseBody != null) {
            if (other.responseBody == null) return false
            if (!responseBody.contentEquals(other.responseBody)) return false
        } else if (other.responseBody != null) return false
        if (responseBodyTruncated != other.responseBodyTruncated) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + (requestBody?.contentHashCode() ?: 0)
        result = 31 * result + requestBodyTruncated.hashCode()
        result = 31 * result + (statusCode ?: 0)
        result = 31 * result + responseHeaders.hashCode()
        result = 31 * result + (responseBody?.contentHashCode() ?: 0)
        result = 31 * result + responseBodyTruncated.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
