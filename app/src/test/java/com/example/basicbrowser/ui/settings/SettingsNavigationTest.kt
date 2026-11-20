package com.example.basicbrowser.ui.settings

import com.example.basicbrowser.data.model.HttpHistoryItem
import com.example.basicbrowser.data.repository.HttpHistoryRepository
import com.example.basicbrowser.filter.data.TrafficFilterRepository
import com.example.basicbrowser.filter.engine.TrafficFilterEngine
import com.example.basicbrowser.filter.model.InterceptFilterSettings
import com.example.basicbrowser.filter.model.ScopeFilterOption
import com.example.basicbrowser.proxy.ProxyServer
import com.example.basicbrowser.proxy.tls.CertificateGenerator
import com.example.basicbrowser.scope.data.ScopeRepository
import com.example.basicbrowser.scope.model.ScopeRule
import com.example.basicbrowser.scope.model.ScopeRuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsNavigationTest {

    // Mirror the Screen model for navigation logic testing
    sealed class TestScreen {
        object Browser : TestScreen()
        object Intercept : TestScreen()
        data class InterceptDetail(val requestId: String) : TestScreen()
        object Repeater : TestScreen()
        object VariationLab : TestScreen()
        object History : TestScreen()
        data class HistoryDetail(val itemId: String) : TestScreen()
        object Settings : TestScreen()
        object TargetScope : TestScreen()
        object CaVerification : TestScreen()
        object ProxySettings : TestScreen()
        object TrafficFilters : TestScreen()
        object GeneralSettings : TestScreen()
    }

    private fun isSettingsActive(current: TestScreen, previous: TestScreen?): Boolean {
        return when (current) {
            TestScreen.Settings,
            TestScreen.ProxySettings,
            TestScreen.TrafficFilters,
            TestScreen.GeneralSettings,
            TestScreen.CaVerification -> true
            TestScreen.TargetScope -> previous == TestScreen.Settings || previous in listOf(
                TestScreen.ProxySettings,
                TestScreen.TrafficFilters,
                TestScreen.GeneralSettings,
                TestScreen.CaVerification
            )
            else -> false
        }
    }

    @Before
    fun setUp() {
        HttpHistoryRepository.getInstance().clear()
        ScopeRepository.getInstance().clearScope()
        TrafficFilterRepository.getInstance().resetHistoryFilter()
        TrafficFilterRepository.getInstance().updateInterceptFilter(InterceptFilterSettings())
    }

    // ----------------------------------------------------
    // 1. BOTTOM NAVIGATION & ACTIVE SELECTION TESTS
    // ----------------------------------------------------

    @Test
    fun testSettingsTab_activeForSettingsOverviewAndSubscreens() {
        assertFalse(isSettingsActive(TestScreen.Browser, null))
        assertFalse(isSettingsActive(TestScreen.Intercept, null))
        assertFalse(isSettingsActive(TestScreen.Repeater, null))
        assertFalse(isSettingsActive(TestScreen.VariationLab, null))
        assertFalse(isSettingsActive(TestScreen.History, null))

        // Settings Overview
        assertTrue(isSettingsActive(TestScreen.Settings, null))

        // CA Management subscreen
        assertTrue(isSettingsActive(TestScreen.CaVerification, TestScreen.Settings))

        // Proxy Settings subscreen
        assertTrue(isSettingsActive(TestScreen.ProxySettings, TestScreen.Settings))

        // Traffic Filters subscreen
        assertTrue(isSettingsActive(TestScreen.TrafficFilters, TestScreen.Settings))

        // General Settings subscreen
        assertTrue(isSettingsActive(TestScreen.GeneralSettings, TestScreen.Settings))
    }

    @Test
    fun testTargetScope_contextualTabSelection() {
        // When opened from Settings, Settings tab remains selected
        assertTrue(isSettingsActive(TestScreen.TargetScope, TestScreen.Settings))
        assertTrue(isSettingsActive(TestScreen.TargetScope, TestScreen.TrafficFilters))

        // When opened from History toolbar, Settings tab is NOT selected (History owns it)
        assertFalse(isSettingsActive(TestScreen.TargetScope, TestScreen.History))

        // When opened from Intercept toolbar, Settings tab is NOT selected (Intercept owns it)
        assertFalse(isSettingsActive(TestScreen.TargetScope, TestScreen.Intercept))

        // When opened from Browser, Settings tab is NOT selected
        assertFalse(isSettingsActive(TestScreen.TargetScope, TestScreen.Browser))
    }

    @Test
    fun testBackNavigation_returnsToOriginScreen() {
        // Navigating to TargetScope from History
        var current: TestScreen = TestScreen.History
        var previous: TestScreen? = null

        val navigateTo = { next: TestScreen ->
            previous = current
            current = next
        }

        navigateTo(TestScreen.TargetScope)
        assertEquals(TestScreen.TargetScope, current)
        assertEquals(TestScreen.History, previous)

        // Back action returns to History
        current = previous ?: TestScreen.Settings
        assertEquals(TestScreen.History, current)

        // Navigating to TargetScope from Settings
        navigateTo(TestScreen.Settings)
        navigateTo(TestScreen.TargetScope)
        assertEquals(TestScreen.TargetScope, current)
        assertEquals(TestScreen.Settings, previous)

        // Back action returns to Settings
        current = previous ?: TestScreen.Settings
        assertEquals(TestScreen.Settings, current)
    }

    // ----------------------------------------------------
    // 2. PROXY SETTINGS MODEL & DEFAULTS
    // ----------------------------------------------------

    @Test
    fun testProxyServer_defaultsAndBounds() {
        val proxy = ProxyServer.getInstance()
        assertNotNull(proxy)

        assertEquals(4, ProxyServer.DEFAULT_CORE_WORKERS)
        assertEquals(16, ProxyServer.DEFAULT_MAX_WORKERS)
        assertEquals(32, ProxyServer.DEFAULT_QUEUE_CAPACITY)
        assertEquals(60L, ProxyServer.KEEP_ALIVE_SECONDS)
    }

    // ----------------------------------------------------
    // 3. TRAFFIC FILTERS REPOSITORY & INTERCEPT ENGINE SYNC
    // ----------------------------------------------------

    @Test
    fun testTrafficFilters_synchronizationAndReset() {
        val repo = TrafficFilterRepository.getInstance()

        // Modify history filter
        repo.updateHistoryFilter(
            repo.historyFilterState.value.copy(
                hideImages = true,
                hideCssAndJs = true,
                scopeFilter = ScopeFilterOption.SHOW_ONLY_IN_SCOPE
            )
        )

        assertTrue(repo.historyFilterState.value.hideImages)
        assertTrue(repo.historyFilterState.value.hideCssAndJs)
        assertEquals(ScopeFilterOption.SHOW_ONLY_IN_SCOPE, repo.historyFilterState.value.scopeFilter)

        // Modify intercept filter
        val interceptSettings = InterceptFilterSettings(
            interceptOnlyInScope = true,
            filterImages = true,
            filterCss = true,
            filterJs = true,
            filterOtherResources = true
        )
        repo.updateInterceptFilter(interceptSettings)

        assertTrue(repo.interceptFilterSettings.value.interceptOnlyInScope)
        assertTrue(repo.interceptFilterSettings.value.filterImages)

        // Reset all
        repo.resetHistoryFilter()
        repo.updateInterceptFilter(InterceptFilterSettings())

        assertFalse(repo.historyFilterState.value.hideImages)
        assertFalse(repo.historyFilterState.value.hideCssAndJs)
        assertEquals(ScopeFilterOption.SHOW_ALL, repo.historyFilterState.value.scopeFilter)
        assertFalse(repo.interceptFilterSettings.value.interceptOnlyInScope)
        assertFalse(repo.interceptFilterSettings.value.filterImages)
    }

    // ----------------------------------------------------
    // 4. GENERAL SETTINGS ACTIONS
    // ----------------------------------------------------

    @Test
    fun testGeneralSettings_historyClear() {
        val repo = HttpHistoryRepository.getInstance()
        val item = HttpHistoryItem(
            id = "test-1",
            method = "GET",
            url = "https://example.com/test",
            requestHeaders = listOf("Host: example.com"),
            statusCode = 200
        )
        repo.addOrUpdate(item)
        assertEquals(1, repo.getItemCount())

        // Clear action triggered from General Settings
        repo.clear()
        assertEquals(0, repo.getItemCount())
        assertTrue(repo.history.value.isEmpty())
    }

    @Test
    fun testGeneralSettings_scopeReset() {
        val repo = ScopeRepository.getInstance()
        repo.addRule(ScopeRule(type = ScopeRuleType.INCLUDE, pattern = "*.test.com"))
        repo.addRule(ScopeRule(type = ScopeRuleType.EXCLUDE, pattern = "admin.test.com"))
        assertEquals(2, repo.rules.value.size)

        // Reset triggered from General Settings
        repo.clearScope()
        assertEquals(0, repo.rules.value.size)
        // With empty rules, scope is unrestricted (target evaluates in scope)
        assertTrue(repo.isInScope("https://any-domain.com"))
    }
}

