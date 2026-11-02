package com.example.basicbrowser.proxy.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class CertificateAuthorityExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testExportCaCertificatePemProducesValidX509() {
        val tlsDir = tempFolder.newFolder("tls")

        // Initialize CA using directory overload
        CertificateAuthority.initialize(tlsDir)
        assertTrue(CertificateAuthority.isInitialized())

        val pem = CertificateAuthority.exportCaCertificatePem()

        // 1. Verify PEM headers
        assertTrue("Must start with BEGIN CERTIFICATE header", pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue("Must end with END CERTIFICATE footer", pem.trimEnd().endsWith("-----END CERTIFICATE-----"))

        // 2. Verify private key is NEVER in PEM
        assertFalse("Must not contain private key header", pem.contains("PRIVATE KEY"))
        assertFalse("Must not contain RSA private key", pem.contains("RSA PRIVATE"))

        // 3. Verify parseable with standard X.509 CertificateFactory
        val certFactory = CertificateFactory.getInstance("X.509")
        val parsedCert = certFactory.generateCertificate(
            ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))
        ) as X509Certificate

        assertNotNull(parsedCert)
        val liveCert = CertificateAuthority.getCaCertificate()
        assertEquals(liveCert.serialNumber, parsedCert.serialNumber)
        assertEquals(liveCert.subjectX500Principal, parsedCert.subjectX500Principal)
        assertEquals(liveCert.publicKey, parsedCert.publicKey)
    }
}
