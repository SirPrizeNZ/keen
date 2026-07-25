package com.keenzero.app.compat

import android.content.Context
import android.content.SharedPreferences

/**
 * Origins the user has agreed to open in compatibility mode, persisted across sessions.
 *
 * Three deliberate constraints:
 *
 * - **Entries expire.** Sites turn Cloudflare's stricter modes on and off; permanently
 *   surrendering ad blocking on a site that stopped needing it would be a slow leak of
 *   protection. After [TTL_MS] the origin is re-tested in normal mode, and if it loops
 *   again the user is simply asked again.
 * - **Some origins can never be promoted.** [PINNED_NORMAL] sites stay on the normal
 *   WebView whatever they serve, so a compromised or hostile page on a site whose
 *   protections matter cannot talk its way out of them.
 * - **Only the user adds entries.** The detector raises a prompt; this store records an
 *   answer. Nothing writes here on its own.
 */
class CompatibilityOriginStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("keen_compat_origins", Context.MODE_PRIVATE)

    fun isAllowed(host: String?): Boolean {
        val h = normalise(host) ?: return false
        if (h in PINNED_NORMAL) return false
        val at = prefs.getLong(h, 0L)
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > TTL_MS) {
            prefs.edit().remove(h).apply()
            return false
        }
        return true
    }

    fun allow(host: String?) {
        val h = normalise(host) ?: return
        if (h in PINNED_NORMAL) return
        prefs.edit().putLong(h, System.currentTimeMillis()).apply()
    }

    fun revoke(host: String?) {
        normalise(host)?.let { prefs.edit().remove(it).apply() }
    }

    fun allowedHosts(): Set<String> = prefs.all.keys.toSet()

    private fun normalise(host: String?): String? =
        host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    private companion object {
        /** Re-test in normal mode after two weeks. */
        const val TTL_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * Never eligible for compatibility mode: origins whose ad blocking, popup broker
         * and overlay guard do the most work, where losing them would be a worse outcome
         * than any challenge loop.
         */
        val PINNED_NORMAL = setOf("dlhd.st")
    }
}
