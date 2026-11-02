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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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

import com.example.basicbrowser.ui.history.HistoryScreen
import com.example.basicbrowser.ui.history.HistoryDetailScreen
import com.example.basicbrowser.ui.intercept.InterceptScreen
import com.example.basicbrowser.ui.intercept.InterceptDetailScreen
import com.example.basicbrowser.repeater.ui.RepeaterScreen
import com.example.basicbrowser.variation.ui.VariationScreen
import com.example.basicbrowser.ui.dev.CaVerificationScreen
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
    object CaVerification : Screen()  // DEVELOPMENT ONLY
}

@Composable
fun AppScreen(externalUrl: String? = null) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Browser) }
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
                currentScreen !is Screen.InterceptDetail &&
                currentScreen !is Screen.CaVerification
            ) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Text("🌐") },
                        label = { Text("Browser") },
                        selected = currentScreen == Screen.Browser,
                        onClick = { currentScreen = Screen.Browser }
                    )
                    NavigationBarItem(
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (pausedRequests.isNotEmpty()) {
                                        Badge { Text("${pausedRequests.size}") }
                                    } else if (isInterceptEnabled) {
                                        Badge(containerColor = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            ) {
                                Text("⚡")
                            }
                        },
                        label = { Text("Intercept") },
                        selected = currentScreen == Screen.Intercept,
                        onClick = { currentScreen = Screen.Intercept }
                    )
                    NavigationBarItem(
                        icon = { Text("🔁") },
                        label = { Text("Repeater") },
                        selected = currentScreen == Screen.Repeater,
                        onClick = { currentScreen = Screen.Repeater }
                    )
                    NavigationBarItem(
                        icon = { Text("🧪") },
                        label = { Text("Lab") },
                        selected = currentScreen == Screen.VariationLab,
                        onClick = { currentScreen = Screen.VariationLab }
                    )
                    NavigationBarItem(
                        icon = { Text("📜") },
                        label = { Text("History") },
                        selected = currentScreen == Screen.History,
                        onClick = { currentScreen = Screen.History }
                    )
                    // CA Certificate & Trust Management
                    NavigationBarItem(
                        icon = { Text("🔒") },
                        label = { Text("CA Cert") },
                        selected = currentScreen == Screen.CaVerification,
                        onClick = { currentScreen = Screen.CaVerification }
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
                    isActive = isBrowser
                )
            }

            when (val screen = currentScreen) {
                is Screen.Browser -> {
                    // Handled in persistent Box above to retain WebView across tab navigation
                }
                is Screen.Intercept -> InterceptScreen(
                    onItemClick = { currentScreen = Screen.InterceptDetail(it) },
                    onBackClick = { currentScreen = Screen.Browser }
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
                    onBackClick = { currentScreen = Screen.Browser }
                )
                is Screen.HistoryDetail -> HistoryDetailScreen(
                    item = screen.item,
                    onBackClick = { currentScreen = Screen.History },
                    onNavigateToRepeater = { currentScreen = Screen.Repeater },
                    onNavigateToVariation = { currentScreen = Screen.VariationLab }
                )
                // DEVELOPMENT ONLY
                is Screen.CaVerification -> CaVerificationScreen(
                    onBackClick = { currentScreen = Screen.Browser },
                    onNavigateToBrowser = { url ->
                        targetBrowserUrl = url
                        currentScreen = Screen.Browser
                    }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    targetUrl: String? = null,
    onUrlConsumed: () -> Unit = {},
    interceptManager: InterceptManager = remember { InterceptManager.getInstance() },
    isActive: Boolean = true
) {
    var urlInput by remember { mutableStateOf(TextFieldValue(targetUrl ?: "https://www.google.com")) }
    var isAddressBarFocused by remember { mutableStateOf(false) }
    var isUserEditing by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    val isInterceptEnabled by interceptManager.isInterceptEnabled.collectAsState()
    val pausedRequests by interceptManager.pausedRequests.collectAsState()

    val context = LocalContext.current

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
        // Use WindowInsets for edge-to-edge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { webView?.let { customGoBack(it) } },
                enabled = canGoBack
            ) {
                Text("<")
            }
            IconButton(
                onClick = { webView?.goForward() },
                enabled = canGoForward
            ) {
                Text(">")
            }
            IconButton(
                onClick = {
                    if (isLoading) {
                        webView?.stopLoading()
                        isLoading = false
                    } else {
                        webView?.reload()
                    }
                }
            ) {
                Text(
                    text = if (isLoading) "✕" else "↻",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
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
                    .padding(horizontal = 4.dp)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && !isAddressBarFocused) {
                            urlInput = urlInput.copy(
                                selection = TextRange(0, urlInput.text.length)
                            )
                        }
                        isAddressBarFocused = focusState.isFocused
                    },
                singleLine = true,
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
                            Text("✕")
                        }
                    }
                }
            )
            Button(onClick = { navigateToTarget() }) {
                Text("Go")
            }
        }

        // Quick Controls Row: Intercept Toggle & CA Trust Test
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isInterceptEnabled,
                onClick = { interceptManager.setEnabled(!isInterceptEnabled) },
                label = {
                    Text(
                        if (isInterceptEnabled) "⚡ Intercept: ON" else "⚡ Intercept: OFF",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                },
                leadingIcon = if (pausedRequests.isNotEmpty()) {
                    {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text("${pausedRequests.size}", fontSize = 10.sp)
                        }
                    }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )

            // Temporary DEVELOPMENT ONLY: CA Trust Test trigger
            FilledTonalButton(
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
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("🧪 Test CA Trust", fontSize = 11.sp)
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp)) // To keep layout stable
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

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
