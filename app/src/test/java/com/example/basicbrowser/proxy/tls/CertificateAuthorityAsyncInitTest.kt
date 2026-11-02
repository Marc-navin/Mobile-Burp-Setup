package com.example.basicbrowser.proxy.tls

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CertificateAuthorityAsyncInitTest {

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
    fun testAwaitInitialization_whenUninitialized_returnsFalseImmediately() {
        assertFalse(CertificateAuthority.isInitialized())
        assertFalse(CertificateAuthority.isInitializing())

        val startTime = System.currentTimeMillis()
        val result = CertificateAuthority.awaitInitialization(15_000)
        val elapsed = System.currentTimeMillis() - startTime

        assertFalse("Must return false when uninitialized", result)
        assertTrue("Must return immediately (<500ms) without waiting full timeout", elapsed < 500)
    }

    @Test
    fun testInitialize_synchronous_completesAndSetsInitialized() {
        CertificateAuthority.initialize(tlsDir)

        assertTrue(CertificateAuthority.isInitialized())
        assertFalse(CertificateAuthority.isInitializing())
        assertTrue(CertificateAuthority.awaitInitialization(1000))
        assertNotNull(CertificateAuthority.getCaCertificate())
        assertNotNull(CertificateAuthority.getKeyPair())
        assertTrue(CertificateAuthority.isPrivateKeyAvailable())
    }

    @Test
    fun testInitializeAsync_completesAndUnblocksAwaiter() {
        CertificateAuthority.initializeAsync(tlsDir)

        // Wait for async completion
        val ready = CertificateAuthority.awaitInitialization(10_000)
        assertTrue("Async initialization must complete and return true", ready)
        assertTrue(CertificateAuthority.isInitialized())
        assertNotNull(CertificateAuthority.getCaCertificate())
    }

    @Test
    fun testConcurrentAwaitInitialization_multipleThreads_allUnblockSuccessfully() {
        val numThreads = 10
        val executor = Executors.newFixedThreadPool(numThreads)
        val startGate = CountDownLatch(1)
        val successCount = AtomicInteger(0)
        val completionLatch = CountDownLatch(numThreads)

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    startGate.await()
                    if (CertificateAuthority.awaitInitialization(10_000)) {
                        successCount.incrementAndGet()
                    }
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        // Start async initialization, then release waiting threads
        CertificateAuthority.initializeAsync(tlsDir)
        startGate.countDown()

        val allCompleted = completionLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All threads must finish", allCompleted)
        assertEquals("All $numThreads threads must receive true from awaitInitialization", numThreads, successCount.get())
        assertTrue(CertificateAuthority.isInitialized())
    }

    @Test
    fun testConcurrentInitializeCalls_safeAndIdempotent() {
        val numThreads = 8
        val executor = Executors.newFixedThreadPool(numThreads)
        val startGate = CountDownLatch(1)
        val completionLatch = CountDownLatch(numThreads)
        val errors = AtomicInteger(0)

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    startGate.await()
                    if (i % 2 == 0) {
                        CertificateAuthority.initialize(tlsDir)
                    } else {
                        CertificateAuthority.initializeAsync(tlsDir)
                        CertificateAuthority.awaitInitialization(10_000)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        startGate.countDown()
        val allDone = completionLatch.await(15, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All threads must complete", allDone)
        assertEquals("Zero errors during concurrent initialization", 0, errors.get())
        assertTrue(CertificateAuthority.isInitialized())
        assertNotNull(CertificateAuthority.getCaCertificate())
    }

    @Test
    fun testAwaitInitialization_whenFailed_returnsFalse() {
        // Point tlsDir to an invalid path that cannot be created / written
        // E.g., a file named 'tls' so mkdirs fails
        val invalidDir = File(tempFolder.root, "invalid_tls")
        assertTrue(invalidDir.createNewFile()) // File instead of directory

        // Attempting to initialize should set state to FAILED
        try {
            CertificateAuthority.initialize(invalidDir)
        } catch (_: Exception) {
            // Expected
        }

        assertFalse(CertificateAuthority.isInitialized())
        assertFalse(CertificateAuthority.awaitInitialization(1000))
    }

    @Test
    fun testCertificateGenerator_awaitsCaInitializationSafely() {
        // Kick off CA init in background
        CertificateAuthority.initializeAsync(tlsDir)

        // Immediately request host cert — CertificateGenerator.getOrCreate must safely await CA readiness
        val hostCert = CertificateGenerator.getOrCreate("example.com")
        assertNotNull(hostCert)
        assertTrue(hostCert.certificate.subjectX500Principal.name.contains("CN=example.com"))
        assertTrue(CertificateAuthority.isInitialized())
    }
}

