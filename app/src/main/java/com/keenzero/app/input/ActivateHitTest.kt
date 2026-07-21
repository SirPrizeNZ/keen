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

    fun isMagnetHref(h: String?): Boolean =
        h != null && h.trim().startsWith("magnet:", ignoreCase = true)

    /** Hrefs the primary anchor path may navigate: http(s) pages and magnet links. */
    fun isNavigableHref(h: String?): Boolean = isHttpHref(h) || isMagnetHref(h)

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

        // 2) Smallest any good http(s)/magnet link
        val anyLinks = under.filter {
            it.tag.equals("A", true) && isGoodHref(it.href) && isNavigableHref(it.href)
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

    // ── Nested icon / decorative-leaf → interactive ancestor ─────────────
    // Generic hit resolution: SVG/PATH/SPAN leaves must not retain activation.

    /** Leaf / wrapper tags that are never the real control. */
    private val DECORATIVE_LEAF_TAGS = setOf(
        "SVG", "PATH", "USE", "I", "SPAN", "IMG", "IMAGE",
        "G", "CIRCLE", "RECT", "POLYLINE", "POLYGON", "LINE", "ELLIPSE",
        "SYMBOL", "DEFS", "CLIPPATH", "MASK", "TITLE", "DESC",
        "STRONG", "B", "EM", "SMALL", "FONT", "U", "S", "SUB", "SUP",
    )

    data class HitNode(
        val id: String,
        val tag: String,
        val role: String = "",
        val href: String? = null,
        val rect: Rect,
        /** DOM depth; higher = deeper. */
        val depth: Int = 0,
        val disabled: Boolean = false,
        val hidden: Boolean = false,
        val pointerEventsNone: Boolean = false,
        val opacity: Double = 1.0,
        val displayNone: Boolean = false,
        val visibilityHidden: Boolean = false,
    )

    enum class ActivationKind {
        /** Native WebView touch only — no synthetic JS click first. */
        TRUSTED_TOUCH,
        LOCATION_ASSIGN,
        SYNTHETIC_CLICK,
        NONE,
    }

    data class NestedResolve(
        val target: HitNode,
        val kind: ActivationKind,
        /** When false, production must not dispatch element.click / MouseEvent first. */
        val allowSyntheticClick: Boolean,
        val reason: String,
        val leafId: String,
    )

    fun isDecorativeLeafTag(tag: String): Boolean =
        DECORATIVE_LEAF_TAGS.contains(tag.uppercase())

    /**
     * Interactive control tags/roles for nested promotion.
     * Does not depend on icon names, aria text, or hostnames.
     */
    fun isInteractiveControl(tag: String, role: String = "", href: String? = null): Boolean {
        val t = tag.uppercase()
        val r = role.lowercase()
        if (t == "BUTTON") return true
        if (r == "button") return true
        if (t == "A" && isGoodHref(href)) return true
        if (t == "INPUT" || t == "LABEL" || t == "SUMMARY" || t == "SELECT" || t == "TEXTAREA") return true
        return false
    }

    fun isPointerEligible(node: HitNode, px: Double, py: Double, pad: Double = 2.0): Boolean {
        if (node.disabled || node.hidden || node.displayNone || node.visibilityHidden) return false
        if (node.pointerEventsNone) return false
        if (node.opacity <= 0.0) return false
        if (node.rect.width < 1.0 || node.rect.height < 1.0) return false
        return node.rect.contains(px, py, pad)
    }

    /**
     * From an elementsFromPoint-ordered stack (topmost/deepest first), promote
     * decorative leaves to the **smallest** visible interactive ancestor/control
     * under the pointer.
     *
     * Non-interactive backdrops are never promoted. Hidden/disabled/pointer-ineligible
     * ancestors are rejected.
     */
    fun resolveNestedInteractive(
        px: Double,
        py: Double,
        /** elementsFromPoint order: index 0 = topmost leaf. */
        stackTopFirst: List<HitNode>,
    ): NestedResolve? {
        if (stackTopFirst.isEmpty()) return null
        val leaf = stackTopFirst.first()

        // Candidates: every stack node that is an eligible interactive control under pointer.
        // Prefer smallest area, then deepest (higher depth).
        val candidates = stackTopFirst.filter { n ->
            isInteractiveControl(n.tag, n.role, n.href) && isPointerEligible(n, px, py)
        }
        val best = candidates.minWithOrNull(
            compareBy<HitNode> { it.rect.area }.thenByDescending { it.depth },
        ) ?: return null

        // Do not "promote" a non-interactive full-viewport backdrop that slipped through —
        // isInteractiveControl already excludes plain DIV.
        val kind = activationKindFor(best)
        val allowSynthetic = kind == ActivationKind.SYNTHETIC_CLICK
        return NestedResolve(
            target = best,
            kind = kind,
            allowSyntheticClick = allowSynthetic,
            reason = when {
                isDecorativeLeafTag(leaf.tag) -> "promote_leaf_${leaf.tag.lowercase()}"
                leaf.id == best.id -> "direct_control"
                else -> "promote_stack"
            },
            leafId = leaf.id,
        )
    }

    fun activationKindFor(node: HitNode): ActivationKind {
        val t = node.tag.uppercase()
        val r = node.role.lowercase()
        if (t == "BUTTON" || r == "button") return ActivationKind.TRUSTED_TOUCH
        if (t == "INPUT") {
            // Text-like inputs are focus (handled elsewhere); button-ish → trusted.
            return ActivationKind.TRUSTED_TOUCH
        }
        if (t == "SUMMARY" || t == "SELECT") return ActivationKind.TRUSTED_TOUCH
        if (t == "A" && isGoodHref(node.href)) return ActivationKind.LOCATION_ASSIGN
        if (t == "LABEL" || t == "TEXTAREA") return ActivationKind.TRUSTED_TOUCH
        return ActivationKind.SYNTHETIC_CLICK
    }

    /** Production activate JS must request trusted touch for these methods. */
    fun methodRequiresTrustedTouch(method: String): Boolean =
        method == "trusted_button" ||
            method == "trusted_control" ||
            method == "playerControl" ||
            method == "clickTouch" ||
            method.startsWith("chevron")
}
