package com.example.basicbrowser.scope.model

/**
 * Indicates whether a scope rule includes or excludes targets.
 * In Burp Suite, explicit exclusions always take precedence over inclusions.
 */
enum class ScopeRuleType {
    INCLUDE,
    EXCLUDE
}

