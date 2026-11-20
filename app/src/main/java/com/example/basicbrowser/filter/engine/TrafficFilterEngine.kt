package com.example.basicbrowser.filter.engine

import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.filter.model.ContentTypeCategory
import com.example.basicbrowser.filter.model.InterceptFilterSettings
import com.example.basicbrowser.filter.model.ProtocolFilter
import com.example.basicbrowser.filter.model.ScopeFilterOption
import com.example.basicbrowser.filter.model.TrafficFilterState
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptedRequest
import com.example.basicbrowser.scope.data.ScopeRepository
import java.net.URI
import java.util.Locale

/**
 * Shared filtering component used by both HTTP History and Intercept.
 */
object TrafficFilterEngine {

    /**
     * Filters a list of captured HTTP history entries according to [filter] criteria.
     * Note: This only filters the displayed collection; it does not mutate the underlying repository.
     */
    fun filterHistory(
        items: List<HttpHistoryItem>,
        filter: TrafficFilterState,
        scopeRepository: ScopeRepository
    ): List<HttpHistoryItem> {
        if (filter.isDefault()) {
            return items
        }

        return items.filter { item ->
            matchesHistoryFilter(item, filter, scopeRepository)
        }
    }

    /**
     * Filters a list of paused requests in Intercept mode for display purposes only.
     * Note: This only filters the displayed collection in the UI; it does NOT forward,
     * drop, or alter the underlying InterceptManager queue in any way.
     */
    fun filterInterceptedRequests(
        items: List<InterceptedRequest>,
        filter: TrafficFilterState,
        scopeRepository: ScopeRepository
    ): List<InterceptedRequest> {
        if (filter.isDefault()) {
            return items
        }

        return items.filter { item ->
            matchesInterceptFilter(item, filter, scopeRepository)
        }
    }

    /**
     * Evaluates whether a single [HttpHistoryItem] satisfies all active filter criteria.
     */
    fun matchesHistoryFilter(
        item: HttpHistoryItem,
        filter: TrafficFilterState,
        scopeRepository: ScopeRepository
    ): Boolean {
        // 1. Scope filter
        if (filter.scopeFilter == ScopeFilterOption.SHOW_ONLY_IN_SCOPE ||
            filter.scopeFilter == ScopeFilterOption.HIDE_OUT_OF_SCOPE
        ) {
            if (!scopeRepository.isInScope(item.url)) {
                return false
            }
        }

        // 2. Protocol filter
        val isHttps = item.url.startsWith("https://", ignoreCase = true)
        when (filter.protocolFilter) {
            ProtocolFilter.HTTP_ONLY -> if (isHttps) return false
            ProtocolFilter.HTTPS_ONLY -> if (!isHttps) return false
            ProtocolFilter.ALL -> { /* no-op */ }
        }

        // 3. HTTP Method filter
        if (filter.selectedMethods.isNotEmpty()) {
            if (!filter.selectedMethods.contains(item.method.uppercase(Locale.ROOT))) {
                return false
            }
        }

        // 4. Status Code filter
        if (filter.selectedStatusCodes.isNotEmpty()) {
            val code = item.statusCode ?: 0
            if (code == 0) return false
            val matched = filter.selectedStatusCodes.any { pattern ->
                matchesStatusCodePattern(code, pattern)
            }
            if (!matched) return false
        }

        // 5. Extension and Content-Type filtering
        val ext = ResourceClassifier.extractExtension(item.url)
        if (filter.hiddenExtensions.isNotEmpty() && ext.isNotEmpty()) {
            if (filter.hiddenExtensions.any { it.equals(ext, ignoreCase = true) }) {
                return false
            }
        }

        val contentTypeHeader = item.responseHeaders.find {
            it.startsWith("Content-Type:", ignoreCase = true)
        }?.substringAfter(":")?.trim()

        val category = ResourceClassifier.classifyResponse(contentTypeHeader, item.url)

        // Quick Toggles
        if (filter.hideImages && category == ContentTypeCategory.IMAGES) {
            return false
        }
        if (filter.hideCssAndJs && (category == ContentTypeCategory.CSS || category == ContentTypeCategory.JAVASCRIPT)) {
            return false
        }

        // Specific Content-Type Categories
        if (filter.contentTypes.isNotEmpty()) {
            if (!filter.contentTypes.contains(category)) {
                return false
            }
        }

        // MIME type query (substring match on Content-Type header)
        if (filter.mimeTypeQuery.isNotBlank()) {
            val query = filter.mimeTypeQuery.trim().lowercase(Locale.ROOT)
            if (contentTypeHeader == null || !contentTypeHeader.lowercase(Locale.ROOT).contains(query)) {
                return false
            }
        }

        // 6. Additional Filters (Parameters, Request Body, Response existence)
        if (filter.onlyWithParams) {
            if (!hasParameters(item.url, item.requestHeaders, item.requestBody)) {
                return false
            }
        }

        if (filter.onlyWithBody) {
            if (item.requestBody == null || item.requestBody.isEmpty()) {
                return false
            }
        }

        if (filter.onlyWithResponse) {
            if (item.statusCode == null && item.responseHeaders.isEmpty()) {
                return false
            }
        }

        // 7. Host filter
        if (filter.hostQuery.isNotBlank()) {
            val host = extractHost(item.url)
            if (!host.contains(filter.hostQuery.trim(), ignoreCase = true)) {
                return false
            }
        }

        // 8. Path filter
        if (filter.pathQuery.isNotBlank()) {
            val path = extractPath(item.url)
            if (!path.contains(filter.pathQuery.trim(), ignoreCase = true)) {
                return false
            }
        }

        // 9. General search query (URL, headers, status code)
        if (filter.searchQuery.isNotBlank()) {
            val query = filter.searchQuery.trim()
            val inUrl = item.url.contains(query, ignoreCase = true)
            val inMethod = item.method.contains(query, ignoreCase = true)
            val inStatus = item.statusCode?.toString()?.contains(query) ?: false
            val inReqHeaders = item.requestHeaders.any { it.contains(query, ignoreCase = true) }
            val inRespHeaders = item.responseHeaders.any { it.contains(query, ignoreCase = true) }

            if (!inUrl && !inMethod && !inStatus && !inReqHeaders && !inRespHeaders) {
                return false
            }
        }

        return true
    }

