package com.example.basicbrowser.proxy.intercept

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Coordinates HTTP and HTTPS request interception.
 *
 * When enabled, incoming HTTP/HTTPS requests are paused before being forwarded
 * upstream. Users can inspect, edit, forward, or drop requests.
 *
 * Designed with mobile resource constraints in mind:
 * - Hard maximum of [DEFAULT_MAX_PAUSED] simultaneously paused requests.
 * - Configurable timeout [DEFAULT_TIMEOUT_SECONDS] (default 60s).
 * - Safe overload behavior: when full, requests pass through without deadlocking.
 * - Clean resource release on drop, timeout, or shutdown.
 */
class InterceptManager internal constructor(
    var maxPaused: Int = DEFAULT_MAX_PAUSED,
    var timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) {

    private val _isInterceptEnabled = MutableStateFlow(false)
    val isInterceptEnabled: StateFlow<Boolean> = _isInterceptEnabled.asStateFlow()

    private val _pausedRequests = MutableStateFlow<List<InterceptedRequest>>(emptyList())
    val pausedRequests: StateFlow<List<InterceptedRequest>> = _pausedRequests.asStateFlow()

    private val pausedList = ArrayList<InterceptedRequest>()

    /**
     * Enables or disables intercept mode. When disabling, any currently paused
     * requests are released/forwarded so proxy worker threads do not hang.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (_isInterceptEnabled.value != enabled) {
            _isInterceptEnabled.value = enabled
            Log.i(TAG, "Intercept mode toggled: $enabled")
            if (!enabled) {
                forwardAll()
            }
        }
    }

    /**
     * Checks if interception is enabled for this request.
     */
    fun isEnabled(): Boolean = _isInterceptEnabled.value

    /**
     * Pauses a request if intercept mode is enabled and queue capacity permits.
     * Blocks the calling proxy worker thread until the user takes action, timeout expires,
     * or the manager is shut down.
     */
    fun interceptOrPass(
        id: String,
        request: HttpRequestData,
        isHttps: Boolean,
        hostname: String
    ): InterceptAction {
        if (!isEnabled()) {
            return InterceptAction.Forward(request)
        }

        val intercepted: InterceptedRequest
        synchronized(this) {
            if (pausedList.size >= maxPaused) {
                Log.w(TAG, "Intercept queue full (${pausedList.size}/$maxPaused) — passing through request to $hostname")
                return InterceptAction.Forward(request)
            }

            intercepted = InterceptedRequest(
                id = id,
                originalRequest = request,
                isHttps = isHttps,
                hostname = hostname,
                timeoutSeconds = timeoutSeconds
            )
            pausedList.add(intercepted)
            _pausedRequests.value = pausedList.toList()
            Log.i(TAG, "Paused request [${intercepted.id}] ${request.method} ${request.url} (queue: ${pausedList.size})")
        }

        return try {
            val action = intercepted.future.get(timeoutSeconds, TimeUnit.SECONDS)
            removePaused(intercepted.id)
            action
        } catch (_: TimeoutException) {
            Log.w(TAG, "Paused request [${intercepted.id}] timed out after ${timeoutSeconds}s — auto-forwarding")
            removePaused(intercepted.id)
            InterceptAction.Forward(request)
        } catch (_: Exception) {
            removePaused(intercepted.id)
            InterceptAction.Forward(request)
        }
    }

    /**
     * Forwards an intercepted request, optionally with user modifications.
     * Normalizes framing headers (Content-Length / Transfer-Encoding) safely.
     */
    @Synchronized
    fun forward(requestId: String, editedRequest: HttpRequestData? = null) {
        val item = pausedList.find { it.id == requestId }
        if (item != null) {
            val finalRequest = if (editedRequest != null) {
                normalizeFramingHeaders(editedRequest)
            } else {
                item.originalRequest
            }
            item.future.complete(InterceptAction.Forward(finalRequest))
            removePaused(requestId)
            Log.i(TAG, "Forwarded request [$requestId] ${finalRequest.method} ${finalRequest.url}")
        }
    }

    /**
     * Drops an intercepted request explicitly without sending it upstream.
     */
    @Synchronized
    fun drop(requestId: String) {
        val item = pausedList.find { it.id == requestId }
        if (item != null) {
            item.future.complete(InterceptAction.Drop)
            removePaused(requestId)
            Log.i(TAG, "Dropped request [$requestId] ${item.originalRequest.method} ${item.originalRequest.url}")
        }
    }

    /**
     * Forwards all currently paused requests unchanged and unblocks worker threads.
     */
    @Synchronized
    fun forwardAll() {
        val current = ArrayList(pausedList)
        for (item in current) {
            item.future.complete(InterceptAction.Forward(item.originalRequest))
        }
        pausedList.clear()
        _pausedRequests.value = emptyList()
        Log.i(TAG, "Forwarded all paused requests (${current.size})")
    }

    /**
     * Drops all currently paused requests and unblocks worker threads.
     */
    @Synchronized
    fun dropAll() {
        val current = ArrayList(pausedList)
        for (item in current) {
            item.future.complete(InterceptAction.Drop)
        }
        pausedList.clear()
        _pausedRequests.value = emptyList()
        Log.i(TAG, "Dropped all paused requests (${current.size})")
    }

    /**
     * Releases all paused requests upon app shutdown or proxy stop.
     */
    @Synchronized
    fun releaseAll(reason: String = "shutdown") {
        Log.i(TAG, "Releasing all paused requests (reason: $reason)")
        forwardAll()
    }

    @Synchronized
    fun getPausedRequest(id: String): InterceptedRequest? {
        return pausedList.find { it.id == id }
    }

    @Synchronized
    fun getPausedCount(): Int = pausedList.size

    @Synchronized
    private fun removePaused(id: String) {
        val removed = pausedList.removeAll { it.id == id }
        if (removed) {
            _pausedRequests.value = pausedList.toList()
        }
    }

    companion object {
        private const val TAG = "InterceptManager"
        const val DEFAULT_MAX_PAUSED = 10
        const val DEFAULT_TIMEOUT_SECONDS = 60L

        @Volatile
        private var instance: InterceptManager? = null

        fun getInstance(): InterceptManager {
            return instance ?: synchronized(this) {
                instance ?: InterceptManager().also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.releaseAll("reset")
                instance = null
            }
        }

        /**
         * Normalizes HTTP/1.1 framing headers after request line, headers, or body edits.
         * Ensures Content-Length matches the actual body size and removes conflicting
         * Transfer-Encoding headers to prevent request smuggling / connection desync.
         */
        fun normalizeFramingHeaders(request: HttpRequestData): HttpRequestData {
            val body = request.body
            val sanitized = request.headers.filterNot { header ->
                header.startsWith("Content-Length:", ignoreCase = true) ||
                header.startsWith("Transfer-Encoding:", ignoreCase = true)
            }.toMutableList()

            if (body != null && body.isNotEmpty()) {
                sanitized.add("Content-Length: ${body.size}")
            } else {
                val isBodyMethod = request.method.equals("POST", ignoreCase = true) ||
                    request.method.equals("PUT", ignoreCase = true) ||
                    request.method.equals("PATCH", ignoreCase = true)
                if (isBodyMethod) {
                    sanitized.add("Content-Length: 0")
                }
            }

            return request.copy(headers = sanitized)
        }
    }
}
