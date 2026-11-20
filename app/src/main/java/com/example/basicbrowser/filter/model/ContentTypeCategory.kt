package com.example.basicbrowser.filter.model

/**
 * Standard content type categories for Burp-style HTTP traffic filtering.
 */
enum class ContentTypeCategory(val displayName: String) {
    HTML("HTML"),
    JSON("JSON"),
    XML("XML"),
    PLAIN_TEXT("Plain Text"),
    IMAGES("Images"),
    CSS("CSS"),
    JAVASCRIPT("Script / JS"),
    FONTS("Fonts"),
    AUDIO("Audio"),
    VIDEO("Video"),
    OTHER_BINARY("Other Binary")
}

