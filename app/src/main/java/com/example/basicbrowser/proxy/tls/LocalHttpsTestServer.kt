package com.example.basicbrowser.proxy.tls

import android.util.Log
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * DEVELOPMENT ONLY — Local HTTPS Test Server.
 *
 * Runs an embedded HTTPS server bound strictly to `127.0.0.1` using a temporary
 * server certificate signed by [CertificateAuthority] (BasicBrowser Development CA).
 *
 * Used exclusively to verify that the application's browser / WebView trusts
 * our Development CA before implementing full HTTPS interception.
 *
 * Security guarantees:
 * - Bound ONLY to 127.0.0.1 (never accessible from outside the device)
 * - Private keys and passwords remain strictly in memory and are never logged or exposed
 * - No Android system trust store modification
 * - No HTTP proxy modification, CONNECT handling, or HTTPS MITM
 */
object LocalHttpsTestServer {

    private const val TAG = "LocalHttpsTestServer"
    const val DEFAULT_PORT = 8443
    const val TEST_HOSTNAME = "test.basicbrowser.local"

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var actualPort: Int = DEFAULT_PORT

    private var acceptExecutor: ExecutorService? = null
    private var connectionExecutor: ExecutorService? = null

    // Cached temporary test certificate & diagnostics
    private var testCertificate: X509Certificate? = null
    private var certFingerprint: String = "—"

    /** Safe diagnostic data for UI display (contains NO secret material). */
    data class Diagnostics(
        val hostname: String,
        val subject: String,
        val issuer: String,
        val sha256Fingerprint: String,
        val port: Int,
        val isRunning: Boolean,
        val testUrl: String
    )

    /** Returns safe diagnostics about the test server and its certificate. */
    @Synchronized
    fun getDiagnostics(): Diagnostics {
        ensureCertificateGenerated()
        val cert = testCertificate
        return Diagnostics(
            hostname = "localhost ($TEST_HOSTNAME)",
            subject = cert?.subjectX500Principal?.name ?: "CN=$TEST_HOSTNAME, O=BasicBrowser Dev Test",
            issuer = cert?.issuerX500Principal?.name ?: "CN=BasicBrowser Development CA",
            sha256Fingerprint = certFingerprint,
            port = actualPort,
            isRunning = isRunning.get(),
            testUrl = "https://localhost:$actualPort/"
        )
    }

    /** Returns whether the test server is currently listening. */
    fun isRunning(): Boolean = isRunning.get()

    /** Returns the test URL to open in WebView. */
    fun getTestUrl(): String = "https://localhost:$actualPort/"

    /**
     * Starts the local HTTPS test server if not already running.
     *
     * @param preferredPort Port to try binding to (defaults to 8443).
     * @return The actual port the server is listening on.
     */
    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        if (isRunning.get()) {
            Log.i(TAG, "Test server is already running on port $actualPort")
            return actualPort
        }

        check(CertificateAuthority.isInitialized()) {
            "CertificateAuthority must be initialized before starting test server"
        }

        ensureCertificateGenerated()
        val cert = testCertificate ?: throw IllegalStateException("Failed to generate test certificate")
        val keyPair = serverKeyPair ?: throw IllegalStateException("Server keypair is missing")

        val sslContext = createSslContext(keyPair, cert)

