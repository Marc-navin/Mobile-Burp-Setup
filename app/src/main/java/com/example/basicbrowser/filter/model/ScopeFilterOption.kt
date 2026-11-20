package com.example.basicbrowser.filter.model

enum class ScopeFilterOption(val displayName: String) {
    SHOW_ALL("Show all"),
    SHOW_ONLY_IN_SCOPE("Show only in-scope"),
    HIDE_OUT_OF_SCOPE("Hide out-of-scope")
}

enum class ProtocolFilter(val displayName: String) {
    ALL("All"),
    HTTP_ONLY("HTTP only"),
    HTTPS_ONLY("HTTPS only")
}

