package com.example.basicbrowser.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.model.InterceptFilterSettings
import com.example.basicbrowser.filter.model.ScopeFilterOption

/**
 * Traffic Filters Screen.
 *
 * Centralized configuration for both:
 * 1. Intercept Filters (early request bypass upstream)
 * 2. HTTP History Filters (display-only transaction filtering)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficFiltersScreen(
    onBackClick: () -> Unit,
    onNavigateToScope: () -> Unit,
    filterRepository: TrafficFilterRepository = remember { TrafficFilterRepository.getInstance() }
) {
    BackHandler(onBack = onBackClick)

    val historyFilterState by filterRepository.historyFilterState.collectAsState()
    val interceptSettings by filterRepository.interceptFilterSettings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Traffic Filters",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Intercept Bypass & History Criteria",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            filterRepository.resetHistoryFilter()
                            filterRepository.updateInterceptFilter(InterceptFilterSettings())
                        }
                    ) {
                        Text("Reset All")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Target Scope Link Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Global Target Scope",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Scope defines which domains are authorized for testing and filtering.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onNavigateToScope) {
                        Text("Edit Scope")
                    }
                }
            }

            // Section 1: Intercept Filters
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚡ Intercept Early Bypass Filters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Filtered requests are automatically forwarded upstream unchanged without waiting in the paused queue or consuming worker capacity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FilterSwitchRow(
                        title = "Intercept only in-scope requests",
                        subtitle = "Out-of-scope targets are automatically forwarded upstream",
                        checked = interceptSettings.interceptOnlyInScope,
                        onCheckedChange = { checked ->
                            filterRepository.updateInterceptFilter(
                                interceptSettings.copy(interceptOnlyInScope = checked)
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    FilterSwitchRow(
                        title = "Filter image requests",
                        subtitle = "Bypass JPEG, PNG, GIF, WebP, SVG, ICO images",
                        checked = interceptSettings.filterImages,
                        onCheckedChange = { checked ->
                            filterRepository.updateInterceptFilter(
                                interceptSettings.copy(filterImages = checked)
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    FilterSwitchRow(
                        title = "Filter CSS stylesheets",
                        subtitle = "Bypass .css files and text/css requests",
                        checked = interceptSettings.filterCss,
                        onCheckedChange = { checked ->
                            filterRepository.updateInterceptFilter(
                                interceptSettings.copy(filterCss = checked)
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    FilterSwitchRow(
                        title = "Filter JavaScript files",
                        subtitle = "Bypass .js, .mjs scripts and script dependencies",
                        checked = interceptSettings.filterJs,
                        onCheckedChange = { checked ->
                            filterRepository.updateInterceptFilter(
                                interceptSettings.copy(filterJs = checked)
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    FilterSwitchRow(
                        title = "Filter other binary resources",
                        subtitle = "Bypass audio, video, fonts, and binary archives",
                        checked = interceptSettings.filterOtherResources,
                        onCheckedChange = { checked ->
                            filterRepository.updateInterceptFilter(
                                interceptSettings.copy(filterOtherResources = checked)
                            )
                        }
                    )
                }
            }

            // Section 2: HTTP History Filters
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📜 HTTP History Display Filters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Filters only control which captured items are displayed on screen. Captured data and the 200-transaction FIFO buffer are never deleted or modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FilterSwitchRow(
                        title = "Show only in-scope items",
                        subtitle = "Hide out-of-scope transactions from the history table",
                        checked = historyFilterState.scopeFilter == ScopeFilterOption.SHOW_ONLY_IN_SCOPE,
                        onCheckedChange = { checked ->
                            filterRepository.updateHistoryFilter(
                                historyFilterState.copy(
                                    scopeFilter = if (checked) ScopeFilterOption.SHOW_ONLY_IN_SCOPE else ScopeFilterOption.SHOW_ALL
                                )
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    FilterSwitchRow(
                        title = "Hide images from history",
                        subtitle = "Exclude image responses from view",
                        checked = historyFilterState.hideImages,
                        onCheckedChange = { checked ->
                            filterRepository.updateHistoryFilter(
                                historyFilterState.copy(hideImages = checked)
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    FilterSwitchRow(
                        title = "Hide CSS and JavaScript",
                        subtitle = "Exclude stylesheets and script responses",
                        checked = historyFilterState.hideCssAndJs,
                        onCheckedChange = { checked ->
                            filterRepository.updateHistoryFilter(
                                historyFilterState.copy(hideCssAndJs = checked)
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val activeCount = historyFilterState.activeFilterCount()
                    if (activeCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$activeCount active history filters",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                TextButton(onClick = { filterRepository.resetHistoryFilter() }) {
                                    Text("Reset History Filters")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
