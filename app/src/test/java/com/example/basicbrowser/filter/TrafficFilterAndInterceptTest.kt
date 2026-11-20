package com.example.basicbrowser.filter

import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.engine.ResourceClassifier
import com.example.basicbrowser.filter.engine.TrafficFilterEngine
import com.example.basicbrowser.filter.model.ContentTypeCategory
import com.example.basicbrowser.filter.model.InterceptFilterSettings
import com.example.basicbrowser.filter.model.ProtocolFilter
import com.example.basicbrowser.filter.model.ScopeFilterOption
import com.example.basicbrowser.filter.model.TrafficFilterState
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptAction
import com.example.basicbrowser.proxy.intercept.InterceptManager
import com.example.basicbrowser.proxy.intercept.InterceptedRequest
import com.example.basicbrowser.scope.data.ScopeRepository
import com.example.basicbrowser.scope.model.ScopeRule
import com.example.basicbrowser.scope.model.ScopeRuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrafficFilterAndInterceptTest {

    private fun createHistoryItem(
        id: String = java.util.UUID.randomUUID().toString(),
        method: String = "GET",
        url: String,
        statusCode: Int = 200,
        contentType: String? = null,
        responseHeaders: List<String> = emptyList(),
        requestHeaders: List<String> = listOf("Host: example.com"),
        requestBody: ByteArray? = null
    ): HttpHistoryItem {
        val headers = if (contentType != null) {
            responseHeaders + "Content-Type: $contentType"
        } else {
            responseHeaders
        }
        return HttpHistoryItem(
            id = id,
            method = method,
            url = url,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            statusCode = statusCode,
            responseHeaders = headers
        )
    }

    private fun createInterceptedRequest(
        id: String = java.util.UUID.randomUUID().toString(),
        method: String = "GET",
        url: String,
        isHttps: Boolean = true,
        hostname: String = "example.com",
        headers: List<String> = listOf("Host: example.com"),
        body: ByteArray? = null
    ): InterceptedRequest {
        val reqData = HttpRequestData(
            method = method,
            url = url,
            headers = headers,
            body = body
        )
        return InterceptedRequest(
            id = id,
            originalRequest = reqData,
            isHttps = isHttps,
            hostname = hostname
        )
    }

    // ----------------------------------------------------
    // 1. HISTORY FILTERING TESTS
    // ----------------------------------------------------

    @Test
    fun testHistoryFilter_inScopeAndOutOfScope() {
        val scopeRepo = ScopeRepository()
        scopeRepo.setRules(
            listOf(
                ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "*.target.com"),
                ScopeRule(type = ScopeRuleType.EXCLUDE, pattern = "cdn.target.com")
            )
        )

        val item1 = createHistoryItem(url = "https://app.target.com/api/data")
        val item2 = createHistoryItem(url = "https://cdn.target.com/bundle.js")
        val item3 = createHistoryItem(url = "https://thirdparty-analytics.com/track")
        val list = listOf(item1, item2, item3)

        // Default: show all
        val all = TrafficFilterEngine.filterHistory(list, TrafficFilterState.DEFAULT, scopeRepo)
        assertEquals(3, all.size)

        // Show only in-scope
        val inScopeFilter = TrafficFilterState(scopeFilter = ScopeFilterOption.SHOW_ONLY_IN_SCOPE)
        val inScopeOnly = TrafficFilterEngine.filterHistory(list, inScopeFilter, scopeRepo)
        assertEquals(1, inScopeOnly.size)
        assertEquals(item1.id, inScopeOnly[0].id)
    }

    @Test
    fun testHistoryFilter_hideImages_jpegPngWebpSvg() {
        val scopeRepo = ScopeRepository()

        val html = createHistoryItem(url = "https://example.com/index.html", contentType = "text/html")
        val jpeg = createHistoryItem(url = "https://example.com/banner.jpg", contentType = "image/jpeg")
        val png = createHistoryItem(url = "https://example.com/logo.png", contentType = "image/png")
        val webp = createHistoryItem(url = "https://example.com/photo.webp", contentType = "image/webp")
        val svg = createHistoryItem(url = "https://example.com/icon.svg", contentType = "image/svg+xml")
        val json = createHistoryItem(url = "https://example.com/api/user", contentType = "application/json")

        val list = listOf(html, jpeg, png, webp, svg, json)

        val filter = TrafficFilterState(hideImages = true)
        val filtered = TrafficFilterEngine.filterHistory(list, filter, scopeRepo)

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.id == html.id })
        assertTrue(filtered.any { it.id == json.id })
        assertFalse(filtered.any { it.id == jpeg.id || it.id == png.id || it.id == webp.id || it.id == svg.id })
    }

    @Test
    fun testHistoryFilter_hideCssAndJavaScript() {
        val scopeRepo = ScopeRepository()

        val html = createHistoryItem(url = "https://example.com/page", contentType = "text/html")
        val css = createHistoryItem(url = "https://example.com/style.css", contentType = "text/css")
        val js1 = createHistoryItem(url = "https://example.com/app.js", contentType = "application/javascript")
        val js2 = createHistoryItem(url = "https://example.com/vendor.mjs", contentType = "text/javascript")
        val api = createHistoryItem(url = "https://example.com/api/v1", contentType = "application/json")

        val list = listOf(html, css, js1, js2, api)

        val filter = TrafficFilterState(hideCssAndJs = true)
        val filtered = TrafficFilterEngine.filterHistory(list, filter, scopeRepo)

        assertEquals(2, filtered.size)
        assertEquals(html.id, filtered[0].id)
        assertEquals(api.id, filtered[1].id)
    }

    @Test
    fun testHistoryFilter_hiddenFileExtensions() {
        val scopeRepo = ScopeRepository()

        val itemPng = createHistoryItem(url = "https://example.com/image.png")
        val itemWoff = createHistoryItem(url = "https://example.com/fonts/inter.woff2")
        val itemJs = createHistoryItem(url = "https://example.com/bundle.js")
        val itemIco = createHistoryItem(url = "https://example.com/favicon.ico")
        val itemHtml = createHistoryItem(url = "https://example.com/index.html")
        val itemApi = createHistoryItem(url = "https://example.com/api/orders")

        val list = listOf(itemPng, itemWoff, itemJs, itemIco, itemHtml, itemApi)

        val filter = TrafficFilterState(
            hiddenExtensions = setOf("png", "woff2", "ico")
        )
        val filtered = TrafficFilterEngine.filterHistory(list, filter, scopeRepo)

        assertEquals(3, filtered.size)
        assertTrue(filtered.any { it.id == itemJs.id })
        assertTrue(filtered.any { it.id == itemHtml.id })
        assertTrue(filtered.any { it.id == itemApi.id })
        assertFalse(filtered.any { it.id == itemPng.id || it.id == itemWoff.id || it.id == itemIco.id })
    }

    @Test
    fun testHistoryFilter_methodAndStatusCodeFiltering() {
        val scopeRepo = ScopeRepository()

        val get200 = createHistoryItem(method = "GET", url = "https://example.com/1", statusCode = 200)
        val post201 = createHistoryItem(method = "POST", url = "https://example.com/2", statusCode = 201)
        val get404 = createHistoryItem(method = "GET", url = "https://example.com/3", statusCode = 404)
        val post500 = createHistoryItem(method = "POST", url = "https://example.com/4", statusCode = 500)
        val delete204 = createHistoryItem(method = "DELETE", url = "https://example.com/5", statusCode = 204)

        val list = listOf(get200, post201, get404, post500, delete204)

        // Filter only POST requests
        val postOnly = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(selectedMethods = setOf("POST")),
            scopeRepo
        )
        assertEquals(2, postOnly.size)
        assertTrue(postOnly.all { it.method == "POST" })

        // Filter 4xx and 5xx errors
        val errorsOnly = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(selectedStatusCodes = setOf("4xx", "5xx")),
            scopeRepo
        )
        assertEquals(2, errorsOnly.size)
        assertTrue(errorsOnly.any { it.statusCode == 404 })
        assertTrue(errorsOnly.any { it.statusCode == 500 })

        // Combined: GET with 2xx status code
        val getSuccess = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(selectedMethods = setOf("GET"), selectedStatusCodes = setOf("2xx")),
            scopeRepo
        )
        assertEquals(1, getSuccess.size)
        assertEquals(get200.id, getSuccess[0].id)
    }

    @Test
    fun testHistoryFilter_parametersAndBodyFiltering() {
        val scopeRepo = ScopeRepository()

        val withQueryParam = createHistoryItem(url = "https://example.com/search?q=test&lang=en")
        val withoutParam = createHistoryItem(url = "https://example.com/about")
        val withFormBody = createHistoryItem(
            url = "https://example.com/login",
            requestHeaders = listOf("Content-Type: application/x-www-form-urlencoded"),
            requestBody = "user=alice&pass=secret".toByteArray()
        )
        val withJsonBody = createHistoryItem(
            url = "https://example.com/api/data",
            requestHeaders = listOf("Content-Type: application/json"),
            requestBody = "{\"foo\":\"bar\"}".toByteArray()
        )

        val list = listOf(withQueryParam, withoutParam, withFormBody, withJsonBody)

        // 1. Only with parameters
        val paramsOnly = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(onlyWithParams = true),
            scopeRepo
        )
        assertEquals(2, paramsOnly.size)
        assertTrue(paramsOnly.any { it.id == withQueryParam.id })
        assertTrue(paramsOnly.any { it.id == withFormBody.id })

        // 2. Only with request body
        val bodyOnly = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(onlyWithBody = true),
            scopeRepo
        )
        assertEquals(2, bodyOnly.size)
        assertTrue(bodyOnly.any { it.id == withFormBody.id })
        assertTrue(bodyOnly.any { it.id == withJsonBody.id })
    }

    @Test
    fun testHistoryFilter_responsePresenceFiltering() {
        val scopeRepo = ScopeRepository()

        val completedItem = createHistoryItem(
            url = "https://example.com/done",
            statusCode = 200,
            responseHeaders = listOf("Server: Apache")
        )
        val pendingOrIncompleteItem = HttpHistoryItem(
            url = "https://example.com/pending",
            method = "GET",
            requestHeaders = listOf("Host: example.com"),
            statusCode = null,
            responseHeaders = emptyList()
        )

        val list = listOf(completedItem, pendingOrIncompleteItem)

        val responseOnly = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(onlyWithResponse = true),
            scopeRepo
        )
        assertEquals(1, responseOnly.size)
        assertEquals(completedItem.id, responseOnly[0].id)
    }

    @Test
    fun testHistoryFilter_searchKeywordAndHostAndPath() {
        val scopeRepo = ScopeRepository()

        val item1 = createHistoryItem(method = "GET", url = "https://api.github.com/users/octocat")
        val item2 = createHistoryItem(method = "POST", url = "https://auth.company.org/oauth/token")
        val item3 = createHistoryItem(method = "GET", url = "https://internal.net/health")

        val list = listOf(item1, item2, item3)

        // Search by keyword in URL
        val foundUser = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(searchQuery = "OCTOCAT"),
            scopeRepo
        )
        assertEquals(1, foundUser.size)
        assertEquals(item1.id, foundUser[0].id)

        // Filter by Host
        val githubHost = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(hostQuery = "github.com"),
            scopeRepo
        )
        assertEquals(1, githubHost.size)
        assertEquals(item1.id, githubHost[0].id)

        // Filter by Path
        val oauthPath = TrafficFilterEngine.filterHistory(
            list,
            TrafficFilterState(pathQuery = "oauth"),
            scopeRepo
        )
        assertEquals(1, oauthPath.size)
        assertEquals(item2.id, oauthPath[0].id)
    }

    @Test
    fun testHistoryFilter_combinedFiltersAndResetDoesNotMutateList() {
        val scopeRepo = ScopeRepository()
        scopeRepo.setRules(listOf(ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "*.shop.com")))

        val item1 = createHistoryItem(method = "GET", url = "https://api.shop.com/products", statusCode = 200, contentType = "application/json")
        val item2 = createHistoryItem(method = "POST", url = "https://api.shop.com/checkout", statusCode = 200, contentType = "application/json")
        val item3 = createHistoryItem(method = "GET", url = "https://api.shop.com/hero.jpg", statusCode = 200, contentType = "image/jpeg")
        val item4 = createHistoryItem(method = "GET", url = "https://tracking.com/pixel.gif", statusCode = 200, contentType = "image/gif")

        val originalHistory = listOf(item1, item2, item3, item4)

        val strictFilter = TrafficFilterState(
            scopeFilter = ScopeFilterOption.SHOW_ONLY_IN_SCOPE,
            hideImages = true,
            selectedMethods = setOf("GET"),
            contentTypes = setOf(ContentTypeCategory.JSON)
        )

        val filtered = TrafficFilterEngine.filterHistory(originalHistory, strictFilter, scopeRepo)
        assertEquals(1, filtered.size)
        assertEquals(item1.id, filtered[0].id)

        // Reset filter
        val reset = TrafficFilterEngine.filterHistory(originalHistory, TrafficFilterState.DEFAULT, scopeRepo)
        assertEquals(4, reset.size)

        // Verify original collection was never mutated
        assertEquals(4, originalHistory.size)
    }

    // ----------------------------------------------------
    // 2. INTERCEPT DISPLAY FILTERING TESTS (NON-DESTRUCTIVE)
    // ----------------------------------------------------

    @Test
    fun testInterceptDisplayFilter_filtersListWithoutMutatingUnderlyingQueue() {
        val scopeRepo = ScopeRepository()
        scopeRepo.setRules(listOf(ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "target.com")))

        val inScopeReq = createInterceptedRequest(
            id = "req_1",
            method = "GET",
            url = "https://target.com/profile",
            hostname = "target.com"
        )
        val outScopeReq = createInterceptedRequest(
            id = "req_2",
            method = "GET",
            url = "https://adservice.google.com/pixel",
            hostname = "adservice.google.com"
        )
        val imageReq = createInterceptedRequest(
            id = "req_3",
            method = "GET",
            url = "https://target.com/avatar.png",
            hostname = "target.com"
        )
        val postReq = createInterceptedRequest(
            id = "req_4",
            method = "POST",
            url = "https://target.com/api/post",
            hostname = "target.com",
            body = "{\"text\":\"hello\"}".toByteArray()
        )

        val underlyingQueue = listOf(inScopeReq, outScopeReq, imageReq, postReq)

        // 1. In-Scope only display filter
        val inScopeDisplay = TrafficFilterEngine.filterInterceptedRequests(
            underlyingQueue,
            TrafficFilterState(scopeFilter = ScopeFilterOption.SHOW_ONLY_IN_SCOPE),
            scopeRepo
        )
        assertEquals(3, inScopeDisplay.size)
        assertFalse(inScopeDisplay.any { it.id == "req_2" })

        // 2. Hide images and filter method POST
        val postDisplay = TrafficFilterEngine.filterInterceptedRequests(
            underlyingQueue,
            TrafficFilterState(hideImages = true, selectedMethods = setOf("POST")),
            scopeRepo
        )
        assertEquals(1, postDisplay.size)
        assertEquals("req_4", postDisplay[0].id)

        // 3. Reset filters: immediately restores all hidden items
        val restored = TrafficFilterEngine.filterInterceptedRequests(
            underlyingQueue,
            TrafficFilterState.DEFAULT,
            scopeRepo
        )
        assertEquals(4, restored.size)

        // 4. Verify underlying queue remained 100% intact and unmutated
        assertEquals(4, underlyingQueue.size)
    }

    @Test
    fun testInterceptDisplayFilter_requestsWithoutResponses_notTreatedAsFailure() {
        val scopeRepo = ScopeRepository()

        val req = createInterceptedRequest(
            method = "GET",
            url = "https://example.com/api/data"
        )

        // Even if status codes are selected, requests in the interception queue
        // (which are pre-upstream response) are NOT discarded by status-code checks
        val filterWithStatus = TrafficFilterState(
            selectedStatusCodes = setOf("2xx")
        )
        val result = TrafficFilterEngine.filterInterceptedRequests(
            listOf(req),
            filterWithStatus,
            scopeRepo
        )
        // Since request has no response yet, status code filter doesn't fail the request
        assertEquals(1, result.size)
    }

    @Test
    fun testInterceptManager_forwardAllAndDropAll_operatesOnEntireQueueDespiteFilters() {
        val scopeRepo = ScopeRepository()
        val manager = InterceptManager(
            maxPaused = 10,
            timeoutSeconds = 5,
            scopeRepositoryProvider = { scopeRepo }
        )
        manager.setEnabled(true)

        val req1 = HttpRequestData(method = "GET", url = "https://example.com/page1", headers = listOf("Host: example.com"))
        val req2 = HttpRequestData(method = "GET", url = "https://example.com/image.png", headers = listOf("Host: example.com"))

        val latch = CountDownLatch(2)
        var action1: InterceptAction? = null
        var action2: InterceptAction? = null

        Thread {
            action1 = manager.interceptOrPass("id_1", req1, true, "example.com")
            latch.countDown()
        }.start()

        Thread {
            action2 = manager.interceptOrPass("id_2", req2, true, "example.com")
            latch.countDown()
        }.start()

        Thread.sleep(100)
        assertEquals(2, manager.pausedRequests.value.size)

        // Active display filter that hides req2 (image)
        val filterState = TrafficFilterState(hideImages = true)
        val visibleRequests = TrafficFilterEngine.filterInterceptedRequests(
            manager.pausedRequests.value,
            filterState,
            scopeRepo
        )
        assertEquals(1, visibleRequests.size) // Only req1 is visible in UI

        // User taps "Forward All": Forward All operates on the ENTIRE underlying queue!
        manager.forwardAll()

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(action1 is InterceptAction.Forward)
        assertTrue(action2 is InterceptAction.Forward)
        assertEquals(0, manager.pausedRequests.value.size) // Both were forwarded properly
    }

    // ----------------------------------------------------
    // 3. REPOSITORY INDEPENDENCE & PERSISTENCE TESTS
    // ----------------------------------------------------

    @Test
    fun testTrafficFilterRepository_historyAndInterceptAreIndependent() {
        val tempFile = File.createTempFile("test_filter_repo", ".json")
        try {
            val repo = TrafficFilterRepository(tempFile)

            val historyFilter = TrafficFilterState(
                searchQuery = "history_query",
                selectedMethods = setOf("GET"),
                hideImages = true
            )
            val interceptFilter = TrafficFilterState(
                searchQuery = "intercept_query",
                selectedMethods = setOf("POST"),
                hideCssAndJs = true
            )

            repo.updateHistoryFilter(historyFilter)
            repo.updateInterceptFilter(interceptFilter)

            assertEquals("history_query", repo.historyFilterState.value.searchQuery)
            assertEquals("intercept_query", repo.interceptFilterState.value.searchQuery)
            assertTrue(repo.historyFilterState.value.hideImages)
            assertFalse(repo.interceptFilterState.value.hideImages)
            assertTrue(repo.interceptFilterState.value.hideCssAndJs)
            assertFalse(repo.historyFilterState.value.hideCssAndJs)

            // Resetting History does NOT reset Intercept
            repo.resetHistoryFilter()
            assertTrue(repo.historyFilterState.value.isDefault())
            assertFalse(repo.interceptFilterState.value.isDefault())
            assertEquals("intercept_query", repo.interceptFilterState.value.searchQuery)

            // Resetting Intercept does not affect History
            repo.resetInterceptFilter()
            assertTrue(repo.interceptFilterState.value.isDefault())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testTrafficFilterRepository_persistenceAcrossReinitialization() {
        val tempFile = File.createTempFile("test_persistence", ".json")
        try {
            val repo1 = TrafficFilterRepository(tempFile)
            val hState = TrafficFilterState(
                scopeFilter = ScopeFilterOption.SHOW_ONLY_IN_SCOPE,
                searchQuery = "admin",
                selectedMethods = setOf("PUT", "DELETE"),
                selectedStatusCodes = setOf("2xx", "4xx"),
                hideImages = true,
                hiddenExtensions = setOf("png", "woff2", "custom"),
                onlyWithParams = true,
                onlyWithBody = true,
                onlyWithResponse = true
            )
            val iState = TrafficFilterState(
                scopeFilter = ScopeFilterOption.HIDE_OUT_OF_SCOPE,
                searchQuery = "bearer",
                selectedMethods = setOf("POST"),
                hideCssAndJs = true
            )
            val bypassSettings = InterceptFilterSettings(
                interceptOnlyInScope = true,
                filterImages = true,
                filterCss = true
            )

            repo1.updateHistoryFilter(hState)
            repo1.updateInterceptFilter(iState)
            repo1.updateInterceptBypassSettings(bypassSettings)

            // Re-instantiate repository from the same storage file
            val repo2 = TrafficFilterRepository(tempFile)

            val loadedH = repo2.historyFilterState.value
            assertEquals(ScopeFilterOption.SHOW_ONLY_IN_SCOPE, loadedH.scopeFilter)
            assertEquals("admin", loadedH.searchQuery)
            assertEquals(setOf("PUT", "DELETE"), loadedH.selectedMethods)
            assertEquals(setOf("2xx", "4xx"), loadedH.selectedStatusCodes)
            assertTrue(loadedH.hideImages)
            assertEquals(setOf("png", "woff2", "custom"), loadedH.hiddenExtensions)
            assertTrue(loadedH.onlyWithParams)
            assertTrue(loadedH.onlyWithBody)
            assertTrue(loadedH.onlyWithResponse)

            val loadedI = repo2.interceptFilterState.value
            assertEquals(ScopeFilterOption.HIDE_OUT_OF_SCOPE, loadedI.scopeFilter)
            assertEquals("bearer", loadedI.searchQuery)
            assertEquals(setOf("POST"), loadedI.selectedMethods)
            assertTrue(loadedI.hideCssAndJs)

            val loadedBypass = repo2.interceptFilterSettings.value
            assertTrue(loadedBypass.interceptOnlyInScope)
            assertTrue(loadedBypass.filterImages)
            assertTrue(loadedBypass.filterCss)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testTrafficFilterRepository_corruptedOrInvalidJson_fallsBackToDefaults() {
        val tempFile = File.createTempFile("test_corrupted", ".json")
        try {
            tempFile.writeText("{ corrupted json content [", Charsets.UTF_8)
            val repo = TrafficFilterRepository(tempFile)

            assertTrue(repo.historyFilterState.value.isDefault())
            assertTrue(repo.interceptFilterState.value.isDefault())
            assertFalse(repo.interceptFilterSettings.value.interceptOnlyInScope)
        } finally {
            tempFile.delete()
        }
    }
}
