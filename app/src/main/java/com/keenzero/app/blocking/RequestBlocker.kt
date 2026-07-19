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
        if (hostBlocked(host)) return Result.BLOCK_HOST
        if (url != null &&
            resourceTypeHint != ResourceType.MEDIA &&
            resourceTypeHint != ResourceType.FONT
        ) {
            val thirdParty = pageHost == null || !sameRegistrable(pageHost, host)
            if (thirdParty && pathLooksLikeAd(url)) return Result.BLOCK_PATH
        }
        return Result.ALLOW
    }

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
