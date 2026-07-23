package com.keenzero.app.favourites

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Site favourites for the home surface. Host-level identity: starring any page
 * on a site favourites the site; the roundel opens the site root. The label is
 * the core of the domain ("www.1337x.to" → "1337x").
 */
class FavouritesStore(context: Context) {

    data class Fav(val host: String, val url: String, val label: String)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Fav> {
        val out = mutableListOf<Fav>()
        val arr = entries()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = o.optString("host").takeIf { it.isNotBlank() } ?: continue
            out.add(
                Fav(
                    host = host,
                    url = o.optString("url").takeIf { it.isNotBlank() } ?: "https://$host/",
                    label = o.optString("label").takeIf { it.isNotBlank() } ?: labelFor(host),
                ),
            )
        }
        return out
    }

    fun isFavourite(pageUrl: String?): Boolean {
        val host = hostOf(pageUrl) ?: return false
        return list().any { it.host == host }
    }

    /** @return true when the site is favourited after the call. */
    fun toggle(pageUrl: String?): Boolean {
        val host = hostOf(pageUrl) ?: return false
        val current = list()
        val exists = current.any { it.host == host }
        val next = JSONArray()
        if (exists) {
            current.filterNot { it.host == host }.forEach { next.put(it.toJson()) }
        } else {
            current.takeLast(MAX_FAVS - 1).forEach { next.put(it.toJson()) }
            val scheme = Uri.parse(pageUrl).scheme ?: "https"
            next.put(Fav(host, "$scheme://$host/", labelFor(host)).toJson())
        }
        prefs.edit().putString(PREF_ENTRIES, next.toString()).apply()
        return !exists
    }

    /** Drop any favourite whose host is in [hosts]. @return number removed. */
    fun removeHosts(hosts: Set<String>): Int {
        if (hosts.isEmpty()) return 0
        val current = list()
        val kept = current.filterNot { it.host in hosts }
        if (kept.size == current.size) return 0
        val next = JSONArray()
        kept.forEach { next.put(it.toJson()) }
        prefs.edit().putString(PREF_ENTRIES, next.toString()).apply()
        return current.size - kept.size
    }

    private fun Fav.toJson(): JSONObject =
        JSONObject().put("host", host).put("url", url).put("label", label)

    private fun entries(): JSONArray = try {
        JSONArray(prefs.getString(PREF_ENTRIES, null) ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    companion object {
        private const val PREFS = "keen_favs"
        private const val PREF_ENTRIES = "entries"
        private const val MAX_FAVS = 8

        fun hostOf(url: String?): String? {
            if (url.isNullOrBlank() || url == "about:blank") return null
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            return try {
                Uri.parse(url).host?.lowercase()?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }

        /** "www.1337x.to" → "1337x"; "flixer.gd" → "flixer". */
        fun labelFor(host: String): String {
            val parts = host.removePrefix("www.").split('.')
            return (parts.firstOrNull { it.isNotBlank() } ?: host).take(14)
        }
    }
}
