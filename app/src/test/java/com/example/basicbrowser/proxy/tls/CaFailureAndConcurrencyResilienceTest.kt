package com.example.basicbrowser.proxy.tls

import com.example.basicbrowser.proxy.ConnectHandler
import com.example.basicbrowser.proxy.HttpProxyHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaFailureAndConcurrencyResilienceTest {

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

    // ------------------------------------------------------------------ //
    // 1. Concurrency Testing: 1, 5, 10, 20 Concurrent Workers
    // ------------------------------------------------------------------ //

    @Test
    fun testSingleHttpsRequestDuringCaInitialization() {
        testConcurrentWorkers(1)
    }

    @Test
    fun testFiveConcurrentHttpsRequestsDuringCaInitialization() {
        testConcurrentWorkers(5)
    }

    @Test
    fun testTenConcurrentHttpsRequestsDuringCaInitialization() {
        testConcurrentWorkers(10)
    }

    @Test
    fun testTwentyConcurrentHttpsRequestsDuringCaInitialization() {
        testConcurrentWorkers(20)
    }

    private fun testConcurrentWorkers(numWorkers: Int) {
        val executor = Executors.newFixedThreadPool(numWorkers)
        val startGate = CountDownLatch(1)
        val completionLatch = CountDownLatch(numWorkers)
        val successCount = AtomicInteger(0)
        val certs = arrayOfNulls<CertificateGenerator.HostCertificate>(numWorkers)

        for (i in 0 until numWorkers) {
            val index = i
            executor.submit {
                try {
                    startGate.await()
                    // All workers request host certificate for "example.com"
                    val cert = CertificateGenerator.getOrCreate("example.com")
                    certs[index] = cert
                    successCount.incrementAndGet()
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        // Kick off CA initialization asynchronously in background
        CertificateAuthority.initializeAsync(tlsDir)

        // Release all concurrent workers simultaneously
        startGate.countDown()

        val finishedInTime = completionLatch.await(15, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All $numWorkers workers must finish without deadlock", finishedInTime)
        assertEquals("All $numWorkers workers must successfully acquire a certificate", numWorkers, successCount.get())
        assertTrue("CA must be initialized", CertificateAuthority.isInitialized())

        // Verify CA initialized exactly once and all workers received the EXACT same cached certificate
        val firstCert = certs[0]
        assertNotNull(firstCert)
        for (i in 1 until numWorkers) {
            assertEquals("Worker $i must receive the cached host certificate without duplicate generation",
                firstCert!!.certificate.serialNumber, certs[i]!!.certificate.serialNumber)
        }
        assertEquals("CertificateGenerator cache must hold exactly 1 certificate for example.com",
            1, CertificateGenerator.cacheSize())
    }

    // ------------------------------------------------------------------ //
    // 2. Failure Handling & State Machine Integrity
    // ------------------------------------------------------------------ //

    @Test
    fun testInitializationFailureTransitionsToFailedAndUnblocksLatch() {
        // Point to an invalid path that cannot be created / written
        val invalidDir = File(tempFolder.root, "uncreatable_dir_test")
        assertTrue(invalidDir.createNewFile()) // File blocks directory creation

        CertificateAuthority.initializeAsync(invalidDir)

        val unblocked = CertificateAuthority.awaitInitialization(3000)
        assertFalse("Initialization must fail", unblocked)
        assertFalse("isInitialized must be false", CertificateAuthority.isInitialized())
        assertFalse("isInitializing must be false", CertificateAuthority.isInitializing())
        assertNotNull("initError must be populated", CertificateAuthority.getInitializationError())
    }

    @Test
    fun testCorruptedKeystoreRecoveryOrRegeneration() {
        // Create an existing corrupted dev_ca.p12 with random garbage bytes
        val keystoreFile = File(tlsDir, "dev_ca.p12")
        val passwordFile = File(tlsDir, "dev_ca.pwd")
        passwordFile.writeText("invalidpassword12345678901234567")
        FileOutputStream(keystoreFile).use { fos ->
            fos.write(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
        }

        // loadExistingCa catches the failure and falls back to generateNewCa
        CertificateAuthority.initialize(tlsDir)

        assertTrue("Must regenerate valid CA on corrupted keystore", CertificateAuthority.isInitialized())
        assertNotNull(CertificateAuthority.getCaCertificate())
        assertTrue(CertificateAuthority.isPrivateKeyAvailable())
    }

    // ------------------------------------------------------------------ //
    // 3. Timeout and Connection Cleanup
    // ------------------------------------------------------------------ //

    @Test
    fun testConnectHandler_failsGracefullyWhenCaInitializationTimesOut() {
        val clientSocket = Socket()
        val clientIn = BufferedInputStream(ByteArrayInputStream(ByteArray(0)))
        val clientOut = ByteArrayOutputStream()

        val targetAddr = InetAddress.getByName("127.0.0.1")
        val handler = ConnectHandler(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            dnsResolver = { arrayOf(targetAddr) },
            socketConnector = { _, _, _ -> Socket() },
            // Simulate CA initialization timeout
            caReadinessChecker = { false }
        )

        handler.handle("example.com:443", "HTTP/1.1")
        val output = clientOut.toString(Charsets.US_ASCII.name())

        assertTrue("Must send 502 Bad Gateway", output.contains("502 Bad Gateway"))
        assertFalse("Must NOT send 200 Connection Established", output.contains("200 Connection Established"))
    }

    // ------------------------------------------------------------------ //
    // 4. HTTP Forwarding Resilience Under CA Failure
    // ------------------------------------------------------------------ //

    @Test
    fun testHttpForwardingContinuesWorkingWhenCaFails() {
        // Simulate CA failure
        val invalidDir = File(tempFolder.root, "failed_ca_dir")
        assertTrue(invalidDir.createNewFile())

        try {
            CertificateAuthority.initialize(invalidDir)
        } catch (_: Exception) {}

        assertFalse("CA is uninitialized/failed", CertificateAuthority.isInitialized())

        // HTTP proxy handler transfer utilities function with zero dependency on CA
        val responseBody = "HTTP/1.1 200 OK\r\nContent-Length: 13\r\n\r\nHello, World!"
        val input = ByteArrayInputStream(responseBody.toByteArray(Charsets.US_ASCII))
        val output = ByteArrayOutputStream()
        val capture = ByteArrayOutputStream()

        val truncated = HttpProxyHandler.transfer(input, output, responseBody.length.toLong(), capture)

        assertFalse("Transfer should not be truncated", truncated)
        assertEquals(responseBody, output.toString(Charsets.US_ASCII.name()))
        assertEquals(responseBody, capture.toString(Charsets.US_ASCII.name()))
    }
}

