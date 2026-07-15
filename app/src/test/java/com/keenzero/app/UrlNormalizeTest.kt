package com.keenzero.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlNormalizeTest {

    @Test
    fun addsHttpsWhenMissing() {
        assertEquals("https://example.com", UrlNormalizer.normalize("example.com"))
    }

    @Test
    fun keepsHttps() {
        assertEquals("https://example.com/path", UrlNormalizer.normalize("https://example.com/path"))
    }

    @Test
    fun rejectsUnknownScheme() {
        assertNull(UrlNormalizer.normalize("intent://foo"))
        assertNull(UrlNormalizer.normalize("javascript:alert(1)"))
    }

    @Test
    fun blankRejected() {
        assertNull(UrlNormalizer.normalize("   "))
    }
}
