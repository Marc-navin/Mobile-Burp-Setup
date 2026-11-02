package com.example.basicbrowser.proxy.tls

import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebView

/**
 * DEVELOPMENT ONLY — Selective SSL error handler for WebView.
 *
 * ## Why this exists
 *
 * Android WebView uses Chromium's network stack, which means:
 * - Custom `X509TrustManager` instances have NO effect on WebView
 * - Android's Network Security Configuration only accepts CAs
 *   embedded as build-time resources (`res/raw/`)
 * - Our development CA is generated at **runtime** (first app launch)
 *
 * Therefore, the ONLY way to make WebView trust certificates signed
 * by our runtime-generated development CA is through the
 * `WebViewClient.onReceivedSslError` callback.
 *
 * ## How it works
 *
 * When the WebView encounters an SSL error (e.g., untrusted CA),
 * this handler checks whether the server certificate was signed by
 * our specific development CA. It does this by calling
 * [CertificateAuthority.isCertificateSignedByDevCa], which verifies
 * the certificate's signature against the CA's **public key** only.
 *
 * - If the cert IS signed by our dev CA → `handler.proceed()`
 * - If the cert is NOT signed by our dev CA → `handler.cancel()`
 *
 * ## Security properties
 *
 * - This is NOT a blanket "accept all certificates" handler
 * - Only certificates cryptographically signed by our specific CA
 *   are accepted
 * - Standard HTTPS to public websites (Google, etc.) is unaffected
 *   because those certs are validated normally via the system trust
 *   store before `onReceivedSslError` would ever fire
 * - The CA's private key is never accessed or exposed by this handler
 * - This handler is clearly scoped as DEVELOPMENT ONLY
 *
 * ## When onReceivedSslError fires
 *
 * `onReceivedSslError` is called ONLY when certificate validation
 * FAILS. For standard HTTPS websites (signed by system-trusted CAs),
 * validation succeeds normally and this handler is never invoked.
 * It only fires when a certificate is NOT trusted by the system
 * store — which is exactly the case for our dev CA-signed certs.
 *
 * ## Future architecture
 *
 * Once HTTPS interception is implemented:
 * 1. WebView connects to our local proxy via CONNECT
 * 2. The proxy presents a per-host cert signed by our dev CA
 * 3. WebView's system validation fails (dev CA not in system store)
 * 4. `onReceivedSslError` fires
 * 5. This handler verifies the cert was signed by our dev CA
 * 6. Connection proceeds
 *
 * Remove this handler before any production release.
 */
object DevCaSslErrorHandler {

    private const val TAG = "DevCaSslErrorHandler"

    /**
     * Handles an SSL error from the WebView by selectively trusting
     * certificates signed by the development CA.
     *
     * Call this from `WebViewClient.onReceivedSslError()`.
     *
     * @param view The WebView that encountered the error.
     * @param handler The SSL error handler (proceed or cancel).
     * @param error The SSL error details including the certificate.
     */
    fun handleSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
        // Guard: if the CA isn't initialized, we can't validate anything
        if (!CertificateAuthority.isInitialized()) {
            Log.w(TAG, "CA not initialized — cancelling SSL connection")
            handler.cancel()
            return
        }

        val sslCertificate = error.certificate
        if (sslCertificate == null) {
            Log.w(TAG, "No certificate in SSL error — cancelling")
            handler.cancel()
            return
        }

        // Extract the X509Certificate from the SslCertificate bundle.
        // Android's SslCertificate class wraps the cert but doesn't
        // directly expose the X509Certificate. We reconstruct it from
        // the bundle representation.
        val x509Cert = extractX509Certificate(sslCertificate)
        if (x509Cert == null) {
            Log.w(TAG, "Cannot extract X509 cert from SSL error — cancelling")
            handler.cancel()
            return
        }

        // Check if this certificate was signed by our development CA
        if (CertificateAuthority.isCertificateSignedByDevCa(x509Cert)) {
            Log.i(TAG, "Certificate for '${error.url}' is signed by dev CA — proceeding")
            handler.proceed()
        } else {
            Log.w(TAG, "Certificate for '${error.url}' is NOT signed by dev CA — cancelling")
            handler.cancel()
        }
    }

    /**
     * Extracts a [java.security.cert.X509Certificate] from Android's
     * [android.net.http.SslCertificate].
     *
     * Android's `SslCertificate` doesn't directly expose the underlying
     * X509Certificate. However, `SslCertificate.saveState()` bundles
     * the DER-encoded certificate bytes under the key "x509-certificate".
     * We decode those bytes to reconstruct the X509Certificate.
     */
    private fun extractX509Certificate(
        sslCert: android.net.http.SslCertificate
    ): java.security.cert.X509Certificate? {
        // Android 10+ (API 29+) directly provides the X509Certificate
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            sslCert.x509Certificate?.let { return it }
        }

        return try {
            val bundle = android.net.http.SslCertificate.saveState(sslCert) ?: return null
            val derBytes = bundle.getByteArray("x509-certificate") ?: return null

            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            certFactory.generateCertificate(
                java.io.ByteArrayInputStream(derBytes)
            ) as java.security.cert.X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract X509Certificate", e)
            null
        }
    }
}
