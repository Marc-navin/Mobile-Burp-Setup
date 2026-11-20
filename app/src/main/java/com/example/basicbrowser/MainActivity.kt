package com.example.basicbrowser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.basicbrowser.proxy.tls.DevCaSslErrorHandler
import android.net.http.SslError
import android.webkit.SslErrorHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.basicbrowser.theme.BasicBrowserTheme
import android.net.Uri
import android.util.Log
import com.example.basicbrowser.proxy.ProxyServer
import com.example.basicbrowser.proxy.tls.CertificateAuthority
import com.example.basicbrowser.proxy.tls.LocalHttpsTestServer
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat

import com.example.basicbrowser.ui.history.HistoryScreen
import com.example.basicbrowser.ui.history.HistoryDetailScreen
import com.example.basicbrowser.ui.intercept.InterceptScreen
import com.example.basicbrowser.ui.intercept.InterceptDetailScreen
import com.example.basicbrowser.repeater.ui.RepeaterScreen
import com.example.basicbrowser.variation.ui.VariationScreen
import com.example.basicbrowser.ui.dev.CaVerificationScreen
import com.example.basicbrowser.scope.ui.ScopeScreen
import com.example.basicbrowser.ui.settings.SettingsScreen
import com.example.basicbrowser.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import com.example.basicbrowser.ui.settings.ProxySettingsScreen
import com.example.basicbrowser.ui.settings.TrafficFiltersScreen
import com.example.basicbrowser.ui.settings.GeneralSettingsScreen
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.proxy.intercept.InterceptManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.basicbrowser.util.UrlUtils

// ... existing helper methods ...

private fun getUrlOrSearch(input: String): String = UrlUtils.getUrlOrSearch(input)

private fun customGoBack(webView: WebView) {
    val history = webView.copyBackForwardList()
    var currentIndex = history.currentIndex
    if (currentIndex > 0) {
        var targetIndex = currentIndex - 1
        while (targetIndex > 0) {
            val currentUrl = history.getItemAtIndex(targetIndex + 1)?.url ?: ""
            val prevUrl = history.getItemAtIndex(targetIndex)?.url ?: ""
            
            val p = prevUrl.removeSuffix("/")
            val c = currentUrl.removeSuffix("/")
            
            val isRedirect = (p == c) || 
                             (p.replace("http://", "https://") == c) ||
                             (p.replace("https://", "https://www.") == c) ||
                             (p.replace("http://", "https://www.") == c)
            
            if (isRedirect) {
                targetIndex--
            } else {
                break
            }
        }
        val steps = targetIndex - currentIndex
        if (webView.canGoBackOrForward(steps)) {
            webView.goBackOrForward(steps)
            return
        }
    }
    if (webView.canGoBack()) {
        webView.goBack()
    }
}

class MainActivity : ComponentActivity() {

    private val proxyServer = ProxyServer.getInstance()

    private val externalUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let { externalUrl.value = it }

        // Stage 6.2 Optimization: Decoupled Startup & Asynchronous CA Initialization.
        // The local proxy server and WebView proxy configuration do not require CA
        // cryptographic material to start listening on 127.0.0.1:8080 or handle plain HTTP.
        //
        // We kick off CA initialization asynchronously in parallel. If an HTTPS CONNECT
        // request arrives before the CA has finished warming up, ConnectHandler and
        // CertificateGenerator safely await completion via awaitInitialization().
        CertificateAuthority.initializeAsync(applicationContext)

        Thread {
            com.example.basicbrowser.scope.data.ScopeRepository.getInstance(applicationContext)
            com.example.basicbrowser.filter.data.TrafficFilterRepository.getInstance(applicationContext)
            com.example.basicbrowser.data.repository.RequestAnnotationRepository.getInstance(applicationContext)

            com.example.basicbrowser.data.repository.HttpHistoryRepository.getInstance()
                .setTempFilesDir(applicationContext.cacheDir)

            // Start proxy server immediately (binds in < 30ms instead of waiting ~2.5s for CA)
            proxyServer.start()
            Log.i("MainActivity", "Local proxy server started on 127.0.0.1:8080")

            // Configure WebView proxy routing immediately
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                configureWebViewProxy()
            }
        }.start()

        enableEdgeToEdge()
        setContent {
            BasicBrowserTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen(externalUrl = externalUrl.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { externalUrl.value = it }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("MainActivity", "onTrimMemory level=$level")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.example.basicbrowser.proxy.tls.CertificateGenerator.trimCache(targetSize = 5)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proxyServer.stop()
        LocalHttpsTestServer.stop()
        // Clear the proxy override so other apps are not affected
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(
                { it.run() },  // executor
                {}             // callback
            )
        }
        Log.i("MainActivity", "Local proxy and test servers stopped")
    }

    private fun configureWebViewProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.e("MainActivity", "PROXY_OVERRIDE feature not supported on this device")
            return
        }

        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:8080")            // Route all HTTP and HTTPS through our proxy
            .build()

        ProxyController.getInstance().setProxyOverride(
            proxyConfig,
            { it.run() },  // executor — run on calling thread
            { Log.i("MainActivity", "WebView proxy configured: ALL traffic (HTTP + HTTPS) → 127.0.0.1:8080") }
        )
    }
}

