package com.example.basicbrowser.variation.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.data.VariationRepository
import com.example.basicbrowser.variation.engine.IntruderRequestGenerator
import com.example.basicbrowser.variation.model.AttackType
import com.example.basicbrowser.variation.model.InsertionPoint
import com.example.basicbrowser.variation.model.MarkerLocation
import com.example.basicbrowser.variation.model.TargetSummary
import com.example.basicbrowser.variation.model.VariationConfig
import com.example.basicbrowser.variation.model.VariationJobState
import com.example.basicbrowser.variation.model.VariationResult
import com.example.basicbrowser.variation.model.VariationSubstitution
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariationScreen(
    onBackClick: () -> Unit
) {
    BackHandler(onBack = onBackClick)
    val context = LocalContext.current
    val repository = remember { VariationRepository.getInstance() }

    val jobState by repository.jobState.collectAsState()
    val results by repository.results.collectAsState()
    val baseRequest by repository.baseRequest.collectAsState()
    val persistedAttackType by repository.selectedAttackType.collectAsState()

    // Form inputs initialized from repository's baseRequest
    var method by remember(baseRequest) { mutableStateOf(baseRequest?.method ?: "GET") }
    var url by remember(baseRequest) { mutableStateOf(baseRequest?.url ?: "https://example.com/api?id=§1§") }
    var headersText by remember(baseRequest) {
        mutableStateOf(
            baseRequest?.headers?.joinToString("\n") ?: "Accept: */*\nUser-Agent: BasicBrowser/1.0"
        )
    }
    var bodyText by remember(baseRequest) {
        mutableStateOf(
            baseRequest?.body?.let { String(it, StandardCharsets.UTF_8) } ?: ""
        )
    }

    // Attack Type state (persisted across tab switches and runs)
    var attackType by remember(persistedAttackType) { mutableStateOf(persistedAttackType) }

    // Payloads: Sniper (one shared list)
    var sniperPayloadsText by remember { mutableStateOf("1\n2\n3\n4\n5") }

    // Payloads: Cluster Bomb (per-insertion-point map)
    var clusterBombPayloadsMap by remember {
        mutableStateOf(
            mapOf(
                "pos_0" to "1\n2\n3\n4\n5",
                "pos_1" to "admin\nguest\ntest",
                "pos_2" to "true\nfalse"
            )
        )
    }

    var delayMs by remember { mutableLongStateOf(VariationConfig.DEFAULT_DELAY_MS) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("CONFIG", "RESULTS (${results.size})")

    // Switch to Results tab when job starts
    LaunchedEffect(jobState) {
        if (jobState is VariationJobState.Running ||
            jobState is VariationJobState.Paused ||
            jobState is VariationJobState.Stopped ||
            jobState is VariationJobState.Completed
        ) {
            selectedTabIndex = 1
        }
    }

    // Construct live request representation for insertion-point detection
    val currentRequest = remember(method, url, headersText, bodyText) {
        val headersList = headersText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val bodyBytes = if (bodyText.isNotEmpty()) bodyText.toByteArray(StandardCharsets.UTF_8) else null
        HttpRequestData(
            method = method.trim().uppercase(),
            url = url.trim(),
            headers = headersList,
            body = bodyBytes
        )
    }

    val detectedInsertionPoints = remember(currentRequest) {
        IntruderRequestGenerator.parseInsertionPoints(currentRequest)
    }

    // Parse payload lists
    val parsedSniperPayloads = remember(sniperPayloadsText) {
        sniperPayloadsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    val parsedClusterBombPayloads = remember(detectedInsertionPoints, clusterBombPayloadsMap) {
        detectedInsertionPoints.associate { ip ->
            val text = clusterBombPayloadsMap[ip.id] ?: "${ip.baselineValue}\ntest"
            ip.id to text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    // Automatic Request Count Calculation
    val calculatedRequestCount = remember(attackType, detectedInsertionPoints, parsedSniperPayloads, parsedClusterBombPayloads) {
        IntruderRequestGenerator.calculateTotalRequests(
            attackType = attackType,
            insertionPoints = detectedInsertionPoints,
            sniperPayloads = parsedSniperPayloads,
            clusterBombPayloads = parsedClusterBombPayloads
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧪", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Variation Lab",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StateBadge(jobState)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (jobState !is VariationJobState.Idle && jobState !is VariationJobState.Configuring) {
                        TextButton(onClick = { repository.reset(); selectedTabIndex = 0 }) {
                            Text("Reset", fontSize = 13.sp)
                        }
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
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> {
                    ConfigTab(
                        attackType = attackType,
                        onAttackTypeChange = {
                            attackType = it
                            repository.setAttackType(it)
                        },
                        method = method,
                        onMethodChange = { method = it },
                        url = url,
                        onUrlChange = { url = it },
                        headersText = headersText,
                        onHeadersChange = { headersText = it },
                        bodyText = bodyText,
                        onBodyChange = { bodyText = it },
                        insertionPoints = detectedInsertionPoints,
                        onRemoveInsertionPoint = { targetIndex ->
                            val updatedReq = IntruderRequestGenerator.removeMarker(currentRequest, targetIndex)
                            url = updatedReq.url
                            headersText = updatedReq.headers.joinToString("\n")
                            bodyText = updatedReq.body?.let { String(it, StandardCharsets.UTF_8) } ?: ""
                        },
                        onClearAllMarkers = {
                            val updatedReq = IntruderRequestGenerator.clearAllMarkers(currentRequest)
                            url = updatedReq.url
                            headersText = updatedReq.headers.joinToString("\n")
                            bodyText = updatedReq.body?.let { String(it, StandardCharsets.UTF_8) } ?: ""
                        },
                        onAddMarkerToUrl = {
                            // If URL has query params without §, wrap the first one; otherwise append §1§
                            url = if (url.contains("=") && !url.contains("§")) {
                                url.replaceFirst("=([^&§]+)".toRegex(), "=§$1§")
                            } else {
                                "$url§1§"
                            }
                        },
                        onAddMarkerToBody = {
                            bodyText = if (bodyText.isEmpty()) {
                                "{\"id\": §1§}"
                            } else if (!bodyText.contains("§")) {
                                bodyText.replaceFirst(": \"?([^\"{},\\s]+)\"?".toRegex(), ": §$1§")
                            } else {
                                "$bodyText§marker§"
                            }
                        },
                        sniperPayloadsText = sniperPayloadsText,
                        onSniperPayloadsChange = { sniperPayloadsText = it },
                        clusterBombPayloadsMap = clusterBombPayloadsMap,
                        onClusterBombPayloadsChange = { id, text ->
                            clusterBombPayloadsMap = clusterBombPayloadsMap.toMutableMap().apply { put(id, text) }
                        },
                        calculatedRequestCount = calculatedRequestCount,
                        delayMs = delayMs,
                        onDelayChange = { delayMs = it },
                        isBusy = jobState is VariationJobState.Running || jobState is VariationJobState.Paused,
                        onPrepareRun = {
                            try {
                                val config = VariationConfig(
                                    baseRequest = currentRequest,
                                    attackType = attackType,
                                    sniperPayloads = parsedSniperPayloads,
                                    clusterBombPayloads = parsedClusterBombPayloads,
                                    delayMs = delayMs
                                )
                                repository.requestAuthorization(config)
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Configuration error", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                1 -> {
                    ResultsTab(
                        jobState = jobState,
                        attackType = attackType,
                        results = results,
                        onPause = { repository.pause() },
                        onResume = { repository.resume() },
                        onStop = { repository.stop() },
                        onEditConfig = { selectedTabIndex = 0 }
                    )
                }
            }
        }
    }

    // Confirmation Dialog
    val currentState = jobState
    if (currentState is VariationJobState.AwaitingConfirmation) {
        ConfirmationDialog(
            summary = currentState.summary,
            onConfirm = { repository.startConfirmedJob() },
            onDismiss = { repository.cancelConfirmation() }
        )
    }
}

@Composable
private fun ConfigTab(
    attackType: AttackType,
    onAttackTypeChange: (AttackType) -> Unit,
    method: String,
    onMethodChange: (String) -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    headersText: String,
    onHeadersChange: (String) -> Unit,
    bodyText: String,
    onBodyChange: (String) -> Unit,
    insertionPoints: List<InsertionPoint>,
    onRemoveInsertionPoint: (Int) -> Unit,
    onClearAllMarkers: () -> Unit,
    onAddMarkerToUrl: () -> Unit,
    onAddMarkerToBody: () -> Unit,
    sniperPayloadsText: String,
    onSniperPayloadsChange: (String) -> Unit,
    clusterBombPayloadsMap: Map<String, String>,
    onClusterBombPayloadsChange: (String, String) -> Unit,
    calculatedRequestCount: Int,
    delayMs: Long,
    onDelayChange: (Long) -> Unit,
    isBusy: Boolean,
    onPrepareRun: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Attack Type Selection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Attack Type", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttackType.values().forEach { type ->
                        FilterChip(
                            selected = attackType == type,
                            onClick = { onAttackTypeChange(type) },
                            label = { Text(type.displayName, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy
                        )
                    }
                }

                Text(
                    text = attackType.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. Base Request Section Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Base Request Template", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = method,
                        onValueChange = onMethodChange,
                        label = { Text("Method") },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        enabled = !isBusy
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        label = { Text("Target URL (supports §marker§)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !isBusy
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = onAddMarkerToUrl,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                        enabled = !isBusy
                    ) {
                        Text("+ § in URL", fontSize = 11.sp)
                    }

                    FilledTonalButton(
                        onClick = onAddMarkerToBody,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                        enabled = !isBusy
                    ) {
                        Text("+ § in Body", fontSize = 11.sp)
                    }

                    if (insertionPoints.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearAllMarkers,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                            enabled = !isBusy
                        ) {
                            Text("Clear §", fontSize = 11.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = headersText,
                    onValueChange = onHeadersChange,
                    label = { Text("Headers (one per line, supports §marker§)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(105.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    enabled = !isBusy
                )

                OutlinedTextField(
                    value = bodyText,
                    onValueChange = onBodyChange,
                    label = { Text("Request Body (supports §marker§)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(95.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    enabled = !isBusy
                )
            }
        }

        // 3. Detected Insertion Points Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Payload Insertion Points", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        color = if (insertionPoints.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "${insertionPoints.size} detected",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (insertionPoints.isEmpty()) {
                    Text(
                        text = "No insertion points marked. Add §value§ around any text in URL, headers, or body to mark an attack position.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        insertionPoints.forEach { ip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "#${ip.index + 1}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${ip.location.name}: \"${ip.baselineValue}\"",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }

                                IconButton(
                                    onClick = { onRemoveInsertionPoint(ip.index) },
                                    modifier = Modifier.size(24.dp),
                                    enabled = !isBusy
                                ) {
                                    Text("✕", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Attack-Aware Payload Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (attackType) {
                    AttackType.SNIPER -> {
                        val sniperCount = sniperPayloadsText.lines().filter { it.trim().isNotEmpty() }.size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sniper Payloads (Shared)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("$sniperCount values", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = "Tested individually on all ${insertionPoints.size} positions (${insertionPoints.size} × $sniperCount = ${insertionPoints.size * sniperCount} requests)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = sniperPayloadsText,
                            onValueChange = onSniperPayloadsChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            placeholder = { Text("1\n2\n3\nadmin\nguest") },
                            enabled = !isBusy
                        )

                        // Quick presets
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { onSniperPayloadsChange((1..10).joinToString("\n")) },
                                label = { Text("1..10", fontSize = 11.sp) },
                                enabled = !isBusy
                            )
                            AssistChip(
                                onClick = { onSniperPayloadsChange(listOf("admin", "test", "guest", "user", "root").joinToString("\n")) },
                                label = { Text("Users", fontSize = 11.sp) },
                                enabled = !isBusy
                            )
                            AssistChip(
                                onClick = { onSniperPayloadsChange(listOf("' OR '1'='1", "admin' --", "1 UNION SELECT 1").joinToString("\n")) },
                                label = { Text("SQLi", fontSize = 11.sp) },
                                enabled = !isBusy
                            )
                        }
                    }

                    AttackType.CLUSTER_BOMB -> {
                        Text("Cluster Bomb Payloads (Per Insertion Point)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = "Every insertion point has its own payload list. All Cartesian combinations will be generated.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (insertionPoints.isEmpty()) {
                            Text(
                                "No insertion points marked. Add markers first.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            var selectedPosIndex by remember { mutableIntStateOf(0) }
                            if (selectedPosIndex >= insertionPoints.size) {
                                selectedPosIndex = 0
                            }

                            // Sub-tabs for each insertion point
                            ScrollableTabRow(
                                selectedTabIndex = selectedPosIndex,
                                edgePadding = 0.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                insertionPoints.forEachIndexed { idx, ip ->
                                    val count = (clusterBombPayloadsMap[ip.id] ?: "${ip.baselineValue}\ntest")
                                        .lines().filter { it.trim().isNotEmpty() }.size
                                    Tab(
                                        selected = selectedPosIndex == idx,
                                        onClick = { selectedPosIndex = idx },
                                        text = {
                                            Text(
                                                "#${idx + 1} (${ip.location.name}: $count)",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    )
                                }
                            }

                            val activeIp = insertionPoints[selectedPosIndex]
                            val currentText = clusterBombPayloadsMap[activeIp.id] ?: "${activeIp.baselineValue}\ntest"

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Payloads for Position #${activeIp.index + 1} (${activeIp.description}):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    val pCount = currentText.lines().filter { it.trim().isNotEmpty() }.size
                                    Text("$pCount items", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }

                                OutlinedTextField(
                                    value = currentText,
                                    onValueChange = { onClusterBombPayloadsChange(activeIp.id, it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp),
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                    enabled = !isBusy
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(
                                        onClick = { onClusterBombPayloadsChange(activeIp.id, (1..5).joinToString("\n")) },
                                        label = { Text("1..5", fontSize = 10.sp) },
                                        enabled = !isBusy
                                    )
                                    AssistChip(
                                        onClick = { onClusterBombPayloadsChange(activeIp.id, listOf("admin", "user", "root").joinToString("\n")) },
                                        label = { Text("Users", fontSize = 10.sp) },
                                        enabled = !isBusy
                                    )
                                    AssistChip(
                                        onClick = { onClusterBombPayloadsChange(activeIp.id, listOf("true", "false").joinToString("\n")) },
                                        label = { Text("Booleans", fontSize = 10.sp) },
                                        enabled = !isBusy
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Execution Constraints & Automatic Calculation Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Execution Pacing & Calculations", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Delay: ${delayMs} ms", fontSize = 13.sp)
                    Slider(
                        value = delayMs.toFloat(),
                        onValueChange = { onDelayChange(it.toLong()) },
                        valueRange = 0f..2000f,
                        steps = 19,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        enabled = !isBusy
                    )
                }

                // Automatic Request Count Status Badge
                Surface(
                    color = when {
                        calculatedRequestCount == 0 -> MaterialTheme.colorScheme.surfaceVariant
                        calculatedRequestCount > VariationConfig.HARD_CAP_MAX_REQUESTS -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Planned Requests: ",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "$calculatedRequestCount / ${VariationConfig.HARD_CAP_MAX_REQUESTS} max",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    calculatedRequestCount > VariationConfig.HARD_CAP_MAX_REQUESTS -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        }

                        if (calculatedRequestCount > VariationConfig.HARD_CAP_MAX_REQUESTS) {
                            Text(
                                text = "⚠️ Total requests ($calculatedRequestCount) exceed safety limit of ${VariationConfig.HARD_CAP_MAX_REQUESTS}. Execution is blocked until configuration is reduced.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (calculatedRequestCount == 0) {
                            Text(
                                text = "Please configure at least one insertion point and non-empty payload list.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "✓ Ready to run $calculatedRequestCount requests (${attackType.displayName} mode, single worker).",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        // Action Button: Prepare & Confirm Run
        val isExecutionPermitted = !isBusy && calculatedRequestCount in 1..VariationConfig.HARD_CAP_MAX_REQUESTS
        Button(
            onClick = onPrepareRun,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = isExecutionPermitted
        ) {
            Text(
                text = if (isExecutionPermitted) "🚀 Prepare & Confirm Run ($calculatedRequestCount reqs)" else "🚀 Cannot Run (Exceeds 500 or Empty)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ResultsTab(
    jobState: VariationJobState,
    attackType: AttackType,
    results: List<VariationResult>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onEditConfig: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Control Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = attackType.displayName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            when (jobState) {
                                is VariationJobState.Running -> {
                                    Text(
                                        "Running: ${jobState.completed}/${jobState.total} (${jobState.errors} errors)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                is VariationJobState.Paused -> {
                                    Text(
                                        "Paused: ${jobState.completed}/${jobState.total} (${jobState.errors} errors)",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontSize = 13.sp
                                    )
                                }
                                is VariationJobState.Stopped -> {
                                    Text(
                                        "Stopped at ${jobState.completed}/${jobState.total} (${jobState.errors} errors)",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 13.sp
                                    )
                                }
                                is VariationJobState.Completed -> {
                                    Text(
                                        "Completed ${jobState.total} requests (${jobState.errors} errors)",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32),
                                        fontSize = 13.sp
                                    )
                                }
                                else -> {
                                    Text("Ready", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (jobState is VariationJobState.Running) {
                            FilledTonalButton(onClick = onPause) {
                                Text("Pause", fontSize = 12.sp)
                            }
                            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text("Stop", fontSize = 12.sp)
                            }
                        } else if (jobState is VariationJobState.Paused) {
                            Button(onClick = onResume) {
                                Text("Resume", fontSize = 12.sp)
                            }
                            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text("Stop", fontSize = 12.sp)
                            }
                        } else {
                            OutlinedButton(onClick = onEditConfig) {
                                Text("Edit Config", fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (jobState is VariationJobState.Running || jobState is VariationJobState.Paused) {
                    val completed = if (jobState is VariationJobState.Running) jobState.completed else (jobState as VariationJobState.Paused).completed
                    val total = if (jobState is VariationJobState.Running) jobState.total else (jobState as VariationJobState.Paused).total
                    val progress = if (total > 0) completed.toFloat() / total.toFloat() else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // Table Header
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TableHeaderText("#", width = 36.dp)
                Text("Payload / Tuple", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                TableHeaderText("Status", width = 55.dp)
                TableHeaderText("Size", width = 60.dp)
                TableHeaderText("Time", width = 50.dp)
            }
        }

        // Results Lazy List
        if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results yet. Run an attack from the Config tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.index }) { item ->
                    ResultRow(item = item)
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TableHeaderText(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text = text, modifier = Modifier.width(width), fontWeight = FontWeight.Bold, fontSize = 11.sp)
}

@Composable
private fun ResultRow(item: VariationResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "${item.index}", modifier = Modifier.width(36.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.displayPayload, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            if (item.displayErrorMessage != null) {
                Text(text = item.displayErrorMessage ?: "", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
            }
        }

        Box(modifier = Modifier.width(55.dp)) {
            if (item.statusCode != null) {
                val color = when (item.statusCode / 100) {
                    2 -> Color(0xFF2E7D32)
                    3 -> Color(0xFF1565C0)
                    4 -> Color(0xFFE65100)
                    else -> Color(0xFFC62828)
                }
                Surface(
                    color = color.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${item.statusCode}",
                        color = color,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            } else {
                Text("ERR", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        val sizeStr = if (item.isTruncated) "${formatBytes(item.responseSizeBytes)} [trunc]" else formatBytes(item.responseSizeBytes)
        Text(text = sizeStr, modifier = Modifier.width(60.dp), fontSize = 11.sp)
        Text(text = "${item.elapsedTimeMs}ms", modifier = Modifier.width(50.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StateBadge(state: VariationJobState) {
    val (label, color) = when (state) {
        is VariationJobState.Idle -> "IDLE" to MaterialTheme.colorScheme.outline
        is VariationJobState.Configuring -> "READY" to MaterialTheme.colorScheme.secondary
        is VariationJobState.AwaitingConfirmation -> "CONFIRM" to MaterialTheme.colorScheme.tertiary
        is VariationJobState.Running -> "RUNNING" to MaterialTheme.colorScheme.primary
        is VariationJobState.Paused -> "PAUSED" to MaterialTheme.colorScheme.tertiary
        is VariationJobState.Stopped -> "STOPPED" to MaterialTheme.colorScheme.error
        is VariationJobState.Completed -> "DONE" to Color(0xFF2E7D32)
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ConfirmationDialog(
    summary: TargetSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmedAuthorization by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Authorization Required", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Request variation attacks send multiple automated requests. You must have explicit permission to test this destination.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Attack Type:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(summary.attackType.displayName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Target Host:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${summary.host}:${summary.port}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Target Path:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(summary.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Planned Requests:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${summary.plannedRequestCount}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Delay Between Requests:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${summary.delayMs} ms", fontSize = 12.sp)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = confirmedAuthorization,
                        onCheckedChange = { confirmedAuthorization = it }
                    )
                    Text(
                        text = "I confirm I am authorized to test ${summary.host}:${summary.port}.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmedAuthorization
            ) {
                Text("Start Attack")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
