package com.example.basicbrowser.repeater.data

import android.util.Log
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.repeater.engine.RepeaterEngine
import com.example.basicbrowser.repeater.model.ExecutionState
import com.example.basicbrowser.repeater.model.RepeaterItem
import com.example.basicbrowser.repeater.model.RepeaterResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RepeaterRepository(
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val engine: RepeaterEngine = RepeaterEngine.getInstance()
) {

    companion object {
        const val DEFAULT_MAX_ITEMS = 5
        private const val TAG = "RepeaterRepository"

        @Volatile
        private var instance: RepeaterRepository? = null

        fun getInstance(): RepeaterRepository {
            return instance ?: synchronized(this) {
                instance ?: RepeaterRepository().also { instance = it }
            }
        }
    }

    private val _items = MutableStateFlow<List<RepeaterItem>>(emptyList())
    val items: StateFlow<List<RepeaterItem>> = _items.asStateFlow()

    private val _selectedItemId = MutableStateFlow<String?>(null)
    val selectedItemId: StateFlow<String?> = _selectedItemId.asStateFlow()

    private val itemCounter = AtomicInteger(1)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RepeaterWorker").apply { isDaemon = true }
    }

    @Synchronized
    fun addItem(request: HttpRequestData, customName: String? = null): RepeaterItem {
        val currentList = _items.value.toMutableList()

        // Enforce maximum capacity of saved Repeater requests (evict oldest if full)
        while (currentList.size >= maxItems) {
            val evicted = currentList.removeAt(0)
            Log.i(TAG, "Evicted oldest Repeater item '${evicted.name}' (${evicted.id}) to enforce cap of $maxItems")
        }

        val name = customName ?: "Req ${itemCounter.getAndIncrement()}"
        val newItem = RepeaterItem(
            name = name,
            request = request
        )
        currentList.add(newItem)
        _items.value = currentList
        _selectedItemId.value = newItem.id
        return newItem
    }

    fun createBlankItem(): RepeaterItem {
        val blankRequest = HttpRequestData(
            method = "GET",
            url = "https://example.com/",
            httpVersion = "HTTP/1.1",
            headers = listOf(
                "Host: example.com",
                "User-Agent: BasicBrowser/1.0",
                "Accept: */*",
                "Connection: close"
            ),
            body = null
        )
        return addItem(blankRequest)
    }

    @Synchronized
    fun updateRequest(id: String, updatedRequest: HttpRequestData) {
        val currentList = _items.value
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            val updatedList = currentList.toMutableList()
            updatedList[index] = updatedList[index].copy(request = updatedRequest)
            _items.value = updatedList
        }
    }

    @Synchronized
    fun deleteItem(id: String) {
        val currentList = _items.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = currentList[index]
            if (item.executionState is ExecutionState.Executing) {
                cancelRequest(id)
            }
            currentList.removeAt(index)
            _items.value = currentList

            if (_selectedItemId.value == id) {
                _selectedItemId.value = currentList.lastOrNull()?.id
            }
        }
    }

    @Synchronized
    fun clearResponse(id: String) {
        val currentList = _items.value
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            val updatedList = currentList.toMutableList()
            updatedList[index] = updatedList[index].copy(
                response = null,
                executionState = ExecutionState.Idle
            )
            _items.value = updatedList
        }
    }

    fun selectItem(id: String) {
        _selectedItemId.value = id
    }

    /**
     * Dispatches request execution on the dedicated single-thread worker.
     */
    fun sendRequest(id: String) {
        synchronized(this) {
            val currentList = _items.value
            val index = currentList.indexOfFirst { it.id == id }
            if (index == -1) return

            val item = currentList[index]
            if (item.executionState is ExecutionState.Executing || engine.isBusy()) {
                Log.w(TAG, "Execution already in progress, ignoring send for $id")
                return
            }

            val updatedList = currentList.toMutableList()
            updatedList[index] = item.copy(executionState = ExecutionState.Executing)
            _items.value = updatedList
        }

        executor.submit {
            val currentItem = _items.value.find { it.id == id } ?: return@submit
            try {
                val response = engine.execute(currentItem.request)
                synchronized(this) {
                    val list = _items.value.toMutableList()
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx != -1) {
                        list[idx] = list[idx].copy(
                            response = response,
                            executionState = ExecutionState.Idle
                        )
                        _items.value = list
                    }
                }
            } catch (e: Exception) {
                val isCancel = e.message?.contains("cancelled", ignoreCase = true) == true
                synchronized(this) {
                    val list = _items.value.toMutableList()
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx != -1) {
                        list[idx] = list[idx].copy(
                            executionState = if (isCancel) {
                                ExecutionState.Cancelled
                            } else {
                                ExecutionState.Error(e.message ?: "Network error")
                            }
                        )
                        _items.value = list
                    }
                }
            }
        }
    }

    fun cancelRequest(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        if (item.executionState is ExecutionState.Executing) {
            engine.cancelActiveRequest()
        }
    }
}

