package com.example.basicbrowser.variation.model

/**
 * Result metadata for a single executed variation request.
 *
 * Full response bodies are intentionally discarded after measuring size
 * to strictly prevent memory exhaustion on mobile devices.
 */
data class VariationResult(
    val index: Int,
    val payload: String,
    val statusCode: Int?,
    val responseSizeBytes: Long,
    val elapsedTimeMs: Long,
    val errorMessage: String? = null,
    val isTruncated: Boolean = false,
    val attackType: AttackType = AttackType.SNIPER,
    val positionPayloads: Map<String, String> = emptyMap(),
    val rawRequestText: String? = null,
    val activeInsertionPoint: InsertionPoint? = null
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
