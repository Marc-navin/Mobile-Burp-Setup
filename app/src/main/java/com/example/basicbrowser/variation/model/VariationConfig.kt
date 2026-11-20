package com.example.basicbrowser.variation.model

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.engine.IntruderRequestGenerator
import com.example.basicbrowser.variation.engine.RawHttpConverter
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
    val attackType: AttackType = AttackType.SNIPER,
    val exceedsThreshold: Boolean = false
)

/**
 * Configuration for an Intruder-style Request Variation run.
 *
 * Supports all four standard attack types:
 * - [AttackType.SNIPER]: 1 shared payload set across all insertion points (1 position at a time).
 * - [AttackType.BATTERING_RAM]: 1 shared payload set (all positions replaced simultaneously with same payload).
 * - [AttackType.PITCHFORK]: Independent payload set per insertion point (lockstep advancing, stops at shortest).
 * - [AttackType.CLUSTER_BOMB]: Independent payload set per insertion point (Cartesian product).
 */
data class VariationConfig(
    val baseRequest: HttpRequestData,
    val attackType: AttackType = AttackType.SNIPER,
    val sniperPayloads: List<String> = emptyList(),
    val clusterBombPayloads: Map<String, List<String>> = emptyMap(),
    val delayMs: Long = DEFAULT_DELAY_MS,
    val maxRequestsOverride: Int? = null,
    val rawTemplate: String? = null,
    val batteringRamPayloads: List<String> = emptyList(),
    val pitchforkPayloads: Map<String, List<String>> = emptyMap(),
    val userConfirmedExceedsThreshold: Boolean = false
) {
    companion object {
        const val DEFAULT_DELAY_MS = 200L
        const val MIN_DELAY_MS = 0L
        const val MAX_DELAY_MS = 5000L
        const val DEFAULT_MAX_REQUESTS = 500
        const val HARD_CAP_MAX_REQUESTS = 500
        const val DEFAULT_SAFETY_THRESHOLD = 500
        const val MAX_SAFE_LIMIT = 5000
        const val MAX_PAYLOADS_COUNT = 5000
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
    val payloads: List<String> get() = getSinglePayloadSet()
    val maxRequests: Int get() = maxRequestsOverride ?: HARD_CAP_MAX_REQUESTS

    fun getSinglePayloadSet(): List<String> {
        return when (attackType) {
            AttackType.BATTERING_RAM -> if (batteringRamPayloads.isNotEmpty()) batteringRamPayloads else sniperPayloads
            else -> sniperPayloads
        }
    }

    fun getMultiPayloadSets(): Map<String, List<String>> {
        return when (attackType) {
            AttackType.PITCHFORK -> if (pitchforkPayloads.isNotEmpty()) pitchforkPayloads else clusterBombPayloads
            else -> clusterBombPayloads
        }
    }

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

        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath

        val insertionPoints = if (rawTemplate != null) {
            val markerResult = RawHttpConverter.validateMarkers(rawTemplate)
            markerResult.positions.map { mp ->
                InsertionPoint(
                    id = mp.id,
                    index = mp.index,
                    location = MarkerLocation.BODY,
                    baselineValue = mp.baselineValue,
                    description = "Position #${mp.index + 1} (${mp.contextSnippet})"
                )
            }
        } else {
            IntruderRequestGenerator.parseInsertionPoints(baseRequest)
        }

        val singleSet = getSinglePayloadSet()
        val multiSets = getMultiPayloadSets()

        val plannedCount: Int

        when (attackType) {
            AttackType.SNIPER -> {
                require(singleSet.isNotEmpty()) {
                    "Payload list must not be empty."
                }

                if (insertionPoints.isEmpty()) {
                    // Backward-compatible fallback for legacy tests where no § was in template
                    plannedCount = if (maxRequestsOverride != null) {
                        minOf(singleSet.size, maxRequestsOverride, HARD_CAP_MAX_REQUESTS)
                    } else {
                        minOf(singleSet.size, HARD_CAP_MAX_REQUESTS)
                    }
                } else {
                    val total = if (maxRequestsOverride != null && maxRequestsOverride < insertionPoints.size * singleSet.size) {
                        minOf(insertionPoints.size * singleSet.size, maxRequestsOverride, HARD_CAP_MAX_REQUESTS)
                    } else {
                        val calc = try {
                            Math.multiplyExact(insertionPoints.size.toLong(), singleSet.size.toLong())
                        } catch (_: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                        if (calc > Int.MAX_VALUE) Int.MAX_VALUE else calc.toInt()
                    }
                    plannedCount = total
                }
            }
            AttackType.BATTERING_RAM -> {
                require(insertionPoints.isNotEmpty()) {
                    "No insertion points marked (§...§). Battering Ram requires at least one position."
                }
                require(singleSet.isNotEmpty()) {
                    "Payload list must not be empty."
                }
                plannedCount = if (maxRequestsOverride != null && maxRequestsOverride < singleSet.size) {
                    minOf(singleSet.size, maxRequestsOverride)
                } else {
                    singleSet.size
                }
            }
            AttackType.PITCHFORK -> {
                require(insertionPoints.isNotEmpty()) {
                    "No insertion points marked (§...§). Please add markers in the URL, headers, or body."
                }
                for (ip in insertionPoints) {
                    val pList = multiSets[ip.id].orEmpty()
                    require(pList.isNotEmpty()) {
                        "Insertion point '${ip.description}' has no payloads assigned."
                    }
                }
                val total = IntruderRequestGenerator.calculateTotalRequests(
                    attackType = AttackType.PITCHFORK,
                    insertionPoints = insertionPoints,
                    sniperPayloads = emptyList(),
                    clusterBombPayloads = emptyMap(),
                    pitchforkPayloads = multiSets
                )
                require(total > 0) { "Configuration produces 0 requests." }
                plannedCount = total
            }
            AttackType.CLUSTER_BOMB -> {
                require(insertionPoints.isNotEmpty()) {
                    "No insertion points marked (§...§). Please add markers in the URL, headers, or body."
                }
                for (ip in insertionPoints) {
                    val pList = multiSets[ip.id].orEmpty()
                    require(pList.isNotEmpty()) {
                        "Insertion point '${ip.description}' has no payloads assigned."
                    }
                }
                val total = IntruderRequestGenerator.calculateTotalRequests(
                    attackType = AttackType.CLUSTER_BOMB,
                    insertionPoints = insertionPoints,
                    sniperPayloads = emptyList(),
                    clusterBombPayloads = multiSets
                )
                require(total > 0) { "Configuration produces 0 requests." }
                plannedCount = total
            }
        }

        if (!userConfirmedExceedsThreshold && maxRequestsOverride == null) {
            require(plannedCount <= HARD_CAP_MAX_REQUESTS) {
                "Calculated request count ($plannedCount) exceeds maximum safety limit of $HARD_CAP_MAX_REQUESTS."
            }
        } else {
            require(plannedCount <= MAX_SAFE_LIMIT) {
                "Calculated request count ($plannedCount) exceeds safe application limit of $MAX_SAFE_LIMIT."
            }
        }

        val exceedsThreshold = plannedCount > (maxRequestsOverride ?: DEFAULT_SAFETY_THRESHOLD)

        return TargetSummary(
            scheme = scheme,
            host = host,
            port = port,
            path = path,
            plannedRequestCount = plannedCount,
            delayMs = delayMs,
            attackType = attackType,
            exceedsThreshold = exceedsThreshold
        )
    }
}
