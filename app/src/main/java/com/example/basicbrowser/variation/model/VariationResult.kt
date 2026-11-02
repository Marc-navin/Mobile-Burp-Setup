package com.example.basicbrowser.variation.model

/**
 * Lightweight result metadata for a single executed variation request.
 *
 * NOTE: Full response bodies are intentionally discarded after measuring size
 * to strictly prevent memory exhaustion on mobile devices.
 */
data class VariationResult(
    val index: Int,
    val payload: String,
    val statusCode: Int?,
    val responseSizeBytes: Long,
    val elapsedTimeMs: Long,
    val errorMessage: String? = null,
    val isTruncated: Boolean = false
) {
    val displayPayload: String
        get() = VariationSubstitution.truncateForDisplay(
            payload,
            VariationSubstitution.MAX_DISPLAY_PAYLOAD_LENGTH
        )

    val displayErrorMessage: String?
        get() = errorMessage?.let {
            VariationSubstitution.truncateForDisplay(
                it,
                VariationSubstitution.MAX_ERROR_MESSAGE_LENGTH
            )
        }
}

