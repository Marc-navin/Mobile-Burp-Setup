package com.example.basicbrowser.filter.model

/**
 * Filter settings specifically for the Intercept module.
 *
 * Requests excluded by interception filters are automatically forwarded unchanged
 * to their upstream destination without pausing or consuming queue capacity.
 */
data class InterceptFilterSettings(
    val interceptOnlyInScope: Boolean = false,
    val filterImages: Boolean = false,
    val filterCss: Boolean = false,
    val filterJs: Boolean = false,
    val filterOtherResources: Boolean = false
) {
    fun hasActiveFilters(): Boolean {
        return interceptOnlyInScope || filterImages || filterCss || filterJs || filterOtherResources
    }

    fun activeFilterCount(): Int {
        var count = 0
        if (interceptOnlyInScope) count++
        if (filterImages) count++
        if (filterCss) count++
        if (filterJs) count++
        if (filterOtherResources) count++
        return count
    }
}

