package com.example.basicbrowser.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.basicbrowser.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.data.model.HighlightColor
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.model.RequestAnnotation
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.data.repository.RequestAnnotationRepository
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.engine.TrafficFilterEngine
import com.example.basicbrowser.filter.ui.TrafficFilterBottomSheet
import com.example.basicbrowser.scope.data.ScopeRepository
import com.example.basicbrowser.ui.menu.RequestContextBottomSheet
import com.example.basicbrowser.theme.*
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onItemClick: (HttpHistoryItem) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToScope: () -> Unit = {},
    onNavigateToBrowser: (String) -> Unit = {},
    onNavigateToRepeater: () -> Unit = {},
    onNavigateToVariation: () -> Unit = {}
) {
    BackHandler(onBack = onBackClick)

    val context = LocalContext.current
    val repository = remember { HttpHistoryRepository.getInstance() }
    val scopeRepository = remember { ScopeRepository.getInstance() }
    val filterRepository = remember { TrafficFilterRepository.getInstance() }
    val annotationRepo = remember { RequestAnnotationRepository.getInstance(context) }

    val historyItems by repository.history.collectAsState()
    val filterState by filterRepository.historyFilterState.collectAsState()
    val scopeRules by scopeRepository.rules.collectAsState()
    val annotations by annotationRepo.annotations.collectAsState()
    val savedRequests by annotationRepo.savedRequests.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var contextMenuItem by remember { mutableStateOf<HttpHistoryItem?>(null) }
    var previewItem by remember { mutableStateOf<HttpHistoryItem?>(null) }

    val filteredItems = remember(historyItems, filterState, scopeRules) {
        TrafficFilterEngine.filterHistory(historyItems, filterState, scopeRepository)
    }

    if (showFilterSheet) {
        TrafficFilterBottomSheet(
            currentState = filterState,
            onDismiss = { showFilterSheet = false },
            onApply = { filterRepository.updateHistoryFilter(it) },
            onReset = { filterRepository.resetHistoryFilter() },
            onNavigateToScope = onNavigateToScope,
            title = "HTTP History Filters",
            visibleCount = filteredItems.size,
            totalCount = historyItems.size
        )
    }

    if (contextMenuItem != null) {
        val item = contextMenuItem!!
        RequestContextBottomSheet(
            id = item.id,
            url = item.url,
            method = item.method,
            requestHeaders = item.requestHeaders,
            requestBody = item.requestBody,
            statusCode = item.statusCode,
            responseHeaders = item.responseHeaders,
            responseBody = item.responseBody,
            isIntercept = false,
            onDismiss = { contextMenuItem = null },
            onNavigateToBrowser = onNavigateToBrowser,
            onNavigateToRepeater = onNavigateToRepeater,
            onNavigateToVariation = onNavigateToVariation,
            onOpenResponseInBrowser = {
                contextMenuItem = null
                previewItem = item
            }
        )
    }

    if (previewItem != null) {
        SafeResponsePreviewDialog(
            item = previewItem!!,
            onDismiss = { previewItem = null }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear HTTP History?") },
            text = { Text("This will clear the HTTP History log. Any saved/starred items will be preserved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val savedIds = annotationRepo.getAllSavedItems().map { it.id }.toSet()
                        repository.clear(savedIds)
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

    val uniqueHostsCount = remember(historyItems) {
        historyItems.map { parseHostAndPath(it.url).first }.distinct().size
    }
    val totalVolumeLabel = remember(historyItems) {
        val totalBytes = historyItems.sumOf { (it.requestBody?.size ?: 0) + (it.responseBody?.size ?: 0).toLong() }
        formatVolumeBytes(totalBytes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceContainerLowest,
                    titleContentColor = OnSurface,
                    navigationIconContentColor = OnSurface,
                    actionIconContentColor = OnSurface
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "HTTP History",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = OnSurface
                        )
                        if (historyItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            val isFiltered = !filterState.isDefault()
                            Surface(
                                color = if (isFiltered) SurfaceContainerHigh else PrimaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (isFiltered) "${filteredItems.size}/${historyItems.size}" else historyItems.size.toString(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isFiltered) Primary else SurfaceContainerLowest,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                    }
                },
                actions = {
                    // 1. Filter Button with badge
                    val activeFilters = filterState.activeFilterCount()
                    IconButton(onClick = { showFilterSheet = true }) {
                        BadgedBox(
                            badge = {
                                if (activeFilters > 0) {
                                    Badge(
                                        containerColor = Primary,
                                        contentColor = OnPrimary
                                    ) { Text("$activeFilters") }
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_filter_sliders),
                                contentDescription = "Filter",
                                tint = OnSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // 2. Clear History
                    if (historyItems.isNotEmpty()) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Text("Clear", color = Error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Surface)
                .padding(paddingValues)
        ) {
            // Live Telemetry Banner (matching UI/src/views/TrafficView.tsx)
            Surface(
                color = SurfaceContainerLow,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, SurfaceContainerHigh),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LIVE indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(SurfaceContainerLowest, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Secondary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Secondary
                        )
                    }

                    // Packets metric
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${historyItems.size}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Primary
                        )
                        Text(
                            text = "Packets",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = OnSurfaceVariant
                        )
                    }

                    // Hosts metric
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$uniqueHostsCount",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = OnSurface
                        )
                        Text(
                            text = "Hosts",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = OnSurfaceVariant
                        )
                    }

                    // Volume metric
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = totalVolumeLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Tertiary
                        )
                        Text(
                            text = "Volume",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = OnSurfaceVariant
                        )
                    }
                }
            }

            if (historyItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(SurfaceContainerLow, RoundedCornerShape(16.dp))
                                .border(1.dp, SurfaceContainerHigh, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_traffic),
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No HTTP Traffic Captured",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = OnSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Use the Browser tab to visit websites. Decrypted HTTP and HTTPS requests will appear here in real time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_filter_sliders),
                            contentDescription = null,
                            tint = Outline,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Matching Traffic",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "All ${historyItems.size} captured requests were hidden by the active filters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { filterRepository.resetHistoryFilter() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceContainerHigh,
                                contentColor = Primary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Reset All Filters")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val isInScope = remember(item.url, scopeRules) {
                            scopeRepository.isInScope(item.url)
                        }
                        val itemAnnotation = annotations[item.id]
                        val isItemSaved = savedRequests.any { it.id == item.id }

                        HistoryItemRow(
                            item = item,
                            isInScope = isInScope,
                            annotation = itemAnnotation,
                            isSaved = isItemSaved,
                            onClick = { onItemClick(item) },
                            onLongClick = { contextMenuItem = item },
                            onMenuClick = { contextMenuItem = item }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryItemRow(
    item: HttpHistoryItem,
    isInScope: Boolean = true,
    annotation: RequestAnnotation? = null,
    isSaved: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMenuClick: () -> Unit = {}
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val timeString = remember(item.timestamp) { timeFormat.format(Date(item.timestamp)) }

    // Parse host and path/query
    val (host, path) = remember(item.url) {
        parseHostAndPath(item.url)
    }

    val highlightColor = remember(annotation?.highlightColor) {
        val c = annotation?.highlightColor ?: HighlightColor.NONE
        if (c == HighlightColor.NONE) null else Color(c.colorValue)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = highlightColor ?: SurfaceContainerLow,
        border = BorderStroke(1.dp, SurfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Method badge
            MethodBadge(method = item.method)

            Spacer(modifier = Modifier.width(6.dp))

            // 2. Scope indicator badge
            Surface(
                color = if (isInScope) MethodGetBg else SurfaceContainerHigh,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, if (isInScope) Primary.copy(alpha = 0.4f) else OutlineVariant)
            ) {
                Text(
                    text = if (isInScope) "IN" else "OUT",
                    color = if (isInScope) Primary else Outline,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 3. Host & Path
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
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (isSaved) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_action_star),
                            contentDescription = "Saved",
                            tint = Tertiary,
                            modifier = Modifier.size(11.dp)
                        )
                    }

                    if (!annotation?.note.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_action_edit),
                            contentDescription = "Note",
                            tint = Primary,
                            modifier = Modifier.size(11.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall,
                        color = Outline,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 4. Status code badge
            StatusCodeBadge(statusCode = item.statusCode)

            // 5. Overflow menu button
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(28.dp)
            ) {
                Text(
                    text = "⋮",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MethodBadge(method: String) {
    val (bgColor, textColor) = when (method.uppercase()) {
        "GET" -> MethodGetBg to MethodGetText
        "POST" -> MethodPostBg to MethodPostText
        "PUT", "PATCH" -> MethodPutBg to MethodPutText
        "DELETE" -> MethodDeleteBg to MethodDeleteText
        "CONNECT" -> SurfaceContainerHigh to Primary
        else -> SurfaceContainerHigh to OnSurfaceVariant
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
            color = SurfaceContainerHigh,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = "...",
                color = Outline,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        return
    }

    val (bgColor, textColor, dotColor) = when (statusCode) {
        in 200..299 -> Triple(Status2xxBg, Status2xxText, Secondary)
        in 300..399 -> Triple(Status3xxBg, Status3xxText, OnSurfaceVariant)
        in 400..499 -> Triple(Status4xxBg, Status4xxText, Error)
        in 500..599 -> Triple(Status5xxBg, Status5xxText, Error)
        else -> Triple(SurfaceContainerHigh, OnSurfaceVariant, Outline)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = statusCode.toString(),
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun formatVolumeBytes(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
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
