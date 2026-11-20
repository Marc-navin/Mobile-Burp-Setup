package com.example.basicbrowser.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.basicbrowser.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.data.model.HighlightColor
import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.data.repository.RequestAnnotationRepository
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.proxy.intercept.InterceptManager
import com.example.basicbrowser.repeater.data.RepeaterRepository
import com.example.basicbrowser.scope.data.ScopeRepository
import com.example.basicbrowser.scope.model.ScopeRule
import com.example.basicbrowser.util.CurlGenerator
import com.example.basicbrowser.util.ResponseLinkExtractor
import com.example.basicbrowser.variation.data.VariationRepository
import java.net.URI

/**
 * Context menu bottom sheet inspired by Burp Suite's desktop context menus.
 * Operates strictly on the exact selected request ID.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestContextBottomSheet(
    id: String,
    url: String,
    method: String,
    requestHeaders: List<String>,
    requestBody: ByteArray?,
    statusCode: Int? = null,
    responseHeaders: List<String> = emptyList(),
    responseBody: ByteArray? = null,
    isIntercept: Boolean,
    onDismiss: () -> Unit,
    onNavigateToBrowser: (String) -> Unit = {},
    onNavigateToRepeater: () -> Unit = {},
    onNavigateToVariation: () -> Unit = {},
    onOpenResponseInBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val annotationRepo = remember { RequestAnnotationRepository.getInstance(context) }
    val annotations by annotationRepo.annotations.collectAsState()
    val savedRequests by annotationRepo.savedRequests.collectAsState()

    val currentAnnotation = annotations[id]
    val isSaved = savedRequests.any { it.id == id }

    var showNoteDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    // Helper to copy text to system clipboard
    val copyToClipboard: (String, String) -> Unit = { label, text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // Convert to HttpRequestData for Repeater and Variation
    val httpRequestData = remember(method, url, requestHeaders, requestBody) {
        HttpRequestData(
            method = method,
            url = url,
            httpVersion = "HTTP/1.1",
            headers = requestHeaders.filterNot { it.startsWith("HTTP/") || it.contains("HTTP/1.") },
            body = requestBody
        )
    }

    if (showNoteDialog) {
        var noteInput by remember { mutableStateOf(currentAnnotation?.note ?: "") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Request Note") },
            text = {
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    placeholder = { Text("Enter notes for this request...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        annotationRepo.setNote(id, noteInput)
                        showNoteDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete History Item?") },
            text = { Text("Are you sure you want to remove this item from the HTTP History log?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        HttpHistoryRepository.getInstance().deleteById(id)
                        showDeleteConfirmDialog = false
                        onDismiss()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear HTTP History?") },
            text = { Text("This will clear the HTTP History log. Any starred/saved items and target scope will be preserved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val savedIds = annotationRepo.getAllSavedItems().map { it.id }.toSet()
                        HttpHistoryRepository.getInstance().clear(savedIds)
                        showClearConfirmDialog = false
                        onDismiss()
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = Color(0xFF22262B),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF6B7280))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // Header: URL and Request Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = Color(0xFF374151),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = method,
                            color = Color(0xFF93C5FD),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (statusCode != null) {
                        Surface(
                            color = Color(0xFF374151),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "$statusCode",
                                color = Color(0xFF86EFAC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (isSaved) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_star),
                                contentDescription = null,
                                tint = Color(0xFFFDE047),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Saved", color = Color(0xFFFDE047), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "#${id.take(8)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF9CA3AF)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = url,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF374151))

            // --- Section 1: Intercept Actions (Only in Intercept Mode) ---
            if (isIntercept) {
                ContextMenuItem(
                    iconRes = R.drawable.ic_action_forward,
                    title = "Forward",
                    subtitle = "Forward this paused request upstream",
                    textColor = Color(0xFF4ADE80),
                    onClick = {
                        val pending = InterceptManager.getInstance().getPausedRequest(id)
                        if (pending != null && !pending.future.isDone) {
                            InterceptManager.getInstance().forward(id)
                            Toast.makeText(context, "Request forwarded", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Request is no longer pending", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                )

                ContextMenuItem(
                    iconRes = R.drawable.ic_action_trash,
                    title = "Drop",
                    subtitle = "Drop this paused request",
                    textColor = Color(0xFFF87171),
                    onClick = {
                        val pending = InterceptManager.getInstance().getPausedRequest(id)
                        if (pending != null && !pending.future.isDone) {
                            InterceptManager.getInstance().drop(id)
                            Toast.makeText(context, "Request dropped", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Request is no longer pending", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF374151))
            }

            // --- Section 2: Core Analysis & Tool Integration ---
            ContextMenuItem(
                iconRes = R.drawable.ic_nav_lab,
                title = "Send to Intruder",
                shortcut = "Ctrl+I",
                onClick = {
                    VariationRepository.getInstance().setBaseRequest(httpRequestData)
                    Toast.makeText(context, "Sent to Intruder (Variation Lab)", Toast.LENGTH_SHORT).show()
                    onDismiss()
                    onNavigateToVariation()
                }
            )

            ContextMenuItem(
                iconRes = R.drawable.ic_nav_repeater,
                title = "Send to Repeater",
                shortcut = "Ctrl+R",
                onClick = {
                    RepeaterRepository.getInstance().addItem(httpRequestData)
                    Toast.makeText(context, "Sent to Repeater", Toast.LENGTH_SHORT).show()
                    onDismiss()
                    onNavigateToRepeater()
                }
            )

            if (!isIntercept) {
                ContextMenuItem(
                    iconRes = R.drawable.ic_action_eye,
                    title = "Open response in browser",
                    subtitle = "Sandboxed preview with JavaScript disabled",
                    onClick = {
                        onDismiss()
                        onOpenResponseInBrowser()
                    }
                )
            }

            ContextMenuItem(
                iconRes = R.drawable.ic_nav_browser,
                title = "Request in browser",
                subtitle = "Load target URL in the ABS browser tab",
                onClick = {
                    onDismiss()
                    onNavigateToBrowser(url)
                }
            )

            ContextMenuItem(
                iconRes = R.drawable.ic_scope_target,
                title = "Add to scope",
                subtitle = "Include domain in Global Target Scope",
                onClick = {
                    val host = try {
                        val uri = URI(url)
                        uri.host ?: url.substringAfter("://").substringBefore("/").substringBefore(":")
                    } catch (_: Exception) {
                        url.substringAfter("://").substringBefore("/").substringBefore(":")
                    }
                    if (host.isNotBlank()) {
                        ScopeRepository.getInstance().addRule(ScopeRule(pattern = host))
                        Toast.makeText(context, "Added $host to Target Scope", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF374151))

            // --- Section 3: Annotations & Color Highlights ---
            ContextMenuItem(
                iconRes = R.drawable.ic_action_edit,
                title = if (currentAnnotation?.note.isNullOrBlank()) "Add notes" else "Edit notes",
                subtitle = currentAnnotation?.note?.takeIf { it.isNotBlank() },
                onClick = { showNoteDialog = true }
            )

            // Highlight Color Picker
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Highlight",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val colors = listOf(
                        HighlightColor.NONE to Color(0xFF6B7280),
                        HighlightColor.RED to Color(0xFFEF4444),
                        HighlightColor.ORANGE to Color(0xFFF97316),
                        HighlightColor.YELLOW to Color(0xFFEAB308),
                        HighlightColor.GREEN to Color(0xFF22C55E),
                        HighlightColor.BLUE to Color(0xFF3B82F6),
                        HighlightColor.PURPLE to Color(0xFFA855F7),
                        HighlightColor.GRAY to Color(0xFF9CA3AF)
                    )

                    colors.forEach { (colorEnum, composeColor) ->
                        val isSelected = (currentAnnotation?.highlightColor ?: HighlightColor.NONE) == colorEnum
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (colorEnum == HighlightColor.NONE) Color.Transparent else composeColor)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color(0xFF6B7280),
                                    shape = CircleShape
                                )
                                .clickable {
                                    annotationRepo.setHighlight(id, colorEnum)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (colorEnum == HighlightColor.NONE) {
                                Text("✕", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                            } else if (isSelected) {
                                Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF374151))

            // --- Section 4: Utilities ---
            ContextMenuItem(
                iconRes = R.drawable.ic_action_copy,
                title = "Copy URL",
                onClick = {
                    copyToClipboard("URL", url)
                    onDismiss()
                }
            )

            ContextMenuItem(
                iconRes = R.drawable.ic_action_terminal,
                title = "Copy as curl command (bash)",
                onClick = {
                    val curl = CurlGenerator.generateCurl(method, url, requestHeaders, requestBody)
                    copyToClipboard("cURL Command", curl)
                    onDismiss()
                }
            )

            if (!isIntercept && responseBody != null && responseBody.isNotEmpty()) {
                ContextMenuItem(
                    iconRes = R.drawable.ic_action_link,
                    title = "Copy links in response",
                    subtitle = "Extracts all unique hyperlinks from response HTML",
                    onClick = {
                        val links = ResponseLinkExtractor.extractLinks(url, responseHeaders, responseBody)
                        if (links.isNotEmpty()) {
                            copyToClipboard("Response Links", links.joinToString("\n"))
                        } else {
                            Toast.makeText(context, "No links found in response", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                )
            }

            ContextMenuItem(
                iconRes = R.drawable.ic_action_star,
                title = if (isSaved) "Remove from saved items" else "Save item",
                subtitle = "Preserves request & response independently across history clears",
                textColor = if (isSaved) Color(0xFFFDE047) else Color(0xFFE5E7EB),
                onClick = {
                    if (isSaved) {
                        annotationRepo.removeSavedItem(id)
                        Toast.makeText(context, "Item removed from saved", Toast.LENGTH_SHORT).show()
                    } else {
                        if (isIntercept) {
                            annotationRepo.saveItem(httpRequestData, id)
                        } else {
                            val historyItem = HttpHistoryItem(
                                id = id,
                                method = method,
                                url = url,
                                requestHeaders = requestHeaders,
                                requestBody = requestBody,
                                statusCode = statusCode,
                                responseHeaders = responseHeaders,
                                responseBody = responseBody
                            )
                            annotationRepo.saveItem(historyItem)
                        }
                        Toast.makeText(context, "Item saved independently", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // --- Section 5: Destructive Actions (History Only) ---
            if (!isIntercept) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF374151))

                ContextMenuItem(
                    iconRes = R.drawable.ic_action_trash,
                    title = "Delete item",
                    textColor = Color(0xFFF87171),
                    onClick = {
                        showDeleteConfirmDialog = true
                    }
                )

                ContextMenuItem(
                    iconRes = R.drawable.ic_action_close,
                    title = "Clear history",
                    textColor = Color(0xFFF87171),
                    onClick = {
                        showClearConfirmDialog = true
                    }
                )
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    iconRes: Int? = null,
    icon: String? = null,
    title: String,
    subtitle: String? = null,
    shortcut: String? = null,
    textColor: Color = Color(0xFFE5E7EB),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (textColor != Color(0xFFE5E7EB)) textColor else Color(0xFF9CA3AF),
                    modifier = Modifier.size(18.dp)
                )
            } else if (icon != null) {
                Text(
                    text = icon,
                    fontSize = 16.sp
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (shortcut != null) {
            Text(
                text = shortcut,
                color = Color(0xFF6B7280),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

