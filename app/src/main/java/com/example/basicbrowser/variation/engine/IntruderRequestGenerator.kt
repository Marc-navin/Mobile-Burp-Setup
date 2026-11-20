package com.example.basicbrowser.variation.engine

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.model.AttackType
import com.example.basicbrowser.variation.model.GeneratedVariationRequest
import com.example.basicbrowser.variation.model.InsertionPoint
import com.example.basicbrowser.variation.model.MarkerLocation
import com.example.basicbrowser.variation.model.VariationConfig
import com.example.basicbrowser.variation.model.VariationSubstitution
import java.nio.charset.StandardCharsets

/**
 * Dedicated, testable request generation component for Intruder-style testing.
 *
 * Responsible for:
 * - Parsing custom insertion points (§...§) in URL, headers, and body.
 * - Calculating exact request counts for Sniper and Cluster Bomb attacks.
 * - Enforcing the 500-request maximum safety limit.
 * - Generating Sniper requests (1 shared list, 1 point tested at a time, others keep baseline).
 * - Generating Cluster Bomb requests (Cartesian product across independent payload lists).
 * - Safe marker removal, addition, and baseline extraction.
 */
object IntruderRequestGenerator {

    const val MARKER = VariationSubstitution.MARKER // "§"
    const val HARD_CAP_MAX_REQUESTS = VariationConfig.HARD_CAP_MAX_REQUESTS // 500

    /**
     * Parses all marker pairs (§...§) in the base HTTP request (URL, headers, and body).
     *
     * Returns a list of [InsertionPoint] instances in the exact order of appearance.
     * Missing or unclosed markers are safely treated as literal text.
     */
    fun parseInsertionPoints(baseRequest: HttpRequestData): List<InsertionPoint> {
        val points = ArrayList<InsertionPoint>()
        var globalIndex = 0

        // 1. Scan URL
        val urlPairs = extractMarkerPairs(baseRequest.url)
        for (pair in urlPairs) {
            points.add(
                InsertionPoint(
                    id = "pos_$globalIndex",
                    index = globalIndex,
                    location = MarkerLocation.URL,
                    baselineValue = pair.baselineValue,
                    description = "Position #${globalIndex + 1} (URL: \"${pair.baselineValue}\")"
                )
            )
            globalIndex++
        }

        // 2. Scan Headers
        for (hIdx in baseRequest.headers.indices) {
            val header = baseRequest.headers[hIdx]
            val headerName = header.substringBefore(":").trim()
            val headerPairs = extractMarkerPairs(header)
            for (pair in headerPairs) {
                points.add(
                    InsertionPoint(
                        id = "pos_$globalIndex",
                        index = globalIndex,
                        location = MarkerLocation.HEADER,
                        headerIndex = hIdx,
                        headerName = headerName,
                        baselineValue = pair.baselineValue,
                        description = "Position #${globalIndex + 1} (Header $headerName: \"${pair.baselineValue}\")"
                    )
                )
                globalIndex++
            }
        }

        // 3. Scan Body
        if (baseRequest.body != null && baseRequest.body.isNotEmpty()) {
            val bodyStr = String(baseRequest.body, StandardCharsets.UTF_8)
            val bodyPairs = extractMarkerPairs(bodyStr)
            for (pair in bodyPairs) {
                points.add(
                    InsertionPoint(
                        id = "pos_$globalIndex",
                        index = globalIndex,
                        location = MarkerLocation.BODY,
                        baselineValue = pair.baselineValue,
                        description = "Position #${globalIndex + 1} (Body: \"${pair.baselineValue}\")"
                    )
                )
                globalIndex++
            }
        }

        return points
    }

