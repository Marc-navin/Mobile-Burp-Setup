package com.example.basicbrowser.ui.history

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.basicbrowser.data.model.HttpHistoryItem
import java.io.ByteArrayInputStream

/**
 * Sandboxed dialog for previewing captured HTTP responses.
 *
 * Strict security constraints:
 * - JavaScript execution disabled.
 * - Local file access and content scheme access disabled.
 * - External network calls completely blocked via WebViewClient.
 * - Hyperlink clicks and redirects prevented.
 * - File downloads blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeResponsePreviewDialog(
    item: HttpHistoryItem,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val rawBodyText = remember(item.responseBody) {
        if (item.responseBody != null && item.responseBody.isNotEmpty()) {
            try {
                String(item.responseBody, Charsets.UTF_8)
            } catch (_: Exception) {
                "[Binary payload: ${item.responseBody.size} bytes]"
            }
        } else {
            ""
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Safe Response Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Sandboxed: JavaScript disabled • External network blocked",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF16A34A) // Safe green indicator
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Text("✕", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider()

                // Status info banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Status: ${item.statusCode ?: "Unknown"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Tabs: Rendered vs Raw
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Rendered Preview") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Raw & Headers") }
                    )
                }

                // Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (selectedTab == 0) {
                        if (rawBodyText.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No response body captured to preview.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        // 1. Strict sandboxing settings
                                        settings.javaScriptEnabled = false
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.domStorageEnabled = false
                                        settings.databaseEnabled = false
                                        settings.setGeolocationEnabled(false)

                                        // 2. Block all file downloads
                                        setDownloadListener { _, _, _, _, _ ->
                                            // Ignore/block
                                        }

                                        // 3. Block all navigation and external resource fetching
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView?,
                                                request: WebResourceRequest?
                                            ): Boolean {
                                                // Block all user link clicks / redirects
                                                return true
                                            }

                                            override fun shouldInterceptRequest(
                                                view: WebView?,
                                                request: WebResourceRequest?
                                            ): WebResourceResponse? {
                                                val uri = request?.url
                                                // Only allow local about:blank or data: schemes
                                                if (uri != null && (uri.scheme == "about" || uri.scheme == "data")) {
                                                    return null // allow local WebView internal rendering
                                                }
                                                // Block all external HTTP/HTTPS calls (trackers, fonts, external images)
                                                return WebResourceResponse(
                                                    "text/plain",
                                                    "UTF-8",
                                                    403,
                                                    "Blocked",
                                                    emptyMap(),
                                                    ByteArrayInputStream("Blocked by ABS Sandbox".toByteArray())
                                                )
                                            }
                                        }

                                        // Load HTML content with about:blank origin
                                        loadDataWithBaseURL("about:blank", rawBodyText, "text/html", "UTF-8", null)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        // Raw Headers & Body View
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Response Headers",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (item.responseHeaders.isNotEmpty()) {
                                        item.responseHeaders.joinToString("\n")
                                    } else {
                                        "No headers captured."
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Response Body (${item.responseBody?.size ?: 0} bytes)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (rawBodyText.isNotEmpty()) rawBodyText else "Empty response body",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

