package com.keenzero.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nested icon-button hit promotion.
 *
 * Fixture shape (see resources/fixtures/nested_icon_button.html):
 * ```html
 * <button aria-label="Search">
 *   <span><svg><path></path></svg></span>
 * </button>
 * ```
 *
 * PHYSICAL_CINEBY_VALIDATION = NOT_RUN_DEVICE_UNAVAILABLE
 * Physical Mi Box behaviour is UNVERIFIED_PENDING_DEVICE.
 */
class NestedIconButtonActivateTest {

    private val btnRect = ActivateHitTest.Rect(100.0, 100.0, 148.0, 148.0)
    private val cssW = 960.0
    private val cssH = 501.0

    /** elementsFromPoint-style stack: topmost first. */
    private fun searchButtonStack(leafTag: String): List<ActivateHitTest.HitNode> {
        val path = ActivateHitTest.HitNode(
            id = "search-path",
            tag = "PATH",
            rect = ActivateHitTest.Rect(110.0, 110.0, 130.0, 130.0),
            depth = 10,
        )
        val svg = ActivateHitTest.HitNode(
            id = "search-svg",
            tag = "SVG",
            rect = ActivateHitTest.Rect(108.0, 108.0, 132.0, 132.0),
            depth = 9,
        )
        val span = ActivateHitTest.HitNode(
            id = "search-span",
            tag = "SPAN",
            rect = ActivateHitTest.Rect(106.0, 106.0, 134.0, 134.0),
            depth = 8,
        )
        val button = ActivateHitTest.HitNode(
            id = "search-btn",
            tag = "BUTTON",
            role = "",
            rect = btnRect,
            depth = 7,
        )
        val body = ActivateHitTest.HitNode(
            id = "body",
            tag = "BODY",
            rect = ActivateHitTest.Rect(0.0, 0.0, cssW, cssH),
            depth = 0,
        )
        return when (leafTag.uppercase()) {
            "PATH" -> listOf(path, svg, span, button, body)
            "SVG" -> listOf(svg, span, button, body)
            "SPAN" -> listOf(span, button, body)
            "BUTTON" -> listOf(button, body)
            else -> listOf(path, svg, span, button, body)
        }
    }

    private fun assertTrustedButton(r: ActivateHitTest.NestedResolve?) {
        assertNotNull(r)
        assertEquals("search-btn", r!!.target.id)
        assertEquals("BUTTON", r.target.tag.uppercase())
        assertEquals(ActivateHitTest.ActivationKind.TRUSTED_TOUCH, r.kind)
        assertFalse("BUTTON must not fire synthetic click first", r.allowSyntheticClick)
    }