sealed class Screen {
    object Browser : Screen()
    object Intercept : Screen()
    data class InterceptDetail(val requestId: String) : Screen()
    object Repeater : Screen()
    object VariationLab : Screen()
    object History : Screen()
    data class HistoryDetail(val item: HttpHistoryItem) : Screen()

    // Centralized Settings Navigation
    object Settings : Screen()
    object TargetScope : Screen()
    object CaVerification : Screen()
    object ProxySettings : Screen()
    object TrafficFilters : Screen()
    object GeneralSettings : Screen()
}

private fun isSettingsActive(current: Screen, previous: Screen?): Boolean {
    return when (current) {
        Screen.Settings,
        Screen.ProxySettings,
        Screen.TrafficFilters,
        Screen.GeneralSettings,
        Screen.CaVerification -> true
        Screen.TargetScope -> previous == Screen.Settings || previous in listOf(
            Screen.ProxySettings,
            Screen.TrafficFilters,
            Screen.GeneralSettings,
            Screen.CaVerification
        )
        else -> false
    }
}

@Composable
fun AppScreen(externalUrl: String? = null) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Browser) }
    var previousScreen by remember { mutableStateOf<Screen?>(null) }
    var targetBrowserUrl by remember { mutableStateOf<String?>(externalUrl) }

    val interceptManager = remember { InterceptManager.getInstance() }
    val pausedRequests by interceptManager.pausedRequests.collectAsState()
    val isInterceptEnabled by interceptManager.isInterceptEnabled.collectAsState()

    LaunchedEffect(externalUrl) {
        if (!externalUrl.isNullOrBlank()) {
            targetBrowserUrl = externalUrl
            currentScreen = Screen.Browser
        }
    }

    Scaffold(
        bottomBar = {
            if (currentScreen !is Screen.HistoryDetail &&
                currentScreen !is Screen.InterceptDetail
            ) {
                NavigationBar(
                    containerColor = SurfaceContainerLowest,
                    contentColor = OnSurface,
                    windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                ) {
                    val navItemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = SurfaceContainerHigh,
                        unselectedIconColor = OnSurfaceVariant,
                        unselectedTextColor = OnSurfaceVariant
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_browser),
                                contentDescription = "Browser",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { NavigationBarLabel("Browser") },
                        selected = currentScreen == Screen.Browser,
                        colors = navItemColors,
                        onClick = {
                            previousScreen = currentScreen
                            currentScreen = Screen.Browser
                        }
                    )
                    NavigationBarItem(
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (pausedRequests.isNotEmpty()) {
                                        Badge(
                                            containerColor = TertiaryContainer,
                                            contentColor = SurfaceContainerLowest
                                        ) {
                                            Text("${pausedRequests.size}", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else if (isInterceptEnabled) {
                                        Badge(containerColor = Primary)
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_nav_intercept),
                                    contentDescription = "Intercept",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        label = { NavigationBarLabel("Intercept") },
                        selected = currentScreen == Screen.Intercept,
                        colors = navItemColors,
                        onClick = {
                            previousScreen = currentScreen
                            currentScreen = Screen.Intercept
                        }
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_repeater),
                                contentDescription = "Repeater",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { NavigationBarLabel("Repeater") },
                        selected = currentScreen == Screen.Repeater,
                        colors = navItemColors,
                        onClick = {
                            previousScreen = currentScreen
                            currentScreen = Screen.Repeater
                        }
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_lab),
                                contentDescription = "Lab",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { NavigationBarLabel("Lab") },
                        selected = currentScreen == Screen.VariationLab,
                        colors = navItemColors,
                        onClick = {
                            previousScreen = currentScreen
                            currentScreen = Screen.VariationLab
                        }
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_traffic),
                                contentDescription = "History",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { NavigationBarLabel("History") },
                        selected = currentScreen == Screen.History,
                        colors = navItemColors,
                        onClick = {
                            previousScreen = currentScreen
                            currentScreen = Screen.History
                        }
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_settings),
                                contentDescription = "Settings",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { NavigationBarLabel("Settings") },
                        selected = isSettingsActive(currentScreen, previousScreen),
                        colors = navItemColors,
                        onClick = {
                            previousScreen = currentScreen
                            currentScreen = Screen.Settings
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            val isBrowser = currentScreen == Screen.Browser
            Box(
                modifier = if (isBrowser) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.size(0.dp).graphicsLayer { alpha = 0f }
                }
            ) {
                BrowserScreen(
                    targetUrl = targetBrowserUrl,
                    onUrlConsumed = { targetBrowserUrl = null },
                    interceptManager = interceptManager,
                    isActive = isBrowser,
                    onNavigateToScope = {
                        previousScreen = currentScreen
                        currentScreen = Screen.TargetScope
                    }
                )
            }

            when (val screen = currentScreen) {
                is Screen.Browser -> {
                    // Handled in persistent Box above to retain WebView across tab navigation
                }
                is Screen.Intercept -> InterceptScreen(
                    onItemClick = { currentScreen = Screen.InterceptDetail(it) },
                    onBackClick = { currentScreen = Screen.Browser },
                    onNavigateToScope = {
                        previousScreen = currentScreen
                        currentScreen = Screen.TargetScope
                    },
                    onNavigateToBrowser = { url ->
                        targetBrowserUrl = url
                        previousScreen = currentScreen
                        currentScreen = Screen.Browser
                    },
                    onNavigateToRepeater = {
                        previousScreen = currentScreen
                        currentScreen = Screen.Repeater
                    },
                    onNavigateToVariation = {
                        previousScreen = currentScreen
                        currentScreen = Screen.VariationLab
                    }
                )
                is Screen.InterceptDetail -> InterceptDetailScreen(
                    requestId = screen.requestId,
                    onBackClick = { currentScreen = Screen.Intercept }
                )
                is Screen.Repeater -> RepeaterScreen(
                    onBackClick = { currentScreen = Screen.Browser },
                    onNavigateToVariation = { currentScreen = Screen.VariationLab }
                )
                is Screen.VariationLab -> VariationScreen(
                    onBackClick = { currentScreen = Screen.Browser }
                )
                is Screen.History -> HistoryScreen(
                    onItemClick = { currentScreen = Screen.HistoryDetail(it) },
                    onBackClick = { currentScreen = Screen.Browser },
                    onNavigateToScope = {
                        previousScreen = currentScreen
                        currentScreen = Screen.TargetScope
                    },
                    onNavigateToBrowser = { url ->
                        targetBrowserUrl = url
                        previousScreen = currentScreen
                        currentScreen = Screen.Browser
                    },
                    onNavigateToRepeater = {
                        previousScreen = currentScreen
                        currentScreen = Screen.Repeater
                    },
                    onNavigateToVariation = {
                        previousScreen = currentScreen
                        currentScreen = Screen.VariationLab
                    }
                )
                is Screen.HistoryDetail -> HistoryDetailScreen(
                    item = screen.item,
                    onBackClick = { currentScreen = Screen.History },
                    onNavigateToRepeater = { currentScreen = Screen.Repeater },
                    onNavigateToVariation = { currentScreen = Screen.VariationLab }
                )
                is Screen.Settings -> {
                    BackHandler { currentScreen = Screen.Browser }
                    SettingsScreen(
                        onNavigateToTargetScope = {
                            previousScreen = currentScreen
                            currentScreen = Screen.TargetScope
                        },
                        onNavigateToCaManagement = {
                            previousScreen = currentScreen
                            currentScreen = Screen.CaVerification
                        },
                        onNavigateToProxySettings = {
                            previousScreen = currentScreen
                            currentScreen = Screen.ProxySettings
                        },
                        onNavigateToTrafficFilters = {
                            previousScreen = currentScreen
                            currentScreen = Screen.TrafficFilters
                        },
                        onNavigateToGeneralSettings = {
                            previousScreen = currentScreen
                            currentScreen = Screen.GeneralSettings
                        }
                    )
                }
                is Screen.CaVerification -> CaVerificationScreen(
                    onBackClick = { currentScreen = Screen.Settings },
                    onNavigateToBrowser = { url ->
                        targetBrowserUrl = url
                        previousScreen = currentScreen
                        currentScreen = Screen.Browser
                    }
                )
                is Screen.TargetScope -> ScopeScreen(
                    onBackClick = { currentScreen = previousScreen ?: Screen.Settings }
                )
                is Screen.ProxySettings -> ProxySettingsScreen(
                    onBackClick = { currentScreen = Screen.Settings }
                )
                is Screen.TrafficFilters -> TrafficFiltersScreen(
                    onBackClick = { currentScreen = Screen.Settings },
                    onNavigateToScope = {
                        previousScreen = currentScreen
                        currentScreen = Screen.TargetScope
                    }
                )
                is Screen.GeneralSettings -> GeneralSettingsScreen(
                    onBackClick = { currentScreen = Screen.Settings }
                )
            }
        }
    }
}