    /**
     * Calculates the exact number of requests that will be generated.
     * Returns 0 if configuration is incomplete or produces no requests.
     */
    fun calculateTotalRequests(
        attackType: AttackType,
        insertionPoints: List<InsertionPoint>,
        sniperPayloads: List<String>,
        clusterBombPayloads: Map<String, List<String>>,
        batteringRamPayloads: List<String> = emptyList(),
        pitchforkPayloads: Map<String, List<String>> = emptyMap()
    ): Int {
        if (insertionPoints.isEmpty()) return 0

        return when (attackType) {
            AttackType.SNIPER -> {
                if (sniperPayloads.isEmpty()) 0 else {
                    val total = try {
                        Math.multiplyExact(insertionPoints.size.toLong(), sniperPayloads.size.toLong())
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                    if (total > HARD_CAP_MAX_REQUESTS) {
                        return if (total > Int.MAX_VALUE) Int.MAX_VALUE else total.toInt()
                    }
                    total.toInt()
                }
            }
            AttackType.BATTERING_RAM -> {
                val payloads = if (batteringRamPayloads.isNotEmpty()) batteringRamPayloads else sniperPayloads
                payloads.size
            }
            AttackType.PITCHFORK -> {
                val pMap = if (pitchforkPayloads.isNotEmpty()) pitchforkPayloads else clusterBombPayloads
                var minSize = Int.MAX_VALUE
                for (ip in insertionPoints) {
                    val count = pMap[ip.id]?.size ?: 0
                    if (count == 0) return 0
                    if (count < minSize) {
                        minSize = count
                    }
                }
                if (minSize == Int.MAX_VALUE) 0 else minSize
            }
            AttackType.CLUSTER_BOMB -> {
                val pMap = if (clusterBombPayloads.isNotEmpty()) clusterBombPayloads else pitchforkPayloads
                var total = 1L
                for (ip in insertionPoints) {
                    val count = pMap[ip.id]?.size?.toLong() ?: 0L
                    if (count == 0L) return 0
                    total = try {
                        Math.multiplyExact(total, count)
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                }
                if (total > Int.MAX_VALUE) Int.MAX_VALUE else total.toInt()
            }
        }
    }

    /**
     * Generates the complete, deterministic list of [GeneratedVariationRequest]s.
     *
     * Enforces the 500-request safety limit unless explicitly confirmed or configured. Never silently truncates requests.
     */
    fun generateRequests(config: VariationConfig): List<GeneratedVariationRequest> {
        val insertionPoints = if (config.rawTemplate != null) {
            val markerResult = RawHttpConverter.validateMarkers(config.rawTemplate)
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
            parseInsertionPoints(config.baseRequest)
        }

        if (insertionPoints.isEmpty()) {
            if ((config.attackType == AttackType.SNIPER || config.attackType == AttackType.BATTERING_RAM) && config.getSinglePayloadSet().isNotEmpty()) {
                val payloads = config.getSinglePayloadSet()
                val total = if (config.maxRequestsOverride != null) {
                    minOf(payloads.size, config.maxRequestsOverride, HARD_CAP_MAX_REQUESTS)
                } else {
                    minOf(payloads.size, HARD_CAP_MAX_REQUESTS)
                }
                return (0 until total).map { i ->
                    val payload = payloads[i]
                    GeneratedVariationRequest(
                        index = i + 1,
                        request = config.baseRequest,
                        payloadSummary = payload,
                        attackType = config.attackType
                    )
                }
            }
            throw IllegalArgumentException("No insertion points marked (§...§). Please add markers in the URL, headers, or body.")
        }

        val singleSet = config.getSinglePayloadSet()
        val multiSets = config.getMultiPayloadSets()

        val total = calculateTotalRequests(
            attackType = config.attackType,
            insertionPoints = insertionPoints,
            sniperPayloads = singleSet,
            clusterBombPayloads = multiSets,
            batteringRamPayloads = singleSet,
            pitchforkPayloads = multiSets
        )

        require(total > 0) { "Configuration produces 0 requests. Ensure all required payload lists are populated." }

        if (!config.userConfirmedExceedsThreshold && config.maxRequestsOverride == null) {
            require(total <= HARD_CAP_MAX_REQUESTS) {
                "Calculated request count ($total) exceeds maximum safety limit of $HARD_CAP_MAX_REQUESTS. Reduce payloads or positions."
            }
        } else {
            require(total <= VariationConfig.MAX_SAFE_LIMIT) {
                "Calculated request count ($total) exceeds application maximum limit of ${VariationConfig.MAX_SAFE_LIMIT}."
            }
        }

        val generated = when (config.attackType) {
            AttackType.SNIPER -> generateSniperRequests(config.baseRequest, insertionPoints, singleSet, config.rawTemplate)
            AttackType.BATTERING_RAM -> generateBatteringRamRequests(config.baseRequest, insertionPoints, singleSet, config.rawTemplate)
            AttackType.PITCHFORK -> generatePitchforkRequests(config.baseRequest, insertionPoints, multiSets, config.rawTemplate)
            AttackType.CLUSTER_BOMB -> generateClusterBombRequests(config.baseRequest, insertionPoints, multiSets, config.rawTemplate)
        }

        return if (config.maxRequestsOverride != null && config.maxRequestsOverride < generated.size) {
            generated.take(config.maxRequestsOverride)
        } else {
            generated
        }
    }

    /**
     * Generates Sniper attack requests.
     */
    private fun generateSniperRequests(
        baseRequest: HttpRequestData,
        insertionPoints: List<InsertionPoint>,
        payloads: List<String>,
        rawTemplate: String? = null
    ): List<GeneratedVariationRequest> {
        require(payloads.isNotEmpty()) { "Sniper attack requires at least one payload." }
        val total = insertionPoints.size * payloads.size
        val requests = ArrayList<GeneratedVariationRequest>(total)
        var reqIndex = 1

        for (activeIp in insertionPoints) {
            for (payload in payloads) {
                val values = insertionPoints.map { ip ->
                    if (ip.index == activeIp.index) payload else ip.baselineValue
                }

                val substitutedRequest = if (rawTemplate != null) {
                    val subRaw = RawHttpConverter.substituteRawMarkers(rawTemplate, values)
                    val parsed = RawHttpConverter.parseRawHttp(subRaw)
                    if (parsed.isValid) RawHttpConverter.toHttpRequestData(parsed) else substituteValues(baseRequest, values)
                } else {
                    substituteValues(baseRequest, values)
                }

                val summary = if (insertionPoints.size > 1) {
                    "${VariationSubstitution.truncateForDisplay(payload, 30)} (Pos #${activeIp.index + 1})"
                } else {
                    VariationSubstitution.truncateForDisplay(payload, 40)
                }

                val posMap = insertionPoints.associate { ip ->
                    val v = if (ip.index == activeIp.index) payload else "${ip.baselineValue} (baseline)"
                    ip.description to v
                }
                val rawReq = if (rawTemplate != null) {
                    RawHttpConverter.substituteRawMarkers(rawTemplate, values)
                } else {
                    RawHttpConverter.serializeToRawHttp(substitutedRequest)
                }

                requests.add(
                    GeneratedVariationRequest(
                        index = reqIndex++,
                        request = substitutedRequest,
                        payloadSummary = summary,
                        attackType = AttackType.SNIPER,
                        activeInsertionPoint = activeIp,
                        positionPayloads = posMap,
                        rawRequestText = rawReq
                    )
                )
            }
        }

        return requests
    }

    /**
     * Generates Battering Ram attack requests.
     *
     * One shared payload set. The exact same payload value is inserted simultaneously into all marked positions.
     * Total = Number of Payloads.
     */
    private fun generateBatteringRamRequests(
        baseRequest: HttpRequestData,
        insertionPoints: List<InsertionPoint>,
        payloads: List<String>,
        rawTemplate: String? = null
    ): List<GeneratedVariationRequest> {
        require(payloads.isNotEmpty()) { "Battering Ram attack requires at least one payload." }
        val requests = ArrayList<GeneratedVariationRequest>(payloads.size)
        var reqIndex = 1

        for (payload in payloads) {
            val values = insertionPoints.map { payload }

            val substitutedRequest = if (rawTemplate != null) {
                val subRaw = RawHttpConverter.substituteRawMarkers(rawTemplate, values)
                val parsed = RawHttpConverter.parseRawHttp(subRaw)
                if (parsed.isValid) RawHttpConverter.toHttpRequestData(parsed) else substituteValues(baseRequest, values)
            } else {
                substituteValues(baseRequest, values)
            }

            val summary = VariationSubstitution.truncateForDisplay(payload, 40)

            val posMap = insertionPoints.associate { ip ->
                ip.description to payload
            }
            val rawReq = if (rawTemplate != null) {
                RawHttpConverter.substituteRawMarkers(rawTemplate, values)
            } else {
                RawHttpConverter.serializeToRawHttp(substitutedRequest)
            }

            requests.add(
                GeneratedVariationRequest(
                    index = reqIndex++,
                    request = substitutedRequest,
                    payloadSummary = summary,
                    attackType = AttackType.BATTERING_RAM,
                    positionPayloads = posMap,
                    rawRequestText = rawReq
                )
            )
        }

        return requests
    }

    /**
     * Generates Pitchfork attack requests.
     *
     * One payload set per position. Sets advance in lockstep, stopping at the shortest set.
     * Total = min(count_1, count_2, ..., count_N).
     */
    private fun generatePitchforkRequests(
        baseRequest: HttpRequestData,
        insertionPoints: List<InsertionPoint>,
        payloadMap: Map<String, List<String>>,
        rawTemplate: String? = null
    ): List<GeneratedVariationRequest> {
        val lists = insertionPoints.map { ip ->
            val pList = payloadMap[ip.id].orEmpty()
            require(pList.isNotEmpty()) {
                "Insertion point '${ip.description}' has no payloads assigned."
            }
            pList
        }

        val minLen = lists.minOfOrNull { it.size } ?: 0
        require(minLen > 0) { "Pitchfork attack requires all position payload sets to be non-empty." }

        val requests = ArrayList<GeneratedVariationRequest>(minLen)
        var reqIndex = 1

        for (i in 0 until minLen) {
            val values = lists.map { it[i] }

            val substitutedRequest = if (rawTemplate != null) {
                val subRaw = RawHttpConverter.substituteRawMarkers(rawTemplate, values)
                val parsed = RawHttpConverter.parseRawHttp(subRaw)
                if (parsed.isValid) RawHttpConverter.toHttpRequestData(parsed) else substituteValues(baseRequest, values)
            } else {
                substituteValues(baseRequest, values)
            }

            val summary = values.joinToString(" / ") {
                VariationSubstitution.truncateForDisplay(it, 15)
            }

            val posMap = insertionPoints.mapIndexed { idx, ip ->
                ip.description to values[idx]
            }.toMap()
            val rawReq = if (rawTemplate != null) {
                RawHttpConverter.substituteRawMarkers(rawTemplate, values)
            } else {
                RawHttpConverter.serializeToRawHttp(substitutedRequest)
            }

            requests.add(
                GeneratedVariationRequest(
                    index = reqIndex++,
                    request = substitutedRequest,
                    payloadSummary = summary,
                    attackType = AttackType.PITCHFORK,
                    positionPayloads = posMap,
                    rawRequestText = rawReq
                )
            )
        }

        return requests
    }

    /**
     * Generates Cluster Bomb attack requests.
     *
     * Every insertion point has its own independent payload list.
     * Generates every combination (Cartesian product) across all insertion points.
     *
     * Total = PayloadCount1 * PayloadCount2 * ... * PayloadCountN.
     */
    private fun generateClusterBombRequests(
        baseRequest: HttpRequestData,
        insertionPoints: List<InsertionPoint>,
        payloadMap: Map<String, List<String>>,
        rawTemplate: String? = null
    ): List<GeneratedVariationRequest> {
        val lists = insertionPoints.map { ip ->
            val pList = payloadMap[ip.id].orEmpty()
            require(pList.isNotEmpty()) {
                "Insertion point '${ip.description}' has no payloads assigned."
            }
            pList
        }

        val combinations = cartesianProduct(lists)
        val requests = ArrayList<GeneratedVariationRequest>(combinations.size)
        var reqIndex = 1

        for (tuple in combinations) {
            val substitutedRequest = if (rawTemplate != null) {
                val subRaw = RawHttpConverter.substituteRawMarkers(rawTemplate, tuple)
                val parsed = RawHttpConverter.parseRawHttp(subRaw)
                if (parsed.isValid) RawHttpConverter.toHttpRequestData(parsed) else substituteValues(baseRequest, tuple)
            } else {
                substituteValues(baseRequest, tuple)
            }

            val summary = tuple.joinToString(" / ") {
                VariationSubstitution.truncateForDisplay(it, 15)
            }

            val posMap = insertionPoints.mapIndexed { idx, ip ->
                ip.description to tuple[idx]
            }.toMap()
            val rawReq = if (rawTemplate != null) {
                RawHttpConverter.substituteRawMarkers(rawTemplate, tuple)
            } else {
                RawHttpConverter.serializeToRawHttp(substitutedRequest)
            }

            requests.add(
                GeneratedVariationRequest(
                    index = reqIndex++,
                    request = substitutedRequest,
                    payloadSummary = summary,
                    attackType = AttackType.CLUSTER_BOMB,
                    positionPayloads = posMap,
                    rawRequestText = rawReq
                )
            )
        }

        return requests
    }

    /**
     * Computes the Cartesian product of a list of lists.
     */
    fun cartesianProduct(lists: List<List<String>>): List<List<String>> {
        if (lists.isEmpty()) return emptyList()
        var result: List<List<String>> = listOf(emptyList())
        for (list in lists) {
            val next = ArrayList<List<String>>(result.size * list.size)
            for (existing in result) {
                for (item in list) {
                    next.add(existing + item)
                }
            }
            result = next
        }
        return result
    }

    /**
     * Substitutes a complete value vector into the base request.
     *
     * Each marker pair (§...§) in URL, headers, and body is replaced by values[i]
     * in the sequential order of appearance.
     */
    fun substituteValues(baseRequest: HttpRequestData, values: List<String>): HttpRequestData {
        var valueIndex = 0

        // 1. Substitute in URL
        val urlPairCount = countMarkerPairs(baseRequest.url)
        val urlValues = values.subList(valueIndex, valueIndex + urlPairCount)
        val newUrl = replaceSequentialMarkers(baseRequest.url, urlValues)
        valueIndex += urlPairCount

        // 2. Substitute in Headers
        val newHeaders = ArrayList<String>(baseRequest.headers.size)
        for (header in baseRequest.headers) {
            val hPairCount = countMarkerPairs(header)
            val hValues = values.subList(valueIndex, valueIndex + hPairCount)
            newHeaders.add(replaceSequentialMarkers(header, hValues))
            valueIndex += hPairCount
        }

        // 3. Substitute in Body
        val newBody = if (baseRequest.body != null && baseRequest.body.isNotEmpty()) {
            val bodyStr = String(baseRequest.body, StandardCharsets.UTF_8)
            val bodyPairCount = countMarkerPairs(bodyStr)
            val bodyValues = values.subList(valueIndex, valueIndex + bodyPairCount)
            val substitutedBody = replaceSequentialMarkers(bodyStr, bodyValues)
            valueIndex += bodyPairCount
            substitutedBody.toByteArray(StandardCharsets.UTF_8)
        } else {
            baseRequest.body
        }

        return baseRequest.copy(
            url = newUrl,
            headers = newHeaders,
            body = newBody
        )
    }

    /**
     * Strips markers (§...§) from around the target insertion point index,
     * retaining its baseline value as literal text.
     */
    fun removeMarker(baseRequest: HttpRequestData, targetIndex: Int): HttpRequestData {
        var currentIndex = 0

        val urlPairCount = countMarkerPairs(baseRequest.url)
        val newUrl = if (targetIndex in currentIndex until (currentIndex + urlPairCount)) {
            removeMarkerAtRelativeIndex(baseRequest.url, targetIndex - currentIndex)
        } else {
            baseRequest.url
        }
        currentIndex += urlPairCount

        val newHeaders = ArrayList<String>(baseRequest.headers.size)
        for (header in baseRequest.headers) {
            val hPairCount = countMarkerPairs(header)
            val newHeader = if (targetIndex in currentIndex until (currentIndex + hPairCount)) {
                removeMarkerAtRelativeIndex(header, targetIndex - currentIndex)
            } else {
                header
            }
            newHeaders.add(newHeader)
            currentIndex += hPairCount
        }

        val newBody = if (baseRequest.body != null && baseRequest.body.isNotEmpty()) {
            val bodyStr = String(baseRequest.body, StandardCharsets.UTF_8)
            val bodyPairCount = countMarkerPairs(bodyStr)
            val substituted = if (targetIndex in currentIndex until (currentIndex + bodyPairCount)) {
                removeMarkerAtRelativeIndex(bodyStr, targetIndex - currentIndex)
            } else {
                bodyStr
            }
            substituted.toByteArray(StandardCharsets.UTF_8)
        } else {
            baseRequest.body
        }

        return baseRequest.copy(
            url = newUrl,
            headers = newHeaders,
            body = newBody
        )
    }

    /**
     * Clears all § markers from URL, headers, and body, leaving baseline text literal.
     */
    fun clearAllMarkers(baseRequest: HttpRequestData): HttpRequestData {
        val newUrl = baseRequest.url.replace(MARKER, "")
        val newHeaders = baseRequest.headers.map { it.replace(MARKER, "") }
        val newBody = if (baseRequest.body != null && baseRequest.body.isNotEmpty()) {
            val bodyStr = String(baseRequest.body, StandardCharsets.UTF_8).replace(MARKER, "")
            bodyStr.toByteArray(StandardCharsets.UTF_8)
        } else {
            baseRequest.body
        }
        return baseRequest.copy(
            url = newUrl,
            headers = newHeaders,
            body = newBody
        )
    }

    /**
     * Wraps the selected text range [start, end] in [text] with §...§ markers.
     */
    fun addMarkerAroundText(text: String, start: Int, end: Int): String {
        if (start < 0 || end > text.length || start > end) return text
        val prefix = text.substring(0, start)
        val selected = text.substring(start, end)
        val suffix = text.substring(end)
        return "$prefix$MARKER$selected$MARKER$suffix"
    }

    // --- Internal Helpers ---

    private data class MarkerPair(val baselineValue: String, val startIndex: Int, val endIndex: Int)

    private fun extractMarkerPairs(text: String): List<MarkerPair> {
        val pairs = ArrayList<MarkerPair>()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(MARKER, i)
            if (start == -1) break
            val end = text.indexOf(MARKER, start + 1)
            if (end == -1) break
            val baseline = text.substring(start + 1, end)
            pairs.add(MarkerPair(baseline, start, end))
            i = end + 1
        }
        return pairs
    }

    fun countMarkerPairs(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(MARKER, i)
            if (start == -1) break
            val end = text.indexOf(MARKER, start + 1)
            if (end == -1) break
            count++
            i = end + 1
        }
        return count
    }