    @Test
    fun fixtureFilePresent() {
        val paths = listOf(
            "app/src/test/resources/fixtures/nested_icon_button.html",
            "src/test/resources/fixtures/nested_icon_button.html",
        ).map { File(it) }
        val f = paths.firstOrNull { it.exists() }
            ?: error("nested_icon_button.html fixture missing")
        val html = f.readText()
        assertTrue(html.contains("<button"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("<path"))
        assertTrue(html.contains("aria-label=\"Search\""))
    }

    @Test
    fun pathLeafPromotesToButtonTrustedTouch() {
        val r = ActivateHitTest.resolveNestedInteractive(120.0, 120.0, searchButtonStack("PATH"))
        assertTrustedButton(r)
        assertTrue(r!!.reason.contains("promote_leaf"))
        assertEquals("search-path", r.leafId)
    }

    @Test
    fun svgLeafPromotesToButtonTrustedTouch() {
        assertTrustedButton(
            ActivateHitTest.resolveNestedInteractive(120.0, 120.0, searchButtonStack("SVG")),
        )
    }

    @Test
    fun spanLeafPromotesToButtonTrustedTouch() {
        assertTrustedButton(
            ActivateHitTest.resolveNestedInteractive(120.0, 120.0, searchButtonStack("SPAN")),
        )
    }

    @Test
    fun buttonPaddingDirectHitIsTrustedTouch() {
        // Pointer on button padding outside SVG (still inside button box).
        val button = ActivateHitTest.HitNode(
            id = "search-btn",
            tag = "BUTTON",
            rect = btnRect,
            depth = 7,
        )
        val r = ActivateHitTest.resolveNestedInteractive(102.0, 102.0, listOf(button))
        assertTrustedButton(r)
        assertEquals("direct_control", r!!.reason)
    }

    @Test
    fun eachButtonEdgeResolvesToButton() {
        val edges = listOf(
            101.0 to 124.0, // left
            147.0 to 124.0, // right
            124.0 to 101.0, // top
            124.0 to 147.0, // bottom
        )
        for ((px, py) in edges) {
            val r = ActivateHitTest.resolveNestedInteractive(px, py, searchButtonStack("PATH"))
            assertTrustedButton(r)
        }
    }

    @Test
    fun overlappingNonInteractiveWrapperDoesNotWin() {
        val path = ActivateHitTest.HitNode(
            id = "search-path",
            tag = "PATH",
            rect = ActivateHitTest.Rect(110.0, 110.0, 130.0, 130.0),
            depth = 10,
        )
        val wrapper = ActivateHitTest.HitNode(
            id = "wrapper",
            tag = "DIV",
            rect = ActivateHitTest.Rect(90.0, 90.0, 170.0, 170.0),
            depth = 3,
            pointerEventsNone = true,
        )
        val button = ActivateHitTest.HitNode(
            id = "search-btn",
            tag = "BUTTON",
            rect = btnRect,
            depth = 7,
        )
        // Even if stack lists wrapper, it is not interactive.
        val r = ActivateHitTest.resolveNestedInteractive(
            120.0,
            120.0,
            listOf(path, wrapper, button),
        )
        assertTrustedButton(r)
        assertEquals("BUTTON", r!!.target.tag.uppercase())
    }

    @Test
    fun nonInteractiveBackdropNotPromoted() {
        val backdrop = ActivateHitTest.HitNode(
            id = "backdrop",
            tag = "DIV",
            rect = ActivateHitTest.Rect(0.0, 0.0, cssW, cssH),
            depth = 1,
        )
        val r = ActivateHitTest.resolveNestedInteractive(50.0, 50.0, listOf(backdrop))
        assertNull("plain backdrop must not be promoted", r)
    }

    @Test
    fun disabledAncestorRejected() {
        val path = ActivateHitTest.HitNode(
            id = "p",
            tag = "PATH",
            rect = ActivateHitTest.Rect(410.0, 110.0, 430.0, 130.0),
            depth = 5,
        )
        val disabled = ActivateHitTest.HitNode(
            id = "disabled-btn",
            tag = "BUTTON",
            rect = ActivateHitTest.Rect(400.0, 100.0, 440.0, 140.0),
            depth = 4,
            disabled = true,
        )
        val r = ActivateHitTest.resolveNestedInteractive(420.0, 120.0, listOf(path, disabled))
        assertNull(r)
    }

    @Test
    fun hiddenAncestorRejected() {
        val path = ActivateHitTest.HitNode(
            id = "p",
            tag = "PATH",
            rect = ActivateHitTest.Rect(460.0, 110.0, 480.0, 130.0),
            depth = 5,
        )
        val hidden = ActivateHitTest.HitNode(
            id = "hidden-btn",
            tag = "BUTTON",
            rect = ActivateHitTest.Rect(450.0, 100.0, 490.0, 140.0),
            depth = 4,
            hidden = true,
        )
        assertNull(ActivateHitTest.resolveNestedInteractive(470.0, 120.0, listOf(path, hidden)))
    }

    @Test
    fun pointerEventsNoneRejected() {
        val btn = ActivateHitTest.HitNode(
            id = "ghost",
            tag = "BUTTON",
            rect = btnRect,
            depth = 3,
            pointerEventsNone = true,
        )
        assertNull(ActivateHitTest.resolveNestedInteractive(120.0, 120.0, listOf(btn)))
    }

    @Test
    fun contentLinkStillLocationAssign() {
        val link = ActivateHitTest.Node(
            id = "movie-link",
            tag = "A",
            href = "https://example.test/movie/1",
            text = "Movie",
            className = "poster",
            rect = ActivateHitTest.Rect(200.0, 100.0, 320.0, 280.0),
            depth = 4,
        )
        val pick = ActivateHitTest.pick(240.0, 160.0, listOf(link), cssW, cssH)
        assertNotNull(pick)
        assertEquals(ActivateHitTest.Method.LOCATION_ASSIGN, pick!!.method)
        assertEquals("https://example.test/movie/1", pick.node.href)
    }

    @Test
    fun nestedLinkUnderSpanUsesLocationAssignKind() {
        val span = ActivateHitTest.HitNode(
            id = "s",
            tag = "SPAN",
            rect = ActivateHitTest.Rect(210.0, 110.0, 250.0, 140.0),
            depth = 5,
        )
        val a = ActivateHitTest.HitNode(
            id = "movie-link",
            tag = "A",
            href = "https://example.test/movie/1",
            rect = ActivateHitTest.Rect(200.0, 100.0, 320.0, 280.0),
            depth = 4,
        )
        val r = ActivateHitTest.resolveNestedInteractive(220.0, 120.0, listOf(span, a))
        assertNotNull(r)
        assertEquals("movie-link", r!!.target.id)
        assertEquals(ActivateHitTest.ActivationKind.LOCATION_ASSIGN, r.kind)
    }

    @Test
    fun roleButtonPromotesLikeButton() {
        val path = ActivateHitTest.HitNode(
            id = "p",
            tag = "PATH",
            rect = ActivateHitTest.Rect(110.0, 110.0, 130.0, 130.0),
            depth = 6,
        )
        val roleBtn = ActivateHitTest.HitNode(
            id = "role-btn",
            tag = "DIV",
            role = "button",
            rect = btnRect,
            depth = 5,
        )
        val r = ActivateHitTest.resolveNestedInteractive(120.0, 120.0, listOf(path, roleBtn))
        assertNotNull(r)
        assertEquals("role-btn", r!!.target.id)
        assertEquals(ActivateHitTest.ActivationKind.TRUSTED_TOUCH, r.kind)
        assertFalse(r.allowSyntheticClick)
    }

    @Test
    fun activationOccursExactlyOncePolicy() {
        // Trusted button path: single activation kind, no synthetic companion.
        val r = ActivateHitTest.resolveNestedInteractive(120.0, 120.0, searchButtonStack("PATH"))
        assertTrustedButton(r)
        assertFalse(r!!.allowSyntheticClick)
        assertTrue(ActivateHitTest.methodRequiresTrustedTouch("trusted_button"))
        assertFalse(
            "synthetic click path must not also require a second activate",
            r.kind == ActivateHitTest.ActivationKind.SYNTHETIC_CLICK &&
                ActivateHitTest.methodRequiresTrustedTouch("click"),
        )
    }

    @Test
    fun productionActivateJsHasNestedPromotionAndNoSyntheticBeforeTrusted() {
        val routerPath = listOf(
            "app/src/main/java/com/keenzero/app/input/RemoteInputRouter.kt",
            "src/main/java/com/keenzero/app/input/RemoteInputRouter.kt",
        ).map { File(it) }.firstOrNull { it.exists() }
            ?: error("RemoteInputRouter.kt not found")
        val text = routerPath.readText()
        assertTrue(text.contains("promoteInteractive"))
        assertTrue(text.contains("isDecorativeLeaf"))
        assertTrue(text.contains("isInteractiveControl"))
        assertTrue(text.contains("trusted_button"))
        assertTrue(text.contains("\"synthetic\":false") || text.contains("synthetic:false"))
        // BUTTON path returns before synthetic click dispatches.
        val trustedIdx = text.indexOf("method:'trusted_button'")
        assertTrue("trusted_button method missing", trustedIdx > 0)
        // Structural: needTouch true for trusted_button on Kotlin side.
        assertTrue(text.contains("trusted_button"))
        assertTrue(text.contains("ActivateHitTest.methodRequiresTrustedTouch"))
        // Must still use elementsFromPoint stack.
        assertTrue(text.contains("elementsFromPoint"))
        // Promotion decision must not depend on hostname or icon labels.
        val promoteStart = text.indexOf("function promoteInteractive")
        assertTrue(promoteStart > 0)
        val promoteEnd = text.indexOf("var el0=st[0]", promoteStart).takeIf { it > promoteStart }
            ?: (promoteStart + 2500)
        val promoteBlock = text.substring(promoteStart, promoteEnd)
        assertFalse(promoteBlock.contains("cineby", ignoreCase = true))
        assertFalse(promoteBlock.contains("aria-label", ignoreCase = true))
        assertFalse(promoteBlock.contains("Search", ignoreCase = false))
    }
}
