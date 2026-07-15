package com.keenzero.app.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject

enum class DeviceTier {
    CONSTRAINT_32,
    BALANCED,
    PERFORMANCE,
}

/**
 * First-class performance policy. Tier is derived from measured capability,
 * not product-flavour ABI name alone.
 */
data class PerformancePolicy(
    val tier: DeviceTier,
    val maxLiveWebViews: Int = 1,
    val allowRendererPool: Boolean = false,
    val maxInteractionCandidates: Int = 256,
    val checkpointMinIntervalMs: Long = 1_200L,
    val disallowEagerServiceWorkerOnHome: Boolean = true,
    val denyFirstPopups: Boolean = true,
    val maxDomPollMsWhileSessionActive: Long = 400L,
    val maxDomPollMsIdle: Long = Long.MAX_VALUE, // effectively off
    val maxQuarantineWebViewLifetimeMs: Long = 1_000L,
    val crashLoopMaxDeaths: Int = 3,
    val crashLoopWindowMs: Long = 60_000L,
    val reason: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tier", tier.name)
        .put("maxLiveWebViews", maxLiveWebViews)
        .put("allowRendererPool", allowRendererPool)
        .put("maxInteractionCandidates", maxInteractionCandidates)
        .put("checkpointMinIntervalMs", checkpointMinIntervalMs)
        .put("disallowEagerServiceWorkerOnHome", disallowEagerServiceWorkerOnHome)
        .put("denyFirstPopups", denyFirstPopups)
        .put("maxDomPollMsWhileSessionActive", maxDomPollMsWhileSessionActive)
        .put("maxQuarantineWebViewLifetimeMs", maxQuarantineWebViewLifetimeMs)
        .put("crashLoopMaxDeaths", crashLoopMaxDeaths)
        .put("crashLoopWindowMs", crashLoopWindowMs)
        .put("reason", reason)
}

object PerformanceGovernor {

    fun evaluate(context: Context): PerformancePolicy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val is64 = android.os.Process.is64Bit()
        val totalMb = mem.totalMem / (1024L * 1024L)
        val lowRam = am.isLowRamDevice
        val memoryClass = am.memoryClass

        // Flavour name is never used. Bitness + RAM class drive the tier.
        return when {
            !is64 || lowRam || totalMb in 1..1536L || memoryClass <= 128 ->
                PerformancePolicy(
                    tier = DeviceTier.CONSTRAINT_32,
                    reason = "is64Bit=$is64 lowRam=$lowRam totalMb=$totalMb memClass=$memoryClass api=${Build.VERSION.SDK_INT}",
                )
            totalMb >= 3072L && is64 && memoryClass >= 256 ->
                PerformancePolicy(
                    tier = DeviceTier.PERFORMANCE,
                    maxInteractionCandidates = 384,
                    checkpointMinIntervalMs = 1_000L,
                    maxQuarantineWebViewLifetimeMs = 1_500L,
                    reason = "is64Bit=$is64 totalMb=$totalMb memClass=$memoryClass",
                )
            else ->
                PerformancePolicy(
                    tier = DeviceTier.BALANCED,
                    maxInteractionCandidates = 320,
                    reason = "is64Bit=$is64 totalMb=$totalMb memClass=$memoryClass",
                )
        }
    }
}
