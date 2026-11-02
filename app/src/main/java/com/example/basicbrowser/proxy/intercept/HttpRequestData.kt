package com.example.basicbrowser.proxy.intercept

data class HttpRequestData(
    val method: String,
    val url: String,
    val httpVersion: String = "HTTP/1.1",
    val headers: List<String>,
    val body: ByteArray? = null,
    val isBodyTruncated: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HttpRequestData

        if (method != other.method) return false
        if (url != other.url) return false
        if (httpVersion != other.httpVersion) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        if (isBodyTruncated != other.isBodyTruncated) return false

        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + httpVersion.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + isBodyTruncated.hashCode()
        return result
    }
}

sealed class InterceptAction {
    data class Forward(val request: HttpRequestData) : InterceptAction()
    object Drop : InterceptAction()
}
