package com.keenzero.app.blocking

/**
 * High-throughput network classifier for WebView / service-worker intercept.
 *
 * Design constraints (performance first):
 * - Immutable after compile; safe to share across threads.
 * - Main-frame documents are never blocked here (NavigationFirewall owns that).
 * - Host match: exact HashSet + suffix set (no linear scan of all rules).
 * - Path heuristics only for **third-party** subresources (reduces false positives).
 * - No regex, no JSON, no I/O on the match path.
 */
class RequestBlocker private constructor(
    private val exactHosts: Set<String>,
    private val suffixHosts: Set<String>,
    private val pathNeedles: Array<String>,
) {
    fun classify(
        url: String?,
        isMainFrame: Boolean,
        pageHost: String? = null,
        resourceTypeHint: ResourceType = ResourceType.OTHER,
    ): Result {
        if (isMainFrame) return Result.ALLOW_MAIN_FRAME
        if (url.isNullOrEmpty()) return Result.ALLOW_UNKNOWN

        val host = hostOf(url) ?: return Result.ALLOW_UNKNOWN
        if (hostBlocked(host)) return Result.BLOCK_HOST

        // Path heuristics only when third-party (or page host unknown) and not media.
        if (resourceTypeHint != ResourceType.MEDIA && resourceTypeHint != ResourceType.FONT) {
            val thirdParty = pageHost == null || !sameRegistrable(pageHost, host)
            if (thirdParty && pathLooksLikeAd(url)) {
                return Result.BLOCK_PATH
            }
        }
        return Result.ALLOW
    }

    /** Hot path for WebView: avoid re-parsing when host is already known. */
    fun classifyHost(
        host: String?,
        isMainFrame: Boolean,
        pageHost: String?,
        url: String?,
        resourceTypeHint: ResourceType,
    ): Result {
        if (isMainFrame) return Result.ALLOW_MAIN_FRAME
        if (host.isNullOrEmpty()) return Result.ALLOW_UNKNOWN
        // Bot-challenge infrastructure is never an ad. Checked FIRST, because these hosts
        // are third-party to the site being protected, so the path and DGA heuristics
        // below would otherwise apply to them — and "fingerprint" is one of our needles,
        // which is exactly the kind of asset a challenge fetches. Nothing is weakened: an
        // ad cannot serve from challenges.cloudflare.com, and the site-relative allowance
        // is limited to Cloudflare's own /cdn-cgi/challenge-platform/ path.
        if (isChallengeInfrastructure(host, url)) return Result.ALLOW
        if (hostBlocked(host)) return Result.BLOCK_HOST
        if (url != null &&
            resourceTypeHint != ResourceType.MEDIA &&
            resourceTypeHint != ResourceType.FONT
        ) {
            val thirdParty = pageHost == null || !sameRegistrable(pageHost, host)
            if (thirdParty && pathLooksLikeAd(url)) return Result.BLOCK_PATH
            // Machine-generated ("DGA") ad domains: streaming sites rotate a fresh random
            // host every load to defeat static lists (e.g. vqjxckklhqfjv.website,
            // rdipmrbwgvk.com serving the dating-cam overlay). Block the clearly-random ones
            // for third-party non-media subresources. Media paths (.m3u8/.ts/…) are excluded
            // so the stream host is never touched, and a safe-label allowlist protects CDNs.
            if (thirdParty && !isMediaPath(url) && looksRandomAdDomain(host)) {
                return Result.BLOCK_PATH
            }
        }
        return Result.ALLOW
    }

    /**
     * Verification-provider infrastructure, always allowed. Host-scoped: a blanket "/fp/"
     * allow would be a hole, ad networks fingerprint on that path too.
     */
    private fun isChallengeInfrastructure(host: String, url: String?): Boolean {
        val h = host.removePrefix("www.")
        if (h == "challenges.cloudflare.com" || h.endsWith(".challenges.cloudflare.com")) return true
        if (h == "hcaptcha.com" || h.endsWith(".hcaptcha.com")) return true
        if (h == "recaptcha.net" || h.endsWith(".recaptcha.net")) return true
        if (h == "arkoselabs.com" || h.endsWith(".arkoselabs.com")) return true
        if (h == "funcaptcha.com" || h.endsWith(".funcaptcha.com")) return true
        if (h == "captcha-delivery.com" || h.endsWith(".captcha-delivery.com")) return true
        if ((h == "google.com" || h.endsWith(".google.com")) &&
            url?.contains("/recaptcha/") == true
        ) {
            return true
        }
        // Cloudflare's own challenge assets, served from the protected site's own origin.
        return url?.contains("/cdn-cgi/challenge-platform/") == true
    }

    /** Public host-only check for non-request callers (e.g. popup / new-window policy). */
    fun blocksHost(host: String?): Boolean =
        !host.isNullOrEmpty() && hostBlocked(host.lowercase())

    private fun hostBlocked(host: String): Boolean {
        if (host in exactHosts) return true
        // Domain rule ".ads.example" stores "ads.example" — match host itself too.
        if (host in suffixHosts) return true
        // Walk labels rightward: a.b.c.example → b.c.example → c.example → example
        var start = 0
        while (true) {
            val dot = host.indexOf('.', start)
            if (dot < 0) break
            val suffix = host.substring(dot + 1)
            if (suffix in suffixHosts) return true
            start = dot + 1
        }
        return false
    }

    /** True for HLS/DASH/progressive media requests — never apply DGA heuristics to these. */
    private fun isMediaPath(url: String): Boolean {
        val q = url.indexOf('?')
        val path = (if (q >= 0) url.substring(0, q) else url).lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".mp4") ||
            path.endsWith(".m4s") || path.endsWith(".mpd") || path.endsWith(".key") ||
            path.endsWith(".aac") || path.endsWith(".webm") || path.endsWith(".mp3") ||
            path.contains(".m3u8?") || path.contains("/segment") || path.contains("/hls/")
    }

    /**
     * Conservative detector for machine-generated ad domains. Deliberately biased to
     * false-NEGATIVES: only fires on registrable labels that are near-impossible for a real
     * brand (all/near-all consonants, or a long consonant run), never on merely short or
     * vowel-light names (flickr/tumblr/chatango stay allowed). Real hosts are additionally
     * shielded by [SAFE_LABELS] and by the media-path exclusion in the caller.
     */
    private fun looksRandomAdDomain(host: String): Boolean {
        val h = host.removePrefix("www.")
        // Registrable main label (second-to-last piece: gjuiepjg from www.gjuiepjg.com).
        val parts = h.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        val label = parts[parts.size - 2]
        if (label.length < 6) return false
        if (label in SAFE_LABELS) return false
        if (label.any { !it.isLetter() && it != '-' }) return false // digits/odd -> handled by list
        val letters = label.filter { it.isLetter() }
        if (letters.length < 6) return false
        var vowels = 0
        var run = 0
        var maxRun = 0
        for (c in letters) {
            if (c in "aeiouy") {
                vowels++
                run = 0
            } else {
                run++
                if (run > maxRun) maxRun = run
            }
        }
        val ratio = vowels.toFloat() / letters.length
        // Egregious only: zero vowels, extreme consonant ratio on a long label, or a
        // 5+ consonant run. Pronounceable names (vowel every 2-3 chars) never match.
        if (vowels == 0 ||
            (ratio <= 0.12f && letters.length >= 9) ||
            maxRun >= 5
        ) {
            return true
        }

        // Second family, caught on-device 2026-07-25: cbarackvuvdfv.online served the
        // fake-chat creative over the player. 13 letters, 23% vowels, longest consonant
        // run only 4 — it slips under every rule above, as do mmirvipxdumpx.online and
        // fafeyrfqyxwivk.com. Loosening the ratio globally would start eating real hosts
        // (romponalis/cloudflare sit at 0.40), so gate the looser test on the throwaway
        // TLDs these domains are registered under. Verified: catches every known DGA host
        // here while leaving the stream chain, CDNs and a plausible real *.online alone.
        val tld = parts.last()
        if (tld in ABUSED_TLDS && letters.length >= 11 && ratio <= 0.36f) return true
        return false
    }

    private fun pathLooksLikeAd(url: String): Boolean {
        // Cheap: scan lowercased path region only (skip scheme/host when possible).
        val pathStart = pathStartIndex(url)
        val slice = if (pathStart >= 0) url.substring(pathStart) else url
        val lower = slice.lowercase()
        for (needle in pathNeedles) {
            if (lower.contains(needle)) return true
        }
        return false
    }

    enum class Result(val blocks: Boolean) {
        ALLOW(false),
        ALLOW_MAIN_FRAME(false),
        ALLOW_UNKNOWN(false),
        BLOCK_HOST(true),
        BLOCK_PATH(true),
    }

    enum class ResourceType {
        OTHER,
        SCRIPT,
        IMAGE,
        XHR,
        MEDIA,
        FONT,
        STYLESHEET,
    }

    companion object {
        val EMPTY = RequestBlocker(emptySet(), emptySet(), emptyArray())

        /**
         * Throwaway TLDs these rotating ad domains are registered under. Only used to gate
         * the looser DGA test — never to block on its own, and [SAFE_LABELS] still wins.
         */
        val ABUSED_TLDS: Set<String> = hashSetOf(
            "online", "site", "website", "space", "click", "xyz", "cyou", "sbs", "icu",
            "cfd", "buzz", "monster", "quest", "fun", "bar", "rest", "store", "shop",
            "wtf", "top", "live", "uno",
        )

        /** Registrable labels that must never trip the DGA heuristic (consonant-heavy but real). */
        val SAFE_LABELS: Set<String> = hashSetOf(
            "cloudflare", "cloudfront", "jsdelivr", "cdnjs", "gstatic", "googleapis",
            "youtube", "ytimg", "ggpht", "gvt1", "akamaized", "akamaihd", "fastly",
            "cloudflareinsights", "recaptcha", "gstaticadssl", "cdn", "cdninstagram",
            "fbcdn", "twimg", "licdn", "pinimg", "wp", "wordpress", "shopify",
            "chatango", "phantemlis", "romponalis", "swarmcloud", "wikimedia",
            // Stream delivery CDNs (HLS segments, incl. R2 buckets serving IMG_*.png segments).
            "cloudflarestorage", "backblazeb2", "wasabisys", "storage",
        )


        /** High-signal third-party path fragments (lowercase). Keep short. */
        val DEFAULT_PATH_NEEDLES: Array<String> = arrayOf(
            "/pagead",
            "/ads?",
            "/ads/",
            "/adserver",
            "/ad-serve",
            "/adserve",
            "doubleclick",
            "googlesyndication",
            "googleadservices",
            "/px.gif",
            "/pixel?",
            "adsystem",
            "/vast/",
            "prebid",
            "/banner",
            "/popunder",
            "/pop.js",
            "/ads.js",
            "/ad.js",
            "tracking.",
            "/track?",
            "/collect?",
            "fingerprint",
            "/sponsor",
            "/promo/",
            "clickunder",
            "/tag.js",
            "/tag.min.js",
            "babymaker",
            "ky6sbqy.png",
        )

        fun fromLines(
            lines: Sequence<String>,
            pathNeedles: Array<String> = DEFAULT_PATH_NEEDLES,
        ): RequestBlocker {
            val exact = HashSet<String>(64)
            val suffix = HashSet<String>(64)
            for (raw in lines) {
                val rule = raw.trim()
                if (rule.isEmpty() || rule.startsWith("#")) continue
                if (rule.startsWith(".")) {
                    suffix += rule.drop(1).lowercase()
                } else {
                    exact += rule.lowercase()
                }
            }
            return RequestBlocker(exact, suffix, pathNeedles)
        }

        fun hostOf(url: String): String? {
            // Avoid URI() allocation on the hot path.
            var start = url.indexOf("://")
            if (start < 0) return null
            start += 3
            if (start >= url.length) return null
            // skip userinfo
            val at = url.indexOf('@', start)
            val hostBegin = if (at > start && !url.substring(start, at).contains('/')) at + 1 else start
            var end = hostBegin
            while (end < url.length) {
                val c = url[end]
                if (c == '/' || c == '?' || c == '#' || c == ':') break
                end++
            }
            if (end <= hostBegin) return null
            return url.substring(hostBegin, end).lowercase()
        }

        private fun pathStartIndex(url: String): Int {
            val scheme = url.indexOf("://")
            if (scheme < 0) return 0
            val path = url.indexOf('/', scheme + 3)
            return if (path < 0) -1 else path
        }

        fun sameRegistrable(a: String, b: String): Boolean {
            if (a == b) return true
            val ta = a.split('.').takeLast(2).joinToString(".")
            val tb = b.split('.').takeLast(2).joinToString(".")
            return ta == tb && ta.contains('.')
        }

        fun resourceTypeOf(request: android.webkit.WebResourceRequest): ResourceType {
            // Accept headers are the portable signal; isForMainFrame already handled.
            val accept = request.requestHeaders?.entries
                ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                ?.value
                ?.lowercase()
                .orEmpty()
            return when {
                accept.contains("text/css") -> ResourceType.STYLESHEET
                accept.contains("javascript") || accept.contains("ecmascript") -> ResourceType.SCRIPT
                accept.contains("image/") -> ResourceType.IMAGE
                accept.contains("video/") || accept.contains("audio/") -> ResourceType.MEDIA
                accept.contains("font/") -> ResourceType.FONT
                accept.contains("json") || accept.contains("xml") -> ResourceType.XHR
                else -> ResourceType.OTHER
            }
        }
    }
}
