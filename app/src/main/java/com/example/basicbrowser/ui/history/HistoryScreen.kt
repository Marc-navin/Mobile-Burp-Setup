package com.example.basicbrowser.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onItemClick: (HttpHistoryItem) -> Unit,
    onBackClick: () -> Unit
) {
    BackHandler(onBack = onBackClick)

    val repository = remember { HttpHistoryRepository.getInstance() }
    val historyItems by repository.history.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear HTTP History?") },
            text = { Text("This will clear the captured in-memory request log. Certificates and proxy settings will not be affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.clear()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("HTTP History")
                        if (historyItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = historyItems.size.toString(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
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
                    if (historyItems.isNotEmpty()) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "No HTTP Traffic Captured",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Use the Browser tab to visit websites. Decrypted HTTP and HTTPS requests will appear here in real time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(historyItems, key = { it.id }) { item ->
                    HistoryItemRow(item = item, onClick = { onItemClick(item) })
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(item: HttpHistoryItem, onClick: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val timeString = remember(item.timestamp) { timeFormat.format(Date(item.timestamp)) }

    // Parse host and path/query
    val (host, path) = remember(item.url) {
        parseHostAndPath(item.url)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Method badge
        MethodBadge(method = item.method)

        Spacer(modifier = Modifier.width(8.dp))

        // 2. Host & Path
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = host,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = path,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 3. Status code badge
        StatusCodeBadge(statusCode = item.statusCode)
    }
}

@Composable
fun MethodBadge(method: String) {
    val (bgColor, textColor) = when (method.uppercase()) {
        "GET" -> Color(0xFFE0F2FE) to Color(0xFF0369A1)       // Light Blue / Deep Blue
        "POST" -> Color(0xFFDCFCE7) to Color(0xFF15803D)      // Light Green / Deep Green
        "PUT", "PATCH" -> Color(0xFFFEF3C7) to Color(0xFFB45309) // Light Amber / Deep Amber
        "DELETE" -> Color(0xFFFEE2E2) to Color(0xFFB91C1C)    // Light Red / Deep Red
        "CONNECT" -> Color(0xFFF3E8FF) to Color(0xFF6B21A8)   // Light Purple / Deep Purple
        else -> Color(0xFFF1F5F9) to Color(0xFF475569)        // Gray
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.width(58.dp)
    ) {
        Text(
            text = method.uppercase(),
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
fun StatusCodeBadge(statusCode: Int?) {
    if (statusCode == null) {
        Surface(
            color = Color(0xFFF1F5F9),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = "...",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        return
    }

    val (bgColor, textColor) = when (statusCode) {
        in 200..299 -> Color(0xFFDCFCE7) to Color(0xFF15803D) // Success: Green
        in 300..399 -> Color(0xFFE0F2FE) to Color(0xFF0369A1) // Redirect: Blue
        in 400..499 -> Color(0xFFFEF3C7) to Color(0xFFB45309) // Client Error: Amber
        in 500..599 -> Color(0xFFFEE2E2) to Color(0xFFB91C1C) // Server Error: Red
        else -> Color(0xFFF1F5F9) to Color(0xFF475569)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = statusCode.toString(),
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Extracts host and path/query safely from full URL or URI strings.
 */
private fun parseHostAndPath(urlStr: String): Pair<String, String> {
    return try {
        val uri = URI(urlStr)
        val host = uri.host ?: urlStr.substringAfter("://").substringBefore("/").substringBefore(":")
        val path = if (uri.rawPath.isNullOrEmpty()) {
            val afterHost = urlStr.substringAfter(host, "")
            if (afterHost.isEmpty()) "/" else afterHost
        } else {
            uri.rawPath + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
        }
        host to path
    } catch (e: Exception) {
        val host = urlStr.substringAfter("://").substringBefore("/").substringBefore(":")
        val path = "/" + urlStr.substringAfter(host, "").substringAfter("/")
        host to path
    }
}
