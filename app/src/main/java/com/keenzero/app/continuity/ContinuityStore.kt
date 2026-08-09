package com.keenzero.app.continuity

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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

    /** Up to [MAX_RECENTS] recently played titles, most-recent first, de-duped. */
    fun loadRecents(): List<ContinuityCheckpoint> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            // Collapse duplicate cards for the same title. Historically a magnet and a
            // slightly different magnet for the same movie (different tracker/&dn= params
            // but the same info-hash) produced two Continue cards; dedupe on the stable
            // key and keep the first (most-recent) occurrence.
            val out = ArrayList<ContinuityCheckpoint>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val cp = arr.optJSONObject(i)?.let { ContinuityCheckpoint.fromJson(it.toString()) } ?: continue
                if (seen.add(recentsKeyOf(cp))) out.add(cp)
            }
            // Only the write paths used to cap, so a list persisted by an older build kept
            // rendering in full. Cap on read too, and order by recency explicitly rather
            // than trusting stored insertion order — "latest MAX_RECENTS, never more".
            out.sortedByDescending { it.timestampMs }.take(MAX_RECENTS)
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
     * Put the real watch state aside so a demo can take the row over, and hand it back.
     *
     * [saveRecents] replaces the row wholesale, which is what a clean capture needs and
     * also what destroys the user's history: removing the seeded cards afterwards leaves
     * an empty row, not the five titles that were there before. So the seed stashes first
     * and the clear path restores. The stash is written once — re-seeding over a live demo
     * must not overwrite the real state with demo state.
     */
    fun stashRealState() {
        if (prefs.contains(KEY_STASH)) return
        val stash = JSONObject()
        // putOpt, not put: a slot that is currently empty must stay absent from the stash,
        // so restoring it removes the key rather than writing the string "null" into it.
        listOf(KEY_RECENTS, KEY_MEDIA_CHECKPOINT, KEY_CHECKPOINT).forEach { key ->
            stash.putOpt(key, prefs.getString(key, null))
        }
        prefs.edit().putString(KEY_STASH, stash.toString()).commit()
    }

    /** True while real watch state is parked — i.e. demo content owns the home surface. */
    fun hasStash(): Boolean = prefs.contains(KEY_STASH)

    /** @return true when a stash existed and the real state is now back in place. */
    fun restoreRealState(): Boolean {
        val raw = prefs.getString(KEY_STASH, null) ?: return false
        val editor = prefs.edit()
        try {
            val stash = JSONObject(raw)
            listOf(KEY_RECENTS, KEY_MEDIA_CHECKPOINT, KEY_CHECKPOINT).forEach { key ->
                val value = if (stash.isNull(key)) null else stash.optString(key, "").ifBlank { null }
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
        } catch (_: Exception) {
            // An unreadable stash is not worth failing the restore over; the demo content
            // is still removed by the caller, which is the part the user asked for.
        }
        editor.remove(KEY_STASH).commit()
        return true
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

    /**
     * Drop [cp] from Continue watching, plus the checkpoint slots pointing at it.
     *
     * Keyed with [recentsKeyOf], the same identity the row de-dupes on, so a magnet card
     * removes cleanly — [removeByContentId] only matches an explicit contentId and would
     * silently leave those behind.
     *
     * @return true when a card was removed.
     */
    fun removeRecent(cp: ContinuityCheckpoint): Boolean {
        val key = recentsKeyOf(cp)
        if (key.isBlank()) return false
        val recents = loadRecents()
        val kept = recents.filterNot { recentsKeyOf(it) == key }
        val editor = prefs.edit()
        if (kept.size != recents.size) {
            val arr = JSONArray()
            kept.take(MAX_RECENTS).forEach { arr.put(it.toJson()) }
            editor.putString(KEY_RECENTS, arr.toString())
        }
        // Otherwise a cold start would auto-resume the card the user just deleted.
        if (loadMedia()?.let { recentsKeyOf(it) } == key) editor.remove(KEY_MEDIA_CHECKPOINT)
        if (load()?.let { recentsKeyOf(it) } == key) editor.remove(KEY_CHECKPOINT)
        editor.commit()
        return kept.size != recents.size
    }

    /** Move [cp] to the front of the recents list, de-duped by stable key, capped. */
    private fun upsertedRecents(cp: ContinuityCheckpoint): JSONArray {
        val key = recentsKeyOf(cp)
        val kept = loadRecents().filterNot { recentsKeyOf(it) == key }
        val arr = JSONArray()
        arr.put(cp.toJson())
        kept.take(MAX_RECENTS - 1).forEach { arr.put(it.toJson()) }
        return arr
    }

    /**
     * Stable identity for de-duping the Continue watching row. Prefers an explicit
     * contentId; for magnets, keys on the info-hash so the same movie added from two
     * different magnet links (differing tracker/&dn= params) collapses to one card;
     * otherwise falls back to the raw url.
     */
    private fun recentsKeyOf(cp: ContinuityCheckpoint): String {
        cp.contentId?.let { return it }
        val url = cp.url ?: return ""
        val ih = MAGNET_INFO_HASH.find(url)?.groupValues?.get(1)?.lowercase()
        return ih ?: url
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

        /** Real watch state parked while demo content occupies the home surface. */
        private const val KEY_STASH = "real_state_stash"
        /**
         * How many Continue cards the row keeps. Every read and write path caps against
         * this one constant, so it is the only place the length is decided.
         */
        private const val MAX_RECENTS = 20
        private const val KEY_AT_HOME = "at_home"
        private const val MIN_INTERVAL_MS = 1_200L
        private const val MIN_POS_DELTA_SEC = 0.75
        private val MAGNET_INFO_HASH =
            Regex("""xt=urn:bt[im]h:([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    }
}
