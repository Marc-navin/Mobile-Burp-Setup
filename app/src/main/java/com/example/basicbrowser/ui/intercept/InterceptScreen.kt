package com.example.basicbrowser.ui.intercept

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
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.engine.TrafficFilterEngine
import com.example.basicbrowser.filter.ui.TrafficFilterBottomSheet
import com.example.basicbrowser.proxy.intercept.InterceptManager
import com.example.basicbrowser.proxy.intercept.InterceptedRequest
import com.example.basicbrowser.scope.data.ScopeRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptScreen(
    onItemClick: (String) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToScope: () -> Unit = {}
) {
    BackHandler(onBack = onBackClick)

    val manager = remember { InterceptManager.getInstance() }
    val scopeRepository = remember { ScopeRepository.getInstance() }
    val filterRepository = remember { TrafficFilterRepository.getInstance() }

    val isInterceptEnabled by manager.isInterceptEnabled.collectAsState()
    val pausedRequests by manager.pausedRequests.collectAsState()
    val interceptFilterState by filterRepository.interceptFilterState.collectAsState()
    val scopeRules by scopeRepository.rules.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "⚡ Intercept",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (pausedRequests.isNotEmpty()) {
                            val isFiltered = !interceptFilterState.isDefault()
                            Badge(
                                containerColor = if (isFiltered) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                                contentColor = if (isFiltered) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text(
                                    text = if (isFiltered) "${filteredPausedRequests.size}/${pausedRequests.size}" else "${pausedRequests.size}",
                                    fontSize = 12.sp,
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
                    // Filter Settings Button with active badge
                    val activeFilters = interceptFilterState.activeFilterCount()
                    IconButton(onClick = { showFilterSheet = true }) {
                        BadgedBox(
                            badge = {
                                if (activeFilters > 0) {
                                    Badge { Text("$activeFilters") }
                                }
                            }
                        ) {
                            Text("🔍", fontSize = 16.sp)
                        }
                    }

                    // Scope Manager Quick Button
                    IconButton(onClick = onNavigateToScope) {
                        Text("🎯", fontSize = 16.sp)
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
            // Master Intercept Toggle Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isInterceptEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
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
                            Text(
                                text = if (isInterceptEnabled) "Intercept is ACTIVE" else "Intercept is OFF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isInterceptEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
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
                            color = if (isInterceptEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            }
                        )
                    }
                    Switch(
                        checked = isInterceptEnabled,
                        onCheckedChange = { manager.setEnabled(it) }
                    )
                }
            }

            // Queue controls & count bar
            if (pausedRequests.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { manager.forwardAll() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Forward All", fontSize = 12.sp, color = Color(0xFF16A34A))
                            }
                            OutlinedButton(
                                onClick = { manager.dropAll() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Drop All", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
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
                        Text(
                            text = if (isInterceptEnabled) "⏳" else "⏸️",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isInterceptEnabled) "Waiting for requests…" else "Intercept is paused",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isInterceptEnabled) {
                                "Browse any HTTP or HTTPS webpage to inspect and edit requests before they are sent."
                            } else {
                                "Toggle Intercept to ACTIVE to pause incoming requests."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Text("🔍", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No requests match active display filters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${pausedRequests.size} request(s) paused in queue. Adjust or reset filters to display them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { filterRepository.resetInterceptFilter() }
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
                        InterceptedRequestCard(
                            item = item,
                            isInScope = isInScope,
                            onClick = { onItemClick(item.id) },
                            onForward = { manager.forward(item.id) },
                            onDrop = { manager.drop(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InterceptedRequestCard(
    item: InterceptedRequest,
    isInScope: Boolean,
    onClick: () -> Unit,
    onForward: () -> Unit,
    onDrop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Method + Protocol + Scope Badge + Timeout
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
                        color = methodColor(item.originalRequest.method),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = item.originalRequest.method,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Protocol badge
                    Surface(
                        color = if (item.isHttps) Color(0xFF15803D) else Color(0xFF6B7280),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (item.isHttps) "HTTPS" else "HTTP",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    // Scope badge
                    if (isInScope) {
                        Surface(
                            color = Color(0xFF0284C7),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "IN SCOPE",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // ID fragment
                Text(
                    text = "#${item.id.take(6)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // URL
            Text(
                text = item.originalRequest.url,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Quick Forward / Drop buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onForward,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFDCFCE7),
                            contentColor = Color(0xFF15803D)
                        )
                    ) {
                        Text("Forward", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = onDrop,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFFEE2E2),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Drop", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun methodColor(method: String): Color {
    return when (method.uppercase()) {
        "GET" -> Color(0xFF2563EB)
        "POST" -> Color(0xFF16A34A)
        "PUT" -> Color(0xFFD97706)
        "DELETE" -> Color(0xFFDC2626)
        "PATCH" -> Color(0xFF9333EA)
        "HEAD" -> Color(0xFF0891B2)
        "OPTIONS" -> Color(0xFF4B5563)
        else -> Color(0xFF6B7280)
    }
}
