package com.example.basicbrowser.ui.intercept

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import com.example.basicbrowser.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.data.model.HighlightColor
import com.example.basicbrowser.data.model.RequestAnnotation
import com.example.basicbrowser.data.repository.RequestAnnotationRepository
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.engine.TrafficFilterEngine
import com.example.basicbrowser.filter.ui.TrafficFilterBottomSheet
import com.example.basicbrowser.proxy.intercept.InterceptManager
import com.example.basicbrowser.proxy.intercept.InterceptedRequest
import com.example.basicbrowser.scope.data.ScopeRepository
import com.example.basicbrowser.ui.menu.RequestContextBottomSheet
import com.example.basicbrowser.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptScreen(
    onItemClick: (String) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToScope: () -> Unit = {},
    onNavigateToBrowser: (String) -> Unit = {},
    onNavigateToRepeater: () -> Unit = {},
    onNavigateToVariation: () -> Unit = {}
) {
    BackHandler(onBack = onBackClick)

    val context = LocalContext.current
    val manager = remember { InterceptManager.getInstance() }
    val scopeRepository = remember { ScopeRepository.getInstance() }
    val filterRepository = remember { TrafficFilterRepository.getInstance() }
    val annotationRepo = remember { RequestAnnotationRepository.getInstance(context) }

    val isInterceptEnabled by manager.isInterceptEnabled.collectAsState()
    val pausedRequests by manager.pausedRequests.collectAsState()
    val interceptFilterState by filterRepository.interceptFilterState.collectAsState()
    val scopeRules by scopeRepository.rules.collectAsState()
    val annotations by annotationRepo.annotations.collectAsState()
    val savedRequests by annotationRepo.savedRequests.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }
    var contextMenuItem by remember { mutableStateOf<InterceptedRequest?>(null) }

    // Display-only filtering: purely determines which requests are rendered in the list.
    // Underlying InterceptManager queue is never altered by display filtering.
    val filteredPausedRequests = remember(pausedRequests, interceptFilterState, scopeRules) {
        TrafficFilterEngine.filterInterceptedRequests(pausedRequests, interceptFilterState, scopeRepository)
    }

    if (showFilterSheet) {
        TrafficFilterBottomSheet(
            currentState = interceptFilterState,
            onDismiss = { showFilterSheet = false },
            onApply = { filterRepository.updateInterceptFilter(it) },
            onReset = { filterRepository.resetInterceptFilter() },
            onNavigateToScope = onNavigateToScope,
            title = "Intercept Filters",
            visibleCount = filteredPausedRequests.size,
            totalCount = pausedRequests.size
        )
    }

    if (contextMenuItem != null) {
        val item = contextMenuItem!!
        RequestContextBottomSheet(
            id = item.id,
            url = item.originalRequest.url,
            method = item.originalRequest.method,
            requestHeaders = item.originalRequest.headers,
            requestBody = item.originalRequest.body,
            isIntercept = true,
            onDismiss = { contextMenuItem = null },
            onNavigateToBrowser = onNavigateToBrowser,
            onNavigateToRepeater = onNavigateToRepeater,
            onNavigateToVariation = onNavigateToVariation
        )
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
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_intercept),
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Intercept",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = OnSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (pausedRequests.isNotEmpty()) {
                            val isFiltered = !interceptFilterState.isDefault()
                            Badge(
                                containerColor = if (isFiltered) SurfaceContainerHigh else TertiaryContainer,
                                contentColor = if (isFiltered) Primary else SurfaceContainerLowest
                            ) {
                                Text(
                                    text = if (isFiltered) "${filteredPausedRequests.size}/${pausedRequests.size}" else "${pausedRequests.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
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
                    // Filter Settings Button with active badge
                    val activeFilters = interceptFilterState.activeFilterCount()
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

                    // Scope Manager Quick Button
                    IconButton(onClick = onNavigateToScope) {
                        Icon(
                            painter = painterResource(R.drawable.ic_scope_target),
                            contentDescription = "Scope",
                            tint = OnSurface,
                            modifier = Modifier.size(18.dp)
                        )
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
            // Master Intercept Toggle Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (isInterceptEnabled) TertiaryContainer.copy(alpha = 0.5f) else SurfaceContainerHigh
                ),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isInterceptEnabled) Tertiary else Outline,
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isInterceptEnabled) "INTERCEPTING" else "INTERCEPT OFF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isInterceptEnabled) Tertiary else OnSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isInterceptEnabled) {
                                "Pausing requests before forwarding (max ${manager.maxPaused})"
                            } else {
                                "Requests pass through directly without pausing"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isInterceptEnabled,
                        onCheckedChange = { manager.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SurfaceContainerLowest,
                            checkedTrackColor = Primary,
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = SurfaceContainerHighest
                        )
                    )
                }
            }

            // Queue controls & count bar
            if (pausedRequests.isNotEmpty()) {
                Surface(
                    color = SurfaceContainerLow,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SurfaceContainerHigh),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isFiltered = !interceptFilterState.isDefault()
                        Text(
                            text = if (isFiltered) {
                                "Paused: ${filteredPausedRequests.size}/${pausedRequests.size} (${pausedRequests.size - filteredPausedRequests.size} hidden)"
                            } else {
                                "Paused: ${pausedRequests.size} / ${manager.maxPaused}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = OnSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { manager.forwardAll() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Primary,
                                    contentColor = OnPrimary
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Forward All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { manager.dropAll() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ErrorContainer.copy(alpha = 0.35f),
                                    contentColor = Error
                                ),
                                border = BorderStroke(1.dp, Error.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Drop All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // List of paused requests or Empty state
            if (pausedRequests.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(SurfaceContainerLow, RoundedCornerShape(16.dp))
                                .border(1.dp, SurfaceContainerHigh, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(if (isInterceptEnabled) R.drawable.ic_nav_traffic else R.drawable.ic_action_pause),
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isInterceptEnabled) "Waiting for requests…" else "Intercept is paused",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = OnSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isInterceptEnabled) {
                                "Browse any HTTP or HTTPS webpage to inspect and edit requests before they are sent."
                            } else {
                                "Toggle Intercept to ACTIVE to pause incoming requests."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            } else if (filteredPausedRequests.isEmpty()) {
                // Paused requests exist in the underlying queue, but all are hidden by active filters
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_filter_sliders),
                            contentDescription = null,
                            tint = Outline,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No requests match active display filters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${pausedRequests.size} request(s) paused in queue. Adjust or reset filters to display them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { filterRepository.resetInterceptFilter() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceContainerHigh,
                                contentColor = Primary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Reset Filters")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPausedRequests, key = { it.id }) { item ->
                        val isInScope = remember(item.originalRequest.url, scopeRules) {
                            scopeRepository.isInScope(item.originalRequest.url)
                        }
                        val itemAnnotation = annotations[item.id]
                        val isItemSaved = savedRequests.any { it.id == item.id }

                        InterceptedRequestCard(
                            item = item,
                            isInScope = isInScope,
                            annotation = itemAnnotation,
                            isSaved = isItemSaved,
                            onClick = { onItemClick(item.id) },
                            onLongClick = { contextMenuItem = item },
                            onMenuClick = { contextMenuItem = item },
                            onForward = {
                                val pending = manager.getPausedRequest(item.id)
                                if (pending != null && !pending.future.isDone) {
                                    manager.forward(item.id)
                                }
                            },
                            onDrop = {
                                val pending = manager.getPausedRequest(item.id)
                                if (pending != null && !pending.future.isDone) {
                                    manager.drop(item.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InterceptedRequestCard(
    item: InterceptedRequest,
    isInScope: Boolean,
    annotation: RequestAnnotation? = null,
    isSaved: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onForward: () -> Unit,
    onDrop: () -> Unit
) {
    val highlightColor = remember(annotation?.highlightColor) {
        val c = annotation?.highlightColor ?: HighlightColor.NONE
        if (c == HighlightColor.NONE) null else Color(c.colorValue)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = highlightColor ?: SurfaceContainerLow
        ),
        border = BorderStroke(1.dp, SurfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Method + Protocol + Scope Badge + Indicators + Overflow Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Method pill
                    Surface(
                        color = methodBgColor(item.originalRequest.method),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = item.originalRequest.method,
                            color = methodTextColor(item.originalRequest.method),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Protocol badge
                    Surface(
                        color = SurfaceContainerHigh,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (item.isHttps) "HTTPS" else "HTTP",
                            color = if (item.isHttps) Secondary else OnSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    // Scope badge
                    if (isInScope) {
                        Surface(
                            color = MethodGetBg,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "IN SCOPE",
                                color = Primary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (isSaved) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_star),
                            contentDescription = "Saved",
                            tint = Tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    if (!annotation?.note.isNullOrBlank()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_edit),
                            contentDescription = "Note",
                            tint = Primary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // ID fragment
                    Text(
                        text = "#${item.id.take(6)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = OnSurfaceVariant
                    )

                    // Overflow menu button
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text(
                            text = "⋮",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = OnSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // URL
            Text(
                text = item.originalRequest.url,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = OnSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Footer Row: Headers / Body info + Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Info text
                val bodySize = item.originalRequest.body?.size ?: 0
                val infoText = if (bodySize > 0) {
                    "${item.originalRequest.headers.size} headers • $bodySize bytes"
                } else {
                    "${item.originalRequest.headers.size} headers"
                }
                Text(
                    text = infoText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Outline
                )

                // Quick Forward / Drop buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onForward,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Forward", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onDrop,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ErrorContainer.copy(alpha = 0.35f),
                            contentColor = Error
                        ),
                        border = BorderStroke(1.dp, Error.copy(alpha = 0.4f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Drop", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun methodBgColor(method: String): Color {
    return when (method.uppercase()) {
        "GET" -> MethodGetBg
        "POST" -> MethodPostBg
        "PUT" -> MethodPutBg
        "DELETE" -> MethodDeleteBg
        else -> SurfaceContainerHigh
    }
}

private fun methodTextColor(method: String): Color {
    return when (method.uppercase()) {
        "GET" -> MethodGetText
        "POST" -> MethodPostText
        "PUT" -> MethodPutText
        "DELETE" -> MethodDeleteText
        else -> OnSurfaceVariant
    }
}
