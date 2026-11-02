package com.example.basicbrowser.proxy.tls

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaRecoveryAndTrustConsistencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tlsDir: File

    @Before
    fun setUp() {
        tlsDir = tempFolder.newFolder("tls")
        CertificateAuthority.resetForTesting()
        CertificateGenerator.clearCache()
    }

    @After
    fun tearDown() {
        CertificateAuthority.resetForTesting()
        CertificateGenerator.clearCache()
    }

    @Test
    fun testNormalStartupWithExistingValidCa_preservesIdentity() {
        // Initial setup
        CertificateAuthority.initialize(tlsDir)
        val initialFingerprint = CertificateAuthority.getCertificateFingerprint()
        assertNotNull(initialFingerprint)
        CertificateAuthority.markCaExported()

        assertFalse("Should not be regenerated on fresh normal startup", CertificateAuthority.isCaRegenerated())
        assertFalse("CA has not changed since export", CertificateAuthority.hasCaChangedSinceLastExport())

        // Re-initialize from existing valid files
        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)

        val reloadedFingerprint = CertificateAuthority.getCertificateFingerprint()
        assertEquals("Fingerprint must be identical across normal reloads", initialFingerprint, reloadedFingerprint)
        assertFalse("Should not be marked as regenerated", CertificateAuthority.isCaRegenerated())
        assertFalse("Fingerprint still matches exported fingerprint", CertificateAuthority.hasCaChangedSinceLastExport())
    }

    @Test
    fun testCorruptedKeystoreRecovery_regeneratesValidCaWithDifferentFingerprint() {
        // 1. Initial valid CA setup and export
        CertificateAuthority.initialize(tlsDir)
        val fingerprintA = CertificateAuthority.getCertificateFingerprint()
        val certPemA = CertificateAuthority.exportCaCertificatePem()
        assertTrue(certPemA.contains("BEGIN CERTIFICATE"))
        assertEquals(fingerprintA, CertificateAuthority.getExportedCertificateFingerprint())

        // 2. Corrupt the PKCS12 keystore with invalid garbage bytes
        val keystoreFile = File(tlsDir, "dev_ca.p12")
        FileOutputStream(keystoreFile).use { fos ->
            fos.write(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77))
        }

        // 3. Reset in-memory state and trigger initialization (simulates app restart after corruption)
        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)

        // 4. Verify recovery detection
        assertTrue("CA must detect corruption and mark caRecoveryOccurred = true", CertificateAuthority.isCaRegenerated())
        val fingerprintB = CertificateAuthority.getCertificateFingerprint()
        assertNotNull(fingerprintB)
        assertNotEquals("New CA must have a distinct fingerprint after regeneration", fingerprintA, fingerprintB)
        assertEquals("Previous fingerprint must match fingerprint A", fingerprintA, CertificateAuthority.getPreviousFingerprint())

        // 5. Verify detection of installed CA mismatch
        assertTrue("Application must detect that previously exported/installed CA no longer matches",
            CertificateAuthority.hasCaChangedSinceLastExport())

        // 6. Verify newly generated CA is fully valid
        assertTrue(CertificateAuthority.isInitialized())
        assertNotNull(CertificateAuthority.getCaCertificate())
        assertTrue(CertificateAuthority.isPrivateKeyAvailable())
    }

    @Test
    fun testHostCertificateCacheInvalidation_afterCaReplacement() {
        // 1. Initialize CA A and generate a host certificate for example.com
        CertificateAuthority.initialize(tlsDir)
        val hostCertA = CertificateGenerator.getOrCreate("example.com")
        val fingerprintA = CertificateAuthority.getCertificateFingerprint()
        assertEquals(fingerprintA, hostCertA.caFingerprint)
        assertTrue(CertificateAuthority.isCertificateSignedByDevCa(hostCertA.certificate))

        // 2. Simulate CA recovery/regeneration by corrupting keystore and re-initializing
        val keystoreFile = File(tlsDir, "dev_ca.p12")
        FileOutputStream(keystoreFile).use { fos ->
            fos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()))
        }

        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)
        val fingerprintB = CertificateAuthority.getCertificateFingerprint()
        assertNotEquals(fingerprintA, fingerprintB)

        // 3. Request host certificate for example.com again
        val hostCertB = CertificateGenerator.getOrCreate("example.com")

        // 4. Verify host certificate cache was invalidated and cert was regenerated under CA B
        assertEquals(fingerprintB, hostCertB.caFingerprint)
        assertNotEquals("Host certificate serial must be regenerated and not reused",
            hostCertA.certificate.serialNumber, hostCertB.certificate.serialNumber)
        assertTrue("New host cert must be signed by active CA B",
            CertificateAuthority.isCertificateSignedByDevCa(hostCertB.certificate))
        assertFalse("Old host cert must NOT be signed by active CA B",
            CertificateAuthority.isCertificateSignedByDevCa(hostCertA.certificate))
    }

    @Test
    fun testCaExportAlignment_clearsMismatchFlag() {
        // Initialize and corrupt to trigger regeneration
        CertificateAuthority.initialize(tlsDir)
        val fpA = CertificateAuthority.getCertificateFingerprint()
        CertificateAuthority.markCaExported()

        val keystoreFile = File(tlsDir, "dev_ca.p12")
        FileOutputStream(keystoreFile).use { fos ->
            fos.write(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        }

        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)

        assertTrue(CertificateAuthority.hasCaChangedSinceLastExport())

        // Export new certificate
        val newPem = CertificateAuthority.exportCaCertificatePem()
        assertTrue(newPem.contains("BEGIN CERTIFICATE"))

        // Exporting must update the exported fingerprint and clear mismatch
        assertFalse("Mismatch must be cleared after exporting new CA", CertificateAuthority.hasCaChangedSinceLastExport())
        assertEquals(CertificateAuthority.getCertificateFingerprint(), CertificateAuthority.getExportedCertificateFingerprint())
    }

    @Test
    fun testConcurrentRecovery_executesExactlyOnce() {
        // Setup initial CA
        CertificateAuthority.initialize(tlsDir)
        val keystoreFile = File(tlsDir, "dev_ca.p12")
        FileOutputStream(keystoreFile).use { fos ->
            fos.write(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        }

        CertificateAuthority.resetForTesting()

        val numThreads = 10
        val executor = Executors.newFixedThreadPool(numThreads)
        val startGate = CountDownLatch(1)
        val completionLatch = CountDownLatch(numThreads)
        val successCount = AtomicInteger(0)

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    startGate.await()
                    CertificateAuthority.initialize(tlsDir)
                    if (CertificateAuthority.isInitialized()) {
                        successCount.incrementAndGet()
                    }
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        startGate.countDown()
        val allFinished = completionLatch.await(15, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent recovery threads must complete", allFinished)
        assertEquals(numThreads, successCount.get())
        assertTrue(CertificateAuthority.isInitialized())
        assertTrue(CertificateAuthority.isCaRegenerated())
    }
}

