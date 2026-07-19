package com.keenzero.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceSchemaTest {

    @Test
    fun samplePayloadHasAllRequiredKeys() {
        val payload = EvidenceSchema.samplePayload(
            webViewCreated = true,
            uiState = "BROWSING",
            currentUrl = "https://example.com/",
            packageName = "com.google.android.webview",
            versionName = "83.0.4103.106",
        )
        assertEquals(emptyList<String>(), EvidenceSchema.missingRequiredKeys(payload))
        assertEquals(1, payload["schemaVersion"])
        @Suppress("UNCHECKED_CAST")
        val runtime = payload["runtime"] as Map<String, Any?>
        assertEquals(true, runtime["webViewCreated"])
        @Suppress("UNCHECKED_CAST")
        val wv = payload["webview"] as Map<String, Any?>
        assertEquals("com.google.android.webview", wv["packageName"])
        @Suppress("UNCHECKED_CAST")
        val build = payload["build"] as Map<String, Any?>
        assertEquals("none", build["corpusVersion"])
    }

    @Test
    fun missingRootKeyIsReported() {
        val payload = EvidenceSchema.samplePayload().toMutableMap()
        payload.remove("events")
        val missing = EvidenceSchema.missingRequiredKeys(payload)
        assertTrue(missing.contains("events"))
    }

    @Test
    fun homeFirstDoesNotRequireLiveWebViewPackage() {
        val payload = EvidenceSchema.samplePayload(webViewCreated = false)
        assertEquals(emptyList<String>(), EvidenceSchema.missingRequiredKeys(payload))
        @Suppress("UNCHECKED_CAST")
        val runtime = payload["runtime"] as Map<String, Any?>
        assertEquals(false, runtime["webViewCreated"])
    }
}