    private fun replaceSequentialMarkers(text: String, values: List<String>): String {
        if (!text.contains(MARKER) || values.isEmpty()) return text
        val sb = StringBuilder()
        var valIdx = 0
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(MARKER, i)
            if (start == -1) {
                sb.append(text.substring(i))
                break
            }
            sb.append(text.substring(i, start))
            val end = text.indexOf(MARKER, start + 1)
            if (end == -1) {
                sb.append(text.substring(start))
                break
            }
            if (valIdx < values.size) {
                val rawVal = values[valIdx++]
                // Safe payload length clamp
                val safeVal = if (rawVal.length > VariationSubstitution.MAX_PAYLOAD_LENGTH) {
                    rawVal.substring(0, VariationSubstitution.MAX_PAYLOAD_LENGTH)
                } else {
                    rawVal
                }
                sb.append(safeVal)
            } else {
                sb.append(text.substring(start + 1, end))
            }
            i = end + 1
        }
        return sb.toString()
    }

    private fun removeMarkerAtRelativeIndex(text: String, targetRelIndex: Int): String {
        val pairs = extractMarkerPairs(text)
        if (targetRelIndex !in pairs.indices) return text
        val target = pairs[targetRelIndex]
        val prefix = text.substring(0, target.startIndex)
        val middle = target.baselineValue
        val suffix = text.substring(target.endIndex + 1)
        return "$prefix$middle$suffix"
    }
}
