package com.example.basicbrowser.variation.model

/**
 * Represents the location of an insertion point within an HTTP request.
 */
enum class MarkerLocation {
    URL,
    HEADER,
    BODY
}

/**
 * Represents a custom insertion point marked with §...§ in a base HTTP request.
 *
 * Each insertion point maintains a unique identifier, its location in the request,
 * and the baseline value extracted between the markers.
 */
data class InsertionPoint(
    val id: String,                  // Unique identity, e.g. "pos_0", "pos_1"
    val index: Int,                 // 0-based position index among all markers
    val location: MarkerLocation,   // URL, HEADER, or BODY
    val headerIndex: Int = -1,      // 0-based index in HttpRequestData.headers if HEADER
    val headerName: String? = null, // Header name (e.g. "Authorization") if HEADER
    val baselineValue: String,      // Baseline value enclosed inside §...§
    val description: String         // Human-readable summary for UI display
)

