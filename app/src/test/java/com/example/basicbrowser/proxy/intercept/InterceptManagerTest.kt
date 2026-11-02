package com.example.basicbrowser.proxy.intercept

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InterceptManagerTest {

    private lateinit var manager: InterceptManager

    @Before
    fun setUp() {
        InterceptManager.resetInstance()
        manager = InterceptManager(maxPaused = 10, timeoutSeconds = 60L)
    }

    @After
    fun tearDown() {
        manager.releaseAll("test_teardown")
        InterceptManager.resetInstance()
    }

    @Test
    fun disabledByDefault_passesThroughImmediately() {
        assertFalse("Intercept should be disabled by default", manager.isEnabled())

        val request = HttpRequestData(
            method = "GET",
            url = "http://example.com/test",
            headers = listOf("Host: example.com")
        )

        val action = manager.interceptOrPass("req-1", request, isHttps = false, hostname = "example.com")
        assertTrue("Action should be Forward", action is InterceptAction.Forward)
        assertEquals("Request should be unchanged", request, (action as InterceptAction.Forward).request)
        assertEquals(0, manager.getPausedCount())
    }

    @Test
    fun whenEnabled_pausesRequestUntilForwarded() {
        manager.setEnabled(true)
        assertTrue(manager.isEnabled())

        val request = HttpRequestData(
            method = "GET",
            url = "https://example.com/index.html",
            headers = listOf("Host: example.com", "User-Agent: BasicBrowser/1.0")
        )

        val startedLatch = CountDownLatch(1)
        var actionResult: InterceptAction? = null

        val thread = Thread {
            startedLatch.countDown()
            actionResult = manager.interceptOrPass("req-2", request, isHttps = true, hostname = "example.com")
        }
        thread.start()

        startedLatch.await(2, TimeUnit.SECONDS)
        Thread.sleep(50) // Allow interceptOrPass to enter synchronized block

        assertEquals("Queue should have 1 item", 1, manager.getPausedCount())
        val paused = manager.getPausedRequest("req-2")
        assertNotNull(paused)
        assertEquals("req-2", paused?.id)

        // Forward request unchanged
        manager.forward("req-2")
        thread.join(2000)

        assertTrue(actionResult is InterceptAction.Forward)
        assertEquals(request, (actionResult as InterceptAction.Forward).request)
        assertEquals(0, manager.getPausedCount())
    }

    @Test
    fun whenEnabled_canEditRequestAndNormalizeFramingHeaders() {
        manager.setEnabled(true)

        val originalRequest = HttpRequestData(
            method = "POST",
            url = "https://api.example.com/v1/data",
            headers = listOf(
                "Host: api.example.com",
                "Content-Length: 100",
                "Transfer-Encoding: chunked"
            ),
            body = "old body".toByteArray(StandardCharsets.UTF_8)
        )

        var actionResult: InterceptAction? = null
        val thread = Thread {
            actionResult = manager.interceptOrPass("req-3", originalRequest, isHttps = true, hostname = "api.example.com")
        }
        thread.start()
        Thread.sleep(50)

        // Modify method, URL, headers, and body
        val newBody = "{\"edited\": true, \"status\": \"injected\"}".toByteArray(StandardCharsets.UTF_8)
        val editedRequest = HttpRequestData(
            method = "PUT",
            url = "https://api.example.com/v2/update",
            headers = listOf("Host: api.example.com", "Content-Type: application/json"),
            body = newBody
        )

        manager.forward("req-3", editedRequest)
        thread.join(2000)

        assertTrue(actionResult is InterceptAction.Forward)
        val forwarded = (actionResult as InterceptAction.Forward).request

        assertEquals("PUT", forwarded.method)
        assertEquals("https://api.example.com/v2/update", forwarded.url)
        assertArrayEquals(newBody, forwarded.body)

        // Framing headers should be normalized:
        // 1. Content-Length should match new body size
        // 2. Transfer-Encoding: chunked should be removed
        assertTrue(forwarded.headers.contains("Content-Length: ${newBody.size}"))
        assertFalse(forwarded.headers.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) })
    }

    @Test
    fun whenEnabled_canDropRequest() {
        manager.setEnabled(true)

        val request = HttpRequestData(
            method = "DELETE",
            url = "https://example.com/account",
            headers = listOf("Host: example.com")
        )

        var actionResult: InterceptAction? = null
        val thread = Thread {
            actionResult = manager.interceptOrPass("req-drop", request, isHttps = true, hostname = "example.com")
        }
        thread.start()
        Thread.sleep(50)

        assertEquals(1, manager.getPausedCount())
        manager.drop("req-drop")
        thread.join(2000)

        assertTrue("Action should be Drop", actionResult is InterceptAction.Drop)
        assertEquals(0, manager.getPausedCount())
    }

    @Test
    fun queueLimit_hardMax10_overloadPassesThroughSafely() {
        manager.setEnabled(true)
        val threads = mutableListOf<Thread>()

        // Pause 10 requests to reach capacity
        for (i in 1..10) {
            val req = HttpRequestData(method = "GET", url = "http://test.com/$i", headers = emptyList())
            val t = Thread {
                manager.interceptOrPass("req-$i", req, isHttps = false, hostname = "test.com")
            }
            threads.add(t)
            t.start()
        }

        Thread.sleep(100)
        assertEquals("Queue should be full at 10", 10, manager.getPausedCount())

        // 11th request exceeds limit: should pass through immediately without pausing
        val req11 = HttpRequestData(method = "GET", url = "http://test.com/11", headers = emptyList())
        val action11 = manager.interceptOrPass("req-11", req11, isHttps = false, hostname = "test.com")

        assertTrue("11th request must pass through immediately", action11 is InterceptAction.Forward)
        assertEquals(req11, (action11 as InterceptAction.Forward).request)
        assertEquals("Queue count must still be 10", 10, manager.getPausedCount())

        // Release all to clean up
        manager.forwardAll()
        threads.forEach { it.join(1000) }
        assertEquals(0, manager.getPausedCount())
    }

    @Test
    fun timeout_autoForwardsRequest() {
        // Fast timeout of 1 second for test
        val fastManager = InterceptManager(maxPaused = 10, timeoutSeconds = 1L)
        fastManager.setEnabled(true)

        val request = HttpRequestData(
            method = "GET",
            url = "https://example.com/slow",
            headers = listOf("Host: example.com")
        )

        val start = System.currentTimeMillis()
        val action = fastManager.interceptOrPass("req-timeout", request, isHttps = true, hostname = "example.com")
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Elapsed should be around 1s ($elapsed ms)", elapsed >= 900)
        assertTrue("Timed out request must auto-forward unchanged", action is InterceptAction.Forward)
        assertEquals(request, (action as InterceptAction.Forward).request)
        assertEquals(0, fastManager.getPausedCount())
    }

    @Test
    fun forwardAll_and_dropAll_unblockWaitingWorkers() {
        manager.setEnabled(true)
        val actions = java.util.concurrent.ConcurrentLinkedQueue<InterceptAction>()
        val threads = mutableListOf<Thread>()

        for (i in 1..5) {
            val req = HttpRequestData(method = "GET", url = "http://test.com/$i", headers = emptyList())
            val t = Thread {
                actions.add(manager.interceptOrPass("req-fa-$i", req, isHttps = false, hostname = "test.com"))
            }
            threads.add(t)
            t.start()
        }

        Thread.sleep(50)
        assertEquals(5, manager.getPausedCount())

        manager.forwardAll()
        threads.forEach { it.join(1000) }

        assertEquals(0, manager.getPausedCount())
        assertEquals(5, actions.size)
        assertTrue(actions.all { it is InterceptAction.Forward })
    }

    @Test
    fun releaseAll_onShutdown_unblocksWaitingWorkers() {
        manager.setEnabled(true)
        val actions = java.util.concurrent.ConcurrentLinkedQueue<InterceptAction>()
        val threads = mutableListOf<Thread>()

        for (i in 1..4) {
            val req = HttpRequestData(method = "POST", url = "http://test.com/$i", headers = emptyList())
            val t = Thread {
                actions.add(manager.interceptOrPass("req-shutdown-$i", req, isHttps = false, hostname = "test.com"))
            }
            threads.add(t)
            t.start()
        }

        Thread.sleep(50)
        assertEquals(4, manager.getPausedCount())

        manager.releaseAll("proxy_stopped")
        threads.forEach { it.join(1000) }

        assertEquals(0, manager.getPausedCount())
        assertEquals(4, actions.size)
    }

    @Test
    fun framingNormalization_postWithoutBody_setsContentLength0() {
        val postRequest = HttpRequestData(
            method = "POST",
            url = "http://example.com/action",
            headers = listOf("Host: example.com")
        )

        val normalized = InterceptManager.normalizeFramingHeaders(postRequest)
        assertTrue("POST without body must have Content-Length: 0", normalized.headers.contains("Content-Length: 0"))
    }

    @Test
    fun concurrentRequests_threadSafety() {
        manager.setEnabled(true)
        val executor = Executors.newFixedThreadPool(16)
        val requestCount = 30
        val completedCount = AtomicInteger(0)

        for (i in 1..requestCount) {
            executor.execute {
                val req = HttpRequestData(method = "GET", url = "https://example.com/$i", headers = emptyList())
                manager.interceptOrPass("req-conc-$i", req, isHttps = true, hostname = "example.com")
                completedCount.incrementAndGet()
            }
        }

        // Allow some requests to pause
        Thread.sleep(100)
        assertTrue("Queue size should never exceed 10", manager.getPausedCount() <= 10)

        // Rapidly forward all
        manager.forwardAll()

        executor.shutdown()
        val finished = executor.awaitTermination(5, TimeUnit.SECONDS)
        assertTrue("All tasks should finish", finished)
        assertEquals(requestCount, completedCount.get())
        assertEquals(0, manager.getPausedCount())
    }
}
