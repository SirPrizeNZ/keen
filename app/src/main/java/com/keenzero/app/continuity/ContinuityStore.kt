package com.keenzero.app.continuity

import android.content.Context
import android.util.Log
import org.json.JSONArray
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
        val editor = prefs.edit()
            .putString(KEY_CHECKPOINT, checkpoint.toJson().toString())
        // Media checkpoints also land in a dedicated slot: browsing checkpoints
        // overwrite "latest" constantly, but the Continue watching card must keep
        // pointing at the last thing the user actually played.
        if (!checkpoint.url.isNullOrBlank() && checkpoint.requiresMediaRestore()) {
            editor.putString(KEY_MEDIA_CHECKPOINT, checkpoint.toJson().toString())
            editor.putString(KEY_RECENTS, upsertedRecents(checkpoint).toString())
        }
        val ok = editor.commit()
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

    /** Last checkpoint that involved actual playback — feeds the Continue watching card. */
    fun loadMedia(): ContinuityCheckpoint? =
        ContinuityCheckpoint.fromJson(prefs.getString(KEY_MEDIA_CHECKPOINT, null))

    /** Up to [MAX_RECENTS] recently played titles, most-recent first. */
    fun loadRecents(): List<ContinuityCheckpoint> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { ContinuityCheckpoint.fromJson(it.toString()) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Replace the recents list wholesale (used to seed demo content). */
    fun saveRecents(items: List<ContinuityCheckpoint>) {
        val arr = JSONArray()
        items.take(MAX_RECENTS).forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RECENTS, arr.toString()).commit()
    }

    /**
     * Purge any recents / checkpoint slots whose contentId is in [contentIds].
     * Used to clear seeded demo content (e.g. the "keen-ui-preview" card) without
     * touching real watch history. @return number of recents removed.
     */
    fun removeByContentId(contentIds: Set<String>): Int {
        if (contentIds.isEmpty()) return 0
        val recents = loadRecents()
        val kept = recents.filterNot { it.contentId in contentIds }
        val editor = prefs.edit()
        if (kept.size != recents.size) {
            val arr = JSONArray()
            kept.take(MAX_RECENTS).forEach { arr.put(it.toJson()) }
            editor.putString(KEY_RECENTS, arr.toString())
        }
        if (loadMedia()?.contentId in contentIds) editor.remove(KEY_MEDIA_CHECKPOINT)
        if (load()?.contentId in contentIds) editor.remove(KEY_CHECKPOINT)
        editor.commit()
        return recents.size - kept.size
    }

    /** Move [cp] to the front of the recents list, de-duped by contentId/url, capped. */
    private fun upsertedRecents(cp: ContinuityCheckpoint): JSONArray {
        val key = cp.contentId ?: cp.url
        val kept = loadRecents().filterNot { (it.contentId ?: it.url) == key }
        val arr = JSONArray()
        arr.put(cp.toJson())
        kept.take(MAX_RECENTS - 1).forEach { arr.put(it.toJson()) }
        return arr
    }

    /**
     * True when the user deliberately backed all the way out to the home surface.
     * A cold start then lands on home (with the Continue card) instead of
     * auto-restoring the last page/playback.
     */
    fun wasAtHome(): Boolean = prefs.getBoolean(KEY_AT_HOME, false)

    fun markAtHome(atHome: Boolean) {
        prefs.edit().putBoolean(KEY_AT_HOME, atHome).apply()
    }

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
        private const val KEY_MEDIA_CHECKPOINT = "latest_media"
        private const val KEY_RECENTS = "recents"
        private const val MAX_RECENTS = 5
        private const val KEY_AT_HOME = "at_home"
        private const val MIN_INTERVAL_MS = 1_200L
        private const val MIN_POS_DELTA_SEC = 0.75
    }
}
