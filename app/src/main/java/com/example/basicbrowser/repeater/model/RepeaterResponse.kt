package com.example.basicbrowser.repeater.model

data class RepeaterResponse(
    val statusCode: Int?,
    val statusLine: String,
    val headers: List<String>,
    val body: ByteArray? = null,
    val isBodyTruncated: Boolean = false,
    val elapsedTimeMs: Long = 0L,
    val responseSizeBytes: Long = 0L,
    val isBinary: Boolean = false,
    val binarySummary: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RepeaterResponse

        if (statusCode != other.statusCode) return false
        if (statusLine != other.statusLine) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        if (isBodyTruncated != other.isBodyTruncated) return false
        if (elapsedTimeMs != other.elapsedTimeMs) return false
        if (responseSizeBytes != other.responseSizeBytes) return false
        if (isBinary != other.isBinary) return false
        if (binarySummary != other.binarySummary) return false

        return true
    }

    override fun hashCode(): Int {
        var result = statusCode ?: 0
        result = 31 * result + statusLine.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + isBodyTruncated.hashCode()
        result = 31 * result + elapsedTimeMs.hashCode()
        result = 31 * result + responseSizeBytes.hashCode()
        result = 31 * result + isBinary.hashCode()
        result = 31 * result + binarySummary.hashCode()
        return result
    }
}

