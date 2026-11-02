package com.example.basicbrowser.proxy.tls

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages a development-only Certificate Authority for the local proxy.
 *
 * This singleton generates and persists a self-signed root CA that will
 * eventually be used to sign per-host certificates for HTTPS interception.
 *
 * ## Storage
 *
 * The CA keypair and certificate are stored in a PKCS12 keystore at:
 *   `<app internal storage>/tls/dev_ca.p12`
 *
 * The keystore password is randomly generated and stored in:
 *   `<app internal storage>/tls/dev_ca.pwd`
 *
 * Both files live in Android's app-private internal storage, which is:
 * - Not accessible to other apps (sandboxed by Android)
 * - Not visible via USB / file manager without root
 * - Deleted when the app is uninstalled
 *
 * ## Security
 *
 * - The private key is NEVER logged, serialized to UI state, or
 *   returned by any public method.
 * - The CA certificate has a clear development-only identity:
 *   `CN=BasicBrowser Development CA, O=BasicBrowser, OU=Development`
 * - The CA is NOT installed into Android's system or user certificate
 *   store. It is only used internally by this proxy.
 * - No attempt is made to bypass certificate pinning.
 *
 * ## Usage
 *
 * Call [initialize] once during app startup (e.g., in `onCreate`).
 * After that, [getCaCertificate] returns the root certificate for
 * configuring WebView trust (future step).
 */
object CertificateAuthority {

    private const val TAG = "CertificateAuthority"

    /** Subdirectory within app internal storage for TLS material. */
    private const val TLS_DIR = "tls"

    /** Filename for the PKCS12 keystore containing the CA keypair + cert. */
    private const val KEYSTORE_FILENAME = "dev_ca.p12"

    /** Filename for the keystore password (randomly generated). */
    private const val PASSWORD_FILENAME = "dev_ca.pwd"

    /** Filename for storing the active CA certificate SHA-256 fingerprint. */
    private const val FINGERPRINT_FILENAME = "dev_ca.fingerprint"

    /** Filename for storing the fingerprint of the last exported certificate. */
    private const val EXPORTED_FINGERPRINT_FILENAME = "dev_ca.exported_fingerprint"

    /** Alias under which the CA entry is stored in the keystore. */
    private const val CA_ALIAS = "dev-ca"

    /** RSA key size for the CA keypair. */
    private const val KEY_SIZE = 2048

    /** Validity period for the CA certificate, in years. */
    private const val VALIDITY_YEARS = 1

    /** Distinguished name for the CA certificate. */
    private const val CA_SUBJECT =
        "CN=BasicBrowser Development CA, O=BasicBrowser, OU=Development"

    /**
     * Initialization lifecycle state of the Certificate Authority.
     */
    enum class State {
        /** Initialization has not been started. */
        UNINITIALIZED,
        /** Initialization is currently running in background. */
        INITIALIZING,
        /** Initialization completed successfully; CA is ready to sign certificates. */
        INITIALIZED,
        /** Initialization failed with an error. */
        FAILED
    }

    // -- Internal state (populated by initialize) -------------------------

    /** The CA keypair — kept internal, NEVER exposed publicly. */
    private var caKeyPair: KeyPair? = null

    /** The self-signed CA certificate. */
    private var caCertificate: X509Certificate? = null

    /** Current lifecycle state of CA initialization. */
    @Volatile
    private var state = State.UNINITIALIZED

    /** Latch unblocked when initialization completes or fails. */
    @Volatile
    private var initLatch = CountDownLatch(1)

    /** Holds the failure exception if initialization failed. */
    @Volatile
    private var initError: Throwable? = null

    /** Flag indicating whether CA recovery/regeneration occurred. */
    @Volatile
    private var caRecoveryOccurred = false

    /** Prior fingerprint if CA identity changed or was regenerated. */
    @Volatile
    private var previousFingerprint: String? = null

    /** Directory holding TLS storage. */
    @Volatile
    private var tlsDirectory: File? = null

    // -- Public API -------------------------------------------------------

