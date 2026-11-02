package com.example.basicbrowser.ui.dev

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.basicbrowser.proxy.tls.CertificateAuthority
import com.example.basicbrowser.proxy.tls.LocalHttpsTestServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DEVELOPMENT ONLY — CA Verification Screen
 *
 * Displays safe, non-secret metadata about the development Certificate
 * Authority to verify that Step 3A (CA generation and persistence) is
 * working correctly, and provides a CA trust verification test against
 * a local HTTPS test server.
 *
 * This screen NEVER displays private key contents, PKCS12 passwords,
 * or any secret material.
 *
 * Remove this screen before any production release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaVerificationScreen(
    onBackClick: () -> Unit,
    onNavigateToBrowser: (url: String) -> Unit = {}
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US) }

    // State for CA info
    var caStatus by remember { mutableStateOf("Checking…") }
    var subject by remember { mutableStateOf("—") }
    var issuer by remember { mutableStateOf("—") }
    var keyAlgorithm by remember { mutableStateOf("—") }
    var keySize by remember { mutableStateOf("—") }
    var validFrom by remember { mutableStateOf("—") }
    var validUntil by remember { mutableStateOf("—") }
    var isCurrentlyValid by remember { mutableStateOf<Boolean?>(null) }
    var privateKeyAvailable by remember { mutableStateOf<Boolean?>(null) }
    var fingerprint by remember { mutableStateOf("—") }

    // Test server state & diagnostics
    var testDiagnostics by remember { mutableStateOf<LocalHttpsTestServer.Diagnostics?>(null) }
    var isStartingServer by remember { mutableStateOf(false) }

    // Reload verification state
    var reloadResult by remember { mutableStateOf<String?>(null) }
    var isReloading by remember { mutableStateOf(false) }

    // Export CA state
    var exportResult by remember { mutableStateOf<String?>(null) }
    var exportedPem by remember { mutableStateOf<String?>(null) }
    var caMismatchNotice by remember { mutableStateOf<String?>(null) }
    var exportedFingerprint by remember { mutableStateOf<String?>("—") }
    var exportStatus by remember { mutableStateOf("Not exported") }
    var installationStatus by remember { mutableStateOf("Not verified (Manual installation in Android Settings required)") }
    var identityConsistency by remember { mutableStateOf("—") }

    // Function to load CA info
    fun loadCaInfo() {
        if (!CertificateAuthority.isInitialized()) {
            caStatus = "❌ NOT INITIALIZED"
            return
        }

        try {
            val cert = CertificateAuthority.getCaCertificate()

            val currentFp = CertificateAuthority.getCertificateFingerprint()
            val exportedFp = CertificateAuthority.getExportedCertificateFingerprint()
            val isRegenerated = CertificateAuthority.isCaRegenerated()
            val hasChanged = CertificateAuthority.hasCaChangedSinceLastExport()

            fingerprint = currentFp
            exportedFingerprint = exportedFp ?: "None (Not yet exported)"

            exportStatus = when (CertificateAuthority.getExportStatus()) {
                CertificateAuthority.ExportStatus.NOT_EXPORTED -> "ℹ️ Not exported"
                CertificateAuthority.ExportStatus.EXPORTED_MATCHES -> "✅ Exported (Matches active CA)"
                CertificateAuthority.ExportStatus.EXPORTED_MISMATCH -> "⚠️ Mismatch (Active CA differs from exported cert)"
            }

            identityConsistency = when {
                isRegenerated || hasChanged -> "⚠️ Active CA changed since export (Re-export required)"
                exportedFp == null -> "ℹ️ Initial export required"
                else -> "✅ Active CA matches exported certificate"
            }

            installationStatus = "⚠️ Not verified (Manual installation in Android Settings required)"

            if (isRegenerated || hasChanged) {
                caStatus = "⚠️ CA Identity Mismatch / Regenerated"
                caMismatchNotice = "The active development CA has changed or was regenerated after recovery. " +
                        "Any previously installed certificate in Android's User Credentials store " +
                        "no longer matches the active CA and cannot intercept HTTPS subresources. " +
                        "Please export the new certificate below and reinstall it in Android Settings."
            } else {
                caStatus = "✅ Initialized (Ready to generate host certificates)"
                caMismatchNotice = null
            }

            subject = cert.subjectX500Principal.name
            issuer = cert.issuerX500Principal.name
            keyAlgorithm = cert.publicKey.algorithm
            keySize = when (cert.publicKey) {
                is java.security.interfaces.RSAPublicKey ->
                    "${(cert.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength()} bits"
                else -> "Unknown"
            }
            validFrom = dateFormat.format(cert.notBefore)
            validUntil = dateFormat.format(cert.notAfter)
            isCurrentlyValid = try {
                cert.checkValidity(Date())
                true
            } catch (_: Exception) {
                false
            }
            privateKeyAvailable = CertificateAuthority.isPrivateKeyAvailable()
            fingerprint = CertificateAuthority.getCertificateFingerprint()
            testDiagnostics = try {
                LocalHttpsTestServer.getDiagnostics()
            } catch (_: Exception) {
                null
            }
        } catch (e: Exception) {
            caStatus = "❌ Error: ${e.message}"
        }
    }

    // Load on first composition
    LaunchedEffect(Unit) {
        loadCaInfo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CA Management", fontWeight = FontWeight.Bold)
                        Text(
                            "HTTPS Interception Authority",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text("← Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // CA instructions banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🔒 CA Certificate & Trust Management\nExport the public CA certificate and install it in Android Settings (User Credentials) to enable HTTPS interception.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (caMismatchNotice != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "⚠️ CA Identity Mismatch / Regenerated",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp
                        )
                        Text(
                            text = caMismatchNotice!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // CA Lifecycle & Trust Status Section
            InfoSection(title = "CA Lifecycle & Trust State") {
                InfoRow(label = "Development CA", value = caStatus)
                InfoRow(label = "Export Status", value = exportStatus)
                InfoRow(label = "Identity Consistency", value = identityConsistency)
                InfoRow(label = "Android Installation", value = installationStatus)
            }

            // Cryptographic Fingerprints
            InfoSection(title = "Cryptographic Fingerprints (SHA-256)") {
                Text(
                    text = "Active CA Fingerprint:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = fingerprint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last Exported Certificate Fingerprint:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = exportedFingerprint ?: "None (Not yet exported)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                )

                Text(
                    text = "Note: Android OS security restrictions prevent apps from reading User Credentials store contents. Installation status cannot be verified automatically.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Certificate Details
            InfoSection(title = "Certificate Details") {
                InfoRow(label = "Subject (DN)", value = subject)
                InfoRow(label = "Issuer", value = issuer)
                InfoRow(label = "Key Algorithm", value = keyAlgorithm)
                InfoRow(label = "Key Size", value = keySize)
                InfoRow(label = "Valid From", value = validFrom)
                InfoRow(label = "Valid Until", value = validUntil)
                InfoRow(
                    label = "Currently Valid",
                    value = when (isCurrentlyValid) {
                        true -> "✅ Yes"
                        false -> "❌ No"
                        null -> "—"
                    }
                )
                InfoRow(
                    label = "Private Key Available",
                    value = when (privateKeyAvailable) {
                        true -> "✅ Yes (internally)"
                        false -> "❌ No"
                        null -> "—"
                    }
                )
            }



            // Reload / Verify button
            InfoSection(title = "Persistence Verification") {
                Button(
                    onClick = {
                        isReloading = true
                        reloadResult = null
                        Thread {
                            val matched = CertificateAuthority.reloadFromDisk(context.applicationContext)
                            reloadResult = if (matched) {
                                "✅ PASSED — CA reloaded from disk. Fingerprint matches."
                            } else {
                                "❌ FAILED — CA fingerprint changed after reload!"
                            }
                            isReloading = false
                            // Refresh displayed info
                        }.start()
                    },
                    enabled = !isReloading && CertificateAuthority.isInitialized(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isReloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reloading…")
                    } else {
                        Text("🔄 Reload & Verify from Disk")
                    }
                }

                if (reloadResult != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (reloadResult!!.startsWith("✅"))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = reloadResult!!,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp
                        )
                    }

                    // After reload, refresh the displayed info
                    LaunchedEffect(reloadResult) {
                        loadCaInfo()
                    }
                }

                Text(
                    text = "This discards the in-memory CA, re-reads the PKCS12 keystore from " +
                            "internal storage, and compares the SHA-256 fingerprint to verify " +
                            "that the same CA was persisted.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Export CA Certificate for OS User Store Installation
            InfoSection(title = "CA Certificate Export & Installation") {
                Text(
                    text = "To allow Android WebView to trust the Development CA for cross-origin subresources " +
                            "(images, scripts, CDNs), the public certificate must be installed into Android's " +
                            "User Credentials store.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Button(
                    onClick = {
                        try {
                            val pem = CertificateAuthority.exportCaCertificatePem()
                            exportedPem = pem

                            // Attempt to save to public Downloads directory
                            var savedPath: String? = null
                            try {
                                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                )
                                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                                val crtFile = java.io.File(downloadsDir, "basicbrowser_dev_ca.crt")
                                crtFile.writeText(pem)
                                savedPath = crtFile.absolutePath
                            } catch (_: Exception) {
                                // Fallback to app external files dir
                                try {
                                    val fallbackFile = java.io.File(context.getExternalFilesDir(null), "basicbrowser_dev_ca.crt")
                                    fallbackFile.writeText(pem)
                                    savedPath = fallbackFile.absolutePath
                                } catch (_: Exception) {}
                            }

                            exportResult = if (savedPath != null) {
                                "✅ Saved certificate to:\n$savedPath"
                            } else {
                                "✅ Certificate exported below (copy to clipboard)."
                            }
                            loadCaInfo()
                        } catch (e: Exception) {
                            exportResult = "❌ Export failed: ${e.message}"
                        }
                    },
                    enabled = CertificateAuthority.isInitialized(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📤 Export Public CA Certificate (.crt)")
                }

                if (exportResult != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = exportResult!!,
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp
                        )
                    }
                }

                if (exportedPem != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("BasicBrowser Dev CA", exportedPem)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 Copy PEM to Clipboard")
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = exportedPem!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Android Installation Steps:\n" +
                            "1. Tap 'Export Public CA Certificate' above.\n" +
                            "2. Open Android OS Settings → Security & privacy → More security settings → Encryption & credentials.\n" +
                            "3. Select 'Install a certificate' → 'CA certificate'.\n" +
                            "4. When prompted that 'Your data won't be private', tap 'Install anyway'.\n" +
                            "5. Choose basicbrowser_dev_ca.crt from Downloads.\n" +
                            "6. Restart BasicBrowser.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠️ Security Notice: Enabling user CA trust causes BasicBrowser to trust all " +
                            "user-installed CAs on this device, not exclusively this development CA. " +
                            "Strict upstream certificate validation and hostname verification remain fully active.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // HTTPS CA Trust Test (Step 3B Verification)
            InfoSection(title = "HTTPS CA Trust Verification Test") {
                Text(
                    text = "Runs a local HTTPS test server bound strictly to 127.0.0.1 using a " +
                            "temporary certificate signed by our Development CA, and opens it " +
                            "in the app's WebView to verify browser trust.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val diag = testDiagnostics
                InfoRow(label = "Test Hostname", value = diag?.hostname ?: "localhost (${LocalHttpsTestServer.TEST_HOSTNAME})")
                InfoRow(label = "Certificate Subject", value = diag?.subject ?: "—")
                InfoRow(label = "Certificate Issuer", value = diag?.issuer ?: "—")
                InfoRow(
                    label = "Server Status",
                    value = if (diag?.isRunning == true) "🟢 Running (${diag.testUrl})" else "⚪ Stopped (starts on click)"
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Server Certificate SHA-256 Fingerprint:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = diag?.sha256Fingerprint ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        isStartingServer = true
                        Thread {
                            try {
                                LocalHttpsTestServer.start()
                                val updatedDiag = LocalHttpsTestServer.getDiagnostics()
                                testDiagnostics = updatedDiag
                                isStartingServer = false
                                // Navigate to the browser screen and load the test page
                                onNavigateToBrowser(updatedDiag.testUrl)
                            } catch (e: Exception) {
                                isStartingServer = false
                            }
                        }.start()
                    },
                    enabled = !isStartingServer && CertificateAuthority.isInitialized(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isStartingServer) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Starting Test Server…")
                    } else {
                        Text("🧪 Test CA Trust in WebView")
                    }
                }

                Text(
                    text = "Expected result:\n" +
                            "• If CA is trusted: Test page loads → \"CA TRUST TEST PASSED\"\n" +
                            "• If CA is not trusted: WebView displays SSL/certificate error",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** A labeled section card. */
@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

/** A single label → value row. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.6f),
            textAlign = TextAlign.End
        )
    }
}