    /**
     * Evaluates whether a single [InterceptedRequest] satisfies all active display filter criteria.
     * Note: Intercepted requests may not have response headers or status codes yet.
     * Missing response information is NOT treated as a failed match unless response-specific
     * filters (such as `onlyWithResponse`) are explicitly requested.
     */
    fun matchesInterceptFilter(
        item: InterceptedRequest,
        filter: TrafficFilterState,
        scopeRepository: ScopeRepository
    ): Boolean {
        val req = item.originalRequest

        // 1. Scope filter
        if (filter.scopeFilter == ScopeFilterOption.SHOW_ONLY_IN_SCOPE ||
            filter.scopeFilter == ScopeFilterOption.HIDE_OUT_OF_SCOPE
        ) {
            if (!scopeRepository.isInScope(req.url)) {
                return false
            }
        }

        // 2. Protocol filter
        when (filter.protocolFilter) {
            ProtocolFilter.HTTP_ONLY -> if (item.isHttps) return false
            ProtocolFilter.HTTPS_ONLY -> if (!item.isHttps) return false
            ProtocolFilter.ALL -> { /* no-op */ }
        }

        // 3. HTTP Method filter
        if (filter.selectedMethods.isNotEmpty()) {
            if (!filter.selectedMethods.contains(req.method.uppercase(Locale.ROOT))) {
                return false
            }
        }

        // 4. Status Code filter:
        // Intercepted requests paused in the pre-upstream queue do not have response metadata yet.
        // We only enforce status-code filtering if response metadata is actually present.
        // If onlyWithResponse is true, a pre-response intercepted request will not match.
        if (filter.onlyWithResponse) {
            return false // Intercepted requests paused before upstream have no response yet
        }

        // 5. Extension and Content-Type filtering
        val ext = ResourceClassifier.extractExtension(req.url)
        if (filter.hiddenExtensions.isNotEmpty() && ext.isNotEmpty()) {
            if (filter.hiddenExtensions.any { it.equals(ext, ignoreCase = true) }) {
                return false
            }
        }

        val earlyCategory = ResourceClassifier.classifyRequestEarly(req.url, req.headers)

        // Quick Toggles
        if (filter.hideImages) {
            if (earlyCategory == ContentTypeCategory.IMAGES ||
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "ico")
            ) {
                return false
            }
        }
        if (filter.hideCssAndJs) {
            if (earlyCategory == ContentTypeCategory.CSS || earlyCategory == ContentTypeCategory.JAVASCRIPT ||
                ext in setOf("css", "js", "mjs")
            ) {
                return false
            }
        }

        // Specific Content-Type Categories
        if (filter.contentTypes.isNotEmpty()) {
            if (earlyCategory != null && !filter.contentTypes.contains(earlyCategory)) {
                return false
            }
        }

