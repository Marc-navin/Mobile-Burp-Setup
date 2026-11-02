package com.example.basicbrowser.variation.model

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.engine.IntruderRequestGenerator
import java.net.URI
import java.net.URL

/**
 * Validated target summary displayed in the authorization confirmation dialog.
 */
data class TargetSummary(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val plannedRequestCount: Int,
    val delayMs: Long,
    val attackType: AttackType = AttackType.SNIPER
)

/**
 * Configuration for an Intruder-style Request Variation run.
 *
 * Supports exactly two attack types:
 * - [AttackType.SNIPER]: 1 shared payload list across all insertion points.
 * - [AttackType.CLUSTER_BOMB]: Independent payload list per insertion point (Cartesian product).
 */
data class VariationConfig(
    val baseRequest: HttpRequestData,
    val attackType: AttackType = AttackType.SNIPER,
    val sniperPayloads: List<String> = emptyList(),
    val clusterBombPayloads: Map<String, List<String>> = emptyMap(),
    val delayMs: Long = DEFAULT_DELAY_MS,
    val maxRequestsOverride: Int? = null
) {
    companion object {
        const val DEFAULT_DELAY_MS = 200L
        const val MIN_DELAY_MS = 0L
        const val MAX_DELAY_MS = 5000L
        const val DEFAULT_MAX_REQUESTS = 500
        const val HARD_CAP_MAX_REQUESTS = 500
        const val MAX_PAYLOADS_COUNT = 500
    }

    /**
     * Backward-compatible secondary constructor for existing tests and call sites.
     */
    constructor(
        baseRequest: HttpRequestData,
        payloads: List<String>,
        delayMs: Long = DEFAULT_DELAY_MS,
        maxRequests: Int = DEFAULT_MAX_REQUESTS
    ) : this(
        baseRequest = baseRequest,
        attackType = AttackType.SNIPER,
        sniperPayloads = payloads,
        clusterBombPayloads = emptyMap(),
        delayMs = delayMs,
        maxRequestsOverride = maxRequests
    )

    /**
     * Backward-compatible accessors for existing tests.
     */
    val payloads: List<String> get() = sniperPayloads
    val maxRequests: Int get() = maxRequestsOverride ?: HARD_CAP_MAX_REQUESTS

    /**
     * Validates configuration and returns the [TargetSummary] if valid.
     * Throws [IllegalArgumentException] on validation failure.
     */
    fun validate(): TargetSummary {
        require(delayMs in MIN_DELAY_MS..MAX_DELAY_MS) {
            "Delay must be between $MIN_DELAY_MS ms and $MAX_DELAY_MS ms."
        }

        val rawUrl = baseRequest.url.trim()
        require(rawUrl.isNotEmpty()) { "Destination URL must not be empty." }

        val withScheme = if (!rawUrl.contains("://")) {
            "http://$rawUrl"
        } else {
            rawUrl
        }

        val authorityPart = withScheme.substringAfter("://").substringBefore("/").substringBefore("?")
        require(!authorityPart.contains(VariationSubstitution.MARKER)) {
            "Destination host and port must be fixed and cannot contain position markers (§)."
        }
        val schemePart = withScheme.substringBefore("://")
        require(!schemePart.contains(VariationSubstitution.MARKER)) {
            "URL scheme cannot contain position markers."
        }

        val uri = try {
            val u = URI(withScheme)
            if (u.host == null) {
                val fallback = URL(withScheme)
                URI(fallback.protocol, fallback.userInfo, fallback.host, fallback.port, fallback.path, fallback.query, fallback.ref)
            } else {
                u
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid target URL format: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Unsupported scheme '${uri.scheme}'. Only HTTP and HTTPS are permitted."
        }
        require(!scheme.contains(VariationSubstitution.MARKER)) {
            "URL scheme cannot contain position markers."
        }

        val host = uri.host
        require(!host.isNullOrBlank()) { "Invalid URL: missing destination host." }
        require(!host.contains(VariationSubstitution.MARKER)) {
            "Destination host must be fixed and cannot contain position markers (§)."
        }

        val port = if (uri.port != -1) {
            uri.port
        } else if (scheme == "https") {
            443
        } else {
            80
        }

        require(port in 1..65535) { "Port must be between 1 and 65535, found $port." }
        require(!withScheme.substringBefore("://").contains(VariationSubstitution.MARKER)) {
            "URL scheme cannot contain position markers."
        }

        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath

        val insertionPoints = IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        val plannedCount: Int

        if (attackType == AttackType.SNIPER) {
            require(sniperPayloads.isNotEmpty()) {
                "Payload list must not be empty."
            }

            if (insertionPoints.isEmpty()) {
                // Backward-compatible fallback for legacy tests where no § was in template
                plannedCount = if (maxRequestsOverride != null) {
                    minOf(sniperPayloads.size, maxRequestsOverride, HARD_CAP_MAX_REQUESTS)
                } else {
                    minOf(sniperPayloads.size, HARD_CAP_MAX_REQUESTS)
                }
            } else {
                val total = if (maxRequestsOverride != null && maxRequestsOverride < insertionPoints.size * sniperPayloads.size) {
                    minOf(insertionPoints.size * sniperPayloads.size, maxRequestsOverride, HARD_CAP_MAX_REQUESTS)
                } else {
                    insertionPoints.size * sniperPayloads.size
                }
                require(total <= HARD_CAP_MAX_REQUESTS) {
                    "Calculated request count ($total) exceeds maximum safety limit of $HARD_CAP_MAX_REQUESTS."
                }
                plannedCount = total
            }
        } else {
            require(insertionPoints.isNotEmpty()) {
                "No insertion points marked (§...§). Please add markers in the URL, headers, or body."
            }
            for (ip in insertionPoints) {
                val pList = clusterBombPayloads[ip.id].orEmpty()
                require(pList.isNotEmpty()) {
                    "Insertion point '${ip.description}' has no payloads assigned."
                }
            }
            val total = IntruderRequestGenerator.calculateTotalRequests(
                attackType = AttackType.CLUSTER_BOMB,
                insertionPoints = insertionPoints,
                sniperPayloads = emptyList(),
                clusterBombPayloads = clusterBombPayloads
            )
            require(total in 1..HARD_CAP_MAX_REQUESTS) {
                if (total > HARD_CAP_MAX_REQUESTS) {
                    "Calculated request count ($total) exceeds maximum safety limit of $HARD_CAP_MAX_REQUESTS."
                } else {
                    "Configuration produces 0 requests."
                }
            }
            plannedCount = total
        }

        return TargetSummary(
            scheme = scheme,
            host = host,
            port = port,
            path = path,
            plannedRequestCount = plannedCount,
            delayMs = delayMs,
            attackType = attackType
        )
    }
}
