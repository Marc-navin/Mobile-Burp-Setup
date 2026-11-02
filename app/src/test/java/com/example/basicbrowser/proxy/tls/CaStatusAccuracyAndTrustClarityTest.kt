package com.example.basicbrowser.proxy.tls

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class CaStatusAccuracyAndTrustClarityTest {

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
    fun test1_caInitialized_butNeverExported() {
        CertificateAuthority.initialize(tlsDir)

        assertTrue("CA must be initialized", CertificateAuthority.isInitialized())
        assertFalse("CA must not be marked as exported", CertificateAuthority.isCaExported())
        assertNull("Exported fingerprint must be null", CertificateAuthority.getExportedCertificateFingerprint())
        assertEquals("Export status must be NOT_EXPORTED",
            CertificateAuthority.ExportStatus.NOT_EXPORTED, CertificateAuthority.getExportStatus())
        assertFalse("hasCaChangedSinceLastExport must be false when never exported",
            CertificateAuthority.hasCaChangedSinceLastExport())
        assertEquals("Installation status must be NOT_VERIFIED",
            CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())
    }

    @Test
    fun test2_caExported_butInstallationNotVerified() {
        CertificateAuthority.initialize(tlsDir)
        val pem = CertificateAuthority.exportCaCertificatePem()
        assertTrue(pem.contains("BEGIN CERTIFICATE"))

        assertTrue("CA must be marked as exported", CertificateAuthority.isCaExported())
        assertNotNull("Exported fingerprint must be recorded", CertificateAuthority.getExportedCertificateFingerprint())
        assertEquals("Export status must be EXPORTED_MATCHES",
            CertificateAuthority.ExportStatus.EXPORTED_MATCHES, CertificateAuthority.getExportStatus())
        // Crucial requirement: Export does NOT imply installation
        assertEquals("Installation status must remain NOT_VERIFIED after export",
            CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())
    }

    @Test
    fun test3_caExported_andThenRegenerated() {
        // Initial setup and export
        CertificateAuthority.initialize(tlsDir)
        val fpA = CertificateAuthority.getCertificateFingerprint()
        CertificateAuthority.exportCaCertificatePem()

        // Corrupt keystore and re-initialize
        val keystoreFile = File(tlsDir, "dev_ca.p12")
        FileOutputStream(keystoreFile).use { fos ->
            fos.write(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        }

        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)
        val fpB = CertificateAuthority.getCertificateFingerprint()

        assertNotEquals("New CA must have different fingerprint", fpA, fpB)
        assertTrue("CA must be marked as regenerated", CertificateAuthority.isCaRegenerated())
        assertTrue("hasCaChangedSinceLastExport must be true after regeneration",
            CertificateAuthority.hasCaChangedSinceLastExport())
        assertEquals("Export status must be EXPORTED_MISMATCH",
            CertificateAuthority.ExportStatus.EXPORTED_MISMATCH, CertificateAuthority.getExportStatus())
        assertEquals("Installation status must be NOT_VERIFIED",
            CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())
    }

    @Test
    fun test4_activeFingerprintMatchesExportedFingerprint() {
        CertificateAuthority.initialize(tlsDir)
        val currentFp = CertificateAuthority.getCertificateFingerprint()
        CertificateAuthority.exportCaCertificatePem()

        val exportedFp = CertificateAuthority.getExportedCertificateFingerprint()
        assertEquals("Active fingerprint must match exported fingerprint", currentFp, exportedFp)
        assertFalse("hasCaChangedSinceLastExport must be false when fingerprints match",
            CertificateAuthority.hasCaChangedSinceLastExport())
        assertEquals("Export status must be EXPORTED_MATCHES",
            CertificateAuthority.ExportStatus.EXPORTED_MATCHES, CertificateAuthority.getExportStatus())
    }

    @Test
    fun test5_activeFingerprintDiffersFromExportedFingerprint() {
        CertificateAuthority.initialize(tlsDir)
        CertificateAuthority.markCaExported()

        // Manually rewrite exported_fingerprint file with a stale fingerprint
        val exportedFile = File(tlsDir, "dev_ca.exported_fingerprint")
        exportedFile.writeText("11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00")

        assertTrue("hasCaChangedSinceLastExport must be true when fingerprints differ",
            CertificateAuthority.hasCaChangedSinceLastExport())
        assertEquals("Export status must be EXPORTED_MISMATCH",
            CertificateAuthority.ExportStatus.EXPORTED_MISMATCH, CertificateAuthority.getExportStatus())
    }

    @Test
    fun test6_reExportAfterCaRegeneration_restoresMatchingStatus() {
        // Corrupt and regenerate
        CertificateAuthority.initialize(tlsDir)
        CertificateAuthority.exportCaCertificatePem()

        val keystoreFile = File(tlsDir, "dev_ca.p12")
        FileOutputStream(keystoreFile).use { fos -> fos.write(byteArrayOf(0x99.toByte())) }

        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)
        assertTrue(CertificateAuthority.hasCaChangedSinceLastExport())

        // Re-export new certificate
        CertificateAuthority.exportCaCertificatePem()

        assertFalse("Mismatch must be cleared after re-export",
            CertificateAuthority.hasCaChangedSinceLastExport())
        assertEquals("Export status must return to EXPORTED_MATCHES",
            CertificateAuthority.ExportStatus.EXPORTED_MATCHES, CertificateAuthority.getExportStatus())
        assertEquals("Exported fingerprint must now match active fingerprint",
            CertificateAuthority.getCertificateFingerprint(), CertificateAuthority.getExportedCertificateFingerprint())
    }

    @Test
    fun test7_restartApplicationAndVerifyPersistedStatus() {
        CertificateAuthority.initialize(tlsDir)
        CertificateAuthority.exportCaCertificatePem()
        val expectedFp = CertificateAuthority.getCertificateFingerprint()

        // Simulate app restart
        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)

        assertTrue(CertificateAuthority.isInitialized())
        assertTrue(CertificateAuthority.isCaExported())
        assertEquals("Persisted exported fingerprint must survive restart",
            expectedFp, CertificateAuthority.getExportedCertificateFingerprint())
        assertEquals("Export status must remain EXPORTED_MATCHES after restart",
            CertificateAuthority.ExportStatus.EXPORTED_MATCHES, CertificateAuthority.getExportStatus())
    }

    @Test
    fun test8_ensureNoStatusIncorrectlyClaimsAndroidInstallation() {
        // 1. Uninitialized
        assertEquals(CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())

        // 2. Initialized
        CertificateAuthority.initialize(tlsDir)
        assertEquals(CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())

        // 3. Exported
        CertificateAuthority.exportCaCertificatePem()
        assertEquals(CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())

        // 4. Reloaded from disk
        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)
        assertEquals(CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())

        // 5. Corrupted and regenerated
        val keystoreFile = File(tlsDir, "dev_ca.p12")
        FileOutputStream(keystoreFile).use { fos -> fos.write(byteArrayOf(0x42)) }
        CertificateAuthority.resetForTesting()
        CertificateAuthority.initialize(tlsDir)
        assertEquals(CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())

        // 6. Re-exported
        CertificateAuthority.exportCaCertificatePem()
        assertEquals(CertificateAuthority.InstallationStatus.NOT_VERIFIED, CertificateAuthority.getInstallationStatus())
    }
}

