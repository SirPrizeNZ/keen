package com.keenzero.app.diagnostics

/**
 * Pure schema checks for Phase 0 diagnostics JSON.
 * Unit-tested without Android org.json stubs.
 */
object EvidenceSchema {

    val ROOT_KEYS: Set<String> = setOf(
        "schemaVersion",
        "capturedAtEpochMs",
        "build",
        "device",
        "webview",
        "runtime",
        "codecs",
        "widevine",
        "events",
        "rendererTerminations",
    )

    val BUILD_KEYS: Set<String> = setOf(
        "applicationId",
        "versionName",
        "versionCode",
        "buildType",
        "buildId",
        "gitSha",
        "debuggable",
        "phase0Lab",
        "corpusVersion",
    )

    val DEVICE_KEYS: Set<String> = setOf(
        "manufacturer",
        "model",
        "androidVersion",
        "api",
        "abis",
        "memoryClassMb",
        "lowRam",
        "screenWidthPx",
        "screenHeightPx",
        "densityDpi",
    )

    val RUNTIME_KEYS: Set<String> = setOf(
        "uiState",
        "webViewCreated",
        "currentUrl",
        "processIs64Bit",
    )

    /**
     * Returns missing-path strings. Empty means shape is acceptable.
     * [payload] is a tree of Map/List/primitive values (JSON-like).
     */
    @Suppress("UNCHECKED_CAST")
    fun missingRequiredKeys(payload: Map<String, Any?>): List<String> {
        val missing = mutableListOf<String>()
        for (key in ROOT_KEYS) {
            if (!payload.containsKey(key)) missing += key
        }
        val build = payload["build"] as? Map<String, Any?>
        if (build != null) {
            for (key in BUILD_KEYS) {
                if (!build.containsKey(key)) missing += "build.$key"
            }
        } else if (payload.containsKey("build")) {
            missing += "build(object)"
        }
        val device = payload["device"] as? Map<String, Any?>
        if (device != null) {
            for (key in DEVICE_KEYS) {
                if (!device.containsKey(key)) missing += "device.$key"
            }
        } else if (payload.containsKey("device")) {
            missing += "device(object)"
        }
        val runtime = payload["runtime"] as? Map<String, Any?>
        if (runtime != null) {
            for (key in RUNTIME_KEYS) {
                if (!runtime.containsKey(key)) missing += "runtime.$key"
            }
        } else if (payload.containsKey("runtime")) {
            missing += "runtime(object)"
        }
        val wv = payload["webview"] as? Map<String, Any?>
        if (wv != null) {
            if (!wv.containsKey("packageName") && !wv.containsKey("error") && !wv.containsKey("note")) {
                missing += "webview.packageName|error|note"
            }
        } else if (payload.containsKey("webview")) {
            missing += "webview(object)"
        }
        return missing
    }

    fun samplePayload(
        webViewCreated: Boolean = false,
        uiState: String = "HOME",
        currentUrl: String? = null,
        packageName: String? = null,
        versionName: String? = null,
    ): Map<String, Any?> {
        val webview: Map<String, Any?> = if (packageName != null) {
            mapOf(
                "packageName" to packageName,
                "versionName" to versionName,
            )
        } else {
            mapOf(
                "packageName" to null,
                "versionName" to null,
                "note" to "not resolved",
            )
        }
        return mapOf(
            "schemaVersion" to 1,
            "capturedAtEpochMs" to 1_700_000_000_000L,
            "build" to mapOf(
                "applicationId" to "com.keenzero.app.debug",
                "versionName" to "0.1.0-phase0-debug",
                "versionCode" to 1,
                "buildType" to "debug",
                "buildId" to "test",
                "gitSha" to "unknown",
                "debuggable" to true,
                "phase0Lab" to true,
                "corpusVersion" to "none",
            ),
            "device" to mapOf(
                "manufacturer" to "Test",
                "model" to "Unit",
                "androidVersion" to "11",
                "api" to 30,
                "abis" to listOf("arm64-v8a"),
                "memoryClassMb" to 192,
                "lowRam" to false,
                "screenWidthPx" to 1920,
                "screenHeightPx" to 1080,
                "densityDpi" to 320,
            ),
            "webview" to webview,
            "runtime" to mapOf(
                "uiState" to uiState,
                "webViewCreated" to webViewCreated,
                "currentUrl" to currentUrl,
                "processIs64Bit" to false,
            ),
            "codecs" to emptyList<Any>(),
            "widevine" to mapOf("supported" to false),
            "events" to emptyList<Any>(),
            "rendererTerminations" to emptyList<Any>(),
        )
    }
}
