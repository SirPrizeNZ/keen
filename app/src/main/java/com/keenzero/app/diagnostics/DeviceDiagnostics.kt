package com.keenzero.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.webkit.WebView
import com.keenzero.app.BuildConfig
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Collects the Phase 0 diagnostic snapshot. All fields are best-effort and
 * must not throw into the UI path.
 */
object DeviceDiagnostics {

    fun collect(
        context: Context,
        currentUrl: String?,
        webViewCreated: Boolean,
        uiState: String,
        events: List<NavigationEvent>,
        rendererTerminations: List<JSONObject>,
    ): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val metrics = displayMetrics(context)
        val webViewInfo = webViewPackageInfo(context)

        return JSONObject().apply {
            put("schemaVersion", 1)
            put("capturedAtEpochMs", System.currentTimeMillis())
            put(
                "build",
                JSONObject()
                    .put("applicationId", BuildConfig.APPLICATION_ID)
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("buildType", BuildConfig.BUILD_TYPE)
                    .put("buildId", BuildConfig.BUILD_ID)
                    .put("gitSha", BuildConfig.GIT_SHA)
                    .put("debuggable", BuildConfig.DEBUG)
                    .put("phase0Lab", BuildConfig.PHASE0_LAB)
                    .put("corpusVersion", BuildConfig.CORPUS_VERSION),
            )
            put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("device", Build.DEVICE)
                    .put("product", Build.PRODUCT)
                    .put("androidVersion", Build.VERSION.RELEASE)
                    .put("api", Build.VERSION.SDK_INT)
                    .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .put("memoryClassMb", am.memoryClass)
                    .put("largeMemoryClassMb", am.largeMemoryClass)
                    .put("lowRam", am.isLowRamDevice)
                    .put("totalMemBytes", memInfo.totalMem)
                    .put("availMemBytes", memInfo.availMem)
                    .put("thresholdBytes", memInfo.threshold)
                    .put("screenWidthPx", metrics.widthPixels)
                    .put("screenHeightPx", metrics.heightPixels)
                    .put("densityDpi", metrics.densityDpi)
                    .put("density", metrics.density.toDouble())
                    .put("refreshRateHz", refreshRate(context)),
            )
            put("webview", webViewInfo)
            put("webviewFeatures", webViewFeatureSupport())
            put(
                "runtime",
                JSONObject()
                    .put("uiState", uiState)
                    .put("webViewCreated", webViewCreated)
                    .put("currentUrl", currentUrl)
                    .put("javaHeapUsedBytes", usedJavaHeap())
                    .put("javaHeapMaxBytes", Runtime.getRuntime().maxMemory())
                    .put("processIs64Bit", android.os.Process.is64Bit())
                    .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .put("supported32BitAbis", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
                    .put("supported64BitAbis", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))
                    .put("pid", android.os.Process.myPid())
                    .put("cpuAbi", getSystemProperty("ro.product.cpu.abi"))
                    .put("cpuAbiList", getSystemProperty("ro.product.cpu.abilist"))
                    .put("zygote", getSystemProperty("ro.zygote"))
                    .put("processArch", System.getProperty("os.arch")),
            )
            put("codecs", codecSummary())
            put("widevine", widevineInfo())
            put(
                "events",
                JSONArray().also { arr ->
                    events.forEach { e ->
                        arr.put(
                            JSONObject()
                                .put("t", e.t)
                                .put("type", e.type)
                                .put("url", e.url)
                                .put("detail", e.detail)
                                .put("isMainFrame", e.isMainFrame),
                        )
                    }
                },
            )
            put(
                "rendererTerminations",
                JSONArray().also { arr ->
                    rendererTerminations.forEach { arr.put(it) }
                },
            )
        }
    }

    private fun webViewFeatureSupport(): JSONObject = JSONObject().apply {
        val features = linkedMapOf(
            "documentStartScript" to WebViewFeature.DOCUMENT_START_SCRIPT,
            "serviceWorkerBasicUsage" to WebViewFeature.SERVICE_WORKER_BASIC_USAGE,
            "webMessageListener" to WebViewFeature.WEB_MESSAGE_LISTENER,
            "safeBrowsingEnable" to WebViewFeature.SAFE_BROWSING_ENABLE,
            "getWebViewRenderer" to WebViewFeature.GET_WEB_VIEW_RENDERER,
            "terminateWebViewRenderer" to WebViewFeature.WEB_VIEW_RENDERER_TERMINATE,
        )
        features.forEach { (name, feature) ->
            put(name, WebViewFeature.isFeatureSupported(feature))
        }
    }

    fun webViewPackageInfo(context: Context): JSONObject {
        val obj = JSONObject()
        try {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg != null) {
                obj.put("packageName", pkg.packageName)
                obj.put("versionName", pkg.versionName)
                obj.put("versionCode", packageLongVersion(pkg))
            } else {
                obj.put("packageName", JSONObject.NULL)
                obj.put("versionName", JSONObject.NULL)
                obj.put("note", "WebView package not yet resolved (may appear after first WebView)")
            }
        } catch (t: Throwable) {
            obj.put("error", t.javaClass.simpleName + ": " + t.message)
        }

        // PackageManager fallback for environments where getCurrentWebViewPackage is null
        // before engine warm-up.
        if (obj.isNull("packageName")) {
            try {
                val pm = context.packageManager
                val candidates = listOf(
                    "com.google.android.webview",
                    "com.android.webview",
                    "com.android.chrome",
                )
                for (name in candidates) {
                    try {
                        val pi = pm.getPackageInfo(name, 0)
                        obj.put("packageName", pi.packageName)
                        obj.put("versionName", pi.versionName)
                        obj.put("versionCode", packageLongVersion(pi))
                        obj.put("resolvedVia", "PackageManager")
                        break
                    } catch (_: PackageManager.NameNotFoundException) {
                        // try next
                    }
                }
            } catch (t: Throwable) {
                obj.put("pmError", t.message)
            }
        }
        return obj
    }

    private fun packageLongVersion(pi: android.content.pm.PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    private fun displayMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun refreshRate(context: Context): Double {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                context.display?.refreshRate?.toDouble() ?: 0.0
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.refreshRate.toDouble()
            }
        } catch (_: Throwable) {
            0.0
        }
    }

    private fun usedJavaHeap(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun codecSummary(): JSONArray {
        val arr = JSONArray()
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val interesting = listOf("avc", "hevc", "vp9", "av01", "mp4a", "opus", "vorbis")
            val seen = linkedSetOf<String>()
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val lower = type.lowercase()
                    if (interesting.any { lower.contains(it) } && seen.add(type)) {
                        arr.put(
                            JSONObject()
                                .put("mime", type)
                                .put("name", info.name)
                                .put("hardware", !info.name.lowercase().contains("android") || info.name.lowercase().contains("c2.qti") || info.name.lowercase().contains("c2.exynos") || info.name.lowercase().contains("c2.mtk") || info.name.lowercase().contains("omx.")),
                        )
                    }
                }
            }
            arr
        } catch (t: Throwable) {
            arr.put(JSONObject().put("error", t.message))
            arr
        }
    }

    /**
     * Widevine level via legitimate MediaDrm APIs only. May be unavailable on some devices.
     */
    private fun widevineInfo(): JSONObject {
        val obj = JSONObject()
        val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        var drm: MediaDrm? = null
        return try {
            if (!MediaDrm.isCryptoSchemeSupported(widevineUuid)) {
                return obj.put("supported", false)
            }
            drm = MediaDrm(widevineUuid)
            obj.put("supported", true)
            obj.put("vendor", safeDrmProperty(drm, MediaDrm.PROPERTY_VENDOR))
            obj.put("version", safeDrmProperty(drm, MediaDrm.PROPERTY_VERSION))
            obj.put("description", safeDrmProperty(drm, MediaDrm.PROPERTY_DESCRIPTION))
            obj.put("algorithms", safeDrmProperty(drm, MediaDrm.PROPERTY_ALGORITHMS))
            obj.put("securityLevel", safeDrmProperty(drm, "securityLevel"))
            obj.put("systemId", safeDrmProperty(drm, "systemId"))
            obj
        } catch (t: Throwable) {
            obj.put("supported", false)
            obj.put("error", t.javaClass.simpleName + ": " + t.message)
            obj
        } finally {
            try {
                drm?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun safeDrmProperty(drm: MediaDrm, key: String): String {
        return try {
            drm.getPropertyString(key)
        } catch (_: Throwable) {
            "unavailable"
        }
    }

    fun getSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            get.invoke(null, key) as String
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun getMemorySnapshot(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pid = android.os.Process.myPid()
        val pss = try {
            val mi = am.getProcessMemoryInfo(intArrayOf(pid))
            if (mi.isNotEmpty()) mi[0].totalPss else 0
        } catch (_: Throwable) {
            0
        }
        val renderers = try {
            am.runningAppProcesses?.count {
                it.processName.contains("sandbox") || it.processName.contains("webview")
            } ?: 0
        } catch (_: Throwable) {
            0
        }
        return JSONObject()
            .put("pid", pid)
            .put("pssKb", pss)
            .put("availMemBytes", memInfo.availMem)
            .put("renderers", renderers)
    }
}

