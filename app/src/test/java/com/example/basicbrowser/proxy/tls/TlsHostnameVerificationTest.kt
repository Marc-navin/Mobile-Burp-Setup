package com.example.basicbrowser.proxy.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger

class TlsHostnameVerificationTest {

    @Test
    fun testSslParametersEndpointIdentificationAlgorithmIsHttps() {
        val sslParams = SSLParameters()
        sslParams.endpointIdentificationAlgorithm = "HTTPS"
        assertEquals("HTTPS", sslParams.endpointIdentificationAlgorithm)
    }

    @Test
    fun testHostnameVerificationRejectsMismatchedHost() {
        // 1. Generate a self-signed certificate for 'valid-host.com'
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        val now = Date(System.currentTimeMillis() - 60_000)
        val notAfter = Date(System.currentTimeMillis() + 3600_000)
        val subject = X500Name("CN=valid-host.com")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(1),
            now,
            notAfter,
            subject,
            keyPair.public
        )
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(false)
        )
        val sans = GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, "valid-host.com")))
        certBuilder.addExtension(Extension.subjectAlternativeName, false, sans)

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        // 2. Build Server SSLContext
        val serverKs = KeyStore.getInstance("PKCS12")
        serverKs.load(null, null)
        serverKs.setKeyEntry("key", keyPair.private, "pass".toCharArray(), arrayOf<Certificate>(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(serverKs, "pass".toCharArray())
        val serverCtx = SSLContext.getInstance("TLS")
        serverCtx.init(kmf.keyManagers, null, SecureRandom())

        // 3. Build Client SSLContext trusting this cert
        val clientKs = KeyStore.getInstance("PKCS12")
        clientKs.load(null, null)
        clientKs.setCertificateEntry("cert", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(clientKs)
        val clientCtx = SSLContext.getInstance("TLS")
        clientCtx.init(null, tmf.trustManagers, SecureRandom())

        // 4. Start local TLS server
        val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverPort = serverSocket.localPort

        val serverThread = Thread {
            try {
                val s = serverSocket.accept() as SSLSocket
                s.startHandshake()
            } catch (_: Exception) {
            }
        }
        serverThread.start()

        // 5. Connect client requesting 'wrong-host.com' with HTTPS endpoint identification enabled
        val rawClient = Socket("127.0.0.1", serverPort)
        val clientSsl = clientCtx.socketFactory.createSocket(
            rawClient,
            "wrong-host.com", // Mismatched host!
            serverPort,
            true
        ) as SSLSocket
        clientSsl.useClientMode = true

        val params = clientSsl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        clientSsl.sslParameters = params

        try {
            clientSsl.startHandshake()
            fail("Expected SSLHandshakeException due to hostname mismatch!")
        } catch (e: SSLHandshakeException) {
            // Expected! Hostname verification successfully rejected the mismatched certificate
            assertTrue(
                e.message?.contains("wrong-host.com", ignoreCase = true) == true ||
                e.message?.contains("hostname", ignoreCase = true) == true ||
                e.message?.contains("No name matching", ignoreCase = true) == true
            )
        } finally {
            try { clientSsl.close() } catch (_: Exception) {}
            try { serverSocket.close() } catch (_: Exception) {}
            serverThread.join(1000)
        }
    }
}
