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
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.model.InterceptFilterSettings
import com.example.basicbrowser.proxy.tls.CertificateGenerator
import com.example.basicbrowser.scope.data.ScopeRepository

/**
 * General Settings Screen.
 *
 * Provides application-level preferences, memory management controls,
 * cache trimming, and application identity information.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBackClick: () -> Unit,
    historyRepository: HttpHistoryRepository = remember { HttpHistoryRepository.getInstance() },
    scopeRepository: ScopeRepository = remember { ScopeRepository.getInstance() },
    filterRepository: TrafficFilterRepository = remember { TrafficFilterRepository.getInstance() }
) {
    BackHandler(onBack = onBackClick)

    val historyItems by historyRepository.history.collectAsState()
    var certCacheSize by remember { mutableIntStateOf(CertificateGenerator.cacheSize()) }

    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showResetScopeDialog by remember { mutableStateOf(false) }
    var actionNotice by remember { mutableStateOf<String?>(null) }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear HTTP History?") },
            text = { Text("This will permanently remove all captured requests, responses, and cached body files. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        historyRepository.clear()
                        showClearHistoryDialog = false
                        actionNotice = "HTTP History cleared successfully."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetScopeDialog) {
        AlertDialog(
            onDismissRequest = { showResetScopeDialog = false },
            title = { Text("Clear All Scope Rules?") },
            text = { Text("This will remove all include and exclude rules. Target scope will revert to unrestricted (all traffic in scope).") },
            confirmButton = {
                Button(
                    onClick = {
                        scopeRepository.clearScope()
                        showResetScopeDialog = false
                        actionNotice = "Target Scope cleared. Scope is now unrestricted."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear Rules")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetScopeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "General Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Storage, Memory & App Preferences",
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
            if (actionNotice != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = actionNotice!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { actionNotice = null }) {
                            Text("Dismiss", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Application Identity Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Application Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    DetailRow(label = "Application Name", value = "ABS")
                    DetailRow(label = "Full Name", value = "Advanced BasicBrowser Security")
                    DetailRow(label = "Package Name", value = "com.example.basicbrowser")
                    DetailRow(label = "Version", value = "1.0 (Build 1)")
                    DetailRow(label = "Target SDK", value = "Android 15 / 16 (API 36)")
                    DetailRow(label = "Minimum SDK", value = "Android 7.0 (API 24)")
                }
            }

            // HTTP History Storage Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HTTP History & Memory Protection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "History uses a fixed 200-transaction FIFO buffer with temporary disk body spillover to maintain strict memory bounds on Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DetailRow(label = "Capacity Limit", value = "200 transactions (Fixed)")
                    DetailRow(label = "Currently Stored", value = "${historyItems.size} items")

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showClearHistoryDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = historyItems.isNotEmpty()
                    ) {
                        Text("Clear HTTP History")
                    }
                }
            }

            // Host Certificate Cache Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Host Certificate Cache",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Dynamically generated host certificates are cached in memory (LRU, max 50 entries) to speed up TLS handshakes for intercepted hosts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DetailRow(label = "Cached Certificates", value = "$certCacheSize / 50")

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            CertificateGenerator.clearCache()
                            certCacheSize = CertificateGenerator.cacheSize()
                            actionNotice = "Host certificate cache cleared."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Trim Certificate Cache")
                    }
                }
            }

            // Reset Options Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Reset Preferences",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { showResetScopeDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Target Scope Rules")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            filterRepository.resetHistoryFilter()
                            filterRepository.updateInterceptFilter(InterceptFilterSettings())
                            actionNotice = "Traffic filters reset to default."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset All Traffic Filters")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

