package com.example.basicbrowser.filter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.filter.model.InterceptFilterSettings

@Composable
fun InterceptFilterDialog(
    initialSettings: InterceptFilterSettings,
    onDismiss: () -> Unit,
    onSave: (InterceptFilterSettings) -> Unit,
    onNavigateToScope: () -> Unit
) {
    var settings by remember { mutableStateOf(initialSettings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚡ Intercept Filters", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Excluded requests are automatically forwarded unchanged without pausing in the queue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // 1. In-Scope Only
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("In-scope only", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Auto-forward out-of-scope traffic", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.interceptOnlyInScope,
                        onCheckedChange = { settings = settings.copy(interceptOnlyInScope = it) }
                    )
                }

                // Scope manager link
                TextButton(
                    onClick = {
                        onDismiss()
                        onNavigateToScope()
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("🎯 Manage Target Scope →", fontSize = 12.sp)
                }

                HorizontalDivider()

                // 2. Filter Images
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Don't intercept images", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Auto-forward JPEG, PNG, GIF, WebP, SVG", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.filterImages,
                        onCheckedChange = { settings = settings.copy(filterImages = it) }
                    )
                }

                // 3. Filter CSS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Don't intercept CSS", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Auto-forward stylesheet requests", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.filterCss,
                        onCheckedChange = { settings = settings.copy(filterCss = it) }
                    )
                }

                // 4. Filter JS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Don't intercept JavaScript", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Auto-forward script resources", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.filterJs,
                        onCheckedChange = { settings = settings.copy(filterJs = it) }
                    )
                }

                // 5. Filter other resources
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Don't intercept other media", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Auto-forward fonts, audio, video", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.filterOtherResources,
                        onCheckedChange = { settings = settings.copy(filterOtherResources = it) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(settings)
                onDismiss()
            }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

