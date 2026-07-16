package com.keenzero.app.navigation

import com.keenzero.app.playback.PopupQuarantine
import java.net.URI

/**
 * Resolves new-window / popup attempts.
 *
 * Rules:
 * - Automatic junk → BLOCK
 * - Confident deliberate destinations → OPEN_CURRENT_SESSION
 * - Deliberate but high-risk mismatch → REQUIRE_CONFIRMATION (never silent drop)
 * - One grant authorises one outcome (caller must consume when consumeGrant=true)
 * - about:blank with authority → provisional capture path (not visible main load)
 */
class WindowRequestBroker(
    private val quarantine: PopupQuarantine = PopupQuarantine(),
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    enum class Action {
        OPEN_CURRENT_SESSION,
        REQUIRE_CONFIRMATION,
        BLOCK,
        /** Authorised blank window: capture destination in hidden provisional WebView. */
        PROVISIONAL_CAPTURE,
    }

    data class Decision(
        val action: Action,
        val reason: String,
        val consumeGrant: Boolean,
        val destinationHost: String? = null,
    )

    fun decide(
        targetUrl: String?,
        isUserGesture: Boolean,
        pageOrigin: String?,
        grant: ActivationLedger.ActivationGrant?,
        playIntentActive: Boolean,
        playOrigin: String?,
    ): Decision {
        val now = clock()
        val liveGrant = grant?.takeIf { it.isLive(now) }
        val blank = targetUrl.isNullOrBlank() ||
            targetUrl.equals("about:blank", ignoreCase = true)

        if (blank) {
            if (liveGrant != null || isUserGesture || playIntentActive) {
                return Decision(Action.PROVISIONAL_CAPTURE, "provisional_blank_authorised", consumeGrant = false)
            }
            return Decision(Action.BLOCK, "blank_no_activation", consumeGrant = false)
        }

        val host = hostOf(targetUrl)
        val v = quarantine.decide(
            targetUrl = targetUrl,
            requestingOrigin = pageOrigin,
            playIntentActive = playIntentActive,
            playOrigin = playOrigin,
        )
        when (v) {
            PopupQuarantine.Verdict.DESTROY_ADVERTISING ->
                return Decision(Action.BLOCK, "ad_host", consumeGrant = true, destinationHost = host)
            PopupQuarantine.Verdict.DESTROY_EXTERNAL_SCHEME ->
                return Decision(Action.BLOCK, "external_scheme", consumeGrant = true, destinationHost = host)
            PopupQuarantine.Verdict.DESTROY_INVALID ->
                return Decision(Action.BLOCK, "invalid_url", consumeGrant = true, destinationHost = host)
            PopupQuarantine.Verdict.ALLOW_PLAY_RESOLUTION ->
                return Decision(Action.OPEN_CURRENT_SESSION, "play_resolution", consumeGrant = true, destinationHost = host)
            PopupQuarantine.Verdict.ALLOW_AUTH_SAME_ORIGIN ->
                return Decision(Action.OPEN_CURRENT_SESSION, "auth_same_origin", consumeGrant = true, destinationHost = host)
            else -> Unit
        }

        if (liveGrant == null && !isUserGesture && !playIntentActive) {
            return Decision(Action.BLOCK, "no_activation", consumeGrant = false, destinationHost = host)
        }

        if (liveGrant != null) {
            val href = liveGrant.expectedHref
            if (!href.isNullOrBlank() && urlsMatch(href, targetUrl)) {
                return Decision(Action.OPEN_CURRENT_SESSION, "grant_href_match", consumeGrant = true, destinationHost = host)
            }
            // Same-origin as page: confident ordinary navigation / blank target.
            if (pageOrigin != null && sameOrigin(pageOrigin, targetUrl)) {
                return Decision(Action.OPEN_CURRENT_SESSION, "same_origin_deliberate", consumeGrant = true, destinationHost = host)
            }
            // Expected href host family matches destination.
            if (!href.isNullOrBlank() && sameHostFamily(href, targetUrl)) {
                return Decision(Action.OPEN_CURRENT_SESSION, "grant_host_family", consumeGrant = true, destinationHost = host)
            }
            if (liveGrant.type == ActivationLedger.Type.PLAY || playIntentActive) {
                // Deliberate Play already cleared ad/scheme checks — open player path in-session.
                return Decision(Action.OPEN_CURRENT_SESSION, "grant_play", consumeGrant = true, destinationHost = host)
            }
            // Deliberate activation, high-risk mismatch — never silent drop; native confirm required.
            return Decision(Action.REQUIRE_CONFIRMATION, "deliberate_uncertain", consumeGrant = true, destinationHost = host)
        }

        // Platform gesture without grant: same-origin open; cross-origin confirm.
        if (isUserGesture || playIntentActive) {
            if (pageOrigin != null && sameOrigin(pageOrigin, targetUrl)) {
                return Decision(Action.OPEN_CURRENT_SESSION, "gesture_same_origin", consumeGrant = false, destinationHost = host)
            }
            return Decision(Action.REQUIRE_CONFIRMATION, "gesture_cross_origin", consumeGrant = false, destinationHost = host)
        }

        return Decision(Action.BLOCK, "default_block", consumeGrant = false, destinationHost = host)
    }

    private fun urlsMatch(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val e = expected.substringBefore('#').trimEnd('/')
        val a = actual.substringBefore('#').trimEnd('/')
        if (e == a) return true
        return try {
            val ue = URI(expected)
            val ua = URI(actual)
            ue.host.equals(ua.host, ignoreCase = true) &&
                (ue.path ?: "/") == (ua.path ?: "/")
        } catch (_: Exception) {
            false
        }
    }

    private fun sameOrigin(originOrUrl: String, targetUrl: String): Boolean {
        return try {
            val a = URI(originOrUrl)
            val b = URI(targetUrl)
            a.scheme.equals(b.scheme, true) &&
                a.host.equals(b.host, true) &&
                a.port == b.port
        } catch (_: Exception) {
            false
        }
    }

    private fun sameHostFamily(a: String, b: String): Boolean {
        val ha = hostOf(a) ?: return false
        val hb = hostOf(b) ?: return false
        if (ha == hb) return true
        val ta = ha.split('.').takeLast(2).joinToString(".")
        val tb = hb.split('.').takeLast(2).joinToString(".")
        return ta == tb && ta.contains('.')
    }

    private fun hostOf(url: String?): String? = try {
        if (url.isNullOrBlank()) null else URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }
}
