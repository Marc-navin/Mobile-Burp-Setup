package com.example.basicbrowser.ui.intercept

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptManager
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptDetailScreen(
    requestId: String,
    onBackClick: () -> Unit
) {
    BackHandler(onBack = onBackClick)

    val manager = remember { InterceptManager.getInstance() }
    val pausedRequests by manager.pausedRequests.collectAsState()
    val interceptedRequest = pausedRequests.find { it.id == requestId }

    // If the request was released, timed out, or not found:
    val isStillPending = interceptedRequest != null

    // Fallback original request data for initial state
    val initialData = remember {
        interceptedRequest?.originalRequest ?: HttpRequestData(
            method = "GET",
            url = "",
            headers = emptyList()
        )
    }

    var method by remember { mutableStateOf(initialData.method) }
    var url by remember { mutableStateOf(initialData.url) }
    var httpVersion by remember { mutableStateOf(initialData.httpVersion) }
    var headersText by remember {
        mutableStateOf(initialData.headers.joinToString("\n"))
    }

    // Binary body detection
    val initialRawBody = initialData.body
    val isBinaryBody = remember(initialRawBody) {
        initialRawBody != null && isBinaryData(initialRawBody)
    }

    var bodyText by remember {
        mutableStateOf(
            if (initialRawBody != null && !isBinaryBody) {
                try {
                    String(initialRawBody, StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }
            } else ""
        )
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("REQUEST LINE & HEADERS", "BODY (${initialRawBody?.size ?: 0} B)")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Edit Request",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (interceptedRequest != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (interceptedRequest.isHttps) Color(0xFFE0E7FF) else Color(0xFFFEF9C3),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (interceptedRequest.isHttps) "HTTPS" else "HTTP",
                                    color = if (interceptedRequest.isHttps) Color(0xFF3730A3) else Color(0xFF854D0E),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            if (isStillPending) {
                                manager.drop(requestId)
                                onBackClick()
                            }
                        },
                        enabled = isStillPending,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Drop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (isStillPending) {
                                val parsedHeaders = headersText.split("\n")
                                    .map { it.trimEnd('\r') }
                                    .filter { it.isNotBlank() }

                                val finalBody = if (isBinaryBody) {
                                    initialRawBody
                                } else if (bodyText.isNotEmpty()) {
                                    bodyText.toByteArray(StandardCharsets.UTF_8)
                                } else {
                                    null
                                }

                                val edited = HttpRequestData(
                                    method = method.trim(),
                                    url = url.trim(),
                                    httpVersion = httpVersion.trim(),
                                    headers = parsedHeaders,
                                    body = finalBody,
                                    isBodyTruncated = initialData.isBodyTruncated
                                )

                                manager.forward(requestId, edited)
                                onBackClick()
                            }
                        },
                        enabled = isStillPending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text("Forward", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Expired/Released warning banner
            if (!isStillPending) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ This request has already been forwarded, dropped, or timed out.",
                        color = Color(0xFFB45309),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Tab row
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Tab Content
            when (selectedTabIndex) {
                0 -> {
                    // Request Line & Headers Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "REQUEST LINE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = method,
                                onValueChange = { method = it },
                                label = { Text("Method") },
                                textStyle = LocalTextStyle.current.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                singleLine = true,
                                modifier = Modifier.width(110.dp)
                            )

                            OutlinedTextField(
                                value = httpVersion,
                                onValueChange = { httpVersion = it },
                                label = { Text("Version") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                singleLine = true,
                                modifier = Modifier.width(100.dp)
                            )
                        }

                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL / Path") },
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Divider(modifier = Modifier.padding(vertical = 4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "HEADERS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${headersText.lines().filter { it.isNotBlank() }.size} headers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedTextField(
                            value = headersText,
                            onValueChange = { headersText = it },
                            label = { Text("Headers (one per line, e.g. Name: Value)") },
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            minLines = 8,
                            maxLines = 16,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "💡 Tip: Content-Length and Transfer-Encoding are automatically normalized upon forwarding to match the final body.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
                1 -> {
                    // Body Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isBinaryBody) {
                            // Binary Body Notice
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📦", fontSize = 28.sp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Binary Payload Detected",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Size: ${initialRawBody?.size ?: 0} bytes. Text editing is disabled for binary data to prevent payload corruption. The original raw bytes will be preserved and forwarded intact.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            // Text Body Editor
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "REQUEST BODY",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${bodyText.toByteArray(StandardCharsets.UTF_8).size} bytes (${bodyText.length} chars)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (initialData.isBodyTruncated) {
                                Surface(
                                    color = Color(0xFFFEF3C7),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "⚠️ Incoming body was truncated at 1 MB capture limit.",
                                        color = Color(0xFFB45309),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = bodyText,
                                onValueChange = { bodyText = it },
                                label = { Text("Payload content (UTF-8)") },
                                placeholder = { Text("Empty body") },
                                textStyle = LocalTextStyle.current.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Checks whether a byte array appears to be binary data (containing null bytes or
 * a high ratio of non-printable control characters).
 */
private fun isBinaryData(bytes: ByteArray): Boolean {
    val sampleLength = minOf(bytes.size, 512)
    var controlByteCount = 0
    for (i in 0 until sampleLength) {
        val b = bytes[i].toInt() and 0xFF
        if (b == 0) return true // Null byte is a definitive binary indicator
        if (b < 32 && b != 9 && b != 10 && b != 13) {
            controlByteCount++
        }
    }
    return controlByteCount > (sampleLength / 10)
}
