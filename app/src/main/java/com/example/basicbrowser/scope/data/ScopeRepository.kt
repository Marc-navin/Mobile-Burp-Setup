package com.example.basicbrowser.scope.data

import android.content.Context
import android.util.Log
import com.example.basicbrowser.scope.engine.ScopeEvaluation
import com.example.basicbrowser.scope.engine.ScopeMatcher
import com.example.basicbrowser.scope.model.ScopeRule
import com.example.basicbrowser.scope.model.ScopeRuleType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Centralized repository for managing and persisting global Target Scope rules.
 */
class ScopeRepository internal constructor(
    private val storageFile: File? = null
) {

    private val _rules = MutableStateFlow<List<ScopeRule>>(emptyList())
    val rules: StateFlow<List<ScopeRule>> = _rules.asStateFlow()

    init {
        loadFromStorage()
    }

    /**
     * Checks if a given URL is in scope according to current rules.
     */
    fun isInScope(url: String): Boolean {
        return ScopeMatcher.evaluate(url, _rules.value).isInScope
    }

    /**
     * Checks if target (host, port, scheme) is in scope according to current rules.
     */
    fun isInScope(host: String, port: Int = -1, scheme: String? = null): Boolean {
        return ScopeMatcher.evaluate(scheme, host, port, _rules.value).isInScope
    }

    /**
     * Evaluates a URL and returns detailed reason and matched rule.
     */
    fun evaluate(url: String): ScopeEvaluation {
        return ScopeMatcher.evaluate(url, _rules.value)
    }

    /**
     * Adds a new scope rule and persists the change.
     */
    @Synchronized
    fun addRule(rule: ScopeRule) {
        val current = _rules.value.toMutableList()
        current.add(rule)
        _rules.value = current
        saveToStorage()
    }

    /**
     * Updates an existing rule by ID.
     */
    @Synchronized
    fun updateRule(updated: ScopeRule) {
        val current = _rules.value.toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            current[index] = updated
            _rules.value = current
            saveToStorage()
        }
    }

    /**
     * Deletes a rule by ID.
     */
    @Synchronized
    fun deleteRule(ruleId: String) {
        val current = _rules.value.toMutableList()
        if (current.removeAll { it.id == ruleId }) {
            _rules.value = current
            saveToStorage()
        }
    }

    /**
     * Enables or disables a rule.
     */
    @Synchronized
    fun toggleRule(ruleId: String, enabled: Boolean) {
        val current = _rules.value.toMutableList()
        val index = current.indexOfFirst { it.id == ruleId }
        if (index >= 0) {
            current[index] = current[index].copy(enabled = enabled)
            _rules.value = current
            saveToStorage()
        }
    }

    /**
     * Clears all scope rules.
     */
    @Synchronized
    fun clearScope() {
        _rules.value = emptyList()
        saveToStorage()
    }

    /**
     * Sets multiple rules at once (useful for testing or batch operations).
     */
    @Synchronized
    fun setRules(newRules: List<ScopeRule>) {
        _rules.value = newRules.toList()
        saveToStorage()
    }

    private fun loadFromStorage() {
        if (storageFile == null || !storageFile.exists()) return
        try {
            val jsonStr = storageFile.readText(Charsets.UTF_8)
            val loaded = parseRulesFromJson(jsonStr)
            _rules.value = loaded
            safeLog("Loaded ${loaded.size} scope rules from storage")
        } catch (e: Exception) {
            safeLog("Failed to load scope rules from storage: ${e.message}")
        }
    }

    private fun saveToStorage() {
        if (storageFile == null) return
        try {
            val jsonText = serializeRulesToJson(_rules.value)
            storageFile.parentFile?.mkdirs()
            storageFile.writeText(jsonText, Charsets.UTF_8)
        } catch (e: Exception) {
            safeLog("Failed to persist scope rules: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ScopeRepository"
        private const val SCOPE_FILE_NAME = "scope_rules.json"

        @Volatile
        private var instance: ScopeRepository? = null

        fun getInstance(context: Context? = null): ScopeRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val file = context?.applicationContext?.let {
                        File(it.filesDir, SCOPE_FILE_NAME)
                    }
                    ScopeRepository(file).also { instance = it }
                }
            }
        }

        /**
         * For unit testing: resets singleton instance.
         */
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }

        private fun safeLog(message: String) {
            try {
                Log.d(TAG, message)
            } catch (_: Throwable) {
                // Ignore in JVM unit tests where android.util.Log is not mocked
            }
        }

        internal fun serializeRulesToJson(rules: List<ScopeRule>): String {
            val sb = StringBuilder("[\n")
            rules.forEachIndexed { i, rule ->
                sb.append("  {\n")
                sb.append("    \"id\": \"${escapeJson(rule.id)}\",\n")
                sb.append("    \"type\": \"${rule.type.name}\",\n")
                sb.append("    \"pattern\": \"${escapeJson(rule.pattern)}\",\n")
                sb.append("    \"enabled\": ${rule.enabled},\n")
                if (rule.scheme != null) {
                    sb.append("    \"scheme\": \"${escapeJson(rule.scheme)}\",\n")
                } else {
                    sb.append("    \"scheme\": null,\n")
                }
                if (rule.port != null) {
                    sb.append("    \"port\": ${rule.port},\n")
                } else {
                    sb.append("    \"port\": null,\n")
                }
                sb.append("    \"comment\": \"${escapeJson(rule.comment)}\"\n")
                sb.append("  }")
                if (i < rules.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]\n")
            return sb.toString()
        }

        internal fun parseRulesFromJson(jsonStr: String): List<ScopeRule> {
            val list = mutableListOf<ScopeRule>()
            val objectRegex = Regex("""\{([^{}]+)\}""")
            for (match in objectRegex.findAll(jsonStr)) {
                val content = match.groupValues[1]
                val id = extractJsonString(content, "id") ?: java.util.UUID.randomUUID().toString()
                val typeStr = extractJsonString(content, "type") ?: ScopeRuleType.INCLUDE.name
                val type = try {
                    ScopeRuleType.valueOf(typeStr)
                } catch (_: Exception) {
                    ScopeRuleType.INCLUDE
                }
                val pattern = extractJsonString(content, "pattern") ?: continue
                val enabled = extractJsonBoolean(content, "enabled") ?: true
                val scheme = extractJsonString(content, "scheme")
                val port = extractJsonInt(content, "port")
                val comment = extractJsonString(content, "comment") ?: ""

                list.add(
                    ScopeRule(
                        id = id,
                        type = type,
                        pattern = pattern,
                        enabled = enabled,
                        scheme = scheme,
                        port = port,
                        comment = comment
                    )
                )
            }
            return list
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        private fun extractJsonString(content: String, key: String): String? {
            val nullMatch = Regex("""\"$key\"\s*:\s*null""").find(content)
            if (nullMatch != null) return null
            val regex = Regex("""\"$key\"\s*:\s*\"((?:[^"\\]|\\.)*)\"""")
            val match = regex.find(content) ?: return null
            return match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
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
    }
}

