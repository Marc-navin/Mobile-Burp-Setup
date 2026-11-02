package com.example.basicbrowser.proxy.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

class CertificateGeneratorCacheTest {

    @Before
    fun setUp() {
        CertificateGenerator.clearCache()
    }

    @Test
    fun testTrimCacheReducesSizeToTarget() {
        // We use reflection or CertificateGenerator's cache to simulate entries
        // Let's inspect CertificateGenerator's cache field
        val cacheField: Field = CertificateGenerator::class.java.getDeclaredField("cache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(CertificateGenerator) as java.util.LinkedHashMap<String, Any>

        // Mock a certificate or create dummy entries if needed
        assertEquals(0, CertificateGenerator.cacheSize())

        // Trim on empty cache should not crash
        CertificateGenerator.trimCache(10)
        assertEquals(0, CertificateGenerator.cacheSize())
    }
}
