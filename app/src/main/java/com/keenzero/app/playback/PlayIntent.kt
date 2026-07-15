package com.keenzero.app.playback

/**
 * Formal record of a deliberate centre-button activation on a Play-like control.
 * Browser gesture flags alone are not sufficient proof of intent.
 */
data class PlayIntent(
    val id: String,
    val origin: String?,
    val url: String?,
    val focusedFingerprint: String?,
    val role: String?,
    val visibleText: String?,
    val expectedHref: String?,
    val geometry: String?,
    val contentId: String?,
    val timestampElapsedMs: Long,
    val wallClockMs: Long = System.currentTimeMillis(),
) {
    fun isActive(nowElapsedMs: Long, windowMs: Long = ACTIVE_WINDOW_MS): Boolean =
        nowElapsedMs - timestampElapsedMs in 0..windowMs

    companion object {
        const val ACTIVE_WINDOW_MS = 12_000L
    }
}
