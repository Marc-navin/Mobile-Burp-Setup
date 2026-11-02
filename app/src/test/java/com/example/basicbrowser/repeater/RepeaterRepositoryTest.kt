package com.example.basicbrowser.repeater

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.repeater.data.RepeaterRepository
import com.example.basicbrowser.repeater.engine.RepeaterEngine
import com.example.basicbrowser.repeater.model.ExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

class RepeaterRepositoryTest {

    @Test
    fun testAddItem_enforcesConfiguredCapacityLimit() {
        val repo = RepeaterRepository(maxItems = 3, engine = RepeaterEngine())

        val item1 = repo.addItem(HttpRequestData(method = "GET", url = "http://example.com/1", headers = emptyList()), "Req 1")
        val item2 = repo.addItem(HttpRequestData(method = "GET", url = "http://example.com/2", headers = emptyList()), "Req 2")
        val item3 = repo.addItem(HttpRequestData(method = "GET", url = "http://example.com/3", headers = emptyList()), "Req 3")

        assertEquals(3, repo.items.value.size)
        assertEquals("Req 3", repo.items.value.last().name)

        // Adding 4th item should evict item1 (FIFO)
        val item4 = repo.addItem(HttpRequestData(method = "GET", url = "http://example.com/4", headers = emptyList()), "Req 4")

        assertEquals(3, repo.items.value.size)
        assertTrue(repo.items.value.none { it.id == item1.id })
        assertTrue(repo.items.value.any { it.id == item2.id })
        assertTrue(repo.items.value.any { it.id == item3.id })
        assertTrue(repo.items.value.any { it.id == item4.id })
        assertEquals(item4.id, repo.selectedItemId.value)
    }

    @Test
    fun testCreateBlankItem() {
        val repo = RepeaterRepository(maxItems = 5, engine = RepeaterEngine())
        val blank = repo.createBlankItem()

        assertNotNull(blank)
        assertEquals("GET", blank.request.method)
        assertEquals("https://example.com/", blank.request.url)
        assertTrue(blank.request.headers.any { it.contains("Host: example.com") })
        assertNull(blank.request.body)
        assertEquals(ExecutionState.Idle, blank.executionState)
    }

    @Test
    fun testUpdateRequest() {
        val repo = RepeaterRepository(maxItems = 5, engine = RepeaterEngine())
        val item = repo.createBlankItem()

        val updatedData = item.request.copy(
            method = "POST",
            url = "https://custom.org/api",
            body = "test".toByteArray()
        )
        repo.updateRequest(item.id, updatedData)

        val retrieved = repo.items.value.find { it.id == item.id }
        assertNotNull(retrieved)
        assertEquals("POST", retrieved?.request?.method)
        assertEquals("https://custom.org/api", retrieved?.request?.url)
        assertEquals("test", retrieved?.request?.body?.let { String(it) })
    }

    @Test
    fun testDeleteItem_andClearResponse() {
        val repo = RepeaterRepository(maxItems = 5, engine = RepeaterEngine())
        val item1 = repo.addItem(HttpRequestData(method = "GET", url = "http://a.com", headers = emptyList()))
        val item2 = repo.addItem(HttpRequestData(method = "GET", url = "http://b.com", headers = emptyList()))

        assertEquals(2, repo.items.value.size)
        repo.deleteItem(item1.id)

        assertEquals(1, repo.items.value.size)
        assertEquals(item2.id, repo.items.value[0].id)
        assertEquals(item2.id, repo.selectedItemId.value)
    }

    @Test
    fun testSendRequest_updatesItemWithResponse() {
        val server = ServerSocket(0)
        val port = server.localPort

        val thread = Thread {
            val client = server.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val writer = OutputStreamWriter(client.getOutputStream())
            writer.write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nPONG!!")
            writer.flush()
            client.close()
            server.close()
        }
        thread.start()

        val repo = RepeaterRepository(maxItems = 5, engine = RepeaterEngine())
        val item = repo.addItem(HttpRequestData(method = "GET", url = "http://127.0.0.1:$port/ping", headers = emptyList()))

        repo.sendRequest(item.id)

        // Wait up to 3 seconds for background worker to complete
        var tries = 0
        while (tries < 30) {
            val current = repo.items.value.find { it.id == item.id }
            if (current?.response != null) break
            Thread.sleep(100)
            tries++
        }

        val completed = repo.items.value.find { it.id == item.id }
        assertNotNull(completed?.response)
        assertEquals(200, completed?.response?.statusCode)
        assertEquals("PONG!!", completed?.response?.body?.let { String(it, StandardCharsets.UTF_8) })
        assertEquals(ExecutionState.Idle, completed?.executionState)
    }
}

