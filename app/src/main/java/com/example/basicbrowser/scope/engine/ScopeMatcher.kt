package com.example.basicbrowser.scope.engine

import com.example.basicbrowser.scope.model.ScopeRule
import com.example.basicbrowser.scope.model.ScopeRuleType
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Result of evaluating a target against scope rules.
 */
data class ScopeEvaluation(
    val isInScope: Boolean,
    val matchedRule: ScopeRule? = null,
    val reason: String = ""
)

/**
 * Centralized matching engine for Target Scope rules.
 *
 * Evaluation Order:
 * 1. Filter enabled rules.
 * 2. Check EXCLUDE rules. If any match, the target is immediately OUT OF SCOPE.
 * 3. Check INCLUDE rules.
 *    - If there are NO enabled INCLUDE rules, default to UNRESTRICTED scope (IN SCOPE).
 *    - If there ARE enabled INCLUDE rules, target is IN SCOPE if and only if at least one matches.
 */
object ScopeMatcher {

    /**
     * Evaluates a target URL against a list of scope rules.
     */
    fun evaluate(url: String, rules: List<ScopeRule>): ScopeEvaluation {
        val target = parseTarget(url) ?: return ScopeEvaluation(
            isInScope = true,
            reason = "Malformed URL; defaulted to in-scope"
        )
        return evaluate(target.scheme, target.host, target.port, rules)
    }

    /**
     * Evaluates target coordinates (scheme, host, port) against a list of scope rules.
     */
    fun evaluate(
        scheme: String?,
        host: String,
        port: Int,
        rules: List<ScopeRule>
    ): ScopeEvaluation {
        val enabledRules = rules.filter { it.enabled }
        val normalizedHost = normalizeHost(host)
        val normalizedScheme = scheme?.lowercase(Locale.ROOT)
        val effectivePort = if (port > 0) port else defaultPortForScheme(normalizedScheme)

        // 1. Check EXCLUDE rules first (exclusions take precedence)
        val excludeRules = enabledRules.filter { it.type == ScopeRuleType.EXCLUDE }
        for (rule in excludeRules) {
            if (matchesRule(normalizedScheme, normalizedHost, effectivePort, rule)) {
                return ScopeEvaluation(
                    isInScope = false,
                    matchedRule = rule,
                    reason = "Excluded by rule: '${rule.pattern}'"
                )
            }
        }

        // 2. Check INCLUDE rules
        val includeRules = enabledRules.filter { it.type == ScopeRuleType.INCLUDE }
        if (includeRules.isEmpty()) {
            // Default to unrestricted scope when no include rules are configured
            return ScopeEvaluation(
                isInScope = true,
                reason = "No include rules configured (unrestricted scope)"
            )
        }

        for (rule in includeRules) {
            if (matchesRule(normalizedScheme, normalizedHost, effectivePort, rule)) {
                return ScopeEvaluation(
                    isInScope = true,
                    matchedRule = rule,
                    reason = "Included by rule: '${rule.pattern}'"
                )
            }
        }

        return ScopeEvaluation(
            isInScope = false,
            reason = "Target does not match any configured include rule"
        )
    }

