package com.example.basicbrowser.data.model

enum class HighlightColor(val colorValue: Long, val displayName: String) {
    NONE(0x00000000, "None"),
    RED(0x33EF4444, "Red"),
    ORANGE(0x33F97316, "Orange"),
    YELLOW(0x33EAB308, "Yellow"),
    GREEN(0x3322C55E, "Green"),
    BLUE(0x333B82F6, "Blue"),
    PURPLE(0x33A855F7, "Purple"),
    GRAY(0x339CA3AF, "Gray");

    companion object {
        fun fromString(name: String?): HighlightColor {
            if (name == null) return NONE
            return values().find { it.name.equals(name, ignoreCase = true) } ?: NONE
        }
    }
}

data class RequestAnnotation(
    val id: String,
    val note: String = "",
    val highlightColor: HighlightColor = HighlightColor.NONE,
    val isSaved: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

