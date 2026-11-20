package com.example.basicbrowser.filter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.filter.model.ContentTypeCategory
import com.example.basicbrowser.filter.model.ProtocolFilter
import com.example.basicbrowser.filter.model.ScopeFilterOption
import com.example.basicbrowser.filter.model.TrafficFilterState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrafficFilterBottomSheet(
    currentState: TrafficFilterState,
    onDismiss: () -> Unit,
    onApply: (TrafficFilterState) -> Unit,
    onReset: () -> Unit,
    onNavigateToScope: () -> Unit,
    title: String = "Traffic Filters",
    visibleCount: Int? = null,
    totalCount: Int? = null
) {
    var state by remember { mutableStateOf(currentState) }
    var customExtInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(scrollState)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (visibleCount != null && totalCount != null) {
                        Text(
                            text = "Showing $visibleCount of $totalCount items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = {
                        state = TrafficFilterState.DEFAULT
                        customExtInput = ""
                        onReset()
                    }
                ) {
                    Text("Reset All", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Search Query
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { state = state.copy(searchQuery = it) },
                label = { Text("Search (URL, Host, Method, Headers)") },
                placeholder = { Text("Filter keywords...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { state = state.copy(searchQuery = "") }) {
                            Text("✕")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Scope Filter Section
            Text("Target Scope", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show only in-scope items", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Hides out-of-scope third-party traffic",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.scopeFilter != ScopeFilterOption.SHOW_ALL,
                    onCheckedChange = { checked ->
                        state = state.copy(
                            scopeFilter = if (checked) ScopeFilterOption.SHOW_ONLY_IN_SCOPE else ScopeFilterOption.SHOW_ALL
                        )
                    }
                )
            }
            TextButton(
                onClick = {
                    onDismiss()
                    onNavigateToScope()
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Manage Target Scope Rules →", fontSize = 12.sp)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 3. Quick Toggles
            Text("Quick Toggles", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.hideImages,
                    onClick = { state = state.copy(hideImages = !state.hideImages) },
                    label = { Text("Hide Images") }
                )
                FilterChip(
                    selected = state.hideCssAndJs,
                    onClick = { state = state.copy(hideCssAndJs = !state.hideCssAndJs) },
                    label = { Text("Hide CSS & JS") }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 4. File Extensions (Hide Common Resource Types)
            Text("Hide File Extensions", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Filter out common static assets and resource types:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrafficFilterState.COMMON_EXTENSIONS.forEach { ext ->
                    val isHidden = state.hiddenExtensions.contains(ext)
                    FilterChip(
                        selected = isHidden,
                        onClick = {
                            val newSet = state.hiddenExtensions.toMutableSet()
                            if (isHidden) newSet.remove(ext) else newSet.add(ext)
                            state = state.copy(hiddenExtensions = newSet)
                        },
                        label = { Text(".$ext") }
                    )
                }
            }

            // Custom extension adder
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customExtInput,
                    onValueChange = { customExtInput = it.trim().removePrefix(".") },
                    label = { Text("Hide custom extension") },
                    placeholder = { Text("e.g. map, swf, ttf") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val clean = customExtInput.trim().lowercase(Locale.ROOT)
                        if (clean.isNotEmpty()) {
                            val newSet = state.hiddenExtensions.toMutableSet()
                            newSet.add(clean)
                            state = state.copy(hiddenExtensions = newSet)
                            customExtInput = ""
                        }
                    },
                    enabled = customExtInput.isNotBlank()
                ) {
                    Text("Add")
                }
            }

            if (state.hiddenExtensions.isNotEmpty()) {
                val customExtensions = state.hiddenExtensions - TrafficFilterState.COMMON_EXTENSIONS.toSet()
                if (customExtensions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        customExtensions.forEach { ext ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    val newSet = state.hiddenExtensions.toMutableSet()
                                    newSet.remove(ext)
                                    state = state.copy(hiddenExtensions = newSet)
                                },
                                label = { Text(".$ext") },
                                trailingIcon = { Text("✕", fontSize = 10.sp) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 5. Response Status Codes
            Text("Response Status Code", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("2xx", "3xx", "4xx", "5xx").forEach { code ->
                    val selected = state.selectedStatusCodes.contains(code)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val newSet = state.selectedStatusCodes.toMutableSet()
                            if (selected) newSet.remove(code) else newSet.add(code)
                            state = state.copy(selectedStatusCodes = newSet)
                        },
                        label = { Text(code) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 6. HTTP Methods
            Text("HTTP Method", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").forEach { method ->
                    val selected = state.selectedMethods.contains(method)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val newSet = state.selectedMethods.toMutableSet()
                            if (selected) newSet.remove(method) else newSet.add(method)
                            state = state.copy(selectedMethods = newSet)
                        },
                        label = { Text(method) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 7. MIME / Content Categories
            Text("MIME / Content Categories", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ContentTypeCategory.values().forEach { cat ->
                    val selected = state.contentTypes.contains(cat)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val newSet = state.contentTypes.toMutableSet()
                            if (selected) newSet.remove(cat) else newSet.add(cat)
                            state = state.copy(contentTypes = newSet)
                        },
                        label = { Text(cat.displayName) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 8. Additional Filters
            Text("Additional Filters", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only requests with parameters", style = MaterialTheme.typography.bodyMedium)
                    Text("URL query strings (?) or form data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.onlyWithParams,
                    onCheckedChange = { state = state.copy(onlyWithParams = it) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only requests with request body", style = MaterialTheme.typography.bodyMedium)
                    Text("Requests carrying payload data (POST, PUT, etc.)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.onlyWithBody,
                    onCheckedChange = { state = state.copy(onlyWithBody = it) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only requests with responses", style = MaterialTheme.typography.bodyMedium)
                    Text("Transactions that have completed and received response data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.onlyWithResponse,
                    onCheckedChange = { state = state.copy(onlyWithResponse = it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 9. Protocol Filter
            Text("Protocol", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProtocolFilter.values().forEach { proto ->
                    FilterChip(
                        selected = state.protocolFilter == proto,
                        onClick = { state = state.copy(protocolFilter = proto) },
                        label = { Text(proto.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Apply Button
            Button(
                onClick = {
                    onApply(state)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                val count = state.activeFilterCount()
                Text(
                    text = if (count > 0) "Apply Filters ($count active)" else "Apply Filters",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
