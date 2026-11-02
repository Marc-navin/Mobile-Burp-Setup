package com.example.basicbrowser.repeater.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.repeater.data.RepeaterRepository
import com.example.basicbrowser.repeater.model.ExecutionState
import com.example.basicbrowser.repeater.model.RepeaterItem
import com.example.basicbrowser.repeater.model.RepeaterResponse
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeaterScreen(
    onBackClick: () -> Unit,
    onNavigateToVariation: (() -> Unit)? = null
) {
    BackHandler(onBack = onBackClick)
    val context = LocalContext.current

    val repository = remember { RepeaterRepository.getInstance() }
    val items by repository.items.collectAsState()
    val selectedId by repository.selectedItemId.collectAsState()

    val selectedItem = items.find { it.id == selectedId } ?: items.firstOrNull()

    // Ensure there's at least one item or an initial blank item if empty
    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            repository.createBlankItem()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔁", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Repeater",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${items.size}/5",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            selectedItem?.let { item ->
                                com.example.basicbrowser.variation.data.VariationRepository.getInstance().setBaseRequest(item.request)
                                Toast.makeText(context, "Sent to Variation Lab", Toast.LENGTH_SHORT).show()
                                onNavigateToVariation?.invoke()
                            }
                        },
                        enabled = selectedItem != null
                    ) {
                        Text("🧪", fontSize = 16.sp)
                    }
                    IconButton(
                        onClick = { repository.createBlankItem() },
                        enabled = items.size < RepeaterRepository.DEFAULT_MAX_ITEMS
                    ) {
                        Text("➕", fontSize = 16.sp)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Horizontal Item Tabs
            if (items.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = items.indexOfFirst { it.id == selectedItem?.id }.coerceAtLeast(0),
                    edgePadding = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items.forEach { item ->
                        val isSelected = item.id == selectedItem?.id
                        Tab(
                            selected = isSelected,
                            onClick = { repository.selectItem(item.id) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (item.executionState is ExecutionState.Executing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = item.name,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        )
                    }
                }
            }

            if (selectedItem != null) {
                RepeaterItemContent(
                    item = selectedItem,
                    repository = repository
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = { repository.createBlankItem() }) {
                        Text("Create New Request")
                    }
                }
            }
        }
    }
}

