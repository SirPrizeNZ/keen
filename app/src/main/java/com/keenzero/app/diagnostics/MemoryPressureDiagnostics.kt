package com.keenzero.app.diagnostics

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log

data class MemoryPressureSnapshot(
    val levelName: String,
    val level: Int?,
    val availMemBytes: Long,
    val lowMemory: Boolean,
    val source: String,
) {
    val detail: String
        get() = "source=$source trimLevel=$levelName trimLevelValue=${level ?: "none"} " +
            "availMemBytes=$availMemBytes lowMemory=$lowMemory"
}

/** Emits pressure observations to logcat so they survive an LMK process kill. */
object MemoryPressureDiagnostics {
    private const val TAG = "KeenMemory"

    fun record(context: Context, level: Int, source: String): MemoryPressureSnapshot =
        capture(context, trimLevelName(level), level, source)

    fun recordLowMemory(context: Context, source: String): MemoryPressureSnapshot =
        capture(context, "LOW_MEMORY_CALLBACK", null, source)

    fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
        else -> "TRIM_MEMORY_UNKNOWN"
    }

    fun shouldReleaseRecreatableState(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

    private fun capture(
        context: Context,
        levelName: String,
        level: Int?,
        source: String,
    ): MemoryPressureSnapshot {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return MemoryPressureSnapshot(
            levelName = levelName,
            level = level,
            availMemBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            source = source,
        ).also { Log.w(TAG, "event=MEMORY_PRESSURE ${it.detail}") }
    }
}