    /**
     * Checks if target (scheme, host, port) matches a single [ScopeRule].
     */
    fun matchesRule(
        targetScheme: String?,
        targetHost: String,
        targetPort: Int,
        rule: ScopeRule
    ): Boolean {
        // 1. Check scheme restriction if specified
        if (!rule.scheme.isNullOrBlank()) {
            val ruleScheme = rule.scheme.trim().lowercase(Locale.ROOT)
            if (targetScheme != null && targetScheme != ruleScheme) {
                return false
            }
        }

        // 2. Check port restriction if specified
        if (rule.port != null && rule.port > 0) {
            if (targetPort > 0 && targetPort != rule.port) {
                return false
            }
        }

        // 3. Match host / IP / CIDR pattern
        val pattern = rule.pattern.trim()
        if (pattern.isEmpty()) return false

        // CIDR match (e.g. 192.168.1.0/24 or 2001:db8::/32)
        if (CidrUtils.isCidr(pattern)) {
            return CidrUtils.matchesCidr(targetHost, pattern)
        }

        // IP literal match
        if (CidrUtils.isIpAddress(pattern)) {
            return if (CidrUtils.isIpAddress(targetHost)) {
                CidrUtils.ipEquals(targetHost, pattern)
            } else {
                false
            }
        }

        // Domain matching (Exact, Subdomain, or Wildcard)
        val normalizedPattern = normalizeHost(pattern)

        // Wildcard domain: "*.example.com" or "*example.com"
        if (normalizedPattern.startsWith("*.")) {
            val suffix = normalizedPattern.removePrefix("*.")
            // Matches "example.com" (apex) and any "*.example.com" (subdomains)
            return targetHost == suffix || targetHost.endsWith(".$suffix")
        } else if (normalizedPattern.startsWith("*")) {
            val suffix = normalizedPattern.removePrefix("*")
            return targetHost.endsWith(suffix)
        }

        // Exact domain or subdomain: "example.com" or "api.example.com"
        // Strict equality prevents "example.com" from matching "notexample.com"
        return targetHost == normalizedPattern
    }

    /**
     * Normalizes a host string to lowercase ASCII (handling IDN / Punycode and bracketed IPv6).
     */
    fun normalizeHost(host: String): String {
        var clean = host.trim().lowercase(Locale.ROOT)
        // Strip scheme or path if user accidentally entered full URL into pattern
        if (clean.contains("://")) {
            clean = clean.substringAfter("://").substringBefore("/").substringBefore(":")
        }
        if (clean.contains("/")) {
            clean = clean.substringBefore("/")
        }
        // Retain IPv6 brackets removal for IP check
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.removeSurrounding("[", "]")
        }
        // Normalize Internationalized Domain Names (IDN) to punycode ASCII
        return try {
            if (!CidrUtils.isIpAddress(clean) && !CidrUtils.isCidr(clean) && !clean.startsWith("*")) {
                IDN.toASCII(clean)
            } else if (clean.startsWith("*.")) {
                val apex = clean.removePrefix("*.")
                "*." + IDN.toASCII(apex)
            } else {
                clean
            }
        } catch (_: Exception) {
            clean
        }
    }

    private fun defaultPortForScheme(scheme: String?): Int {
        return when (scheme?.lowercase(Locale.ROOT)) {
            "https" -> 443
            "http" -> 80
            else -> 80
        }
    }

    private data class ParsedTarget(val scheme: String?, val host: String, val port: Int)

    private fun parseTarget(url: String): ParsedTarget? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        val scheme = if (trimmed.contains("://")) {
            trimmed.substringBefore("://").lowercase(Locale.ROOT)
        } else {
            null
        }

        val afterScheme = if (trimmed.contains("://")) {
            trimmed.substringAfter("://")
        } else {
            trimmed
        }

        val hostAndPort = afterScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        if (hostAndPort.isEmpty()) return null

        val (rawHost, port) = if (hostAndPort.startsWith("[") && hostAndPort.contains("]")) {
            // IPv6 literal
            val hostPart = hostAndPort.substringAfter("[").substringBefore("]")
            val remainder = hostAndPort.substringAfter("]", "")
            val portPart = if (remainder.startsWith(":")) remainder.removePrefix(":").toIntOrNull() else null
            Pair(hostPart, portPart ?: defaultPortForScheme(scheme))
        } else if (hostAndPort.contains(":")) {
            val h = hostAndPort.substringBefore(":")
            val p = hostAndPort.substringAfter(":").toIntOrNull() ?: defaultPortForScheme(scheme)
            Pair(h, p)
        } else {
            Pair(hostAndPort, defaultPortForScheme(scheme))
        }

        if (rawHost.isEmpty()) return null
        return ParsedTarget(scheme, rawHost, port)
    }
}