        // Try preferred port, fallback to dynamic ephemeral port (0) if busy
        val server = try {
            val s = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort))
            actualPort = preferredPort
            s
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind to preferred port $preferredPort, binding to free port", e)
            val s = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            actualPort = s.localPort
            s
        }

        serverSocket = server
        isRunning.set(true)

        acceptExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "https-test-accept").apply { isDaemon = true }
        }
        connectionExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "https-test-conn").apply { isDaemon = true }
        }

        acceptExecutor?.execute { acceptLoop(server) }

        Log.i(TAG, "Local HTTPS test server started on https://127.0.0.1:$actualPort")
        return actualPort
    }

    /**
     * Stops the test server and releases all sockets.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "Stopping local HTTPS test server…")
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        acceptExecutor?.shutdownNow()
        connectionExecutor?.shutdownNow()
        acceptExecutor = null
        connectionExecutor = null
        Log.i(TAG, "Local HTTPS test server stopped")
    }

    // ------------------------------------------------------------------ //
    //  Internal Server Logic
    // ------------------------------------------------------------------ //

    private var serverKeyPair: KeyPair? = null

    @Synchronized
    private fun ensureCertificateGenerated() {
        if (testCertificate != null && serverKeyPair != null) return

        Log.i(TAG, "Generating temporary server certificate signed by Development CA…")

        // 1. Generate RSA 2048-bit keypair for test server
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair = keyGen.generateKeyPair()
        serverKeyPair = keyPair

        // 2. Dates
        val now = Date(System.currentTimeMillis() - 60_000) // 1 min in past for clock skew
        val cal = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, 30)
        }
        val notAfter = cal.time

        // 3. Subject and Issuer
        val subject = X500Name("CN=$TEST_HOSTNAME, O=BasicBrowser Dev Test, OU=Development")
        val caCert = CertificateAuthority.getCaCertificate()
        val issuer = X500Name(caCert.subjectX500Principal.name)
        val serial = BigInteger(128, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            now,
            notAfter,
            subject,
            keyPair.public
        )

        // End-entity cert (not a CA)
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(false)
        )

        // Key usage: digital signature & key encipherment
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        // Extended key usage: serverAuth
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )

        // Subject Alternative Names (SAN) for localhost, test hostname, and 127.0.0.1
        val ipBytes = InetAddress.getByName("127.0.0.1").address
        val sans = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, TEST_HOSTNAME),
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.iPAddress, DEROctetString(ipBytes))
            )
        )
        certBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            sans
        )

        // 4. Sign using Development CA private key
        val caKeyPair = CertificateAuthority.getKeyPair()
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(caKeyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .getCertificate(certHolder)

        // Cryptographically verify the cert with CA's public key
        cert.verify(caCert.publicKey)

        testCertificate = cert
        certFingerprint = computeSha256Fingerprint(cert)
        Log.i(TAG, "Temporary test certificate generated. Fingerprint: $certFingerprint")
    }

    private fun createSslContext(keyPair: KeyPair, cert: X509Certificate): SSLContext {
        val caCert = CertificateAuthority.getCaCertificate()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)

        val password = CharArray(24) { 'x' } // In-memory only
        keyStore.setKeyEntry(
            "dev-test-server",
            keyPair.private,
            password,
            arrayOf<Certificate>(cert, caCert)
        )

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())
        return sslContext
    }

    private fun acceptLoop(server: ServerSocket) {
        while (isRunning.get()) {
            try {
                val socket = server.accept() as SSLSocket
                connectionExecutor?.execute { handleClient(socket) }
            } catch (_: SocketException) {
                // Expected when socket is closed via stop()
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Accept error in HTTPS test server", e)
                }
            }
        }
    }

    private fun handleClient(socket: SSLSocket) {
        try {
            socket.use { s ->
                // Ensure TLS handshake completes
                s.startHandshake()
                val session = s.session

                val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                // Drain headers until blank line
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                val cert = testCertificate
                val htmlBody = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>CA Trust Test</title>
                      <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body {
                          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                          background-color: #0b0f19;
                          color: #e2e8f0;
                          padding: 24px;
                          display: flex;
                          flex-direction: column;
                          align-items: center;
                          min-height: 100vh;
                        }
                        .container {
                          max-width: 540px;
                          width: 100%;
                          margin-top: 20px;
                        }
                        .badge {
                          background: linear-gradient(135deg, #10b981 0%, #059669 100%);
                          color: white;
                          padding: 16px 24px;
                          border-radius: 12px;
                          font-size: 20px;
                          font-weight: 800;
                          text-align: center;
                          box-shadow: 0 4px 16px rgba(16, 185, 129, 0.4);
                          margin-bottom: 24px;
                          letter-spacing: 0.5px;
                        }
                        .lead {
                          text-align: center;
                          color: #94a3b8;
                          font-size: 14px;
                          margin-bottom: 24px;
                          line-height: 1.5;
                        }
                        .card {
                          background-color: #161e2e;
                          border: 1px solid #283548;
                          border-radius: 12px;
                          padding: 20px;
                          box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
                        }
                        .card-title {
                          font-size: 13px;
                          font-weight: 700;
                          color: #38bdf8;
                          text-transform: uppercase;
                          letter-spacing: 0.8px;
                          margin-bottom: 16px;
                          border-bottom: 1px solid #283548;
                          padding-bottom: 8px;
                        }
                        .field {
                          margin-bottom: 14px;
                        }
                        .label {
                          color: #64748b;
                          font-size: 11px;
                          text-transform: uppercase;
                          font-weight: 600;
                          margin-bottom: 4px;
                        }
                        .val {
                          font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
                          font-size: 12px;
                          color: #f1f5f9;
                          word-break: break-all;
                          background: #0f172a;
                          padding: 8px 10px;
                          border-radius: 6px;
                          border: 1px solid #1e293b;
                        }
                        .status-ok {
                          color: #4ade80;
                          font-weight: 700;
                        }
                      </style>
                    </head>
                    <body>
                      <div class="container">
                        <div class="badge">✅ CA TRUST TEST PASSED</div>
                        <p class="lead">
                          The WebView successfully established an authenticated TLS connection
                          with the local test server using a certificate signed by our Development CA!
                        </p>
                        <div class="card">
                          <div class="card-title">TLS Handshake & Diagnostic Information</div>
                          <div class="field">
                            <div class="label">Test Hostname</div>
                            <div class="val">localhost ($TEST_HOSTNAME)</div>
                          </div>
                          <div class="field">
                            <div class="label">Certificate Subject</div>
                            <div class="val">${cert?.subjectX500Principal?.name ?: "—"}</div>
                          </div>
                          <div class="field">
                            <div class="label">Certificate Issuer</div>
                            <div class="val">${cert?.issuerX500Principal?.name ?: "—"}</div>
                          </div>
                          <div class="field">
                            <div class="label">SHA-256 Fingerprint</div>
                            <div class="val">$certFingerprint</div>
                          </div>
                          <div class="field">
                            <div class="label">TLS Protocol</div>
                            <div class="val">${session.protocol}</div>
                          </div>
                          <div class="field">
                            <div class="label">Cipher Suite</div>
                            <div class="val">${session.cipherSuite}</div>
                          </div>
                          <div class="field">
                            <div class="label">Trust Status</div>
                            <div class="val status-ok">Verified: Cryptographically signed by BasicBrowser Development CA</div>
                          </div>
                        </div>
                      </div>
                    </body>
                    </html>
                """.trimIndent()

                val bodyBytes = htmlBody.toByteArray(StandardCharsets.UTF_8)
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"

                s.outputStream.write(responseHeader.toByteArray(StandardCharsets.UTF_8))
                s.outputStream.write(bodyBytes)
                s.outputStream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling TLS client connection", e)
        }
    }

    private fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(cert.encoded)
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}
