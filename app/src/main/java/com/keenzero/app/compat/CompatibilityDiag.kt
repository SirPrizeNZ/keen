package com.keenzero.app.compat

import android.os.SystemClock
import android.util.Log

/**
 * Structured, single-tag diagnostics for compatibility mode.
 *
 * Deliberately records *presence* and *state*, never content: no cookie values, no
 * challenge tokens, no form data, no page text. `cf_clearance=true` is a fact about the
 * session; the token itself is a credential and never reaches logcat.
 *
 * Read with: `adb logcat -s KZ_COMPAT`
 */
object CompatibilityDiag {

    const val TAG = "KZ_COMPAT"

    private val startedAt = SystemClock.elapsedRealtime()

    /** Monotonic ms since process start — immune to wall-clock jumps mid-challenge. */
    private fun t(): Long = SystemClock.elapsedRealtime() - startedAt

    fun event(name: String, instanceId: Int? = null, vararg fields: Pair<String, Any?>) {
        val body = buildString {
            append("t=").append(t())
            append(" ev=").append(name)
            append(" mode=compat")
            if (instanceId != null) append(" wv#").append(instanceId)
            for ((k, v) in fields) {
                if (v == null) continue
                append(' ').append(k).append('=').append(v)
            }
        }
        Log.i(TAG, body)
    }

    /** Hash rather than log the UA: identifies drift without printing a fingerprint. */
    fun uaHash(ua: String?): String =
        if (ua.isNullOrEmpty()) "none" else Integer.toHexString(ua.hashCode())
}
