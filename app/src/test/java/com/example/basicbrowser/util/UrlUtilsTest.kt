package com.example.basicbrowser.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun emptyAndWhitespaceInput_returnsEmptyString() {
        assertEquals("", UrlUtils.getUrlOrSearch(""))
        assertEquals("", UrlUtils.getUrlOrSearch("   "))
        assertEquals("", UrlUtils.getUrlOrSearch("\t\n"))
    }

    @Test
    fun searchQueries_singleWordWithoutDot_routesToGoogleSearch() {
        assertEquals(
            "https://www.google.com/search?q=android",
            UrlUtils.getUrlOrSearch("android")
        )
    }

    @Test
    fun searchQueries_multiWordWithWhitespace_routesToGoogleSearch() {
        assertEquals(
            "https://www.google.com/search?q=kotlin+coroutines",
            UrlUtils.getUrlOrSearch("kotlin coroutines")
        )
        assertEquals(
            "https://www.google.com/search?q=how+to+debug+proxy",
            UrlUtils.getUrlOrSearch("  how to debug proxy  ")
        )
    }

    @Test
    fun searchQueries_specialCharacters_correctlyEncoded() {
        assertEquals(
            "https://www.google.com/search?q=c%2B%2B+primer",
            UrlUtils.getUrlOrSearch("c++ primer")
        )
        assertEquals(
            "https://www.google.com/search?q=cats+%26+dogs",
            UrlUtils.getUrlOrSearch("cats & dogs")
        )
        assertEquals(
            "https://www.google.com/search?q=price%3D%24100",
            UrlUtils.getUrlOrSearch("price=$100")
        )
    }

    @Test
    fun validUrls_withExplicitScheme_preserved() {
        assertEquals(
            "https://example.com",
            UrlUtils.getUrlOrSearch("https://example.com")
        )
        assertEquals(
            "http://neverssl.com",
            UrlUtils.getUrlOrSearch("http://neverssl.com")
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Main_Page",
            UrlUtils.getUrlOrSearch("https://en.wikipedia.org/wiki/Main_Page")
        )
        assertEquals(
            "http://example.com:8080/path?key=value#frag",
            UrlUtils.getUrlOrSearch("http://example.com:8080/path?key=value#frag")
        )
    }

    @Test
    fun missingScheme_prependsHttps() {
        assertEquals(
            "https://example.com",
            UrlUtils.getUrlOrSearch("example.com")
        )
        assertEquals(
            "https://sub.domain.org/path?q=1",
            UrlUtils.getUrlOrSearch("sub.domain.org/path?q=1")
        )
    }

    @Test
    fun duplicateSchemes_conservativelyNormalized() {
        assertEquals(
            "https://example.com",
            UrlUtils.getUrlOrSearch("https://https://example.com")
        )
        assertEquals(
            "http://example.com",
            UrlUtils.getUrlOrSearch("http://http://example.com")
        )
    }

    @Test
    fun googleDomain_normalizedToWwwToPreventRedirectLoops() {
        assertEquals(
            "https://www.google.com",
            UrlUtils.getUrlOrSearch("google.com")
        )
        assertEquals(
            "https://www.google.com",
            UrlUtils.getUrlOrSearch("https://google.com")
        )
        assertEquals(
            "https://www.google.com",
            UrlUtils.getUrlOrSearch("http://google.com")
        )
        assertEquals(
            "https://www.google.com",
            UrlUtils.getUrlOrSearch("https://www.google.com")
        )
    }
}

