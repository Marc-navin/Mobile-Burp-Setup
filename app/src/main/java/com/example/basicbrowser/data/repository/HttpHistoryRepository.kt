package com.example.basicbrowser.data.repository

import com.example.basicbrowser.data.model.HttpHistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HttpHistoryRepository internal constructor(
    var maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    private val items = ArrayList<HttpHistoryItem>()
    private val _history = MutableStateFlow<List<HttpHistoryItem>>(emptyList())
    val history: StateFlow<List<HttpHistoryItem>> = _history.asStateFlow()

    private var tempFilesDir: java.io.File? = null

    /**
     * Optional directory where temporary body spill files are stored.
     */
    fun setTempFilesDir(dir: java.io.File?) {
        tempFilesDir = dir
        cleanAllTempFiles()
    }

    /**
     * Updates the maximum number of history entries retained in memory.
     * If the new limit is smaller than the current size, older entries are evicted.
     */
    @Synchronized
    fun setMaxEntriesLimit(limit: Int) {
        require(limit > 0) { "limit must be > 0" }
        maxEntries = limit
        var changed = false
        while (items.size > maxEntries) {
            val evicted = items.removeAt(items.size - 1)
            cleanupEvictedItem(evicted)
            changed = true
        }
        if (changed) {
            _history.value = items.toList()
        }
    }

    @Synchronized
    fun addOrUpdate(item: HttpHistoryItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            val oldItem = items[index]
            if (oldItem == item) {
                // Skip cloning and StateFlow updates when there is no change
                return
            }
            items[index] = item
        } else {
            // New entry added at the top
            items.add(0, item)
            while (items.size > maxEntries) {
                val evicted = items.removeAt(items.size - 1)
                cleanupEvictedItem(evicted)
            }
        }
        _history.value = items.toList()
    }

    @Synchronized
    fun getById(id: String): HttpHistoryItem? {
        return items.find { it.id == id }
    }

    @Synchronized
    fun getItemCount(): Int = items.size

    @Synchronized
    fun clear() {
        items.clear()
        cleanAllTempFiles()
        _history.value = emptyList()
    }

    private fun cleanupEvictedItem(item: HttpHistoryItem) {
        tempFilesDir?.let { dir ->
            try {
                val file = java.io.File(dir, "body_${item.id}.tmp")
                if (file.exists()) file.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun cleanAllTempFiles() {
        tempFilesDir?.let { dir ->
            try {
                dir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("body_") && file.name.endsWith(".tmp")) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200

        @Volatile
        private var instance: HttpHistoryRepository? = null

        fun getInstance(): HttpHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: HttpHistoryRepository().also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.clear()
                instance = null
            }
        }
    }
}
