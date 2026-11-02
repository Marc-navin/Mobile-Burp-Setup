package com.example.basicbrowser.data.repository

import com.example.basicbrowser.data.model.HttpHistoryItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

class HistoryMemoryStressTest {

    private lateinit var repository: HttpHistoryRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        HttpHistoryRepository.resetInstance()
        repository = HttpHistoryRepository(maxEntries = 200)
        tempDir = File(System.getProperty("java.io.tmpdir"), "test_history_stress_" + UUID.randomUUID())
        tempDir.mkdirs()
        repository.setTempFilesDir(tempDir)
    }

    @After
    fun tearDown() {
        repository.clear()
        tempDir.deleteRecursively()
        HttpHistoryRepository.resetInstance()
    }

    private fun getUsedMemoryBytes(): Long {
        System.gc()
        System.runFinalization()
        System.gc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun test1_repeatedSmallResponses_memoryRemainsMinimal() {
        val initialMem = getUsedMemoryBytes()

        for (i in 1..200) {
            val item = HttpHistoryItem(
                id = "small-$i",
                method = "GET",
                url = "https://example.com/api/$i",
                requestHeaders = listOf("Host: example.com"),
                requestBody = null,
                statusCode = 200,
                responseHeaders = listOf("HTTP/1.1 200 OK", "Content-Type: application/json"),
                responseBody = "{\"status\":\"ok\",\"index\":$i}".toByteArray()
            )
            repository.addOrUpdate(item)
        }

        assertEquals(200, repository.getItemCount())
        assertEquals(200, repository.history.value.size)

        val postMem = getUsedMemoryBytes()
        val deltaMb = (postMem - initialMem) / (1024 * 1024)
        // 200 small entries should consume < 10 MB heap delta
        assertTrue("Memory delta for 200 small entries should be < 10 MB, got ${deltaMb}MB", deltaMb < 10)
    }

    @Test
    fun test2_eviction_releasesReferencesAndPermitsGarbageCollection() {
        val weakRefs = mutableListOf<WeakReference<ByteArray>>()

        // Fill 200 entries with 100 KB payload each
        for (i in 1..200) {
            val body = ByteArray(100 * 1024) { (it % 128).toByte() }
            if (i <= 50) {
                weakRefs.add(WeakReference(body))
            }
            val item = HttpHistoryItem(
                id = "item-$i",
                method = "POST",
                url = "https://example.com/upload/$i",
                requestHeaders = listOf("Host: example.com"),
                requestBody = body,
                statusCode = 200,
                responseHeaders = listOf("HTTP/1.1 200 OK"),
                responseBody = body
            )
            repository.addOrUpdate(item)
        }

        assertEquals(200, repository.getItemCount())
        // Items 1..50 are currently in history (since they are inserted at top, item 200 is index 0, item 1 is index 199)
        assertEquals("item-200", repository.history.value[0].id)
        assertEquals("item-1", repository.history.value[199].id)

        // Add 50 more items to evict items 1..50
        for (i in 201..250) {
            val body = ByteArray(1024)
            val item = HttpHistoryItem(
                id = "item-$i",
                method = "GET",
                url = "https://example.com/item/$i",
                requestHeaders = listOf("Host: example.com"),
                responseBody = body
            )
            repository.addOrUpdate(item)
        }

        assertEquals(200, repository.getItemCount())
        // Confirm items 1..50 are evicted
        for (i in 1..50) {
            assertNull("Item $i should be evicted", repository.getById("item-$i"))
        }

        // Force GC to verify that the evicted byte arrays are collected
        System.gc()
        System.runFinalization()
        System.gc()

        var collectedCount = 0
        for (ref in weakRefs) {
            if (ref.get() == null) {
                collectedCount++
            }
        }
        // At least some or all weak references to evicted ByteArrays must be reclaimed
        assertTrue("Evicted byte arrays should be reclaimed by GC, collected: $collectedCount / 50", collectedCount > 0)
    }

    @Test
    fun test3_clearingHistory_releasesAllItemReferences() {
        val weakRefs = mutableListOf<WeakReference<ByteArray>>()

        for (i in 1..50) {
            val body = ByteArray(200 * 1024)
            weakRefs.add(WeakReference(body))
            val item = HttpHistoryItem(
                id = "clear-test-$i",
                method = "POST",
                url = "https://example.com/$i",
                requestHeaders = listOf("Host: example.com"),
                requestBody = body,
                responseBody = body
            )
            repository.addOrUpdate(item)
        }

        assertEquals(50, repository.getItemCount())
        repository.clear()

        assertEquals(0, repository.getItemCount())
        assertTrue(repository.history.value.isEmpty())

        System.gc()
        System.runFinalization()
        System.gc()

        var clearedCount = 0
        for (ref in weakRefs) {
            if (ref.get() == null) {
                clearedCount++
            }
        }
        assertTrue("Cleared history items must be reclaimed by GC, collected: $clearedCount / 50", clearedCount > 0)
    }

    @Test
    fun test4_tempFileCleanup_onlyDeletesHistoryFilesAndPreservesOthers() {
        val unrelatedFile = File(tempDir, "unrelated_cache.dat").apply { writeText("keep me") }
        val webviewDir = File(tempDir, "WebView").apply { mkdirs(); File(this, "cookies.db").writeText("cookies") }
        val historyTemp1 = File(tempDir, "body_item-123.tmp").apply { writeText("temp body") }
        val historyTemp2 = File(tempDir, "body_item-456.tmp").apply { writeText("temp body 2") }

        assertTrue(unrelatedFile.exists())
        assertTrue(historyTemp1.exists())
        assertTrue(historyTemp2.exists())

        // Re-initialize tempFilesDir (which triggers cleanAllTempFiles)
        repository.setTempFilesDir(tempDir)

        // Only body_*.tmp files should be deleted
        assertFalse("body_item-123.tmp should be deleted", historyTemp1.exists())
        assertFalse("body_item-456.tmp should be deleted", historyTemp2.exists())
        assertTrue("unrelated_cache.dat must be preserved", unrelatedFile.exists())
        assertTrue("WebView dir must be preserved", webviewDir.exists())
        assertTrue("cookies.db must be preserved", File(webviewDir, "cookies.db").exists())
    }

    @Test
    fun test5_tempFileCleanup_handlesNonExistentDirectoryGracefully() {
        val nonExistentDir = File(tempDir, "does_not_exist_xyz")
        assertFalse(nonExistentDir.exists())

        // Should not throw any exception
        repository.setTempFilesDir(nonExistentDir)
        repository.clear()
    }
}

