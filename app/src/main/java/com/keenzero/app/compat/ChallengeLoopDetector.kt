package com.keenzero.app.compat

import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * Spots a Cloudflare verification loop in the *normal* WebView, so Keen can offer the
 * compatibility WebView instead of leaving the user stuck on a spinner.
 *
 * Two things make an eager trigger safe here:
 *
 * 1. **Authenticity is checked, not assumed.** A challenge only counts when the response
 *    carries Cloudflare's own headers (`cf-ray`, `server: cloudflare`) on a main-frame
 *    403. Page text is never trusted — ad interstitials imitate challenge wording, and
 *    that is exactly how a hostile site would try to talk Keen out of its ad blocking.
 * 2. **Detection only ever raises a prompt.** Nothing is disabled without the user
 *    agreeing, so a false positive costs one dismissible message rather than silently
 *    surrendering protection. That is what lets the threshold be a single failure
 *    instead of a long wait — one repeat, or one stalled challenge, is enough.
 */
class ChallengeLoopDetector(
    private val onLoopDetected: (host: String, url: String, reason: String) -> Unit,
) {

    private var host: String? = null
    private var challengeCount = 0
    private var firstChallengeAt = 0L
    private var lastChallengeUrl: String? = null
    private var contentLoaded = false
    private var notified = false

    /**
     * Did the main-frame response for the *current* commit come back as a challenge?
     *
     * A Cloudflare interstitial finishes loading like any other page, and its main-frame
     * URL is the site's own URL — the `/cdn-cgi/challenge-platform/` part lives in
     * subresources. Without this, every challenge's own onPageFinished looked like
     * "content arrived" and reset the counter, so the loop could never be counted.
     */
    private var challengeThisCommit = false

    /** When the last genuine Cloudflare challenge response was seen. */
    private var lastChallengeAt = 0L

    /** A main-frame HTTP error; the only place Cloudflare's headers are visible to us. */
    fun onMainFrameHttpError(url: String?, status: Int, headers: Map<String, String>?) {
        if (status != 403 && status != 503) return
        if (!isCloudflare(headers)) return
        record(url, "http_$status")
    }

    /** A committed main-frame URL that is itself the challenge platform. */
    fun onMainFrameCommitted(url: String?) {
        val h = hostOf(url)
        if (h != null && h != host) reset(h)
        challengeThisCommit = false
        if (url != null && url.contains("/cdn-cgi/challenge-platform/")) {
            record(url, "challenge_url")
        }
    }

    /** Real site content settled — clears the loop state for this origin. */
    fun onContentLoaded(url: String?) {
        if (url != null && url.contains("/cdn-cgi/challenge-platform/")) return
        // The interstitial finishes loading exactly like a real page, so "finished" alone
        // proves nothing. Ordering between onReceivedHttpError and onPageFinished is not
        // guaranteed either, which defeated the per-commit flag: a finish that arrived
        // first cleared the counter before the 403 could raise it. Time is the reliable
        // discriminator — a finish landing within a moment of a challenge response is
        // that challenge, not the site.
        val sinceChallenge = SystemClock.elapsedRealtime() - lastChallengeAt
        if (challengeThisCommit || (lastChallengeAt != 0L && sinceChallenge < CHALLENGE_GRACE_MS)) {
            Log.i(TAG, "content_ignored host=$host sinceChallengeMs=$sinceChallenge")
            return
        }
        Log.i(TAG, "content_loaded host=$host — loop state cleared")
        contentLoaded = true
        challengeCount = 0
        notified = false
    }

    /**
     * Called on a timer while a challenge is on screen. A challenge that never yields
     * content is the "spinner forever" case — no reload needed to call it stuck.
     */
    fun checkStall() {
        if (notified || challengeCount == 0 || contentLoaded) return
        val heldMs = SystemClock.elapsedRealtime() - firstChallengeAt
        if (heldMs >= STALL_MS) fire("stalled_${heldMs / 1000}s")
    }

    private fun record(url: String?, reason: String) {
        val h = hostOf(url) ?: host ?: return
        if (h != host) reset(h)
        lastChallengeUrl = url
        contentLoaded = false
        challengeThisCommit = true
        lastChallengeAt = SystemClock.elapsedRealtime()
        if (challengeCount == 0) firstChallengeAt = SystemClock.elapsedRealtime()
        challengeCount++
        Log.i(TAG, "challenge host=$h count=$challengeCount reason=$reason")
        // One repeat = the challenge was served, completed or not, and served again.
        if (challengeCount >= REPEAT_THRESHOLD) fire("repeat_x$challengeCount")
    }

    private fun fire(reason: String) {
        if (notified) return
        val h = host ?: return
        notified = true
        Log.i(TAG, "loop_detected host=$h reason=$reason")
        onLoopDetected(h, lastChallengeUrl ?: "https://$h/", reason)
    }

    private fun reset(newHost: String?) {
        host = newHost
        challengeCount = 0
        firstChallengeAt = 0L
        contentLoaded = false
        challengeThisCommit = false
        lastChallengeAt = 0L
        notified = false
    }

    /** Genuine Cloudflare edge response, not a page that merely looks like one. */
    private fun isCloudflare(headers: Map<String, String>?): Boolean {
        if (headers == null) return false
        val lower = headers.entries.associate { it.key.lowercase() to it.value.lowercase() }
        return lower.containsKey("cf-ray") || lower["server"]?.contains("cloudflare") == true
    }

    private fun hostOf(url: String?): String? = try {
        url?.let { Uri.parse(it).host?.lowercase()?.removePrefix("www.") }
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val TAG = "KZ_CHALLENGE"

        /** Two challenge responses for one origin: served, then served again. */
        const val REPEAT_THRESHOLD = 2

        /**
         * How long a challenge may sit without producing content before Keen switches.
         *
         * Not zero, deliberately. Firing on the first Cloudflare response would also
         * promote every site whose interstitial clears on its own in a second or two —
         * and those sites would lose ad blocking for nothing. A genuine quick pass is
         * done well inside this window; a challenge still showing after it is one this
         * configuration cannot pass, which is precisely the case worth switching on.
         */
        const val STALL_MS = 4_000L

        /** A page finishing this soon after a challenge response *is* that challenge. */
        const val CHALLENGE_GRACE_MS = 5_000L
    }
}
