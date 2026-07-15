package com.keenzero.app.playback

import java.net.URI

/**
 * Isolates every new-window request. Never surfaces a requested window to the user.
 * Advertising and unknown destinations are destroyed; narrow exceptions only when a
 * live PlayIntent or same-origin authentication context exists.
 */
class PopupQuarantine(
    private val advertisingHosts: Set<String> = DEFAULT_AD_HOSTS,
) {
    enum class Verdict(val blocks: Boolean) {
        DESTROY_ADVERTISING(true),
        DESTROY_UNKNOWN(true),
        DESTROY_INVALID(true),
        DESTROY_EXTERNAL_SCHEME(true),
        /** No second WebView created — rejected at the window-open boundary. */
        REJECT_IMMEDIATE(true),
        ALLOW_PLAY_RESOLUTION(false),
        ALLOW_AUTH_SAME_ORIGIN(false),
    }

    /**
     * Deny-first pre-check before constructing a quarantine WebView.
     * When this returns a blocking verdict, [KeenWebChromeClient] must not create
     * a second WebView solely to classify the popup.
     */
    fun preflight(
        targetUrl: String?,
        isUserGesture: Boolean,
        playIntentActive: Boolean,
    ): Verdict? {
        // No gesture and no play context: never open a classification surface.
        if (!isUserGesture && !playIntentActive) {
            return Verdict.REJECT_IMMEDIATE
        }
        if (!targetUrl.isNullOrBlank()) {
            val early = decide(
                targetUrl = targetUrl,
                requestingOrigin = null,
                playIntentActive = playIntentActive,
                playOrigin = null,
            )
            if (early == Verdict.DESTROY_ADVERTISING ||
                early == Verdict.DESTROY_EXTERNAL_SCHEME ||
                early == Verdict.DESTROY_INVALID
            ) {
                return early
            }
            // Unknown host without play intent: reject without loading.
            if (!playIntentActive && early == Verdict.DESTROY_UNKNOWN) {
                return Verdict.REJECT_IMMEDIATE
            }
        }
        // Need a transient quarantine only for gesture/play resolution with unknown URL.
        return null
    }

    fun decide(
        targetUrl: String?,
        requestingOrigin: String?,
        playIntentActive: Boolean,
        playOrigin: String?,
    ): Verdict {
        val uri = parse(targetUrl) ?: return Verdict.DESTROY_INVALID
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Verdict.DESTROY_EXTERNAL_SCHEME

        val host = uri.host?.lowercase() ?: return Verdict.DESTROY_INVALID
        if (isAdvertising(host)) return Verdict.DESTROY_ADVERTISING

        val targetOrigin = originOf(uri) ?: return Verdict.DESTROY_INVALID
        if (requestingOrigin != null && targetOrigin == requestingOrigin) {
            return Verdict.ALLOW_AUTH_SAME_ORIGIN
        }

        if (playIntentActive) {
            // Source-resolution hop after deliberate Play: same registrable family or
            // known media CDN hostnames may proceed inside the single session.
            if (playOrigin != null && sameSiteFamily(playOrigin, targetOrigin)) {
                return Verdict.ALLOW_PLAY_RESOLUTION
            }
            if (isRecognisedMediaProvider(host)) {
                return Verdict.ALLOW_PLAY_RESOLUTION
            }
        }

        return Verdict.DESTROY_UNKNOWN
    }

    private fun isAdvertising(host: String): Boolean {
        if (host in advertisingHosts) return true
        return advertisingHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun isRecognisedMediaProvider(host: String): Boolean {
        return host.endsWith(".googlevideo.com") ||
            host == "interactive-examples.mdn.mozilla.net" ||
            host.endsWith(".cloudfront.net") ||
            host.endsWith(".akamaized.net")
    }

    private fun sameSiteFamily(a: String, b: String): Boolean {
        val ha = hostOf(a) ?: return false
        val hb = hostOf(b) ?: return false
        if (ha == hb) return true
        val ta = ha.split('.').takeLast(2).joinToString(".")
        val tb = hb.split('.').takeLast(2).joinToString(".")
        return ta == tb && ta.contains('.')
    }

    private fun hostOf(originOrUrl: String): String? = parse(originOrUrl)?.host?.lowercase()

    private fun originOf(uri: URI): String? {
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return "$scheme://$host${if (uri.port != -1) ":${uri.port}" else ""}"
    }

    private fun parse(url: String?): URI? = try {
        if (url.isNullOrBlank()) null else URI(url)
    } catch (_: Exception) {
        null
    }

    companion object {
        val DEFAULT_AD_HOSTS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "facebook.com",
            "adnxs.com",
            "ads.example",
            "pop.example",
            "evil.example",
            "tracker.example",
        )
    }
}
