package com.example.basicbrowser.data.repository

import android.content.Context
import android.util.Log
import com.example.basicbrowser.data.model.HighlightColor
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.model.RequestAnnotation
import com.example.basicbrowser.data.model.SavedRequestItem
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages per-request annotations (notes and color highlights) and independently
 * saved requests/responses. Persisted to disk across app restarts.
 */
class RequestAnnotationRepository internal constructor(
    private val annotationsFile: File? = null,
    private val savedRequestsFile: File? = null
) {

    private val _annotations = MutableStateFlow<Map<String, RequestAnnotation>>(emptyMap())
    val annotations: StateFlow<Map<String, RequestAnnotation>> = _annotations.asStateFlow()

    private val _savedRequests = MutableStateFlow<List<SavedRequestItem>>(emptyList())
    val savedRequests: StateFlow<List<SavedRequestItem>> = _savedRequests.asStateFlow()

    init {
        loadFromStorage()
    }

    @Synchronized
    fun setNote(id: String, note: String) {
        val current = _annotations.value.toMutableMap()
        val existing = current[id] ?: RequestAnnotation(id = id)
        current[id] = existing.copy(note = note.trim(), updatedAt = System.currentTimeMillis())
        _annotations.value = current
        saveAnnotationsToStorage()
    }

    @Synchronized
    fun setHighlight(id: String, color: HighlightColor) {
        val current = _annotations.value.toMutableMap()
        val existing = current[id] ?: RequestAnnotation(id = id)
        current[id] = existing.copy(highlightColor = color, updatedAt = System.currentTimeMillis())
        _annotations.value = current
        saveAnnotationsToStorage()
    }

    @Synchronized
    fun getAnnotation(id: String): RequestAnnotation? {
        return _annotations.value[id]
    }

    @Synchronized
    fun isSaved(id: String): Boolean {
        return _savedRequests.value.any { it.id == id }
    }

    @Synchronized
    fun saveItem(item: HttpHistoryItem): SavedRequestItem {
        val current = _savedRequests.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == item.id }
        val annotation = _annotations.value[item.id]

        val savedItem = SavedRequestItem(
            id = item.id,
            method = item.method,
            url = item.url,
            requestHeaders = item.requestHeaders,
            requestBody = item.requestBody,
            statusCode = item.statusCode,
            responseHeaders = item.responseHeaders,
            responseBody = item.responseBody,
            timestamp = item.timestamp,
            note = annotation?.note ?: "",
            highlightColor = annotation?.highlightColor ?: HighlightColor.NONE,
            savedAt = System.currentTimeMillis()
        )

        if (existingIndex >= 0) {
            current[existingIndex] = savedItem
        } else {
            current.add(0, savedItem)
        }
        _savedRequests.value = current

        // Update annotation isSaved flag
        val currentAnnotations = _annotations.value.toMutableMap()
        val existingAnn = currentAnnotations[item.id] ?: RequestAnnotation(id = item.id)
        currentAnnotations[item.id] = existingAnn.copy(isSaved = true)
        _annotations.value = currentAnnotations

        saveSavedRequestsToStorage()
        saveAnnotationsToStorage()
        return savedItem
    }

    @Synchronized
    fun saveItem(req: HttpRequestData, id: String): SavedRequestItem {
        val current = _savedRequests.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == id }
        val annotation = _annotations.value[id]

        val savedItem = SavedRequestItem(
            id = id,
            method = req.method,
            url = req.url,
            requestHeaders = listOf("${req.method} ${req.url} ${req.httpVersion}") + req.headers,
            requestBody = req.body,
            statusCode = null,
            responseHeaders = emptyList(),
            responseBody = null,
            timestamp = System.currentTimeMillis(),
            note = annotation?.note ?: "",
            highlightColor = annotation?.highlightColor ?: HighlightColor.NONE,
            savedAt = System.currentTimeMillis()
        )

        if (existingIndex >= 0) {
            current[existingIndex] = savedItem
        } else {
            current.add(0, savedItem)
        }
        _savedRequests.value = current

        val currentAnnotations = _annotations.value.toMutableMap()
        val existingAnn = currentAnnotations[id] ?: RequestAnnotation(id = id)
        currentAnnotations[id] = existingAnn.copy(isSaved = true)
        _annotations.value = currentAnnotations

        saveSavedRequestsToStorage()
        saveAnnotationsToStorage()
        return savedItem
    }

    @Synchronized
    fun removeSavedItem(id: String): Boolean {
        val current = _savedRequests.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _savedRequests.value = current
            val currentAnnotations = _annotations.value.toMutableMap()
            currentAnnotations[id]?.let {
                currentAnnotations[id] = it.copy(isSaved = false)
                _annotations.value = currentAnnotations
            }
            saveSavedRequestsToStorage()
            saveAnnotationsToStorage()
            return true
        }
        return false
    }

    @Synchronized
    fun getSavedItem(id: String): SavedRequestItem? {
        return _savedRequests.value.find { it.id == id }
    }

    @Synchronized
    fun getAllSavedItems(): List<SavedRequestItem> {
        return _savedRequests.value
    }

    @Synchronized
    fun clearAllAnnotations() {
        _annotations.value = emptyMap()
        saveAnnotationsToStorage()
    }

    @Synchronized
    fun clearAllSavedItems() {
        _savedRequests.value = emptyList()
        saveSavedRequestsToStorage()
    }

    private fun loadFromStorage() {
        // Load annotations
        annotationsFile?.let { file ->
            try {
                if (file.exists() && file.length() > 0) {
                    val json = file.readText(Charsets.UTF_8)
                    _annotations.value = parseAnnotationsFromJson(json)
                }
            } catch (e: Exception) {
                safeLog("Error loading annotations: ${e.message}")
            }
        }

        // Load saved requests
        savedRequestsFile?.let { file ->
            try {
                if (file.exists() && file.length() > 0) {
                    val json = file.readText(Charsets.UTF_8)
                    _savedRequests.value = parseSavedRequestsFromJson(json)
                }
            } catch (e: Exception) {
                safeLog("Error loading saved requests: ${e.message}")
            }
        }
    }

    private fun saveAnnotationsToStorage() {
        annotationsFile?.let { file ->
            try {
                val json = serializeAnnotationsToJson(_annotations.value)
                file.parentFile?.mkdirs()
                file.writeText(json, Charsets.UTF_8)
            } catch (e: Exception) {
                safeLog("Error saving annotations: ${e.message}")
            }
        }
    }

    private fun saveSavedRequestsToStorage() {
        savedRequestsFile?.let { file ->
            try {
                val json = serializeSavedRequestsToJson(_savedRequests.value)
                file.parentFile?.mkdirs()
                file.writeText(json, Charsets.UTF_8)
            } catch (e: Exception) {
                safeLog("Error saving saved requests: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "RequestAnnotationRepo"
        const val ANNOTATIONS_FILE_NAME = "request_annotations.json"
        const val SAVED_REQUESTS_FILE_NAME = "saved_requests.json"

        @Volatile
        private var instance: RequestAnnotationRepository? = null

        fun getInstance(context: Context? = null): RequestAnnotationRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val annFile = context?.applicationContext?.let {
                        File(it.filesDir, ANNOTATIONS_FILE_NAME)
                    }
                    val savedFile = context?.applicationContext?.let {
                        File(it.filesDir, SAVED_REQUESTS_FILE_NAME)
                    }
                    RequestAnnotationRepository(annFile, savedFile).also { instance = it }
                }
            }
        }

        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }

        private fun safeLog(msg: String) {
            try {
                Log.d(TAG, msg)
            } catch (_: Throwable) {}
        }

        internal fun serializeAnnotationsToJson(map: Map<String, RequestAnnotation>): String {
            val sb = StringBuilder("[\n")
            val list = map.values.toList()
            list.forEachIndexed { i, ann ->
                sb.append("  {\n")
                sb.append("    \"id\": \"${escapeJson(ann.id)}\",\n")
                sb.append("    \"note\": \"${escapeJson(ann.note)}\",\n")
                sb.append("    \"highlightColor\": \"${ann.highlightColor.name}\",\n")
                sb.append("    \"isSaved\": ${ann.isSaved},\n")
                sb.append("    \"updatedAt\": ${ann.updatedAt}\n")
                sb.append("  }")
                if (i < list.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]\n")
            return sb.toString()
        }

        internal fun parseAnnotationsFromJson(json: String): Map<String, RequestAnnotation> {
            val map = mutableMapOf<String, RequestAnnotation>()
            val objectRegex = Regex("""\{([^{}]+)\}""")
            for (match in objectRegex.findAll(json)) {
                val content = match.groupValues[1]
                val id = extractJsonString(content, "id") ?: continue
                val note = extractJsonString(content, "note") ?: ""
                val colorStr = extractJsonString(content, "highlightColor")
                val color = HighlightColor.fromString(colorStr)
                val isSaved = extractJsonBoolean(content, "isSaved") ?: false
                val updatedAt = extractJsonLong(content, "updatedAt") ?: System.currentTimeMillis()

                map[id] = RequestAnnotation(
                    id = id,
                    note = note,
                    highlightColor = color,
                    isSaved = isSaved,
                    updatedAt = updatedAt
                )
            }
            return map
        }

        internal fun serializeSavedRequestsToJson(items: List<SavedRequestItem>): String {
            val sb = StringBuilder("[\n")
            items.forEachIndexed { i, item ->
                sb.append("  {\n")
                sb.append("    \"id\": \"${escapeJson(item.id)}\",\n")
                sb.append("    \"method\": \"${escapeJson(item.method)}\",\n")
                sb.append("    \"url\": \"${escapeJson(item.url)}\",\n")
                sb.append("    \"requestHeaders\": ${serializeStringList(item.requestHeaders)},\n")
                sb.append("    \"requestBodyBase64\": ${if (item.requestBody != null) "\"${toBase64(item.requestBody)}\"" else "null"},\n")
                sb.append("    \"statusCode\": ${item.statusCode},\n")
                sb.append("    \"responseHeaders\": ${serializeStringList(item.responseHeaders)},\n")
                sb.append("    \"responseBodyBase64\": ${if (item.responseBody != null) "\"${toBase64(item.responseBody)}\"" else "null"},\n")
                sb.append("    \"timestamp\": ${item.timestamp},\n")
                sb.append("    \"note\": \"${escapeJson(item.note)}\",\n")
                sb.append("    \"highlightColor\": \"${item.highlightColor.name}\",\n")
                sb.append("    \"savedAt\": ${item.savedAt}\n")
                sb.append("  }")
                if (i < items.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]\n")
            return sb.toString()
        }

        internal fun parseSavedRequestsFromJson(json: String): List<SavedRequestItem> {
            val list = mutableListOf<SavedRequestItem>()
            val objectRegex = Regex("""\{([^{}]+)\}""")
            for (match in objectRegex.findAll(json)) {
                val content = match.groupValues[1]
                val id = extractJsonString(content, "id") ?: continue
                val method = extractJsonString(content, "method") ?: "GET"
                val url = extractJsonString(content, "url") ?: continue
                val requestHeaders = extractStringList(content, "requestHeaders")
                val reqBodyBase64 = extractJsonString(content, "requestBodyBase64")
                val reqBody = reqBodyBase64?.let { fromBase64(it) }
                val statusCode = extractJsonInt(content, "statusCode")
                val responseHeaders = extractStringList(content, "responseHeaders")
                val respBodyBase64 = extractJsonString(content, "responseBodyBase64")
                val respBody = respBodyBase64?.let { fromBase64(it) }
                val timestamp = extractJsonLong(content, "timestamp") ?: System.currentTimeMillis()
                val note = extractJsonString(content, "note") ?: ""
                val colorStr = extractJsonString(content, "highlightColor")
                val color = HighlightColor.fromString(colorStr)
                val savedAt = extractJsonLong(content, "savedAt") ?: System.currentTimeMillis()

                list.add(
                    SavedRequestItem(
                        id = id,
                        method = method,
                        url = url,
                        requestHeaders = requestHeaders,
                        requestBody = reqBody,
                        statusCode = statusCode,
                        responseHeaders = responseHeaders,
                        responseBody = respBody,
                        timestamp = timestamp,
                        note = note,
                        highlightColor = color,
                        savedAt = savedAt
                    )
                )
            }
            return list
        }

        private fun serializeStringList(list: List<String>): String {
            val sb = StringBuilder("[")
            list.forEachIndexed { i, s ->
                sb.append("\"${escapeJson(s)}\"")
                if (i < list.size - 1) sb.append(",")
            }
            sb.append("]")
            return sb.toString()
        }

        private fun extractStringList(content: String, key: String): List<String> {
            val regex = Regex("""\"$key\"\s*:\s*\[(.*?)\]""")
            val match = regex.find(content) ?: return emptyList()
            val inner = match.groupValues[1].trim()
            if (inner.isEmpty()) return emptyList()
            val itemRegex = Regex("""\"((?:[^"\\]|\\.)*)\"""")
            return itemRegex.findAll(inner).map { unescapeJson(it.groupValues[1]) }.toList()
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        private fun unescapeJson(str: String): String {
            return str.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }

        private fun extractJsonString(content: String, key: String): String? {
            val nullMatch = Regex("""\"$key\"\s*:\s*null""").find(content)
            if (nullMatch != null) return null
            val regex = Regex("""\"$key\"\s*:\s*\"((?:[^"\\]|\\.)*)\"""")
            val match = regex.find(content) ?: return null
            return unescapeJson(match.groupValues[1])
        }

        private fun extractJsonBoolean(content: String, key: String): Boolean? {
            val regex = Regex("""\"$key\"\s*:\s*(true|false)""")
            val match = regex.find(content) ?: return null
            return match.groupValues[1].toBoolean()
        }

        private fun extractJsonInt(content: String, key: String): Int? {
            val nullMatch = Regex("""\"$key\"\s*:\s*null""").find(content)
            if (nullMatch != null) return null
            val regex = Regex("""\"$key\"\s*:\s*(-?\d+)""")
            val match = regex.find(content) ?: return null
            return match.groupValues[1].toIntOrNull()
        }

        private fun extractJsonLong(content: String, key: String): Long? {
            val nullMatch = Regex("""\"$key\"\s*:\s*null""").find(content)
            if (nullMatch != null) return null
            val regex = Regex("""\"$key\"\s*:\s*(-?\d+)""")
            val match = regex.find(content) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        // Pure Kotlin RFC 4648 Base64 conversion (zero dependencies)
        private val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        internal fun toBase64(data: ByteArray): String {
            val sb = java.lang.StringBuilder()
            var i = 0
            while (i < data.size) {
                val b0 = data[i++].toInt() and 0xFF
                val b1 = if (i < data.size) data[i++].toInt() and 0xFF else -1
                val b2 = if (i < data.size) data[i++].toInt() and 0xFF else -1

                val d0 = b0 ushr 2
                val d1 = ((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)
                val d2 = if (b1 >= 0) ((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0) else -1
                val d3 = if (b2 >= 0) b2 and 0x3F else -1

                sb.append(BASE64_ALPHABET[d0])
                sb.append(BASE64_ALPHABET[d1])
                sb.append(if (d2 >= 0) BASE64_ALPHABET[d2] else '=')
                sb.append(if (d3 >= 0) BASE64_ALPHABET[d3] else '=')
            }
            return sb.toString()
        }

        internal fun fromBase64(base64: String): ByteArray {
            val clean = base64.filter { it in BASE64_ALPHABET || it == '=' }
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < clean.length) {
                val c0 = clean[i++]
                val c1 = if (i < clean.length) clean[i++] else '='
                val c2 = if (i < clean.length) clean[i++] else '='
                val c3 = if (i < clean.length) clean[i++] else '='

                val d0 = BASE64_ALPHABET.indexOf(c0)
                val d1 = BASE64_ALPHABET.indexOf(c1)
                val d2 = if (c2 != '=') BASE64_ALPHABET.indexOf(c2) else -1
                val d3 = if (c3 != '=') BASE64_ALPHABET.indexOf(c3) else -1

                if (d0 < 0 || d1 < 0) break
                val b0 = (d0 shl 2) or (d1 ushr 4)
                out.write(b0)

                if (d2 >= 0) {
                    val b1 = ((d1 and 0x0F) shl 4) or (d2 ushr 2)
                    out.write(b1)
                    if (d3 >= 0) {
                        val b2 = ((d2 and 0x03) shl 6) or d3
                        out.write(b2)
                    }
                }
            }
            return out.toByteArray()
        }
    }
}