@Composable
private fun RepeaterItemContent(
    item: RepeaterItem,
    repository: RepeaterRepository
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Internal edit states synced with the current item's request
    var method by remember(item.id) { mutableStateOf(item.request.method) }
    var url by remember(item.id) { mutableStateOf(item.request.url) }
    var httpVersion by remember(item.id) { mutableStateOf(item.request.httpVersion) }
    var headersText by remember(item.id) { mutableStateOf(item.request.headers.joinToString("\n")) }

    val rawBody = item.request.body
    val isBinaryBody = remember(rawBody) {
        rawBody != null && isBinaryData(rawBody)
    }

    var bodyText by remember(item.id) {
        mutableStateOf(
            if (rawBody != null && !isBinaryBody) {
                try {
                    String(rawBody, StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }
            } else ""
        )
    }

    // Propagate edits back to the repository whenever user changes fields
    fun flushEdits() {
        val parsedHeaders = headersText.split("\n")
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }

        val finalBody = if (isBinaryBody) {
            rawBody
        } else if (bodyText.isNotEmpty()) {
            bodyText.toByteArray(StandardCharsets.UTF_8)
        } else {
            null
        }

        val updated = HttpRequestData(
            method = method.trim(),
            url = url.trim(),
            httpVersion = httpVersion.trim(),
            headers = parsedHeaders,
            body = finalBody,
            isBodyTruncated = item.request.isBodyTruncated
        )
        repository.updateRequest(item.id, updated)
    }

    val isExecuting = item.executionState is ExecutionState.Executing
    var mainTabIndex by remember(item.id) { mutableIntStateOf(0) }
    val mainTabs = listOf("REQUEST", "RESPONSE")

    Column(modifier = Modifier.fillMaxSize()) {
        // --- Target Destination & Controls Bar ---
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Destination indicator
                val isHttps = url.trim().startsWith("https://", ignoreCase = true)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = if (isHttps) Color(0xFFE0E7FF) else Color(0xFFFEF9C3),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isHttps) "HTTPS (TLS Verified)" else "HTTP",
                            color = if (isHttps) Color(0xFF3730A3) else Color(0xFF854D0E),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = url.takeIf { it.isNotBlank() } ?: "No URL set",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action Buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isExecuting) {
                        Button(
                            onClick = { repository.cancelRequest(item.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                flushEdits()
                                mainTabIndex = 1 // Switch to response tab
                                repository.sendRequest(item.id)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF16A34A),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Send", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = { repository.deleteItem(item.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("🗑️", fontSize = 14.sp)
                    }
                }
            }
        }

        // --- Main Tabs (Request vs Response) ---
        TabRow(selectedTabIndex = mainTabIndex) {
            mainTabs.forEachIndexed { index, title ->
                Tab(
                    selected = mainTabIndex == index,
                    onClick = {
                        flushEdits()
                        mainTabIndex = index
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            if (index == 1 && item.response?.statusCode != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    color = if (item.response.statusCode in 200..299) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "${item.response.statusCode}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.response.statusCode in 200..299) Color(0xFF15803D) else Color(0xFFB91C1C),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

        // --- Tab Contents ---
        when (mainTabIndex) {
            0 -> {
                // Request Editor Tab
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            onValueChange = {
                                method = it
                                flushEdits()
                            },
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
                            onValueChange = {
                                httpVersion = it
                                flushEdits()
                            },
                            label = { Text("Version") },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            modifier = Modifier.width(100.dp)
                        )
                    }

                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            flushEdits()
                        },
                        label = { Text("Destination URL (e.g. https://example.com/api)") },
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                        onValueChange = {
                            headersText = it
                            flushEdits()
                        },
                        label = { Text("Headers (Name: Value, one per line)") },
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        minLines = 6,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                            text = if (isBinaryBody) "${rawBody?.size ?: 0} B (Binary)" else "${bodyText.toByteArray(StandardCharsets.UTF_8).size} bytes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isBinaryBody) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📦 Binary Request Payload (${rawBody?.size ?: 0} bytes). Text editing disabled to prevent binary corruption.",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = bodyText,
                            onValueChange = {
                                bodyText = it
                                flushEdits()
                            },
                            label = { Text("Body content (UTF-8)") },
                            placeholder = { Text("Empty body") },
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            minLines = 4,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            1 -> {
                // Response Viewer Tab
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (val state = item.executionState) {
                        is ExecutionState.Executing -> {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Sending request...",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Connecting directly to ${item.request.url.take(40)}",
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                        is ExecutionState.Cancelled -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "🛑 Request was cancelled by user.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        is ExecutionState.Error -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "⚠️ Request Failed",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.message,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        is ExecutionState.Idle -> {
                            // Display response or prompt to send
                        }
                    }

                    val resp = item.response
                    if (resp != null) {
                        // Response Metadata Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (resp.statusCode != null) {
                                            Surface(
                                                color = if (resp.statusCode in 200..299) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "${resp.statusCode}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = if (resp.statusCode in 200..299) Color(0xFF15803D) else Color(0xFFB91C1C),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${resp.elapsedTimeMs} ms",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${resp.responseSizeBytes} bytes",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    TextButton(
                                        onClick = { repository.clearResponse(item.id) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear", fontSize = 11.sp)
                                    }
                                }

                                if (resp.statusLine.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    SelectionContainer {
                                        Text(
                                            text = resp.statusLine,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Response Headers
                        Text(
                            text = "RESPONSE HEADERS (${resp.headers.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    if (resp.headers.isEmpty()) {
                                        Text("(No response headers)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        resp.headers.forEachIndexed { i, header ->
                                            Text(
                                                text = header,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (i < resp.headers.size - 1) {
                                                HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Response Body
                        Text(
                            text = "RESPONSE BODY (${resp.body?.size ?: 0} B)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (resp.isBodyTruncated) {
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚠️ Response body exceeded 1 MB limit and was truncated for mobile memory safety.",
                                    color = Color(0xFFB45309),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        if (resp.body == null || resp.body.isEmpty()) {
                            Text(
                                text = "(No response body)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (resp.isBinary) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📦", fontSize = 22.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = resp.binarySummary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            val fullString = remember(resp.body) {
                                try {
                                    String(resp.body, StandardCharsets.UTF_8)
                                } catch (_: Exception) {
                                    ""
                                }
                            }
                            val maxChars = 10_000
                            var showAllText by remember { mutableStateOf(false) }
                            val isPreviewCapped = fullString.length > maxChars
                            val textToShow = if (showAllText || !isPreviewCapped) fullString else fullString.take(maxChars)

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                SelectionContainer {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 60.dp, max = 360.dp)
                                            .verticalScroll(rememberScrollState())
                                            .horizontalScroll(rememberScrollState())
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = textToShow,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (isPreviewCapped) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (showAllText) "Showing full text (${fullString.length} chars)" else "Previewing 10,000 of ${fullString.length} chars",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = { showAllText = !showAllText }) {
                                        Text(if (showAllText) "Show Less" else "Show Full")
                                    }
                                }
                            }
                        }
                    } else if (item.executionState is ExecutionState.Idle) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("🛰️", fontSize = 32.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Ready to send",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap 'Send' to dispatch this request to the server.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isBinaryData(bytes: ByteArray): Boolean {
    val sampleLength = minOf(bytes.size, 512)
    var controlByteCount = 0
    for (i in 0 until sampleLength) {
        val b = bytes[i].toInt() and 0xFF
        if (b == 0) return true
        if (b < 32 && b != 9 && b != 10 && b != 13) {
            controlByteCount++
        }
    }
    return controlByteCount > (sampleLength / 10)
}
