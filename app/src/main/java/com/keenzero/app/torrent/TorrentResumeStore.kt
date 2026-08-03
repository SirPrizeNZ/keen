package com.keenzero.app.torrent

import android.content.Context
import org.json.JSONObject

/**
 * Remembers playback positions for torrent streams across sessions.
 *
 * Exiting playback still deletes the downloaded media and session cache; only
 * the resume point survives, keyed by the torrent's identity (btih/btmh
 * info-hash for magnets, origin URL for .torrent links), so activating the
 * same magnet again continues where the user left off.
 */
class TorrentResumeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved resume point in ms, or 0 when none / previously finished. */
    fun positionMs(originKey: String): Long =
        entries().optJSONObject(originKey)?.optLong(KEY_POS, 0L) ?: 0L

    /**
     * Persist the latest position. Near-complete or barely-started playback
     * clears the entry so a replay starts from the beginning.
     */
    /** Stored duration for [originKey], or 0 when unknown. */
    fun durationMs(originKey: String): Long = try {
        entries().optJSONObject(originKey)?.optLong(KEY_DURATION, 0L) ?: 0L
    } catch (_: Exception) {
        0L
    }

    fun savePosition(originKey: String, positionMs: Long, durationMs: Long) {
        val all = entries()
        val nearEnd = durationMs > 0 &&
            (durationMs - positionMs <= END_CREDITS_MS || positionMs * 100 / durationMs >= END_PERCENT)
        if (positionMs < MIN_SAVE_MS || nearEnd) {
            all.remove(originKey)
        } else {
            all.put(
                originKey,
                JSONObject()
                    .put(KEY_POS, positionMs)
                    // Never written before, while durationMs() read it — so it always
                    // answered 0, the resume fraction was always 0, and every resume
                    // quietly buffered the head of the file instead of the playhead.
                    .put(KEY_DURATION, durationMs)
                    .put(KEY_TS, System.currentTimeMillis()),
            )
            prune(all)
        }
        prefs.edit().putString(PREF_ENTRIES, all.toString()).apply()
    }

    /**
     * True when this position counts as having finished the film — the same rule
     * [savePosition] uses to drop a resume point, so "no resume point" and "watched"
     * can never disagree.
     */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 &&
            (durationMs - positionMs <= END_CREDITS_MS || positionMs * 100 / durationMs >= END_PERCENT)

    /**
     * Remember that a file inside a multi-file torrent has been watched through.
     *
     * Kept apart from the resume entries because those are deliberately *cleared* on
     * finishing, so they cannot answer "have I seen this one". Keyed by torrent identity
     * plus file index, which is what the picker needs to tick the right row.
     */
    fun markWatched(originKey: String, fileIndex: Int) {
        val all = watched()
        val list = all.optJSONArray(originKey) ?: org.json.JSONArray()
        for (i in 0 until list.length()) if (list.optInt(i, -1) == fileIndex) return
        list.put(fileIndex)
        all.put(originKey, list)
        while (all.length() > MAX_ENTRIES) all.remove(all.keys().next())
        prefs.edit().putString(PREF_WATCHED, all.toString()).apply()
    }

    /** File indices of [originKey] already watched through. */
    fun watchedIndices(originKey: String): Set<Int> {
        val list = watched().optJSONArray(originKey) ?: return emptySet()
        return (0 until list.length()).mapNotNull { list.optInt(it, -1).takeIf { v -> v >= 0 } }.toSet()
    }

    private fun watched(): JSONObject = try {
        JSONObject(prefs.getString(PREF_WATCHED, null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun entries(): JSONObject = try {
        JSONObject(prefs.getString(PREF_ENTRIES, null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun prune(all: JSONObject) {
        while (all.length() > MAX_ENTRIES) {
            var oldestKey: String? = null
            var oldestTs = Long.MAX_VALUE
            for (key in all.keys()) {
                val ts = all.optJSONObject(key)?.optLong(KEY_TS, 0L) ?: 0L
                if (ts < oldestTs) {
                    oldestTs = ts
                    oldestKey = key
                }
            }
            all.remove(oldestKey ?: return)
        }
    }

    companion object {
        /**
         * Stable identity for a torrent origin: the info-hash when present
         * (same content re-offered under different trackers/display names
         * still resumes), else the raw origin string.
         */
        fun keyOf(origin: String): String {
            val match = Regex("""xt=urn:bt[im]h:([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(origin)
            return match?.groupValues?.get(1)?.lowercase() ?: origin.take(MAX_KEY_LENGTH)
        }

        /**
         * Resume identity for one file inside a multi-file torrent.
         *
         * Without the index a 4-film pack shared a single resume point, so stopping one
         * film half way and starting another dropped the second one into the first one's
         * playhead. [fileIndex] null keeps the plain key for single-file torrents, so
         * existing entries still resolve.
         */
        fun fileKeyOf(originKey: String, fileIndex: Int?): String =
            if (fileIndex == null) originKey else "$originKey#$fileIndex"

        private const val PREFS = "torrent_resume"
        private const val PREF_ENTRIES = "entries"
        private const val PREF_WATCHED = "watched"
        private const val KEY_POS = "p"
        private const val KEY_DURATION = "d"
        private const val KEY_TS = "t"
        /** Below this there is nothing meaningful to resume. */
        private const val MIN_SAVE_MS = 15_000L
        /** Within credits distance of the end counts as finished. */
        private const val END_CREDITS_MS = 90_000L
        private const val END_PERCENT = 97
        private const val MAX_ENTRIES = 50
        private const val MAX_KEY_LENGTH = 200
    }
}