@Composable
private fun NavigationBarLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        letterSpacing = (-0.2).sp
    )
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    targetUrl: String? = null,
    onUrlConsumed: () -> Unit = {},
    interceptManager: InterceptManager = remember { InterceptManager.getInstance() },
    isActive: Boolean = true,
    onNavigateToScope: () -> Unit = {}
) {
    var urlInput by remember { mutableStateOf(TextFieldValue(targetUrl ?: "https://www.google.com")) }
    var isAddressBarFocused by remember { mutableStateOf(false) }
    var isUserEditing by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var showZoomMenu by remember { mutableStateOf(false) }

    val isInterceptEnabled by interceptManager.isInterceptEnabled.collectAsState()
    val pausedRequests by interceptManager.pausedRequests.collectAsState()

    val context = LocalContext.current

    val applyZoom: (Int) -> Unit = { percent ->
        zoomPercent = percent
        webView?.settings?.textZoom = percent
        webView?.evaluateJavascript(
            "if (document.body) { document.body.style.zoom = '${percent / 100.0}'; }",
            null
        )
    }

    // Handle system back button ONLY when Browser tab is active
    BackHandler(enabled = isActive && canGoBack) {
        webView?.let { customGoBack(it) }
    }

    val navigateToTarget = {
        val target = UrlUtils.getUrlOrSearch(urlInput.text)
        if (target.isNotEmpty()) {
            isUserEditing = false
            webView?.stopLoading()
            webView?.loadUrl(target)
        }
    }

    LaunchedEffect(targetUrl) {
        if (!targetUrl.isNullOrBlank()) {
            urlInput = TextFieldValue(targetUrl)
            isUserEditing = false
            webView?.stopLoading()
            webView?.loadUrl(targetUrl)
            onUrlConsumed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceContainerLowest)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { webView?.let { customGoBack(it) } },
                enabled = canGoBack,
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceContainerLow, RoundedCornerShape(8.dp))
                    .border(1.dp, SurfaceContainerHigh, RoundedCornerShape(8.dp))
            ) {
                Text(
                    "<",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (canGoBack) OnSurface else Outline
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = { webView?.goForward() },
                enabled = canGoForward,
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceContainerLow, RoundedCornerShape(8.dp))
                    .border(1.dp, SurfaceContainerHigh, RoundedCornerShape(8.dp))
            ) {
                Text(
                    ">",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (canGoForward) OnSurface else Outline
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = {
                    if (isLoading) {
                        webView?.stopLoading()
                        isLoading = false
                    } else {
                        webView?.reload()
                    }
                },
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceContainerLow, RoundedCornerShape(8.dp))
                    .border(1.dp, SurfaceContainerHigh, RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = if (isLoading) "✕" else "↻",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = OnSurface
                )
            }
            OutlinedTextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it
                    isUserEditing = true
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceContainerLow,
                    unfocusedContainerColor = SurfaceContainerLow,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = SurfaceContainerHigh,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    cursorColor = Primary
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = { navigateToTarget() }
                ),
                trailingIcon = {
                    if (urlInput.text.isNotEmpty()) {
                        IconButton(onClick = {
                            urlInput = TextFieldValue("")
                            isUserEditing = true
                        }) {
                            Text("✕", color = Outline)
                        }
                    }
                }
            )
            Button(
                onClick = { navigateToTarget() },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Go", fontWeight = FontWeight.Bold)
            }
        }

        // Quick Controls Row: Intercept Toggle, Test CA Trust (Center), Zoom Option (Right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Intercept Toggle (Left)
            Surface(
                onClick = { interceptManager.setEnabled(!isInterceptEnabled) },
                shape = RoundedCornerShape(16.dp),
                color = if (isInterceptEnabled) TertiaryContainer.copy(alpha = 0.2f) else SurfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    if (isInterceptEnabled) TertiaryContainer.copy(alpha = 0.5f) else SurfaceContainerHigh
                ),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_zap),
                        contentDescription = null,
                        tint = if (isInterceptEnabled) Tertiary else Outline,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isInterceptEnabled) "Intercept: ON" else "Intercept: OFF",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isInterceptEnabled) Tertiary else OnSurfaceVariant
                    )
                    if (pausedRequests.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Badge(
                            containerColor = TertiaryContainer,
                            contentColor = SurfaceContainerLowest
                        ) {
                            Text("${pausedRequests.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. CA Trust Test trigger (Center)
            Surface(
                onClick = {
                    isUserEditing = false
                    Thread {
                        LocalHttpsTestServer.start()
                        val testUrl = LocalHttpsTestServer.getTestUrl()
                        urlInput = TextFieldValue(testUrl)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            webView?.stopLoading()
                            webView?.loadUrl(testUrl)
                        }
                    }.start()
                },
                shape = RoundedCornerShape(16.dp),
                color = SurfaceContainerLow,
                border = BorderStroke(1.dp, SurfaceContainerHigh),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_security_shield),
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Test CA Trust",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Primary
                    )
                }
            }

            // 3. Zoom Option (Right)
            Box {
                Surface(
                    onClick = { showZoomMenu = !showZoomMenu },
                    shape = RoundedCornerShape(16.dp),
                    color = if (zoomPercent != 100) Primary.copy(alpha = 0.15f) else SurfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        if (zoomPercent != 100) Primary.copy(alpha = 0.5f) else SurfaceContainerHigh
                    ),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_zoom),
                            contentDescription = "Zoom",
                            tint = if (zoomPercent != 100) Primary else OnSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (zoomPercent == 100) "Zoom" else "$zoomPercent%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (zoomPercent != 100) Primary else OnSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = showZoomMenu,
                    onDismissRequest = { showZoomMenu = false },
                    modifier = Modifier.background(SurfaceContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val newZoom = (zoomPercent - 25).coerceAtLeast(25)
                                applyZoom(newZoom)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(SurfaceContainerHigh, RoundedCornerShape(6.dp))
                        ) {
                            Text("-", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OnSurface)
                        }

                        Text(
                            text = "$zoomPercent%",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        IconButton(
                            onClick = {
                                val newZoom = (zoomPercent + 25).coerceAtMost(300)
                                applyZoom(newZoom)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(SurfaceContainerHigh, RoundedCornerShape(6.dp))
                        ) {
                            Text("+", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OnSurface)
                        }
                    }

                    HorizontalDivider(color = SurfaceContainerHigh)

                    val presetLevels = listOf(50, 75, 100, 125, 150, 200)
                    presetLevels.forEach { level ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (level == 100) "100% (Default)" else "$level%",
                                        fontWeight = if (level == zoomPercent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (level == zoomPercent) Primary else OnSurface,
                                        fontSize = 13.sp
                                    )
                                    if (level == zoomPercent) {
                                        Text("✓", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            },
                            onClick = {
                                applyZoom(level)
                                showZoomMenu = false
                            }
                        )
                    }

                    if (zoomPercent != 100) {
                        HorizontalDivider(color = SurfaceContainerHigh)
                        DropdownMenuItem(
                            text = {
                                Text("Reset to 100%", color = Tertiary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            },
                            onClick = {
                                applyZoom(100)
                                showZoomMenu = false
                            }
                        )
                    }
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Primary,
                trackColor = SurfaceContainerLow
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.textZoom = zoomPercent

                    // Clean up any dark mode cookie so Google and websites render in default mode
                    try {
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setCookie("https://www.google.com", "PREF=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=.google.com")
                        cookieManager.setCookie("https://google.com", "PREF=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=.google.com")
                        cookieManager.flush()
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Cookie cleanup: ${e.message}")
                    }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                        @Suppress("DEPRECATION")
                        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                            if (url != null && !isAddressBarFocused && !isUserEditing) {
                                urlInput = TextFieldValue(url)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                            if (!isAddressBarFocused) {
                                isUserEditing = false
                            }
                            // Re-apply zoom if non-default
                            if (zoomPercent != 100) {
                                view?.settings?.textZoom = zoomPercent
                                view?.evaluateJavascript(
                                    "if (document.body) { document.body.style.zoom = '${zoomPercent / 100.0}'; }",
                                    null
                                )
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            isLoading = false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                            }
                        }

                        // DEVELOPMENT ONLY — Selective SSL error handling.
                        // Delegates to DevCaSslErrorHandler which ONLY
                        // trusts certificates signed by our specific dev CA.
                        // This does NOT accept all certificates.
                        // Normal HTTPS (system-trusted CAs) never triggers
                        // this callback — it only fires on validation failure.
                        @android.annotation.SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler,
                            error: SslError
                        ) {
                            DevCaSslErrorHandler.handleSslError(view, handler, error)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }

                    loadUrl(urlInput.text)
                }
            },
            update = {
                webView = it
            }
        )
    }
}
