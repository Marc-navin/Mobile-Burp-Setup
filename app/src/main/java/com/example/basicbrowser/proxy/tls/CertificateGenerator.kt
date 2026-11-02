package com.example.basicbrowser.proxy.tls

import android.util.Log
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
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Generates per-host TLS certificates signed by the Development CA.
 *
 * When the proxy intercepts an HTTPS connection to `example.com`, this
 * class generates a short-lived certificate with `CN=example.com` and a
 * matching Subject Alternative Name, signed by the
 * [CertificateAuthority] root. The browser trusts this certificate
 * because it trusts our dev CA (via [DevCaSslErrorHandler]).
 *
 * ## Caching
 *
 * Certificates are cached in an LRU map (max [MAX_CACHE_SIZE] entries)
 * keyed by hostname. This avoids regenerating a certificate for every
 * request to the same host within a browsing session.
 *
 * ## Security
 *
 * - Per-host private keys are kept strictly in memory and never logged.
 * - Certificates are short-lived (24 hours) to limit exposure.
 * - The CA private key is accessed only through
 *   [CertificateAuthority.getKeyPair] (package-internal).
 * - No certificate pinning bypass is implemented.
 */
object CertificateGenerator {

    private const val TAG = "CertificateGenerator"

    /** RSA key size for per-host certificates. */
    private const val KEY_SIZE = 2048

    /** Maximum number of cached host certificates. */
    private const val MAX_CACHE_SIZE = 100

    /** Validity period for generated certificates, in hours. */
    private const val VALIDITY_HOURS = 24

    /**
     * Holds a generated host certificate along with its keypair and
     * the CA certificate for building a complete cert chain.
     *
     * The [keyPair] is needed to configure the SSLContext's key manager.
     * The [caCertificate] is included in the chain so the browser can
     * verify the chain back to the dev CA root.
     */
    data class HostCertificate(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val caCertificate: X509Certificate,
        val caFingerprint: String = CertificateAuthority.computeSha256Fingerprint(caCertificate)
    )

    // -- LRU cache --------------------------------------------------------

