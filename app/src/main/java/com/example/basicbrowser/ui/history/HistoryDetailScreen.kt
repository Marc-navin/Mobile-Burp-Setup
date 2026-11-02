package com.example.basicbrowser.ui.history

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.repeater.data.RepeaterRepository
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    item: HttpHistoryItem,
    onBackClick: () -> Unit,
    onNavigateToRepeater: (() -> Unit)? = null,
    onNavigateToVariation: (() -> Unit)? = null
) {
    BackHandler(onBack = onBackClick)
    val context = LocalContext.current

    // Observe repository so real-time updates (e.g. streaming response) reflect immediately
    val repository = remember { HttpHistoryRepository.getInstance() }
    val historyList by repository.history.collectAsState()
    val liveItem = historyList.find { it.id == item.id } ?: item

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("REQUEST", "RESPONSE")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MethodBadge(method = liveItem.method)
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusCodeBadge(statusCode = liveItem.statusCode)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
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
                            val requestLine = liveItem.requestHeaders.firstOrNull() ?: ""
                            val parts = requestLine.split(" ")
                            val httpVer = if (parts.size >= 3) parts[2] else "HTTP/1.1"
                            val reqData = HttpRequestData(
                                method = liveItem.method,
                                url = liveItem.url,
                                httpVersion = httpVer,
                                headers = liveItem.requestHeaders.drop(1),
                                body = liveItem.requestBody,
                                isBodyTruncated = liveItem.requestBodyTruncated
                            )
                            val newItem = RepeaterRepository.getInstance().addItem(reqData)
                            Toast.makeText(context, "Sent to Repeater (${newItem.name})", Toast.LENGTH_SHORT).show()
                            onNavigateToRepeater?.invoke()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("🔁", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = {
                            val requestLine = liveItem.requestHeaders.firstOrNull() ?: ""
                            val parts = requestLine.split(" ")
                            val httpVer = if (parts.size >= 3) parts[2] else "HTTP/1.1"
                            val reqData = HttpRequestData(
                                method = liveItem.method,
                                url = liveItem.url,
                                httpVersion = httpVer,
                                headers = liveItem.requestHeaders.drop(1),
                                body = liveItem.requestBody,
                                isBodyTruncated = liveItem.requestBodyTruncated
                            )
                            com.example.basicbrowser.variation.data.VariationRepository.getInstance().setBaseRequest(reqData)
                            Toast.makeText(context, "Sent to Variation Lab", Toast.LENGTH_SHORT).show()
                            onNavigateToVariation?.invoke()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("🧪", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, fontWeight = FontWeight.Bold)
                                if (index == 1 && liveItem.statusCode != null) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "(${liveItem.statusCode})",
                                        fontSize = 11.sp,
                                        color = if (liveItem.statusCode in 200..299) Color(0xFF15803D) else Color(0xFFB91C1C)
                                    )
                                }
                            }
                        }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> RequestTabContent(item = liveItem)
                1 -> ResponseTabContent(item = liveItem)
            }
        }
    }
}

