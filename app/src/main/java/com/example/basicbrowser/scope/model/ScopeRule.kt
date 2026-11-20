package com.example.basicbrowser.scope.model

import java.util.UUID

/**
 * Definition of a single Target Scope rule.
 *
 * Supports:
 * - Exact domains: "example.com"
 * - Subdomains: "api.example.com"
 * - Wildcards: "*.example.com"
 * - IPv4 addresses: "192.168.1.1"
 * - IPv6 addresses: "2001:db8::1" or "::1"
 * - CIDR ranges: "192.168.1.0/24" or "2001:db8::/32"
 * - Optional scheme restriction: "http" or "https"
 * - Optional port restriction: e.g. 8080
 */
data class ScopeRule(
    val id: String = UUID.randomUUID().toString(),
    val type: ScopeRuleType = ScopeRuleType.INCLUDE,
    val pattern: String,
    val enabled: Boolean = true,
    val scheme: String? = null,
    val port: Int? = null,
    val comment: String = ""
)

