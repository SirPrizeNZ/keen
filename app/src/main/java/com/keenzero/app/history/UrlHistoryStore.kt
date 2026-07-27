package com.keenzero.app.history

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the user has actually typed and opened, so the address bar can finish a URL
 * instead of making them spell it out with a D-pad keyboard.
 *
 * Ranking is frecency-ish and deliberately simple: visits first, recency as the
 * tie-break. On a TV the same handful of sites dominate, so the top match is nearly
 * always the intended one after two or three characters.
 *
 * Entries are stored as typed-form keys ("example.org/watch") rather than full URLs:
 * that is the string the user is typing, so prefix matching needs no scheme or "www."
 * gymnastics at suggest time.
 */
class UrlHistoryStore(context: Context) {

    data class Entry(val url: String, val typed: String, val visits: Int, val lastMs: Long)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val out = mutableListOf<Entry>()
        val arr = entries()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
            out.add(
                Entry(
                    url = url,
                    typed = o.optString("typed").takeIf { it.isNotBlank() } ?: typedFormOf(url),
                    visits = o.optInt("visits", 1).coerceAtLeast(1),
                    lastMs = o.optLong("lastMs", 0L),
                ),
            )
        }
        return out
    }

    /**
     * Record a successful navigation. Magnets and non-http schemes are ignored — the
     * point is completion of things a person types, and nobody types a 40-hex info-hash
     * from memory.
     */
    fun record(url: String?, atMs: Long = System.currentTimeMillis()) {
        val clean = url?.trim().orEmpty()
        if (clean.isBlank() || clean == "about:blank") return
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return
        val typed = typedFormOf(clean)
        if (typed.isBlank()) return

        val current = list()
        val existing = current.firstOrNull { it.typed.equals(typed, ignoreCase = true) }
        val updated = Entry(
            url = clean,
            typed = typed,
            visits = (existing?.visits ?: 0) + 1,
            lastMs = atMs,
        )
        val kept = current.filterNot { it.typed.equals(typed, ignoreCase = true) } + updated
        // Trim by rank, not by age: a site visited fifty times must not be evicted by a
        // burst of one-off pages, or completion stops working exactly when it matters.
        val trimmed = kept.sortedWith(RANK).take(MAX_ENTRIES)
        val next = JSONArray()
        trimmed.forEach { next.put(it.toJson()) }
        prefs.edit().putString(PREF_ENTRIES, next.toString()).apply()
    }

    /**
     * Best completion for what has been typed so far, or null when nothing matches.
     *
     * @return the full typed-form of the match (e.g. "example.org/live" for "exa"),
     *   never the raw URL — the address bar shows and commits typed form.
     */
    fun suggest(prefix: String): String? {
        val typedPrefix = typedPrefixOf(prefix.trim())
        if (typedPrefix.length < MIN_PREFIX_CHARS) return null
        return list()
            .filter {
                it.typed.startsWith(typedPrefix, ignoreCase = true) &&
                    it.typed.length > typedPrefix.length
            }
            .sortedWith(RANK)
            .firstOrNull()
            ?.typed
    }

    fun clear() {
        prefs.edit().remove(PREF_ENTRIES).apply()
    }

    private fun Entry.toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("typed", typed)
        .put("visits", visits)
        .put("lastMs", lastMs)

    private fun entries(): JSONArray = try {
        JSONArray(prefs.getString(PREF_ENTRIES, null) ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    companion object {
        private const val PREFS = "keen_url_history"
        private const val PREF_ENTRIES = "entries"
        private const val MAX_ENTRIES = 60

        /** Below this, a prefix matches half the list and the completion just flickers. */
        private const val MIN_PREFIX_CHARS = 2

        private val RANK = compareByDescending<Entry> { it.visits }.thenByDescending { it.lastMs }

        /**
         * The string a person would type for [url]: no scheme, no "www.", no trailing
         * slash on a bare host. "https://www.example.org/" → "example.org".
         */
        fun typedFormOf(url: String): String {
            val s = typedPrefixOf(url)
            // A bare host reads better without the slash, but "site.com/a/" keeps its
            // shape — the trailing slash there is part of a path the user typed.
            return if (s.endsWith("/") && s.count { it == '/' } == 1) s.dropLast(1) else s
        }

        /**
         * Same scheme/"www." stripping as [typedFormOf], but nothing is removed from the
         * *end* — a query prefix must stay a literal prefix of what the user has typed,
         * or the completed tail is spliced on at the wrong offset ("example.org//live").
         */
        fun typedPrefixOf(url: String): String {
            var s = url.trim()
            s = s.removePrefix("https://").removePrefix("http://")
            s = s.removePrefix("HTTPS://").removePrefix("HTTP://")
            if (s.startsWith("www.", ignoreCase = true)) s = s.substring(4)
            return s
        }

        /** Registrable-ish host of a stored entry, for callers that group by site. */
        fun hostOf(url: String): String? = try {
            Uri.parse(url).host?.lowercase()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
