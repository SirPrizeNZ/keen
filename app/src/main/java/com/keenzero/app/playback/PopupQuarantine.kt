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
        val h = host.lowercase().removePrefix("www.")
        if (h in advertisingHosts) return true
        if (advertisingHosts.any { h == it || h.endsWith(".$it") }) return true
        // Generated redirect/ad farms (e.g. hai8g.com) are not on static lists.
        return looksDisposableAdHost(h)
    }

    /**
     * Heuristic for throwaway ad/redirect hosts that never deserve a confirm dialog.
     * Tuned for streaming-site junk: short nonsense labels + cheap TLDs + digits.
     * Must not classify real brands (netflix, youtube, bcine, …).
     */
    fun looksDisposableAdHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (h in SAFE_BRAND_HOSTS || SAFE_BRAND_HOSTS.any { h == it || h.endsWith(".$it") }) {
            return false
        }
        val parts = h.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        val name = parts[parts.size - 2]
        val tld = parts.last()
        if (tld !in JUNK_TLDS) return false
        if (name.length !in 4..12) return false
        if (!name.all { it.isLetterOrDigit() || it == '-' }) return false
        // Explicit known junk samples / patterns.
        if (name in KNOWN_JUNK_LABELS) return true
        val letters = name.filter { it.isLetter() }
        val digits = name.count { it.isDigit() }
        val vowels = letters.count { it in "aeiou" }
        // hai8g-style: short label with digits (generated campaign hosts).
        if (digits > 0 && name.length <= 8 && letters.length >= 3) return true
        // Consonant soup / near-zero vowels (generated).
        if (letters.length in 4..8 && vowels <= 1 && digits == 0) return true
        // Pure random alnum 5–7 with mixed digits+letters.
        if (name.length in 5..7 && digits >= 1 && vowels <= 2) return true
        return false
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
            // Common streaming ad / click-under farms (extend with evidence, not guesses alone).
            "hai8g.com",
            "popads.net",
            "propellerads.com",
            "adsterra.com",
            "exoclick.com",
            "juicyads.com",
            "clickadu.com",
            "trafficjunky.com",
            "adcash.com",
            "hilltopads.com",
        )

        /** Registrable labels / hosts that must never be treated as disposable ads. */
        private val SAFE_BRAND_HOSTS = setOf(
            "google", "youtube", "youtu", "netflix", "amazon", "primevideo", "disney", "hulu",
            "spotify", "twitch", "vimeo", "cloudflare", "github", "microsoft", "apple",
            "bcine", "coreflix", "fmhy", "imdb", "tmdb", "themoviedb", "wikipedia",
            "reddit", "discord", "telegram", "whatsapp", "twitter", "x", "facebook",
            "instagram", "tiktok", "akamai", "cloudfront", "fastly", "jsdelivr", "unpkg",
            "gstatic", "googleapis", "gvt1", "ggpht", "ytimg",
        )

        private val JUNK_TLDS = setOf(
            "com", "net", "xyz", "top", "click", "link", "live", "site", "online",
            "icu", "buzz", "work", "space", "fun", "pw", "cc", "icu", "rest", "quest",
            "cfd", "sbs", "cyou", "shop", "store", "pro", "info", "biz",
        )

        private val KNOWN_JUNK_LABELS = setOf(
            "hai8g", "popads", "adsterra", "exoclick", "juicyads", "clickadu",
        )
    }
}
