package com.example.basicbrowser.scope

import com.example.basicbrowser.scope.data.ScopeRepository
import com.example.basicbrowser.scope.engine.CidrUtils
import com.example.basicbrowser.scope.engine.ScopeMatcher
import com.example.basicbrowser.scope.model.ScopeRule
import com.example.basicbrowser.scope.model.ScopeRuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScopeMatcherAndRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ----------------------------------------------------
    // 1. DOMAIN & WILDCARD MATCHING
    // ----------------------------------------------------

    @Test
    fun testExactDomain_matchesExact_rejectsSubdomain_rejectsUnrelated() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "example.com")
        )

        // Exact match
        assertTrue(ScopeMatcher.evaluate("http://example.com/api", rules).isInScope)
        assertTrue(ScopeMatcher.evaluate("https://example.com/", rules).isInScope)
        assertTrue(ScopeMatcher.evaluate("example.com", rules).isInScope)

        // Case insensitivity
        assertTrue(ScopeMatcher.evaluate("https://EXAMPLE.COM/path", rules).isInScope)

        // Must NOT match unrelated suffix (notexample.com)
        assertFalse(ScopeMatcher.evaluate("http://notexample.com", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://myexample.com/test", rules).isInScope)

        // Exact domain rule should NOT match arbitrary subdomains
        assertFalse(ScopeMatcher.evaluate("http://api.example.com", rules).isInScope)
    }

    @Test
    fun testSubdomainMatching_matchesSpecificSubdomainOnly() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "api.example.com")
        )

        assertTrue(ScopeMatcher.evaluate("https://api.example.com/v1", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://example.com", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://web.example.com", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://notapi.example.com", rules).isInScope)
    }

    @Test
    fun testWildcardMatching_matchesApexAndSubdomains() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "*.example.com")
        )

        // Apex domain
        assertTrue(ScopeMatcher.evaluate("https://example.com", rules).isInScope)

        // Single-level subdomain
        assertTrue(ScopeMatcher.evaluate("https://api.example.com", rules).isInScope)
        assertTrue(ScopeMatcher.evaluate("https://shop.example.com/cart", rules).isInScope)

        // Multi-level subdomain
        assertTrue(ScopeMatcher.evaluate("https://v2.dev.api.example.com", rules).isInScope)

        // Must NOT match unrelated suffix
        assertFalse(ScopeMatcher.evaluate("https://anotherexample.com", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://fakexample.com", rules).isInScope)
    }

    @Test
    fun testInternationalizedDomainNames_normalizedViaPunycode() {
        // e.g., "münchen.de" -> "xn--mnchen-3ya.de"
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "münchen.de")
        )

        assertTrue(ScopeMatcher.evaluate("https://münchen.de/news", rules).isInScope)
        assertTrue(ScopeMatcher.evaluate("https://xn--mnchen-3ya.de/news", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://berlin.de", rules).isInScope)
    }

    // ----------------------------------------------------
    // 2. EXPLICIT EXCLUSIONS PRECEDENCE
    // ----------------------------------------------------

    @Test
    fun testExplicitExclusion_takesPrecedenceOverInclusion() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "*.corp.com"),
            ScopeRule(type = ScopeRuleType.EXCLUDE, pattern = "logout.corp.com"),
            ScopeRule(type = ScopeRuleType.EXCLUDE, pattern = "tracking.corp.com")
        )

        // Normal in-scope subdomains
        assertTrue(ScopeMatcher.evaluate("https://api.corp.com/users", rules).isInScope)
        assertTrue(ScopeMatcher.evaluate("https://corp.com", rules).isInScope)

        // Excluded subdomains must be OUT OF SCOPE
        val evalLogout = ScopeMatcher.evaluate("https://logout.corp.com/session", rules)
        assertFalse(evalLogout.isInScope)
        assertTrue(evalLogout.reason.contains("Excluded by rule"))

        val evalTracking = ScopeMatcher.evaluate("https://tracking.corp.com/event", rules)
        assertFalse(evalTracking.isInScope)
    }

    // ----------------------------------------------------
    // 3. IP AND CIDR MATCHING
    // ----------------------------------------------------

    @Test
    fun testIpv4Literal_exactMatch() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "192.168.1.100")
        )

        assertTrue(ScopeMatcher.evaluate("http://192.168.1.100:8080/admin", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("http://192.168.1.101:8080/admin", rules).isInScope)
    }

    @Test
    fun testIpv4Cidr_matchesSubnetRange() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "10.0.0.0/24")
        )

        assertTrue(ScopeMatcher.evaluate("http://10.0.0.1", rules).isInScope)
        assertTrue(ScopeMatcher.evaluate("http://10.0.0.254", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("http://10.0.1.1", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("http://192.168.0.1", rules).isInScope)
    }

    @Test
    fun testIpv6LiteralAndCidr() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "2001:db8::/32"),
            ScopeRule(type = ScopeRuleType.EXCLUDE, pattern = "2001:db8::dead:beef")
        )

        // Within CIDR
        assertTrue(ScopeMatcher.evaluate("http://[2001:db8::1]/", rules).isInScope)
        assertTrue(ScopeMatcher.evaluate("http://[2001:db8:ffff::1]/", rules).isInScope)

        // Explicitly excluded IPv6
        assertFalse(ScopeMatcher.evaluate("http://[2001:db8::dead:beef]/", rules).isInScope)

        // Outside IPv6 CIDR
        assertFalse(ScopeMatcher.evaluate("http://[2001:db9::1]/", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("http://[::1]/", rules).isInScope)
    }

    // ----------------------------------------------------
    // 4. SCHEME AND PORT RESTRICTIONS
    // ----------------------------------------------------

    @Test
    fun testSchemeRestriction() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "secure.com", scheme = "https")
        )

        assertTrue(ScopeMatcher.evaluate("https://secure.com/login", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("http://secure.com/login", rules).isInScope)
    }

    @Test
    fun testPortRestriction() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "internal.net", port = 8443)
        )

        assertTrue(ScopeMatcher.evaluate("https://internal.net:8443/console", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://internal.net:443/console", rules).isInScope)
        assertFalse(ScopeMatcher.evaluate("https://internal.net/console", rules).isInScope)
    }

    // ----------------------------------------------------
    // 5. EMPTY SCOPE CONFIGURATION
    // ----------------------------------------------------

    @Test
    fun testEmptyScopeConfiguration_defaultsToUnrestricted() {
        val emptyRules = emptyList<ScopeRule>()
        val eval = ScopeMatcher.evaluate("https://any-domain.com/path", emptyRules)

        assertTrue("Empty scope must default to in-scope", eval.isInScope)
        assertTrue(eval.reason.contains("No include rules configured"))
    }

    @Test
    fun testDisabledRules_areIgnored() {
        val rules = listOf(
            ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "target.com", enabled = false),
            ScopeRule(type = ScopeRuleType.EXCLUDE, pattern = "bad.com", enabled = false)
        )

        // Since the only include rule is disabled, include rules count as empty -> unrestricted
        val evalTarget = ScopeMatcher.evaluate("https://target.com", rules)
        assertTrue(evalTarget.isInScope)

        // Disabled exclude rule should NOT exclude bad.com
        val evalBad = ScopeMatcher.evaluate("https://bad.com", rules)
        assertTrue(evalBad.isInScope)
    }

    // ----------------------------------------------------
    // 6. PERSISTENCE & RESTORATION
    // ----------------------------------------------------

    @Test
    fun testRepository_persistenceAndRestoration() {
        val storageFile = File(tempFolder.root, "test_scope_rules.json")
        val repo1 = ScopeRepository(storageFile)

        assertEquals(0, repo1.rules.value.size)

        val rule1 = ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "*.target.org", comment = "Main target")
        val rule2 = ScopeRule(type = ScopeRuleType.EXCLUDE, pattern = "admin.target.org", port = 9090)

        repo1.addRule(rule1)
        repo1.addRule(rule2)

        assertEquals(2, repo1.rules.value.size)
        assertTrue(storageFile.exists())

        // Create new repository pointing to the same file
        val repo2 = ScopeRepository(storageFile)
        assertEquals(2, repo2.rules.value.size)

        val loaded1 = repo2.rules.value.find { it.pattern == "*.target.org" }
        assertNotNull(loaded1)
        assertEquals(ScopeRuleType.INCLUDE, loaded1?.type)
        assertEquals("Main target", loaded1?.comment)

        val loaded2 = repo2.rules.value.find { it.pattern == "admin.target.org" }
        assertNotNull(loaded2)
        assertEquals(9090, loaded2?.port)

        // Toggle rule in repo2
        repo2.toggleRule(loaded1!!.id, false)
        assertFalse(repo2.rules.value.find { it.id == loaded1.id }!!.enabled)

        // Reload repo3
        val repo3 = ScopeRepository(storageFile)
        assertFalse(repo3.rules.value.find { it.id == loaded1.id }!!.enabled)

        // Delete rule
        repo3.deleteRule(loaded2!!.id)
        assertEquals(1, repo3.rules.value.size)

        // Clear scope
        repo3.clearScope()
        assertEquals(0, repo3.rules.value.size)

        val repo4 = ScopeRepository(storageFile)
        assertEquals(0, repo4.rules.value.size)
    }
}

