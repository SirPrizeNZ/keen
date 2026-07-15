package com.keenzero.app.continuity

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Persists the latest semantic checkpoint across process death. */
class ContinuityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var lastWrittenPos = Double.NaN
    private var lastWriteAtMs = 0L

    private val executor = Executors.newSingleThreadExecutor()
    private val pendingCheckpoint = AtomicReference<ContinuityCheckpoint?>()

    /**
     * Debounced write. Offloads SharedPreferences.commit() to a background thread to prevent UI lag.
     * Replaces pending checkpoints to prevent growing queue (only latest is written).
     */
    fun save(checkpoint: ContinuityCheckpoint, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val posDelta = if (lastWrittenPos.isNaN()) Double.MAX_VALUE
        else kotlin.math.abs(checkpoint.playbackPositionSec - lastWrittenPos)
        if (!force &&
            posDelta < MIN_POS_DELTA_SEC &&
            now - lastWriteAtMs < MIN_INTERVAL_MS
        ) {
            return
        }
        pendingCheckpoint.set(checkpoint)
        if (force) {
            flush()
        } else {
            executor.execute { writePending() }
        }
    }

    @Synchronized
    private fun writePending() {
        val checkpoint = pendingCheckpoint.getAndSet(null) ?: return
        val t0 = System.currentTimeMillis()
        val ok = prefs.edit()
            .putString(KEY_CHECKPOINT, checkpoint.toJson().toString())
            .commit()
        val duration = System.currentTimeMillis() - t0
        Log.d("KeenContinuity", "Persisted checkpoint. duration=${duration}ms status=$ok")
        if (ok) {
            lastWrittenPos = checkpoint.playbackPositionSec
            lastWriteAtMs = System.currentTimeMillis()
        }
    }

    fun flush() {
        val future = executor.submit { writePending() }
        try {
            future.get(1500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e("KeenContinuity", "Timeout waiting for checkpoint flush", e)
        }
    }

    fun load(): ContinuityCheckpoint? =
        ContinuityCheckpoint.fromJson(prefs.getString(KEY_CHECKPOINT, null))

    fun clear() {
        pendingCheckpoint.set(null)
        val future = executor.submit {
            prefs.edit().remove(KEY_CHECKPOINT).commit()
        }
        try {
            future.get(1000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
        lastWrittenPos = Double.NaN
        lastWriteAtMs = 0L
    }

    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: Exception) {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val PREFS = "keen_continuity"
        private const val KEY_CHECKPOINT = "latest"
        private const val MIN_INTERVAL_MS = 1_200L
        private const val MIN_POS_DELTA_SEC = 0.75
    }
}
