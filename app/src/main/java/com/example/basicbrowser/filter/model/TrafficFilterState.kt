package com.example.basicbrowser.filter.model

/**
 * Encapsulates all filter criteria for Burp-style traffic filtering in HTTP History and Intercept.
 */
data class TrafficFilterState(
    val scopeFilter: ScopeFilterOption = ScopeFilterOption.SHOW_ALL,
    val searchQuery: String = "",
    val selectedMethods: Set<String> = emptySet(),
    val selectedStatusCodes: Set<String> = emptySet(), // "2xx", "3xx", "4xx", "5xx", or specific codes
    val hideImages: Boolean = false,
    val hideCssAndJs: Boolean = false,
    val contentTypes: Set<ContentTypeCategory> = emptySet(),
    val hiddenExtensions: Set<String> = emptySet(),
    val onlyWithParams: Boolean = false,
    val onlyWithBody: Boolean = false,
    val onlyWithResponse: Boolean = false,
    val hostQuery: String = "",
    val pathQuery: String = "",
    val mimeTypeQuery: String = "",
    val protocolFilter: ProtocolFilter = ProtocolFilter.ALL
) {

    fun isDefault(): Boolean {
        return scopeFilter == ScopeFilterOption.SHOW_ALL &&
                searchQuery.isBlank() &&
                selectedMethods.isEmpty() &&
                selectedStatusCodes.isEmpty() &&
                !hideImages &&
                !hideCssAndJs &&
                contentTypes.isEmpty() &&
                hiddenExtensions.isEmpty() &&
                !onlyWithParams &&
                !onlyWithBody &&
                !onlyWithResponse &&
                hostQuery.isBlank() &&
                pathQuery.isBlank() &&
                mimeTypeQuery.isBlank() &&
                protocolFilter == ProtocolFilter.ALL
    }

    fun activeFilterCount(): Int {
        var count = 0
        if (scopeFilter != ScopeFilterOption.SHOW_ALL) count++
        if (searchQuery.isNotBlank()) count++
        if (selectedMethods.isNotEmpty()) count++
        if (selectedStatusCodes.isNotEmpty()) count++
        if (hideImages) count++
        if (hideCssAndJs) count++
        if (contentTypes.isNotEmpty()) count++
        if (hiddenExtensions.isNotEmpty()) count++
        if (onlyWithParams) count++
        if (onlyWithBody) count++
        if (onlyWithResponse) count++
        if (hostQuery.isNotBlank()) count++
        if (pathQuery.isNotBlank()) count++
        if (mimeTypeQuery.isNotBlank()) count++
        if (protocolFilter != ProtocolFilter.ALL) count++
        return count
    }

    companion object {
        val DEFAULT = TrafficFilterState()

        val COMMON_EXTENSIONS = listOf(
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp",
            "css", "js", "woff", "woff2"
        )
    }
}
