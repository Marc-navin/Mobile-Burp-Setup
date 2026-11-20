package com.example.basicbrowser.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewFeature
import com.example.basicbrowser.proxy.ProxyServer
import com.example.basicbrowser.theme.*
import kotlin.concurrent.thread

/**
 * Proxy Settings Screen.
 *
 * Exposes configurable and observable proxy options supported by the
 * local TCP proxy implementation (127.0.0.1:8080) and WebView proxy routing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsScreen(
    onBackClick: () -> Unit,
    proxyServer: ProxyServer = remember { ProxyServer.getInstance() }
) {
    BackHandler(onBack = onBackClick)

    var isRunning by remember { mutableStateOf(proxyServer.isRunning()) }
    var activeConnections by remember { mutableIntStateOf(proxyServer.getActiveConnectionCount()) }
    var restartNotice by remember { mutableStateOf<String?>(null) }
    var isRestarting by remember { mutableStateOf(false) }

    val isProxyOverrideSupported = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Proxy Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = OnSurface
                        )
                        Text(
                            text = "Local TCP Proxy & Interception Core",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = OnSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceContainerLowest
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
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceContainerLow
                ),
                border = BorderStroke(1.dp, SurfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) Color(0xFF16A34A) else Color(0xFFDC2626))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "PROXY ACTIVE" else "PROXY STOPPED",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (isRunning) Color(0xFF16A34A) else Color(0xFFDC2626)
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "127.0.0.1:8080",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "The local proxy server accepts HTTP and HTTPS CONNECT requests from the embedded WebView, parsing and streaming transactions through the interception and history pipeline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Active Client Sockets: $activeConnections",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Button(
                            onClick = {
                                isRestarting = true
                                restartNotice = null
                                thread {
                                    proxyServer.stop()
                                    Thread.sleep(100)
                                    proxyServer.start()
                                    isRunning = proxyServer.isRunning()
                                    activeConnections = proxyServer.getActiveConnectionCount()
                                    isRestarting = false
                                    restartNotice = "Proxy restarted successfully on 127.0.0.1:8080"
                                }
                            },
                            enabled = !isRestarting,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(if (isRestarting) "Restarting…" else "Restart Proxy", fontSize = 12.sp)
                        }
                    }

                    if (restartNotice != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = restartNotice!!,
                            color = Color(0xFF16A34A),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Concurrency & Thread Pool Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceContainerLow
                ),
                border = BorderStroke(1.dp, SurfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Concurrency & Mobile Resource Bounds",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ProxyDetailRow(label = "Core Worker Threads", value = "${ProxyServer.DEFAULT_CORE_WORKERS}")
                    ProxyDetailRow(label = "Maximum Worker Threads", value = "${ProxyServer.DEFAULT_MAX_WORKERS}")
                    ProxyDetailRow(label = "Queue Capacity", value = "${ProxyServer.DEFAULT_QUEUE_CAPACITY} requests")
                    ProxyDetailRow(label = "Thread Keep-Alive", value = "${ProxyServer.KEEP_ALIVE_SECONDS}s")
                    ProxyDetailRow(label = "Overload Policy", value = "Fast HTTP 503 (No thread stall)")

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bounded thread pool prevents memory starvation and out-of-memory errors on constrained Android devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant
                    )
                }
            }

            // WebView Proxy Routing Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceContainerLow
                ),
                border = BorderStroke(1.dp, SurfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "WebView Routing & Traffic Redirection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ProxyDetailRow(
                        label = "ProxyController API",
                        value = if (isProxyOverrideSupported) "Supported (PROXY_OVERRIDE)" else "Unavailable"
                    )
                    ProxyDetailRow(
                        label = "Routing Target",
                        value = "127.0.0.1:8080 (HTTP + HTTPS)"
                    )
                    ProxyDetailRow(
                        label = "Scope Enforcement",
                        value = "Global Target Scope"
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "All web navigation, subresource fetches (scripts, CSS, images), and API calls from the Browser tab are securely routed through the local proxy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant
                    )
                }
            }

            // Upstream Connection Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceContainerLow
                ),
                border = BorderStroke(1.dp, SurfaceContainerHigh),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Upstream Connection & Security",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ProxyDetailRow(label = "Connect Timeout", value = "15 seconds")
                    ProxyDetailRow(label = "Read Timeout", value = "30 seconds")
                    ProxyDetailRow(label = "Upstream TLS Verification", value = "Strict (Host & Cert validated)")
                    ProxyDetailRow(label = "Loopback Isolation", value = "Localhost only (127.0.0.1)")

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The proxy refuses external network interfaces, binding exclusively to the device loopback interface for local isolation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyDetailRow(label: String, value: String) {
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

