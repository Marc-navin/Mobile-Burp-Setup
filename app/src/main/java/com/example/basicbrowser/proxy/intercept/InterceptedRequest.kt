package com.example.basicbrowser.proxy.intercept

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Represents a paused HTTP or HTTPS request awaiting user action in Intercept mode.
 */
data class InterceptedRequest(
    val id: String = UUID.randomUUID().toString(),
    val originalRequest: HttpRequestData,
    val isHttps: Boolean,
    val hostname: String,
    val createdAt: Long = System.currentTimeMillis(),
    val timeoutSeconds: Long = InterceptManager.DEFAULT_TIMEOUT_SECONDS,
    val future: CompletableFuture<InterceptAction> = CompletableFuture()
)
