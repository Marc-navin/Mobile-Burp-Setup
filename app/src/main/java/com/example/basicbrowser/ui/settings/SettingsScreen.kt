package com.example.basicbrowser.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.engine.TrafficFilterEngine
import com.example.basicbrowser.proxy.ProxyServer
import com.example.basicbrowser.proxy.tls.CertificateAuthority
import com.example.basicbrowser.scope.data.ScopeRepository

/**
 * Centralized Settings Screen.
 *
 * Categorizes management and configuration screens into:
 * - Target & Scope
 * - CA Management
 * - Proxy Settings
 * - Traffic Filters
 * - General Settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToTargetScope: () -> Unit,
    onNavigateToCaManagement: () -> Unit,
    onNavigateToProxySettings: () -> Unit,
    onNavigateToTrafficFilters: () -> Unit,
    onNavigateToGeneralSettings: () -> Unit
) {
    val scopeRules by ScopeRepository.getInstance().rules.collectAsState()
    val historyFilterState by TrafficFilterRepository.getInstance().historyFilterState.collectAsState()
    val historyItems by HttpHistoryRepository.getInstance().history.collectAsState()
    val isProxyRunning = remember { ProxyServer.getInstance().isRunning() }

    val activeScopeRulesCount = remember(scopeRules) { scopeRules.count { it.enabled } }
    val scopeSummary = if (activeScopeRulesCount > 0) {
        "$activeScopeRulesCount active rules"
    } else {
        "Unrestricted scope"
    }

    val isCaInitialized = remember { CertificateAuthority.isInitialized() }
    val caSummary = if (isCaInitialized) "Initialized (Ready)" else "Not Initialized"

    val activeFilterCount = remember(historyFilterState) { historyFilterState.activeFilterCount() }
    val filterSummary = if (activeFilterCount > 0) "$activeFilterCount active" else "Default (Show all)"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "ABS Configuration & Preferences",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group: Target & Recon
            item {
                SettingsGroupHeader(title = "TARGET & SCOPE")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCategoryCard(
                    icon = "🎯",
                    iconBackground = MaterialTheme.colorScheme.primaryContainer,
                    title = "Target & Scope",
                    description = "Manage included/excluded domains, CIDRs, IPs, and test URLs with the live scope tester.",
                    badgeText = scopeSummary,
                    badgeColor = if (activeScopeRulesCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    onClick = onNavigateToTargetScope
                )
            }

            // Group: Security & Cryptography
            item {
                SettingsGroupHeader(title = "SECURITY & CERTIFICATES")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCategoryCard(
                    icon = "🔒",
                    iconBackground = MaterialTheme.colorScheme.tertiaryContainer,
                    title = "CA Certificate & Trust",
                    description = "Development CA status, certificate export (PEM/DER), SHA-256 fingerprint, and trust verification.",
                    badgeText = caSummary,
                    badgeColor = if (isCaInitialized) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                    onClick = onNavigateToCaManagement
                )
            }

            // Group: Network & Interception
            item {
                SettingsGroupHeader(title = "NETWORK & PROXY")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCategoryCard(
                    icon = "🔌",
                    iconBackground = MaterialTheme.colorScheme.secondaryContainer,
                    title = "Proxy Settings",
                    description = "Local TCP proxy configuration (127.0.0.1:8080), worker threads, connection status, and WebView routing.",
                    badgeText = if (isProxyRunning) "127.0.0.1:8080" else "Stopped",
                    badgeColor = if (isProxyRunning) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                    onClick = onNavigateToProxySettings
                )
            }

            // Group: Traffic Filters
            item {
                SettingsGroupHeader(title = "TRAFFIC & RESOURCE FILTERING")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCategoryCard(
                    icon = "🔍",
                    iconBackground = MaterialTheme.colorScheme.primaryContainer,
                    title = "Traffic Filters",
                    description = "Configure Intercept early bypass rules (images, CSS, JS) and HTTP History display criteria.",
                    badgeText = filterSummary,
                    badgeColor = if (activeFilterCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    onClick = onNavigateToTrafficFilters
                )
            }

            // Group: System & Preferences
            item {
                SettingsGroupHeader(title = "GENERAL & STORAGE")
                Spacer(modifier = Modifier.height(6.dp))
                SettingsCategoryCard(
                    icon = "⚙️",
                    iconBackground = MaterialTheme.colorScheme.surfaceVariant,
                    title = "General Settings",
                    description = "Memory protections, 200-transaction FIFO history limit, CA certificate cache, and application info.",
                    badgeText = "${historyItems.size}/200 Items",
                    badgeColor = MaterialTheme.colorScheme.outline,
                    onClick = onNavigateToGeneralSettings
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ABS — Advanced BasicBrowser Security v1.0",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun SettingsCategoryCard(
    icon: String,
    iconBackground: Color,
    title: String,
    description: String,
    badgeText: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, fontSize = 22.sp)
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "›",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