@Composable
private fun RequestTabContent(item: HttpHistoryItem) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Extract HTTP version from request line (first element of requestHeaders)
    val requestLine = item.requestHeaders.firstOrNull() ?: ""
    val httpVersion = remember(requestLine) {
        val parts = requestLine.split(" ")
        if (parts.size >= 3) parts[2] else "HTTP/1.1"
    }

    // Header pairs (excluding request line)
    val headerPairs = remember(item.requestHeaders) {
        parseHeaderPairs(item.requestHeaders.drop(1))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // --- 1. General Overview Card ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MethodBadge(method = item.method)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = httpVersion,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(item.url))
                            Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Copy URL", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                SelectionContainer {
                    Text(
                        text = item.url,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. Headers Section ---
        SectionTitle(title = "Headers (${headerPairs.size})")

        if (headerPairs.isEmpty()) {
            Text(
                text = "(No headers)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            HeadersTable(pairs = headerPairs)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 3. Body Section ---
        val bodySize = item.requestBody?.size ?: 0
        SectionTitle(
            title = if (bodySize > 0) "Body ($bodySize bytes)" else "Body"
        )

        BodyContainer(
            body = item.requestBody,
            headers = item.requestHeaders,
            truncated = item.requestBodyTruncated,
            emptyMessage = "(No request body)"
        )
    }
}

@Composable
private fun ResponseTabContent(item: HttpHistoryItem) {
    // Extract HTTP version and status line
    val statusLine = item.responseHeaders.firstOrNull() ?: ""
    val httpVersion = remember(statusLine) {
        val parts = statusLine.split(" ")
        if (parts.isNotEmpty()) parts[0] else "HTTP/1.1"
    }

    // Header pairs (excluding status line)
    val headerPairs = remember(item.responseHeaders) {
        parseHeaderPairs(item.responseHeaders.drop(1))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // --- 1. General Overview Card ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusCodeBadge(statusCode = item.statusCode)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = httpVersion,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (statusLine.isNotEmpty()) {
                        SelectionContainer {
                            Text(
                                text = statusLine,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. Headers Section ---
        SectionTitle(title = "Headers (${headerPairs.size})")

        if (item.responseHeaders.isEmpty()) {
            Text(
                text = "Waiting for response or connection closed...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else if (headerPairs.isEmpty()) {
            Text(
                text = "(No response headers)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            HeadersTable(pairs = headerPairs)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 3. Body Section ---
        val bodySize = item.responseBody?.size ?: 0
        SectionTitle(
            title = if (bodySize > 0) "Body ($bodySize bytes)" else "Body"
        )

        BodyContainer(
            body = item.responseBody,
            headers = item.responseHeaders,
            truncated = item.responseBodyTruncated,
            emptyMessage = if (item.responseHeaders.isEmpty()) "(Waiting for response...)" else "(No response body)"
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun HeadersTable(pairs: List<Pair<String, String>>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(8.dp)) {
                pairs.forEachIndexed { index, (key, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = "$key:",
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.widthIn(min = 90.dp, max = 150.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = value,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (index < pairs.size - 1) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class BodyAnalysis(
    val isBinary: Boolean,
    val binarySummary: String,
    val previewText: String = "",
    val fullTextLength: Int = 0,
    val isTruncatedByPreview: Boolean = false,
    val fullText: String? = null
)

private fun analyzeBody(body: ByteArray, headers: List<String>): BodyAnalysis {
    // 1. Content-Type inspection
    val contentTypeHeader = headers.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
    val ct = contentTypeHeader?.substringAfter(":")?.trim()?.lowercase() ?: ""

    if (ct.startsWith("image/")) return BodyAnalysis(isBinary = true, binarySummary = "Image ($ct, ${body.size} bytes)")
    if (ct.startsWith("video/")) return BodyAnalysis(isBinary = true, binarySummary = "Video ($ct, ${body.size} bytes)")
    if (ct.startsWith("audio/")) return BodyAnalysis(isBinary = true, binarySummary = "Audio ($ct, ${body.size} bytes)")
    if (ct.startsWith("font/") || ct.contains("font")) return BodyAnalysis(isBinary = true, binarySummary = "Font ($ct, ${body.size} bytes)")
    if (ct.contains("application/octet-stream")) return BodyAnalysis(isBinary = true, binarySummary = "Binary stream (${body.size} bytes)")
    if (ct.contains("application/pdf")) return BodyAnalysis(isBinary = true, binarySummary = "PDF Document (${body.size} bytes)")
    if (ct.contains("zip") || ct.contains("gzip") || ct.contains("tar") || ct.contains("compressed")) {
        return BodyAnalysis(isBinary = true, binarySummary = "Compressed Archive ($ct, ${body.size} bytes)")
    }

    // 2. Magic byte signatures on raw ByteArray (zero string allocations)
    if (body.size >= 8 && body[0] == 0x89.toByte() && body[1] == 0x50.toByte() && body[2] == 0x4E.toByte() && body[3] == 0x47.toByte()) {
        return BodyAnalysis(isBinary = true, binarySummary = "PNG Image (${body.size} bytes)")
    }
    if (body.size >= 3 && body[0] == 0xFF.toByte() && body[1] == 0xD8.toByte() && body[2] == 0xFF.toByte()) {
        return BodyAnalysis(isBinary = true, binarySummary = "JPEG Image (${body.size} bytes)")
    }
    if (body.size >= 6 && body[0] == 'G'.code.toByte() && body[1] == 'I'.code.toByte() && body[2] == 'F'.code.toByte()) {
        return BodyAnalysis(isBinary = true, binarySummary = "GIF Image (${body.size} bytes)")
    }
    if (body.size >= 4 && body[0] == 0x50.toByte() && body[1] == 0x4B.toByte() && (body[2] == 0x03.toByte() || body[2] == 0x05.toByte())) {
        return BodyAnalysis(isBinary = true, binarySummary = "ZIP Archive (${body.size} bytes)")
    }
    if (body.size >= 2 && body[0] == 0x1F.toByte() && body[1] == 0x8B.toByte()) {
        return BodyAnalysis(isBinary = true, binarySummary = "GZIP Compressed (${body.size} bytes)")
    }
    if (body.size >= 4 && body[0] == '%'.code.toByte() && body[1] == 'P'.code.toByte() && body[2] == 'D'.code.toByte() && body[3] == 'F'.code.toByte()) {
        return BodyAnalysis(isBinary = true, binarySummary = "PDF Document (${body.size} bytes)")
    }

    // 3. Inspect first 512 bytes for control or null characters without converting entire body to string
    val sampleLength = minOf(body.size, 512)
    var controlByteCount = 0
    for (i in 0 until sampleLength) {
        val b = body[i].toInt() and 0xFF
        if (b == 0) {
            return BodyAnalysis(isBinary = true, binarySummary = "Binary data with null bytes (${body.size} bytes)")
        }
        if (b < 0x09 || (b in 0x0E..0x1F)) {
            controlByteCount++
        }
    }
    if (controlByteCount > sampleLength / 10) {
        return BodyAnalysis(isBinary = true, binarySummary = "Binary data (${body.size} bytes)")
    }

    // 4. Decode text payload with 10,000 character initial preview limit
    val text = try {
        String(body, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        return BodyAnalysis(isBinary = true, binarySummary = "Binary / decoding error: ${e.message} (${body.size} bytes)")
    }

    val maxPreview = 10_000
    return if (text.length > maxPreview) {
        BodyAnalysis(
            isBinary = false,
            binarySummary = "",
            previewText = text.substring(0, maxPreview),
            fullTextLength = text.length,
            isTruncatedByPreview = true,
            fullText = text
        )
    } else {
        BodyAnalysis(
            isBinary = false,
            binarySummary = "",
            previewText = text,
            fullTextLength = text.length,
            isTruncatedByPreview = false,
            fullText = text
        )
    }
}

@Composable
private fun BodyContainer(
    body: ByteArray?,
    headers: List<String>,
    truncated: Boolean,
    emptyMessage: String
) {
    if (body == null || body.isEmpty()) {
        Text(
            text = emptyMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }

    Column {
        if (truncated) {
            Surface(
                color = Color(0xFFFEF3C7),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = "⚠️ Body was truncated because it exceeded maximum capture size (1 MB)",
                    color = Color(0xFFB45309),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        val analysis = remember(body) {
            analyzeBody(body, headers)
        }

        var showFullText by remember(body) { mutableStateOf(false) }

        if (analysis.isBinary) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text("📦", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = analysis.binarySummary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val textToDisplay = if (showFullText) analysis.fullText ?: analysis.previewText else analysis.previewText

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 400.dp)
            ) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(
                            text = textToDisplay,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (analysis.isTruncatedByPreview) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showFullText) {
                            "Showing all ${analysis.fullTextLength} characters"
                        } else {
                            "Showing 10,000 of ${analysis.fullTextLength} characters"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { showFullText = !showFullText },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (showFullText) "Show Less" else "Show Full (${analysis.fullTextLength})",
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Splits lines into (Header-Name, Header-Value) pairs.
 */
private fun parseHeaderPairs(headerLines: List<String>): List<Pair<String, String>> {
    return headerLines.mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val colonIndex = line.indexOf(':')
        if (colonIndex > 0) {
            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            key to value
        } else {
            line to ""
        }
    }
}
