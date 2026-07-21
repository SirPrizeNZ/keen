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
    fun savePosition(originKey: String, positionMs: Long, durationMs: Long) {
        val all = entries()
        val nearEnd = durationMs > 0 &&
            (durationMs - positionMs <= END_CREDITS_MS || positionMs * 100 / durationMs >= END_PERCENT)
        if (positionMs < MIN_SAVE_MS || nearEnd) {
            all.remove(originKey)
        } else {
            all.put(
                originKey,
                JSONObject().put(KEY_POS, positionMs).put(KEY_TS, System.currentTimeMillis()),
            )
            prune(all)
        }
        prefs.edit().putString(PREF_ENTRIES, all.toString()).apply()
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

        private const val PREFS = "torrent_resume"
        private const val PREF_ENTRIES = "entries"
        private const val KEY_POS = "p"
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
