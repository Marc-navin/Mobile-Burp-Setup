package com.example.basicbrowser.scope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.scope.data.ScopeRepository
import com.example.basicbrowser.scope.model.ScopeRule
import com.example.basicbrowser.scope.model.ScopeRuleType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeScreen(
    onBackClick: () -> Unit,
    repository: ScopeRepository = remember { ScopeRepository.getInstance() }
) {
    BackHandler(onBack = onBackClick)

    val rules by repository.rules.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Include, 1: Exclude
    var showAddDialog by remember { mutableStateOf(false) }
    var ruleToEdit by remember { mutableStateOf<ScopeRule?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Scope Tester State
    var testUrlInput by remember { mutableStateOf("https://example.com/api") }
    val testEvaluation = remember(testUrlInput, rules) {
        if (testUrlInput.isNotBlank()) repository.evaluate(testUrlInput) else null
    }

    val includeRules = remember(rules) { rules.filter { it.type == ScopeRuleType.INCLUDE } }
    val excludeRules = remember(rules) { rules.filter { it.type == ScopeRuleType.EXCLUDE } }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Scope Rules?") },
            text = { Text("This will remove all include and exclude rules. Scope will revert to unrestricted (all traffic in scope).") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.clearScope()
                        showClearConfirm = false
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddDialog || ruleToEdit != null) {
        ScopeRuleDialog(
            initialRule = ruleToEdit,
            defaultType = if (selectedTab == 0) ScopeRuleType.INCLUDE else ScopeRuleType.EXCLUDE,
            onDismiss = {
                showAddDialog = false
                ruleToEdit = null
            },
            onSave = { rule ->
                if (ruleToEdit != null) {
                    repository.updateRule(rule)
                } else {
                    repository.addRule(rule)
                }
                showAddDialog = false
                ruleToEdit = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎯 Target Scope", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        val totalEnabled = rules.count { it.enabled }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "$totalEnabled active",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (rules.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Scope Tester Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Scope Rule Tester",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = testUrlInput,
                        onValueChange = { testUrlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Enter URL, domain, or IP (e.g. https://api.test.com)") },
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (testEvaluation != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                color = if (testEvaluation.isInScope) Color(0xFF16A34A) else Color(0xFFDC2626),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (testEvaluation.isInScope) "IN SCOPE" else "OUT OF SCOPE",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = testEvaluation.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Tabs for Include vs Exclude
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text("Include Rules (${includeRules.size})", fontWeight = FontWeight.SemiBold)
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text("Exclude Rules (${excludeRules.size})", fontWeight = FontWeight.SemiBold)
                    }
                )
            }

            // Rules List
            val currentList = if (selectedTab == 0) includeRules else excludeRules
            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (selectedTab == 0) "No Include Rules Configured" else "No Exclude Rules Configured",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (selectedTab == 0) {
                                "When no include rules are defined, the scope is unrestricted and all traffic is treated as in-scope."
                            } else {
                                "Add exclude rules to filter out third-party trackers, CDNs, or specific networks."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showAddDialog = true }) {
                            Text(if (selectedTab == 0) "+ Add Include Rule" else "+ Add Exclude Rule")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(currentList, key = { it.id }) { rule ->
                        ScopeRuleCard(
                            rule = rule,
                            onToggle = { enabled -> repository.toggleRule(rule.id, enabled) },
                            onEdit = { ruleToEdit = rule },
                            onDelete = { repository.deleteRule(rule.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScopeRuleCard(
    rule: ScopeRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (rule.type == ScopeRuleType.INCLUDE) Color(0xFF16A34A) else Color(0xFFDC2626),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = rule.type.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = rule.pattern,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Scheme and port info
                val details = buildList {
                    if (rule.scheme != null) add("Scheme: ${rule.scheme.uppercase()}")
                    if (rule.port != null) add("Port: ${rule.port}")
                    if (rule.comment.isNotBlank()) add(rule.comment)
                }.joinToString(" • ")

                if (details.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            IconButton(onClick = onEdit) {
                Text("✏️", fontSize = 14.sp)
            }

            IconButton(onClick = onDelete) {
                Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ScopeRuleDialog(
    initialRule: ScopeRule?,
    defaultType: ScopeRuleType,
    onDismiss: () -> Unit,
    onSave: (ScopeRule) -> Unit
) {
    var type by remember { mutableStateOf(initialRule?.type ?: defaultType) }
    var pattern by remember { mutableStateOf(initialRule?.pattern ?: "") }
    var scheme by remember { mutableStateOf(initialRule?.scheme ?: "") }
    var portText by remember { mutableStateOf(initialRule?.port?.toString() ?: "") }
    var comment by remember { mutableStateOf(initialRule?.comment ?: "") }
    var enabled by remember { mutableStateOf(initialRule?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialRule != null) "Edit Scope Rule" else "Add Scope Rule")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Rule Type Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = type == ScopeRuleType.INCLUDE,
                        onClick = { type = ScopeRuleType.INCLUDE },
                        label = { Text("Include") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = type == ScopeRuleType.EXCLUDE,
                        onClick = { type = ScopeRuleType.EXCLUDE },
                        label = { Text("Exclude") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Target Pattern
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Target Pattern") },
                    placeholder = { Text("example.com, *.example.com, 192.168.1.0/24") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Supported: exact domain, *.domain.com wildcard, IP, or CIDR (/24, /16, /8).",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Optional Scheme & Port Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = scheme,
                        onValueChange = { scheme = it },
                        label = { Text("Scheme (opt)") },
                        placeholder = { Text("http / https") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port (opt)") },
                        placeholder = { Text("80, 443, 8080") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Comment
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = pattern.trim()
                    if (p.isNotEmpty()) {
                        val parsedPort = portText.trim().toIntOrNull()
                        val parsedScheme = scheme.trim().ifEmpty { null }?.lowercase()
                        val rule = initialRule?.copy(
                            type = type,
                            pattern = p,
                            scheme = parsedScheme,
                            port = parsedPort,
                            comment = comment.trim(),
                            enabled = enabled
                        ) ?: ScopeRule(
                            type = type,
                            pattern = p,
                            scheme = parsedScheme,
                            port = parsedPort,
                            comment = comment.trim(),
                            enabled = enabled
                        )
                        onSave(rule)
                    }
                },
                enabled = pattern.trim().isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

