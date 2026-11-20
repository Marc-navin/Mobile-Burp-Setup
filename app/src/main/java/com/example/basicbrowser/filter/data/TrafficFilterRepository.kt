package com.example.basicbrowser.filter.data

import android.content.Context
import com.example.basicbrowser.filter.model.ContentTypeCategory
import com.example.basicbrowser.filter.model.InterceptFilterSettings
import com.example.basicbrowser.filter.model.ProtocolFilter
import com.example.basicbrowser.filter.model.ScopeFilterOption
import com.example.basicbrowser.filter.model.TrafficFilterState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * Shared state manager holding active filter settings for History and Intercept.
 *
 * Maintains independent filter states for HTTP History and Intercept, persisted
 * independently across application restarts.
 */
class TrafficFilterRepository internal constructor(
    private val storageFile: File? = null
) {

    private val _historyFilterState = MutableStateFlow(TrafficFilterState.DEFAULT)
    val historyFilterState: StateFlow<TrafficFilterState> = _historyFilterState.asStateFlow()

    private val _interceptFilterState = MutableStateFlow(TrafficFilterState.DEFAULT)
    val interceptFilterState: StateFlow<TrafficFilterState> = _interceptFilterState.asStateFlow()

    private val _interceptFilterSettings = MutableStateFlow(InterceptFilterSettings())
    val interceptFilterSettings: StateFlow<InterceptFilterSettings> = _interceptFilterSettings.asStateFlow()

    init {
        loadFromStorage()
    }

    // --- History Filter Methods ---

    @Synchronized
    fun updateHistoryFilter(newState: TrafficFilterState) {
        _historyFilterState.value = newState
        saveToStorage()
    }

    @Synchronized
    fun resetHistoryFilter() {
        _historyFilterState.value = TrafficFilterState.DEFAULT
        saveToStorage()
    }

    // --- Intercept Display Filter Methods ---

    @Synchronized
    fun updateInterceptFilter(newState: TrafficFilterState) {
        _interceptFilterState.value = newState
        saveToStorage()
    }

    @Synchronized
    fun resetInterceptFilter() {
        _interceptFilterState.value = TrafficFilterState.DEFAULT
        saveToStorage()
    }

    // --- Intercept Early Bypass Settings Methods ---

    @Synchronized
    fun updateInterceptBypassSettings(newSettings: InterceptFilterSettings) {
        _interceptFilterSettings.value = newSettings
        saveToStorage()
    }

    // Compatibility method
    @Synchronized
    fun updateInterceptFilter(newSettings: InterceptFilterSettings) {
        updateInterceptBypassSettings(newSettings)
    }

    // --- Persistence ---

    private fun loadFromStorage() {
        if (storageFile == null || !storageFile.exists()) return
        try {
            val jsonStr = storageFile.readText(Charsets.UTF_8)
            val historyBlock = extractSection(jsonStr, "history")
            if (historyBlock != null) {
                _historyFilterState.value = parseFilterStateFromJson(historyBlock)
            }
            val interceptBlock = extractSection(jsonStr, "intercept")
            if (interceptBlock != null) {
                _interceptFilterState.value = parseFilterStateFromJson(interceptBlock)
            }
            val bypassBlock = extractSection(jsonStr, "intercept_bypass")
            if (bypassBlock != null) {
                _interceptFilterSettings.value = parseBypassSettingsFromJson(bypassBlock)
            }
        } catch (_: Exception) {
            // Fallback safely to defaults on parse error
        }
    }

    private fun saveToStorage() {
        if (storageFile == null) return
        try {
            val jsonText = buildString {
                append("{\n")
                append("  \"history\": ").append(serializeFilterStateToJson(_historyFilterState.value, "  ")).append(",\n")
                append("  \"intercept\": ").append(serializeFilterStateToJson(_interceptFilterState.value, "  ")).append(",\n")
                append("  \"intercept_bypass\": ").append(serializeBypassSettingsToJson(_interceptFilterSettings.value, "  ")).append("\n")
                append("}\n")
            }
            storageFile.parentFile?.mkdirs()
            storageFile.writeText(jsonText, Charsets.UTF_8)
        } catch (_: Exception) {
            // Log/ignore silently
        }
    }

    companion object {
        private const val FILENAME = "traffic_filter_preferences.json"

        @Volatile
        private var instance: TrafficFilterRepository? = null

        fun getInstance(context: Context? = null): TrafficFilterRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val file = context?.applicationContext?.let {
                        File(it.filesDir, FILENAME)
                    }
                    TrafficFilterRepository(file).also { instance = it }
                }
            }
        }

        fun resetInstanceForTesting(storageFile: File? = null): TrafficFilterRepository {
            return synchronized(this) {
                TrafficFilterRepository(storageFile).also { instance = it }
            }
        }

        internal fun serializeFilterStateToJson(state: TrafficFilterState, indent: String = ""): String {
            val inner = "$indent  "
            val sb = StringBuilder("{\n")
            sb.append(inner).append("\"scopeFilter\": \"").append(state.scopeFilter.name).append("\",\n")
            sb.append(inner).append("\"searchQuery\": \"").append(escapeJson(state.searchQuery)).append("\",\n")
            sb.append(inner).append("\"selectedMethods\": ").append(serializeStringList(state.selectedMethods)).append(",\n")
            sb.append(inner).append("\"selectedStatusCodes\": ").append(serializeStringList(state.selectedStatusCodes)).append(",\n")
            sb.append(inner).append("\"hideImages\": ").append(state.hideImages).append(",\n")
            sb.append(inner).append("\"hideCssAndJs\": ").append(state.hideCssAndJs).append(",\n")
            sb.append(inner).append("\"contentTypes\": ").append(serializeStringList(state.contentTypes.map { it.name })).append(",\n")
            sb.append(inner).append("\"hiddenExtensions\": ").append(serializeStringList(state.hiddenExtensions)).append(",\n")
            sb.append(inner).append("\"onlyWithParams\": ").append(state.onlyWithParams).append(",\n")
            sb.append(inner).append("\"onlyWithBody\": ").append(state.onlyWithBody).append(",\n")
            sb.append(inner).append("\"onlyWithResponse\": ").append(state.onlyWithResponse).append(",\n")
            sb.append(inner).append("\"hostQuery\": \"").append(escapeJson(state.hostQuery)).append("\",\n")
            sb.append(inner).append("\"pathQuery\": \"").append(escapeJson(state.pathQuery)).append("\",\n")
            sb.append(inner).append("\"mimeTypeQuery\": \"").append(escapeJson(state.mimeTypeQuery)).append("\",\n")
            sb.append(inner).append("\"protocolFilter\": \"").append(state.protocolFilter.name).append("\"\n")
            sb.append(indent).append("}")
            return sb.toString()
        }

        internal fun parseFilterStateFromJson(json: String): TrafficFilterState {
            return try {
                val scopeStr = extractJsonString(json, "scopeFilter") ?: ScopeFilterOption.SHOW_ALL.name
                val scopeFilter = try { ScopeFilterOption.valueOf(scopeStr) } catch (_: Exception) { ScopeFilterOption.SHOW_ALL }

                val searchQuery = extractJsonString(json, "searchQuery") ?: ""
                val selectedMethods = extractJsonStringList(json, "selectedMethods").toSet()
                val selectedStatusCodes = extractJsonStringList(json, "selectedStatusCodes").toSet()
                val hideImages = extractJsonBoolean(json, "hideImages") ?: false
                val hideCssAndJs = extractJsonBoolean(json, "hideCssAndJs") ?: false

                val contentTypesStr = extractJsonStringList(json, "contentTypes")
                val contentTypes = contentTypesStr.mapNotNull { name ->
                    try { ContentTypeCategory.valueOf(name) } catch (_: Exception) { null }
                }.toSet()

                val hiddenExtensions = extractJsonStringList(json, "hiddenExtensions").toSet()
                val onlyWithParams = extractJsonBoolean(json, "onlyWithParams") ?: false
                val onlyWithBody = extractJsonBoolean(json, "onlyWithBody") ?: false
                val onlyWithResponse = extractJsonBoolean(json, "onlyWithResponse") ?: false
                val hostQuery = extractJsonString(json, "hostQuery") ?: ""
                val pathQuery = extractJsonString(json, "pathQuery") ?: ""
                val mimeTypeQuery = extractJsonString(json, "mimeTypeQuery") ?: ""

                val protoStr = extractJsonString(json, "protocolFilter") ?: ProtocolFilter.ALL.name
                val protocolFilter = try { ProtocolFilter.valueOf(protoStr) } catch (_: Exception) { ProtocolFilter.ALL }

                TrafficFilterState(
                    scopeFilter = scopeFilter,
                    searchQuery = searchQuery,
                    selectedMethods = selectedMethods,
                    selectedStatusCodes = selectedStatusCodes,
                    hideImages = hideImages,
                    hideCssAndJs = hideCssAndJs,
                    contentTypes = contentTypes,
                    hiddenExtensions = hiddenExtensions,
                    onlyWithParams = onlyWithParams,
                    onlyWithBody = onlyWithBody,
                    onlyWithResponse = onlyWithResponse,
                    hostQuery = hostQuery,
                    pathQuery = pathQuery,
                    mimeTypeQuery = mimeTypeQuery,
                    protocolFilter = protocolFilter
                )
            } catch (_: Exception) {
                TrafficFilterState.DEFAULT
            }
        }

        internal fun serializeBypassSettingsToJson(settings: InterceptFilterSettings, indent: String = ""): String {
            val inner = "$indent  "
            val sb = StringBuilder("{\n")
            sb.append(inner).append("\"interceptOnlyInScope\": ").append(settings.interceptOnlyInScope).append(",\n")
            sb.append(inner).append("\"filterImages\": ").append(settings.filterImages).append(",\n")
            sb.append(inner).append("\"filterCss\": ").append(settings.filterCss).append(",\n")
            sb.append(inner).append("\"filterJs\": ").append(settings.filterJs).append(",\n")
            sb.append(inner).append("\"filterOtherResources\": ").append(settings.filterOtherResources).append("\n")
            sb.append(indent).append("}")
            return sb.toString()
        }

        internal fun parseBypassSettingsFromJson(json: String): InterceptFilterSettings {
            return try {
                InterceptFilterSettings(
                    interceptOnlyInScope = extractJsonBoolean(json, "interceptOnlyInScope") ?: false,
                    filterImages = extractJsonBoolean(json, "filterImages") ?: false,
                    filterCss = extractJsonBoolean(json, "filterCss") ?: false,
                    filterJs = extractJsonBoolean(json, "filterJs") ?: false,
                    filterOtherResources = extractJsonBoolean(json, "filterOtherResources") ?: false
                )
            } catch (_: Exception) {
                InterceptFilterSettings()
            }
        }

        private fun serializeStringList(items: Collection<String>): String {
            if (items.isEmpty()) return "[]"
            return items.joinToString(prefix = "[", postfix = "]", separator = ", ") {
                "\"${escapeJson(it)}\""
            }
        }

        private fun extractSection(json: String, sectionName: String): String? {
            val startIdx = json.indexOf("\"$sectionName\"")
            if (startIdx < 0) return null
            val braceStart = json.indexOf('{', startIdx)
            if (braceStart < 0) return null
            var depth = 0
            for (i in braceStart until json.length) {
                if (json[i] == '{') depth++
                else if (json[i] == '}') {
                    depth--
                    if (depth == 0) {
                        return json.substring(braceStart, i + 1)
                    }
                }
            }
            return null
        }

        private fun extractJsonString(content: String, key: String): String? {
            val regex = Regex("""\"$key\"\s*:\s*\"((?:\\\"|[^\"])*)\"""")
            val match = regex.find(content) ?: return null
            return unescapeJson(match.groupValues[1])
        }

        private fun extractJsonBoolean(content: String, key: String): Boolean? {
            val regex = Regex("""\"$key\"\s*:\s*(true|false)""")
            val match = regex.find(content) ?: return null
            return match.groupValues[1] == "true"
        }

        private fun extractJsonStringList(content: String, key: String): List<String> {
            val regex = Regex("""\"$key\"\s*:\s*\[([^\]]*)\]""")
            val match = regex.find(content) ?: return emptyList()
            val inner = match.groupValues[1].trim()
            if (inner.isEmpty()) return emptyList()
            val itemRegex = Regex("""\"((?:\\\"|[^\"])*)\"""")
            return itemRegex.findAll(inner).map { unescapeJson(it.groupValues[1]) }.toList()
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\").replace("\"", "\\\"")
        }

        private fun unescapeJson(str: String): String {
            return str.replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }
}
