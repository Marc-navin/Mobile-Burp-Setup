package com.example.basicbrowser.data.repository

import com.example.basicbrowser.data.model.HttpHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpHistoryRepositoryTest {

    private lateinit var repository: HttpHistoryRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        HttpHistoryRepository.resetInstance()
        repository = HttpHistoryRepository(maxEntries = 200)
        tempDir = File(System.getProperty("java.io.tmpdir"), "test_history_repo_" + UUID.randomUUID())
        tempDir.mkdirs()
        repository.setTempFilesDir(tempDir)
    }

    @Test
    fun testHistoryCapacityAndEviction() {
        repository.setMaxEntriesLimit(50)

        // Add 75 entries
        for (i in 1..75) {
            val item = HttpHistoryItem(
                id = "item-$i",
                method = "GET",
                url = "http://example.com/$i",
                requestHeaders = listOf("GET / HTTP/1.1")
            )
            repository.addOrUpdate(item)
        }

        val history = repository.history.value
        assertEquals(50, history.size)
        assertEquals(50, repository.getItemCount())

        // The most recently added item should be at index 0
        assertEquals("item-75", history[0].id)
        // The oldest surviving item should be item-26 (items 1-25 were evicted)
        assertEquals("item-26", history[49].id)
        assertNull(repository.getById("item-1"))
        assertNull(repository.getById("item-25"))
        assertNotNull(repository.getById("item-26"))
        assertNotNull(repository.getById("item-75"))
    }

    @Test
    fun testUpdateExistingItemDoesNotDuplicate() {
        val itemId = "unique-item-123"
        val item1 = HttpHistoryItem(
            id = itemId,
            method = "GET",
            url = "http://example.com/test",
            requestHeaders = listOf("GET /test HTTP/1.1")
        )
        repository.addOrUpdate(item1)
        assertEquals(1, repository.getItemCount())

        val item2 = item1.copy(
            statusCode = 200,
            responseHeaders = listOf("HTTP/1.1 200 OK"),
            responseBody = "Hello world".toByteArray()
        )
        repository.addOrUpdate(item2)

        assertEquals(1, repository.getItemCount())
        val updated = repository.getById(itemId)
        assertNotNull(updated)
        assertEquals(200, updated!!.statusCode)
        assertEquals("Hello world", String(updated.responseBody!!))
    }

    @Test
    fun testIdenticalUpdateSkipped() {
        val item = HttpHistoryItem(
            id = "item-skip",
            method = "GET",
            url = "http://example.com/skip",
            requestHeaders = listOf("GET /skip HTTP/1.1")
        )
        repository.addOrUpdate(item)
        val snapshot1 = repository.history.value

        // Re-adding the identical item
        repository.addOrUpdate(item)
        val snapshot2 = repository.history.value

        // Should be the exact same list instance (no clone created)
        assertTrue(snapshot1 === snapshot2)
    }

    @Test
    fun testConcurrentHistoryUpdates() {
        repository.setMaxEntriesLimit(100)
        val threadCount = 10
        val itemsPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 1..threadCount) {
            executor.submit {
                try {
                    for (i in 1..itemsPerThread) {
                        val item = HttpHistoryItem(
                            id = "thread-$t-item-$i",
                            method = "POST",
                            url = "http://example.com/$t/$i",
                            requestHeaders = listOf("POST / HTTP/1.1")
                        )
                        repository.addOrUpdate(item)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val history = repository.history.value
        assertEquals(100, history.size)
        assertEquals(100, repository.getItemCount())
    }

    @Test
    fun testClearAndTempFilesCleanup() {
        val item = HttpHistoryItem(
            id = "temp-test-item",
            method = "GET",
            url = "http://example.com/file",
            requestHeaders = listOf("GET /file HTTP/1.1")
        )
        repository.addOrUpdate(item)

        // Create a dummy temp body file
        val tempFile = File(tempDir, "body_${item.id}.tmp")
        tempFile.writeText("cached body data")
        assertTrue(tempFile.exists())

        repository.clear()

        assertEquals(0, repository.getItemCount())
        assertTrue(repository.history.value.isEmpty())
        assertTrue(!tempFile.exists())
    }

    @Test
    fun testBodyTruncationFlagsPreserved() {
        val item = HttpHistoryItem(
            id = "trunc-test-item",
            method = "POST",
            url = "http://example.com/upload",
            requestHeaders = listOf("POST /upload HTTP/1.1"),
            requestBody = ByteArray(1024),
            requestBodyTruncated = true,
            statusCode = 200,
            responseHeaders = listOf("HTTP/1.1 200 OK"),
            responseBody = ByteArray(2048),
            responseBodyTruncated = true
        )
        repository.addOrUpdate(item)

        val retrieved = repository.getById("trunc-test-item")
        assertNotNull(retrieved)
        assertTrue(retrieved!!.requestBodyTruncated)
        assertTrue(retrieved.responseBodyTruncated)
        assertEquals(1024, retrieved.requestBody?.size)
        assertEquals(2048, retrieved.responseBody?.size)
    }
}
