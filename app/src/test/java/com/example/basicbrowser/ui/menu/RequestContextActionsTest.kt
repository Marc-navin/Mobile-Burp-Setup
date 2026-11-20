package com.example.basicbrowser.ui.menu

import com.example.basicbrowser.data.model.HighlightColor
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.data.repository.RequestAnnotationRepository
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptAction
import com.example.basicbrowser.proxy.intercept.InterceptManager
import com.example.basicbrowser.util.CurlGenerator
import com.example.basicbrowser.util.ResponseLinkExtractor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class RequestContextActionsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        HttpHistoryRepository.resetInstance()
        InterceptManager.resetInstance()
        RequestAnnotationRepository.resetInstanceForTesting()
        tempDir = File(System.getProperty("java.io.tmpdir"), "test_ctx_" + UUID.randomUUID())
        tempDir.mkdirs()
    }

    // --- 1. CurlGenerator Tests ---

    @Test
    fun testCurlGeneratorSimpleGet() {
        val curl = CurlGenerator.generateCurl(
            method = "GET",
            url = "https://example.com/test",
            headers = listOf("GET /test HTTP/1.1", "Host: example.com", "Accept: */*"),
            body = null
        )

        assertTrue(curl.startsWith("curl -i -s -k -X 'GET'"))
        assertTrue(curl.contains("-H 'Host: example.com'"))
        assertTrue(curl.contains("-H 'Accept: */*'"))
        assertFalse(curl.contains("GET /test HTTP/1.1"))
        assertTrue(curl.endsWith("'https://example.com/test'"))
    }

    @Test
    fun testCurlGeneratorPostWithBodyAndBashEscaping() {
        val payload = """{"message":"It's a test"}""".toByteArray(Charsets.UTF_8)
        val curl = CurlGenerator.generateCurl(
            method = "POST",
            url = "https://api.example.com/login",
            headers = listOf("Content-Type: application/json", "Authorization: Bearer test'token"),
            body = payload
        )

        assertTrue(curl.contains("-X 'POST'"))
        assertTrue(curl.contains("-H 'Content-Type: application/json'"))
        // Check single quote escaping in header
        assertTrue(curl.contains("""-H 'Authorization: Bearer test'\''token'"""))
        // Check single quote escaping in body
        assertTrue(curl.contains("""--data-binary '{"message":"It'\''s a test"}'"""))
        assertTrue(curl.endsWith("'https://api.example.com/login'"))
    }

    // --- 2. ResponseLinkExtractor Tests ---

    @Test
    fun testResponseLinkExtractor() {
        val html = """
            <html>
            <body>
                <a href="/dashboard">Dashboard</a>
                <a href="https://other.com/about">About</a>
                <script src="/static/app.js"></script>
                <img src="https://images.com/logo.png" />
                <a href="#section">Fragment</a>
                <a href="javascript:void(0)">JS</a>
                <a href="mailto:admin@example.com">Email</a>
                <a href="/dashboard">Duplicate</a>
            </body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val links = ResponseLinkExtractor.extractLinks(
            baseUrl = "https://example.com/home",
            responseHeaders = listOf("Content-Type: text/html"),
            responseBody = html
        )

        assertEquals(4, links.size)
        assertTrue(links.contains("https://example.com/dashboard"))
        assertTrue(links.contains("https://other.com/about"))
        assertTrue(links.contains("https://example.com/static/app.js"))
        assertTrue(links.contains("https://images.com/logo.png"))
        assertFalse(links.any { it.contains("javascript:") || it.contains("mailto:") || it.contains("#section") })
    }

    // --- 3. HttpHistoryRepository deleteById Tests ---

    @Test
    fun testHttpHistoryRepositoryDeleteById() {
        val repo = HttpHistoryRepository(maxEntries = 10)
        val item1 = HttpHistoryItem(id = "id-1", method = "GET", url = "https://example.com/1", requestHeaders = emptyList())
        val item2 = HttpHistoryItem(id = "id-2", method = "POST", url = "https://example.com/2", requestHeaders = emptyList())
        val item3 = HttpHistoryItem(id = "id-3", method = "GET", url = "https://example.com/3", requestHeaders = emptyList())

        repo.addOrUpdate(item1)
        repo.addOrUpdate(item2)
        repo.addOrUpdate(item3)

        assertEquals(3, repo.getItemCount())

        val deleted = repo.deleteById("id-2")
        assertTrue(deleted)
        assertEquals(2, repo.getItemCount())
        assertNull(repo.getById("id-2"))
        assertNotNull(repo.getById("id-1"))
        assertNotNull(repo.getById("id-3"))

        // Deleting non-existent ID returns false
        assertFalse(repo.deleteById("id-non-existent"))
        assertEquals(2, repo.getItemCount())
    }

    // --- 4. RequestAnnotationRepository & Saved Items Independence ---

    @Test
    fun testAnnotationAndSavedRequestIndependence() {
        val annFile = File(tempDir, "annotations.json")
        val savedFile = File(tempDir, "saved.json")
        val repo = RequestAnnotationRepository(annFile, savedFile)

        // 1. Set note and highlight
        repo.setNote("req-101", "Suspicious SQL injection parameter")
        repo.setHighlight("req-101", HighlightColor.RED)

        val ann = repo.getAnnotation("req-101")
        assertNotNull(ann)
        assertEquals("Suspicious SQL injection parameter", ann?.note)
        assertEquals(HighlightColor.RED, ann?.highlightColor)

        // 2. Save complete item with response
        val historyItem = HttpHistoryItem(
            id = "req-101",
            method = "POST",
            url = "https://example.com/login",
            requestHeaders = listOf("POST /login HTTP/1.1", "Content-Type: application/x-www-form-urlencoded"),
            requestBody = "user=admin&pass=123".toByteArray(Charsets.UTF_8),
            statusCode = 200,
            responseHeaders = listOf("HTTP/1.1 200 OK", "Content-Type: text/html"),
            responseBody = "<h1>Welcome</h1>".toByteArray(Charsets.UTF_8)
        )

        val saved = repo.saveItem(historyItem)
        assertTrue(repo.isSaved("req-101"))
        assertEquals("Suspicious SQL injection parameter", saved.note)
        assertEquals(HighlightColor.RED, saved.highlightColor)
        assertEquals("POST", saved.method)
        assertEquals("https://example.com/login", saved.url)
        assertArrayEquals("user=admin&pass=123".toByteArray(Charsets.UTF_8), saved.requestBody)
        assertArrayEquals("<h1>Welcome</h1>".toByteArray(Charsets.UTF_8), saved.responseBody)

        // 3. Re-initialize repository from disk and verify persistence
        val reloadedRepo = RequestAnnotationRepository(annFile, savedFile)
        val reloadedSaved = reloadedRepo.getSavedItem("req-101")
        assertNotNull(reloadedSaved)
        assertEquals("req-101", reloadedSaved?.id)
        assertEquals("POST", reloadedSaved?.method)
        assertEquals("https://example.com/login", reloadedSaved?.url)
        assertEquals(200, reloadedSaved?.statusCode)
        assertEquals("Suspicious SQL injection parameter", reloadedSaved?.note)
        assertEquals(HighlightColor.RED, reloadedSaved?.highlightColor)
        assertArrayEquals("user=admin&pass=123".toByteArray(Charsets.UTF_8), reloadedSaved?.requestBody)
        assertArrayEquals("<h1>Welcome</h1>".toByteArray(Charsets.UTF_8), reloadedSaved?.responseBody)

        // 4. Verify saved items survive clearing HttpHistoryRepository
        val historyRepo = HttpHistoryRepository()
        historyRepo.addOrUpdate(historyItem)
        val preservedIds = reloadedRepo.getAllSavedItems().map { it.id }.toSet()
        historyRepo.clear(preservedIds)

        // Saved items remain in history if preserved, and remain in saved repository
        assertEquals(1, historyRepo.getItemCount())
        assertEquals(1, reloadedRepo.getAllSavedItems().size)
    }

    // --- 5. Intercept Forward & Drop Safety Tests ---

    @Test
    fun testInterceptTargetSafety() {
        val manager = InterceptManager(maxPaused = 10, timeoutSeconds = 30)
        manager.setEnabled(true)

        val req1 = HttpRequestData(method = "GET", url = "https://example.com/1", headers = emptyList())
        val req2 = HttpRequestData(method = "POST", url = "https://example.com/2", headers = emptyList())

        val latch1 = java.util.concurrent.CountDownLatch(1)
        val latch2 = java.util.concurrent.CountDownLatch(1)
        var action1: InterceptAction? = null
        var action2: InterceptAction? = null

        val t1 = Thread {
            action1 = manager.interceptOrPass("id-req-1", req1, false, "example.com")
            latch1.countDown()
        }
        val t2 = Thread {
            action2 = manager.interceptOrPass("id-req-2", req2, false, "example.com")
            latch2.countDown()
        }

        t1.start()
        t2.start()

        Thread.sleep(100)
        assertEquals(2, manager.getPausedCount())

        // Forward only req2
        manager.forward("id-req-2")
        latch2.await(2, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(action2 is InterceptAction.Forward)
        // req1 must still be pending!
        assertEquals(1, manager.getPausedCount())
        assertNotNull(manager.getPausedRequest("id-req-1"))

        // Drop req1
        manager.drop("id-req-1")
        latch1.await(2, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(action1 is InterceptAction.Drop)
        assertEquals(0, manager.getPausedCount())
    }

    // --- 6. Tool Integration Transfers & Scope Tests ---

    @Test
    fun testRepeaterAndIntruderTransfer() {
        val req = HttpRequestData(
            method = "POST",
            url = "https://example.com/api/v1/auth",
            headers = listOf("Content-Type: application/json"),
            body = """{"user":"admin"}""".toByteArray(Charsets.UTF_8)
        )

        val repeaterRepo = com.example.basicbrowser.repeater.data.RepeaterRepository.getInstance()
        val item = repeaterRepo.addItem(req)
        assertNotNull(item)
        assertEquals("POST", item.request.method)
        assertEquals("https://example.com/api/v1/auth", item.request.url)

        val variationRepo = com.example.basicbrowser.variation.data.VariationRepository.getInstance()
        variationRepo.setBaseRequest(req)
        assertEquals(req, variationRepo.baseRequest.value)
        assertEquals(com.example.basicbrowser.variation.model.VariationJobState.Configuring, variationRepo.jobState.value)
    }

    @Test
    fun testScopeAdditionAction() {
        val scopeFile = File(tempDir, "scope_rules.json")
        val scopeRepo = com.example.basicbrowser.scope.data.ScopeRepository(scopeFile)

        // When no include rules are configured, ABS defaults to unrestricted scope
        assertTrue(scopeRepo.isInScope("https://target.corp/login"))

        // Add domain to scope (restricting scope to target.corp)
        scopeRepo.addRule(com.example.basicbrowser.scope.model.ScopeRule(pattern = "target.corp"))
        assertTrue(scopeRepo.isInScope("https://target.corp/login"))
        assertFalse(scopeRepo.isInScope("https://nottarget.corp/login"))
    }

    @Test
    fun testSavedRequestSurvivesIndividualHistoryDeletion() {
        val annFile = File(tempDir, "annotations2.json")
        val savedFile = File(tempDir, "saved2.json")
        val repo = RequestAnnotationRepository(annFile, savedFile)

        val historyItem = HttpHistoryItem(
            id = "del-test-1",
            method = "GET",
            url = "https://example.com/secure",
            requestHeaders = listOf("GET /secure HTTP/1.1"),
            statusCode = 200,
            responseHeaders = listOf("Content-Type: text/plain"),
            responseBody = "Confidential".toByteArray(Charsets.UTF_8)
        )

        // Save item
        repo.saveItem(historyItem)
        assertTrue(repo.isSaved("del-test-1"))

        // Delete from history
        val historyRepo = HttpHistoryRepository()
        historyRepo.addOrUpdate(historyItem)
        assertEquals(1, historyRepo.getItemCount())
        val deleted = historyRepo.deleteById("del-test-1")
        assertTrue(deleted)
        assertEquals(0, historyRepo.getItemCount())

        // Saved copy must still exist and be completely accessible
        val savedCopy = repo.getSavedItem("del-test-1")
        assertNotNull(savedCopy)
        assertEquals("https://example.com/secure", savedCopy?.url)
        assertArrayEquals("Confidential".toByteArray(Charsets.UTF_8), savedCopy?.responseBody)
    }
}

