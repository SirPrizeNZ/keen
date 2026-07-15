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
    }

    fun clearPlayIntent() {
        activePlayIntent = null
    }

    fun activePlayIntent(): PlayIntent? = activePlayIntent?.takeIf { it.isActive(clock()) }

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
     * New-window / popup path. Never returns a soft allow that would display a
     * separate window — caller must either destroy or (rarely) load in-session.
     */
    fun decideNewWindow(url: String?): Decision {
        val play = activePlayIntent()
        val verdict = quarantine.decide(
            targetUrl = url,
            requestingOrigin = topLevelOrigin,
            playIntentActive = play != null,
            playOrigin = play?.origin,
        )
        return when (verdict) {
            PopupQuarantine.Verdict.DESTROY_ADVERTISING -> Decision.BLOCK_POPUP_AD
            PopupQuarantine.Verdict.DESTROY_EXTERNAL_SCHEME -> Decision.BLOCK_EXTERNAL_SCHEME
            PopupQuarantine.Verdict.DESTROY_INVALID -> Decision.BLOCK_INVALID_URL
            PopupQuarantine.Verdict.DESTROY_UNKNOWN,
            PopupQuarantine.Verdict.REJECT_IMMEDIATE,
            -> Decision.BLOCK_POPUP
            PopupQuarantine.Verdict.ALLOW_AUTH_SAME_ORIGIN -> {
                navigationWindowUntil = clock() + REDIRECT_CHAIN_WINDOW_MS
                Decision.ALLOW_SAME_SESSION
            }
            PopupQuarantine.Verdict.ALLOW_PLAY_RESOLUTION -> {
                navigationWindowUntil = clock() + REDIRECT_CHAIN_WINDOW_MS
                Decision.ALLOW_PLAY_RESOLUTION
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
