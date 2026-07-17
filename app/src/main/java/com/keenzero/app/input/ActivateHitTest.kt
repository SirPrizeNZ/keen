package com.keenzero.app.input

/**
 * Pure geometry activate pick (shipped policy).
 * Production pointer-activate JS in [RemoteInputRouter] must follow the same rules:
 * - only targets whose box contains the pointer
 * - never first content link in a rail-sized parent
 * - prefer smallest containing content link
 */
object ActivateHitTest {

    /** Shared with production pointer-activate JS in [RemoteInputRouter]. */
    const val CONTENT_HREF_JS_REGEX =
        """/\/movie\/|\/tv\/|\/show\/|\/title\/|\/watch\/|\/play\/|\/film\/|\/series\/|\/v\/|\/embed\//i"""

    const val RAIL_CLASS_JS_REGEX =
        """/swiper(?!-slide)|carousel|rail|row-scroll|slider-track|items\b/i"""

    const val RAIL_WIDTH_FRAC = 0.55
    const val RAIL_WIDE_FRAC = 0.38
    const val RAIL_TALL_FRAC = 0.42

    data class Rect(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val width: Double get() = (right - left).coerceAtLeast(0.0)
        val height: Double get() = (bottom - top).coerceAtLeast(0.0)
        val area: Double get() = width * height
        fun contains(x: Double, y: Double, pad: Double = 2.0): Boolean =
            x >= left - pad && x <= right + pad && y >= top - pad && y <= bottom + pad
    }

    data class Node(
        val id: String,
        val tag: String,
        val href: String?,
        val text: String,
        val className: String,
        val rect: Rect,
        /** DOM depth; higher = deeper (prefer for ties). */
        val depth: Int = 0,
    )

    data class PickResult(
        val node: Node,
        val method: Method,
        val reason: String,
    )

    enum class Method {
        LOCATION_ASSIGN,
        CLICK,
    }

    fun isGoodHref(h: String?): Boolean {
        if (h.isNullOrBlank()) return false
        val t = h.trim()
        if (t == "#" || t == "/" || t.startsWith("javascript:", ignoreCase = true)) return false
        if (t.startsWith("#")) return false
        return true
    }

    fun isContentHref(h: String?): Boolean {
        if (h.isNullOrBlank()) return false
        return Regex(
            """/(movie|tv|show|title|watch|play|film|series|v|embed)/""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(h)
    }

    fun isHttpHref(h: String?): Boolean =
        h != null && Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(h)

    fun isRailSized(rect: Rect, cssW: Double, cssH: Double, className: String = ""): Boolean {
        if (rect.width > cssW * 0.55) return true
        if (rect.width > cssW * 0.38 && rect.height > cssH * 0.42) return true
        if (Regex("""swiper(?!-slide)|carousel|rail|row-scroll|slider-track|\bitems\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(className)
        ) {
            return true
        }
        return false
    }

    /**
     * Pick activate target from nodes that could be under the pointer.
     * [candidates] should already be filtered to nodes that participate in hit testing
     * (e.g. elementsFromPoint stack + their link ancestors), all containing (px,py) optional
     * — this function re-checks containment.
     */
    fun pick(
        px: Double,
        py: Double,
        candidates: List<Node>,
        cssW: Double,
        cssH: Double,
        currentHost: String? = null,
    ): PickResult? {
        val under = candidates.filter { it.rect.contains(px, py) && !isRailSized(it.rect, cssW, cssH, it.className) }
        if (under.isEmpty()) return null

        // 1) Smallest content link containing point
        val contentLinks = under.filter {
            it.tag.equals("A", true) && isGoodHref(it.href) && isContentHref(it.href)
        }
        val bestContent = contentLinks.minWithOrNull(
            compareBy<Node> { it.rect.area }.thenByDescending { it.depth },
        )
        if (bestContent != null) {
            return PickResult(bestContent, Method.LOCATION_ASSIGN, "content_link")
        }

        // 2) Smallest any good http(s) link
        val anyLinks = under.filter {
            it.tag.equals("A", true) && isGoodHref(it.href) && isHttpHref(it.href)
        }
        val bestLink = anyLinks.minWithOrNull(
            compareBy<Node> { it.rect.area }.thenByDescending { it.depth },
        )
        if (bestLink != null) {
            val sameHost = currentHost != null && bestLink.href!!.contains(currentHost, ignoreCase = true)
            val pathish = isContentHref(bestLink.href)
            val method = if (!sameHost || pathish) Method.LOCATION_ASSIGN else Method.LOCATION_ASSIGN
            return PickResult(bestLink, method, "http_link")
        }

        // 3) Smallest card-like non-rail node (SPA click)
        val cards = under.filter {
            Regex("""card|poster|tile|movie|slide|thumb|media""", RegexOption.IGNORE_CASE)
                .containsMatchIn(it.className + it.tag) ||
                it.tag.equals("ARTICLE", true) || it.tag.equals("LI", true)
        }
        val bestCard = cards.minWithOrNull(
            compareBy<Node> { it.rect.area }.thenByDescending { it.depth },
        )
        if (bestCard != null) {
            return PickResult(bestCard, Method.CLICK, "card")
        }

        // 4) Deepest/smallest remaining node
        val leaf = under.minWithOrNull(
            compareBy<Node> { it.rect.area }.thenByDescending { it.depth },
        ) ?: return null
        return PickResult(leaf, Method.CLICK, "leaf")
    }

    /**
     * Multi-title safety: two different pointer positions on two poster links
     * must not resolve to the same wrong first-rail link.
     */
    fun assertDistinctPicks(
        a: PickResult?,
        b: PickResult?,
    ): Boolean {
        if (a == null || b == null) return false
        return a.node.id != b.node.id && a.node.href != b.node.href
    }
}