    /**
     * Asynchronously initializes the Certificate Authority on a background worker thread.
     *
     * This decouples heavy cryptographic startup (keystore loading / RSA key generation)
     * from proxy startup and UI rendering, allowing the proxy to bind to 127.0.0.1:8080
     * in milliseconds while the CA warms up in parallel.
     *
     * Safe to call multiple times or concurrently — subsequent calls are no-ops.
     *
     * @param context Application context used to resolve the files directory. The context is not retained.
     */
    fun initializeAsync(context: Context) {
        val appCtx = context.applicationContext
        initializeAsync(File(appCtx.filesDir, TLS_DIR))
    }

    /**
     * Internal overload for asynchronous initialization using a specific directory.
     */
    internal fun initializeAsync(tlsDir: File) {
        synchronized(this) {
            if (state == State.INITIALIZED || state == State.INITIALIZING) {
                return
            }
            state = State.INITIALIZING
            initLatch = CountDownLatch(1)
            initError = null
        }

        Thread({
            performInit(tlsDir)
        }, "ca-init-async").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Initializes the Certificate Authority synchronously.
     *
     * If initialization is already complete, returns immediately.
     * If another thread is currently performing async initialization, waits up to 30s for it to finish.
     * If uninitialized, performs initialization synchronously on the calling thread.
     *
     * @param context Application or Activity context (used to resolve
     *   [Context.getFilesDir] for storage). The context is NOT retained.
     */
    fun initialize(context: Context) {
        initialize(File(context.filesDir, TLS_DIR))
    }

    /**
     * Initializes the Certificate Authority using a specific directory.
     * Internal overload for testing and runtime initialization.
     */
    internal fun initialize(tlsDir: File) {
        val shouldInit: Boolean
        synchronized(this) {
            if (state == State.INITIALIZED) {
                Log.d(TAG, "Already initialized — skipping")
                return
            }
            if (state == State.INITIALIZING) {
                shouldInit = false
            } else {
                state = State.INITIALIZING
                initLatch = CountDownLatch(1)
                initError = null
                shouldInit = true
            }
        }

        if (shouldInit) {
            performInit(tlsDir)
        } else {
            awaitInitialization(30_000)
        }
    }

    /**
     * Blocks the calling thread until CA initialization completes or times out.
     *
     * If initialization is already complete ([isInitialized] == true), returns `true` immediately.
     * If initialization has not started ([state] == State.UNINITIALIZED) or has failed ([state] == State.FAILED),
     * returns `false` immediately without blocking.
     *
     * If initialization is in progress ([state] == State.INITIALIZING), blocks up to [timeoutMs]
     * milliseconds for completion.
     *
     * @param timeoutMs Maximum milliseconds to wait (default: 15,000 ms).
     * @return `true` if initialization completed successfully and the CA is ready to sign certificates;
     *   `false` on timeout, failure, or if initialization was never started.
     */
    fun awaitInitialization(timeoutMs: Long = 15_000): Boolean {
        val s = state
        if (s == State.INITIALIZED) return true
        if (s == State.FAILED || s == State.UNINITIALIZED) return false

        return try {
            val finished = initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            finished && state == State.INITIALIZED
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Returns the CA's self-signed root certificate.
     *
     * @throws IllegalStateException if [initialize] has not completed successfully.
     */
    fun getCaCertificate(): X509Certificate {
        check(state == State.INITIALIZED) { "CertificateAuthority.initialize() must be called first" }
        return caCertificate!!
    }

    /**
     * Returns `true` if [initialize] has completed successfully.
     */
    fun isInitialized(): Boolean = state == State.INITIALIZED

    /**
     * Returns `true` if CA initialization is currently in progress.
     */
    fun isInitializing(): Boolean = state == State.INITIALIZING

    /**
     * Returns the failure cause if CA initialization failed, or null.
     */
    fun getInitializationError(): Throwable? = initError

    /**
     * Export status of the development CA certificate.
     */
    enum class ExportStatus {
        /** The CA certificate has never been exported. */
        NOT_EXPORTED,
        /** The active CA matches the last exported certificate. */
        EXPORTED_MATCHES,
        /** The active CA differs from the last exported certificate (identity changed). */
        EXPORTED_MISMATCH
    }

    /**
     * Installation status in Android OS User Credentials store.
     */
    enum class InstallationStatus {
        /** Cannot be verified programmatically due to Android sandbox boundaries. */
        NOT_VERIFIED
    }

    /**
     * Computes the SHA-256 fingerprint of any X.509 certificate as a colon-separated hex string.
     */
    fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Returns true if the CA was regenerated (e.g. after keystore corruption recovery).
     */
    fun isCaRegenerated(): Boolean = caRecoveryOccurred

    /**
     * Returns the previous CA certificate fingerprint before recovery/regeneration, if available.
     */
    fun getPreviousFingerprint(): String? = previousFingerprint

    /**
     * Returns true if the CA certificate has ever been exported.
     */
    fun isCaExported(): Boolean = getExportedCertificateFingerprint() != null

    /**
     * Returns the fingerprint of the last exported certificate, or null if never exported.
     */
    fun getExportedCertificateFingerprint(): String? {
        val dir = tlsDirectory ?: return null
        val file = File(dir, EXPORTED_FINGERPRINT_FILENAME)
        return if (file.exists()) file.readText().trim() else null
    }

    /**
     * Returns true if the active CA's fingerprint differs from the last exported certificate.
     * Returns false if never exported or if active matches exported.
     */
    fun hasCaChangedSinceLastExport(): Boolean {
        if (!isInitialized()) return false
        val exportedFp = getExportedCertificateFingerprint() ?: return false
        return exportedFp != getCertificateFingerprint()
    }

    /**
     * Returns the structured export status of the CA.
     */
    fun getExportStatus(): ExportStatus {
        if (!isInitialized()) return ExportStatus.NOT_EXPORTED
        val exportedFp = getExportedCertificateFingerprint() ?: return ExportStatus.NOT_EXPORTED
        return if (exportedFp == getCertificateFingerprint()) {
            ExportStatus.EXPORTED_MATCHES
        } else {
            ExportStatus.EXPORTED_MISMATCH
        }
    }

    /**
     * Returns the installation status in Android OS User Credentials store.
     * Always NOT_VERIFIED because the Android security sandbox prevents apps from querying the OS store.
     */
    fun getInstallationStatus(): InstallationStatus = InstallationStatus.NOT_VERIFIED

    /**
     * Records the active CA fingerprint as exported, aligning the active identity with exported identity.
     */
    fun markCaExported() {
        if (!isInitialized()) return
        val dir = tlsDirectory ?: return
        val file = File(dir, EXPORTED_FINGERPRINT_FILENAME)
        file.writeText(getCertificateFingerprint())
        caRecoveryOccurred = false
    }

    /**
     * Returns the SHA-256 fingerprint of the CA certificate as a
     * colon-separated hex string (e.g., "AB:CD:EF:...").
     *
     * This is safe to display — it is a one-way hash of the public
     * certificate and reveals no private key material.
     *
     * DEVELOPMENT ONLY — used by the CA verification screen.
     */
    fun getCertificateFingerprint(): String {
        check(state == State.INITIALIZED) { "CertificateAuthority.initialize() must be called first" }
        return computeSha256Fingerprint(caCertificate!!)
    }

    /**
     * Exports the PUBLIC CA certificate formatted in standard RFC 7468 PEM format.
     *
     * SECURITY:
     * - Only the public X.509 certificate is exported.
     * - The private key and PKCS12 keystore are NEVER included.
     * - This PEM can be installed in Android OS User Credentials store
     *   (Settings -> Security -> Encryption & credentials -> Install a certificate -> CA certificate)
     *   so that WebView Chromium trusts it for cross-origin subresources.
     *
     * @return PEM-encoded public X.509 certificate string.
     * @throws IllegalStateException if [initialize] has not been called.
     */
    fun exportCaCertificatePem(): String {
        check(state == State.INITIALIZED) { "CertificateAuthority.initialize() must be called first" }
        val cert = caCertificate ?: throw IllegalStateException("CA certificate is not available")
        val base64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        markCaExported()
        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
    }

    /**
     * Returns `true` if the CA private key is loaded in memory.
     *
     * This is a safe boolean check — the key itself is never exposed.
     *
     * DEVELOPMENT ONLY — used by the CA verification screen.
     */
    fun isPrivateKeyAvailable(): Boolean {
        return state == State.INITIALIZED && caKeyPair?.private != null
    }

    /**
     * Forces a reload of the CA from disk storage, discarding the
     * current in-memory state. This verifies that the CA was correctly
     * persisted and can survive an app restart.
     *
     * Returns `true` if the reload succeeded and the certificate
     * fingerprint matches the previously loaded CA.
     *
     * DEVELOPMENT ONLY — used by the CA verification screen.
     *
     * @param context Application context for resolving filesDir.
     */
    @Synchronized
    fun reloadFromDisk(context: Context): Boolean {
        val previousFingerprint = if (state == State.INITIALIZED) getCertificateFingerprint() else null

        // Reset in-memory state
        caKeyPair = null
        caCertificate = null
        state = State.UNINITIALIZED
        initLatch = CountDownLatch(1)
        initError = null

        // Re-initialize from disk
        initialize(context)

        if (state != State.INITIALIZED) return false

        // If we had a previous fingerprint, verify it matches
        return if (previousFingerprint != null) {
            val newFingerprint = getCertificateFingerprint()
            val matches = previousFingerprint == newFingerprint
            if (matches) {
                Log.i(TAG, "Reload verification PASSED — fingerprint matches")
            } else {
                Log.w(TAG, "Reload verification FAILED — fingerprint changed!")
            }
            matches
        } else {
            true
        }
    }

    /**
     * Validates whether the given certificate was signed by this
     * development CA.
     *
     * This is the core trust check used by the WebView SSL error
     * handler. It verifies the certificate's signature against the
     * CA's public key — if verification succeeds, the certificate
     * was issued by our CA.
     *
     * This method only uses the CA's PUBLIC key for verification.
     * The private key is not involved and remains isolated.
     *
     * @param certificate The certificate to validate.
     * @return `true` if the certificate was signed by this CA.
     */
    fun isCertificateSignedByDevCa(certificate: java.security.cert.Certificate): Boolean {
        if (state != State.INITIALIZED || caCertificate == null) return false
        return try {
            certificate.verify(caCertificate!!.publicKey)
            true
        } catch (_: Exception) {
            false
        }
    }

    // -- Internal: keypair access for future CertificateGenerator ---------

    /**
     * Returns the CA keypair for signing per-host certificates.
     *
     * This is `internal` so it is accessible within the `tls` package
     * (for the future [CertificateGenerator]) but NOT from outside.
     * The private key is never exposed to UI, logs, or external callers.
     */
    internal fun getKeyPair(): KeyPair {
        check(state == State.INITIALIZED) { "CertificateAuthority.initialize() must be called first" }
        return caKeyPair!!
    }

    /**
     * Resets CA state for testing isolation.
     */
    @Synchronized
    internal fun resetForTesting() {
        caKeyPair = null
        caCertificate = null
        state = State.UNINITIALIZED
        initLatch = CountDownLatch(1)
        initError = null
        caRecoveryOccurred = false
        previousFingerprint = null
        tlsDirectory = null
    }

    // -- Private implementation -------------------------------------------

    /**
     * Performs CA loading or generation, updating the lifecycle state and latch.
     */
    private fun performInit(tlsDir: File) {
        tlsDirectory = tlsDir
        try {
            val keystoreFile = File(tlsDir, KEYSTORE_FILENAME)
            val passwordFile = File(tlsDir, PASSWORD_FILENAME)
            val fingerprintFile = File(tlsDir, FINGERPRINT_FILENAME)

            if (fingerprintFile.exists()) {
                previousFingerprint = fingerprintFile.readText().trim()
            }

            if (keystoreFile.exists() && passwordFile.exists()) {
                loadExistingCa(keystoreFile, passwordFile)
            } else {
                generateNewCa(tlsDir, keystoreFile, passwordFile)
            }

            val currentFp = computeSha256Fingerprint(caCertificate!!)
            fingerprintFile.writeText(currentFp)

            if (previousFingerprint != null && previousFingerprint != currentFp) {
                caRecoveryOccurred = true
                Log.w(TAG, "CA identity changed! Previous: $previousFingerprint, Current: $currentFp")
                CertificateGenerator.clearCache()
            }

            synchronized(this) {
                state = State.INITIALIZED
                initError = null
                initLatch.countDown()
            }
            Log.i(TAG, "Development CA initialized (cert serial: ${caCertificate?.serialNumber}, fingerprint: $currentFp)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Development CA", t)
            synchronized(this) {
                state = State.FAILED
                initError = t
                initLatch.countDown()
            }
        }
    }

    // -- Private implementation -------------------------------------------

    /**
     * Loads an existing CA from the PKCS12 keystore on disk.
     */
    private fun loadExistingCa(keystoreFile: File, passwordFile: File) {
        var password: CharArray? = null
        try {
            password = passwordFile.readText().toCharArray()

            val keyStore = KeyStore.getInstance("PKCS12")
            FileInputStream(keystoreFile).use { fis ->
                keyStore.load(fis, password)
            }

            val privateKey = keyStore.getKey(CA_ALIAS, password)
                ?: throw IllegalStateException("CA private key not found in keystore")
            val cert = keyStore.getCertificate(CA_ALIAS) as? X509Certificate
                ?: throw IllegalStateException("CA certificate not found in keystore")

            // Reconstruct the KeyPair from the stored private key and the
            // public key embedded in the certificate.
            caKeyPair = KeyPair(cert.publicKey, privateKey as java.security.PrivateKey)
            caCertificate = cert

            Log.i(TAG, "Loaded existing development CA from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load existing CA — will regenerate", e)
            caRecoveryOccurred = true
            CertificateGenerator.clearCache()
            // Fall through to generate a new one
            generateNewCa(
                keystoreFile.parentFile!!,
                keystoreFile,
                File(keystoreFile.parentFile!!, PASSWORD_FILENAME)
            )
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * Generates a new CA keypair and self-signed certificate, then
     * persists them to a PKCS12 keystore.
     */
    private fun generateNewCa(tlsDir: File, keystoreFile: File, passwordFile: File) {
        Log.i(TAG, "Generating new development CA…")

        // Ensure the TLS directory exists
        if (!tlsDir.exists()) {
            tlsDir.mkdirs()
        }

        // 1. Generate RSA keypair
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(KEY_SIZE, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        // 2. Build the self-signed X.509 v3 certificate
        val now = Date()
        val calendar = Calendar.getInstance().apply {
            time = now
            add(Calendar.YEAR, VALIDITY_YEARS)
        }
        val notAfter = calendar.time

        val subject = X500Name(CA_SUBJECT)
        val serial = BigInteger(128, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(
            /* issuer  = */ subject,       // Self-signed: issuer == subject
            /* serial  = */ serial,
            /* notBefore = */ now,
            /* notAfter  = */ notAfter,
            /* subject = */ subject,
            /* publicKey = */ keyPair.public
        )

        // Mark as a CA certificate
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,   // critical
            BasicConstraints(true)  // isCA = true
        )

        // Key usage: certificate signing and CRL signing
        certBuilder.addExtension(
            Extension.keyUsage,
            true,   // critical
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        // Sign the certificate with the CA's own private key.
        // NOTE: Do NOT use .setProvider("BC") here — Android ships a
        // stripped Bouncy Castle provider that doesn't register all
        // algorithm names. Letting JCA find the provider automatically
        // works correctly on all Android versions.
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .getCertificate(certHolder)

        // Verify the certificate is valid
        cert.verify(keyPair.public)

        // 3. Generate a random keystore password
        val password = generateRandomPassword()
        try {
            // 4. Persist to PKCS12 keystore
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)  // Initialize empty keystore
            keyStore.setKeyEntry(
                CA_ALIAS,
                keyPair.private,
                password,
                arrayOf(cert)
            )

            FileOutputStream(keystoreFile).use { fos ->
                keyStore.store(fos, password)
            }

            // 5. Save the password (app-private storage, not world-readable)
            passwordFile.writeText(String(password))
        } finally {
            password.fill('\u0000')
        }

        // 6. Update in-memory state
        caKeyPair = keyPair
        caCertificate = cert

        Log.i(TAG, "Development CA generated and saved to ${keystoreFile.absolutePath}")
        // NOTE: Private key details are intentionally NOT logged.
    }

    /**
     * Generates a cryptographically random password for the keystore.
     * Uses [SecureRandom] to produce a 32-character alphanumeric string.
     */
    private fun generateRandomPassword(): CharArray {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return CharArray(32) { chars[random.nextInt(chars.length)] }
    }
}