    /**
     * Thread-safe LRU cache of per-host certificates.
     *
     * Uses [LinkedHashMap] with `accessOrder = true` and an overridden
     * [removeEldestEntry] to evict the least-recently-used entry when
     * the cache exceeds [MAX_CACHE_SIZE].
     */
    private val cache: LinkedHashMap<String, HostCertificate> =
        object : LinkedHashMap<String, HostCertificate>(
            /* initialCapacity = */ 32,
            /* loadFactor = */ 0.75f,
            /* accessOrder = */ true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, HostCertificate>?
            ): Boolean {
                val shouldEvict = size > MAX_CACHE_SIZE
                if (shouldEvict) {
                    Log.d(TAG, "Cache full — evicting cert for '${eldest?.key}'")
                }
                return shouldEvict
            }
        }

    // -- Public API -------------------------------------------------------

    /**
     * Returns a [HostCertificate] for the given [hostname].
     *
     * If a cached certificate exists and is still valid, it is returned
     * immediately. Otherwise a new certificate is generated, cached,
     * and returned.
     *
     * @param hostname The destination hostname (e.g. "example.com").
     * @return A [HostCertificate] containing the per-host keypair,
     *   the signed certificate, and the CA certificate.
     * @throws IllegalStateException if the [CertificateAuthority] has
     *   not been initialized.
     */
    @Synchronized
    fun getOrCreate(hostname: String): HostCertificate {
        if (!CertificateAuthority.isInitialized()) {
            CertificateAuthority.awaitInitialization(15_000)
        }
        check(CertificateAuthority.isInitialized()) {
            "CertificateAuthority must be initialized before generating host certificates"
        }

        val activeCaFingerprint = CertificateAuthority.getCertificateFingerprint()

        // Check cache
        val cached = cache[hostname]
        if (cached != null) {
            // Verify the cached cert matches the active CA identity AND is still valid
            if (cached.caFingerprint == activeCaFingerprint) {
                try {
                    cached.certificate.checkValidity()
                    Log.d(TAG, "Cache hit for '$hostname'")
                    return cached
                } catch (_: Exception) {
                    Log.d(TAG, "Cached cert for '$hostname' expired — regenerating")
                    cache.remove(hostname)
                }
            } else {
                Log.w(TAG, "Cached cert for '$hostname' belongs to old CA (${cached.caFingerprint}) — invalidating")
                cache.remove(hostname)
            }
        }

        // Generate new certificate
        val hostCert = generate(hostname)
        cache[hostname] = hostCert
        Log.i(TAG, "Generated cert for '$hostname' (cache size: ${cache.size})")
        return hostCert
    }

    /**
     * Creates an [SSLContext] configured with the given [hostCert]'s
     * keypair and certificate chain.
     *
     * The returned context is suitable for wrapping a socket in server
     * mode — the browser will see the per-host certificate during the
     * TLS handshake.
     *
     * @param hostCert The host certificate from [getOrCreate].
     * @return An initialized [SSLContext].
     */
    fun createSslContext(hostCert: HostCertificate): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)

        // In-memory only password — never persisted
        val password = charArrayOf('p', 'r', 'o', 'x', 'y')
        keyStore.setKeyEntry(
            "host-cert",
            hostCert.keyPair.private,
            password,
            arrayOf<Certificate>(hostCert.certificate, hostCert.caCertificate)
        )

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())
        return sslContext
    }

    /**
     * Returns the current number of cached host certificates.
     * For diagnostics/testing only.
     */
    @Synchronized
    fun cacheSize(): Int = cache.size

    /**
     * Clears the certificate cache. For testing only.
     */
    @Synchronized
    fun clearCache() {
        cache.clear()
        Log.d(TAG, "Certificate cache cleared")
    }

    /**
     * Trims the per-host certificate cache under memory pressure.
     * Evicts expired certificates first, then oldest entries until cache size <= targetSize.
     * Note: This only affects the per-host certificate cache and does NOT affect the persistent root CA.
     */
    @Synchronized
    fun trimCache(targetSize: Int = 10) {
        val initialSize = cache.size
        // 1. Remove expired
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            try {
                entry.value.certificate.checkValidity()
            } catch (_: Exception) {
                iter.remove()
            }
        }
        // 2. Trim oldest if still above target
        while (cache.size > targetSize && cache.isNotEmpty()) {
            val oldestKey = cache.keys.first()
            cache.remove(oldestKey)
        }
        Log.i(TAG, "Trimmed cert cache from $initialSize to ${cache.size} entries")
    }

    // -- Private implementation -------------------------------------------

    /**
     * Generates a new per-host certificate signed by the Development CA.
     */
    private fun generate(hostname: String): HostCertificate {
        // 1. Generate RSA keypair for this host
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(KEY_SIZE, SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        // 2. Certificate dates — 1 minute in the past for clock skew
        val now = Date(System.currentTimeMillis() - 60_000)
        val cal = Calendar.getInstance().apply {
            time = now
            add(Calendar.HOUR_OF_DAY, VALIDITY_HOURS)
        }
        val notAfter = cal.time

        // 3. Subject and Issuer
        val subject = X500Name("CN=$hostname, O=BasicBrowser Proxy")
        val caCert = CertificateAuthority.getCaCertificate()
        val issuer = X500Name(caCert.subjectX500Principal.name)
        val serial = BigInteger(128, SecureRandom())

        // 4. Build the X.509 v3 certificate
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            now,
            notAfter,
            subject,
            keyPair.public
        )

        // End-entity certificate — NOT a CA
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,   // critical
            BasicConstraints(false)
        )

        // Key usage: digital signature + key encipherment
        certBuilder.addExtension(
            Extension.keyUsage,
            true,   // critical
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        // Extended key usage: TLS server authentication
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )

        // Subject Alternative Name — the hostname the browser expects
        val sans = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, hostname)
            )
        )
        certBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            sans
        )

        // 5. Sign with the CA's private key
        val caKeyPair = CertificateAuthority.getKeyPair()
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(caKeyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .getCertificate(certHolder)

        // Verify the certificate was correctly signed by our CA
        cert.verify(caCert.publicKey)

        return HostCertificate(
            keyPair = keyPair,
            certificate = cert,
            caCertificate = caCert,
            caFingerprint = CertificateAuthority.computeSha256Fingerprint(caCert)
        )
    }
}
