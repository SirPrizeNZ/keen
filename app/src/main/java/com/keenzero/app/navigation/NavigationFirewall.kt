package com.keenzero.app.navigation

import android.os.SystemClock
import android.webkit.WebResourceRequest
import com.keenzero.app.playback.PlayIntent
import com.keenzero.app.playback.PopupQuarantine
import java.net.URI

/**
 * Small, fail-closed policy for the single browsing session.
 *
 * A remote action creates a short-lived navigation intent. A formal PlayIntent
 * additionally authorises recognised source-resolution hops. Unsolicited
 * cross-origin top-level navigation and quarantined popups do not escape.
 */
class NavigationFirewall(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val quarantine: PopupQuarantine = PopupQuarantine(),
    val activationLedger: ActivationLedger = ActivationLedger(clock),
) {
    private var topLevelOrigin: String? = null
    private var lastInputAt: Long = Long.MIN_VALUE
    private var navigationWindowUntil: Long = Long.MIN_VALUE
    private var activePlayIntent: PlayIntent? = null

    fun recordUserInput() {
        lastInputAt = clock()
        navigationWindowUntil = lastInputAt + USER_NAVIGATION_WINDOW_MS
    }

    fun recordPlayIntent(intent: PlayIntent) {
        activePlayIntent = intent
        navigationWindowUntil = clock() + PlayIntent.ACTIVE_WINDOW_MS
        activationLedger.record(
            type = ActivationLedger.Type.PLAY,
            sourceOrigin = intent.origin,
            expectedHref = intent.expectedHref ?: intent.url,
            elementRole = intent.role,
            fingerprint = intent.focusedFingerprint,
        )
    }

    fun clearPlayIntent() {
        activePlayIntent = null
    }

    fun clearActivation() {
        activationLedger.clear()
    }

    fun activePlayIntent(): PlayIntent? = activePlayIntent?.takeIf { it.isActive(clock()) }

    fun topLevelOrigin(): String? = topLevelOrigin

    fun recordCommittedUrl(url: String?) {
        topLevelOrigin = url?.let(::origin)
    }

    fun decide(request: WebResourceRequest?): Decision {
        return decide(
            url = request?.url?.toString(),
            isMainFrame = request?.isForMainFrame == true,
            hasGesture = request?.hasGesture() == true,
        )
    }

    fun decide(url: String?, isMainFrame: Boolean, hasGesture: Boolean): Decision {
        val parsed = url?.let(::parse) ?: return Decision.BLOCK_INVALID_URL
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Decision.BLOCK_EXTERNAL_SCHEME
        if (!isMainFrame) return Decision.ALLOW_CURRENT

        val targetOrigin = origin(url)
        val sourceOrigin = topLevelOrigin
        if (sourceOrigin == null || sourceOrigin == targetOrigin) return Decision.ALLOW_CURRENT

        // Advertising destinations never replace the top-level page.
        val host = parsed.host?.lowercase()
        if (host != null && quarantine.decide(
                targetUrl = url,
                requestingOrigin = sourceOrigin,
                playIntentActive = false,
                playOrigin = null,
            ) == PopupQuarantine.Verdict.DESTROY_ADVERTISING
        ) {
            return Decision.BLOCK_ADVERTISING_NAV
        }

        val play = activePlayIntent()
        if (play != null) {
            val v = quarantine.decide(
                targetUrl = url,
                requestingOrigin = sourceOrigin,
                playIntentActive = true,
                playOrigin = play.origin,
            )
            if (!v.blocks) {
                navigationWindowUntil = clock() + REDIRECT_CHAIN_WINDOW_MS
                return Decision.ALLOW_PLAY_RESOLUTION
            }
            if (v == PopupQuarantine.Verdict.DESTROY_ADVERTISING) {
                return Decision.BLOCK_ADVERTISING_NAV
            }
        }

        val now = clock()
        val hasRecentInput = now <= navigationWindowUntil
        if (hasGesture || hasRecentInput) {
            navigationWindowUntil = now + REDIRECT_CHAIN_WINDOW_MS
            return Decision.ALLOW_SAME_SESSION
        }
        return Decision.BLOCK_UNINTENDED_REDIRECT
    }

    /**
     * New-window / popup path via [WindowRequestBroker].
     * Deliberate activations map to same-session load; automatic junk is blocked.
     */
    fun decideNewWindow(url: String?, isUserGesture: Boolean = false): Decision {
        val play = activePlayIntent()
        val broker = WindowRequestBroker(quarantine, clock)
        val d = broker.decide(
            targetUrl = url,
            isUserGesture = isUserGesture,
            pageOrigin = topLevelOrigin,
            grant = activationLedger.peek(),
            playIntentActive = play != null,
            playOrigin = play?.origin,
        )
        return when (d.action) {
            WindowRequestBroker.Action.BLOCK -> {
                when (d.reason) {
                    "ad_host", "ad_host_heuristic" -> Decision.BLOCK_POPUP_AD
                    "external_scheme" -> Decision.BLOCK_EXTERNAL_SCHEME
                    "invalid_url" -> Decision.BLOCK_INVALID_URL
                    else -> Decision.BLOCK_POPUP
                }
            }
            WindowRequestBroker.Action.OPEN_CURRENT_SESSION,
            WindowRequestBroker.Action.PROVISIONAL_CAPTURE,
            -> {
                navigationWindowUntil = clock() + REDIRECT_CHAIN_WINDOW_MS
                if (d.reason == "play_resolution") Decision.ALLOW_PLAY_RESOLUTION
                else Decision.ALLOW_SAME_SESSION
            }
            WindowRequestBroker.Action.REQUIRE_CONFIRMATION -> {
                // Not a silent drop — UI must present Open/Cancel. Policy allows the
                // navigation only after explicit native confirmation.
                Decision.REQUIRE_CONFIRMATION
            }
        }
    }

    private fun origin(url: String): String? = parse(url)?.let { uri ->
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        "$scheme://$host${if (uri.port != -1) ":${uri.port}" else ""}"
    }

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: Exception) {
        null
    }

    enum class Decision(val blocks: Boolean) {
        ALLOW_CURRENT(false),
        ALLOW_SAME_SESSION(false),
        ALLOW_PLAY_RESOLUTION(false),
        /** Deliberate high-risk mismatch — must surface native confirmation, not auto-nav. */
        REQUIRE_CONFIRMATION(true),
        BLOCK_EXTERNAL_SCHEME(true),
        BLOCK_UNINTENDED_REDIRECT(true),
        BLOCK_POPUP(true),
        BLOCK_POPUP_AD(true),
        BLOCK_ADVERTISING_NAV(true),
        BLOCK_INVALID_URL(true),
    }

    private companion object {
        const val USER_NAVIGATION_WINDOW_MS = 1_250L
        const val REDIRECT_CHAIN_WINDOW_MS = 4_000L
    }
}