        // 6. Additional Filters (Parameters, Request Body)
        if (filter.onlyWithParams) {
            if (!hasParameters(req.url, req.headers, req.body)) {
                return false
            }
        }

        if (filter.onlyWithBody) {
            if (req.body == null || req.body.isEmpty()) {
                return false
            }
        }

        // 7. Host filter
        if (filter.hostQuery.isNotBlank()) {
            val host = item.hostname.ifBlank { extractHost(req.url) }
            if (!host.contains(filter.hostQuery.trim(), ignoreCase = true)) {
                return false
            }
        }

        // 8. Path filter
        if (filter.pathQuery.isNotBlank()) {
            val path = extractPath(req.url)
            if (!path.contains(filter.pathQuery.trim(), ignoreCase = true)) {
                return false
            }
        }

        // 9. General search query (URL, headers, method, host)
        if (filter.searchQuery.isNotBlank()) {
            val query = filter.searchQuery.trim()
            val inUrl = req.url.contains(query, ignoreCase = true)
            val inHost = item.hostname.contains(query, ignoreCase = true)
            val inMethod = req.method.contains(query, ignoreCase = true)
            val inHeaders = req.headers.any { it.contains(query, ignoreCase = true) }

            if (!inUrl && !inHost && !inMethod && !inHeaders) {
                return false
            }
        }

        return true
    }

    /**
     * Decision engine for the Intercept module.
     *
     * Determines whether an incoming request should be paused in the interception queue,
     * or automatically forwarded unchanged to upstream.
     *
     * Rule: When classification is uncertain, default to allowing the request through (intercepting)
     * rather than accidentally blocking or bypassing unintended traffic.
     */
    fun shouldInterceptRequest(
        request: HttpRequestData,
        isHttps: Boolean,
        hostname: String,
        scopeRepository: ScopeRepository,
        settings: InterceptFilterSettings
    ): Boolean {
        // 1. Check Scope
        if (settings.interceptOnlyInScope) {
            if (!scopeRepository.isInScope(request.url)) {
                return false // Out of scope -> auto-forward unchanged
            }
        }

        // 2. Early resource classification (URL extension + Accept header)
        val category = ResourceClassifier.classifyRequestEarly(request.url, request.headers)

        if (category != null) {
            if (settings.filterImages && category == ContentTypeCategory.IMAGES) {
                return false
            }
            if (settings.filterCss && category == ContentTypeCategory.CSS) {
                return false
            }
            if (settings.filterJs && category == ContentTypeCategory.JAVASCRIPT) {
                return false
            }
            if (settings.filterOtherResources &&
                (category == ContentTypeCategory.FONTS ||
                        category == ContentTypeCategory.AUDIO ||
                        category == ContentTypeCategory.VIDEO ||
                        category == ContentTypeCategory.OTHER_BINARY)
            ) {
                return false
            }
        }

        // Uncertain classification or permitted resource -> intercept
        return true
    }

    private fun matchesStatusCodePattern(code: Int, pattern: String): Boolean {
        return when (pattern.lowercase(Locale.ROOT)) {
            "2xx" -> code in 200..299
            "3xx" -> code in 300..399
            "4xx" -> code in 400..499
            "5xx" -> code in 500..599
            else -> code.toString() == pattern || pattern == "${code / 100}xx"
        }
    }

    private fun hasParameters(url: String, headers: List<String>, body: ByteArray?): Boolean {
        val qIdx = url.indexOf('?')
        if (qIdx >= 0 && qIdx < url.length - 1) {
            val query = url.substring(qIdx + 1).trim()
            if (query.isNotEmpty()) return true
        }
        val isFormUrlEncoded = headers.any {
            it.contains("Content-Type", ignoreCase = true) &&
                    it.contains("application/x-www-form-urlencoded", ignoreCase = true)
        }
        if (isFormUrlEncoded && body != null && body.isNotEmpty()) {
            return true
        }
        return false
    }

    private fun extractHost(url: String): String {
        return try {
            if (url.contains("://")) {
                URI(url).host ?: url.substringAfter("://").substringBefore("/").substringBefore(":")
            } else {
                url.substringBefore("/").substringBefore(":")
            }
        } catch (_: Exception) {
            url.substringAfter("://", url).substringBefore("/").substringBefore(":")
        }
    }

    private fun extractPath(url: String): String {
        return try {
            if (url.contains("://")) {
                val p = URI(url).rawPath
                if (p.isNullOrEmpty()) "/" else p
            } else {
                val afterHost = url.substringAfter("/")
                if (afterHost.isEmpty()) "/" else "/$afterHost"
            }
        } catch (_: Exception) {
            "/"
        }
    }
}
