package com.keenzero.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivateHitTestTest {

    private val cssW = 960.0
    private val cssH = 501.0

    private fun poster(
        id: String,
        href: String,
        left: Double,
        title: String,
    ) = ActivateHitTest.Node(
        id = id,
        tag = "A",
        href = href,
        text = title,
        className = "movieCard poster",
        rect = ActivateHitTest.Rect(left, 100.0, left + 120.0, 280.0),
        depth = 5,
    )

    @Test
    fun multiTitleEachOpensOwnHrefNotFirstInRail() {
        val toy = poster("toy", "https://cineby.at/movie/toy-story", 100.0, "Toy Story")
        val xmen = poster("xmen", "https://cineby.at/movie/x-men", 250.0, "X-Men")
        val evil = poster("evil", "https://cineby.at/movie/evil-dead", 400.0, "Evil Dead")
        val backrooms = poster("br", "https://cineby.at/movie/backrooms", 550.0, "Backrooms")
        // Rail shell under all — must never win pick
        val rail = ActivateHitTest.Node(
            id = "rail",
            tag = "DIV",
            href = null,
            text = "",
            className = "swiper swiper-initialized items",
            rect = ActivateHitTest.Rect(0.0, 80.0, 960.0, 300.0),
            depth = 1,
        )
        val all = listOf(rail, toy, xmen, evil, backrooms)

        val pToy = ActivateHitTest.pick(160.0, 180.0, all, cssW, cssH)
        val pXmen = ActivateHitTest.pick(300.0, 180.0, all, cssW, cssH)
        val pEvil = ActivateHitTest.pick(450.0, 180.0, all, cssW, cssH)
        val pBr = ActivateHitTest.pick(600.0, 180.0, all, cssW, cssH)

        assertNotNull(pToy)
        assertNotNull(pXmen)
        assertNotNull(pEvil)
        assertNotNull(pBr)
        assertEquals("https://cineby.at/movie/toy-story", pToy!!.node.href)
        assertEquals("https://cineby.at/movie/x-men", pXmen!!.node.href)
        assertEquals("https://cineby.at/movie/evil-dead", pEvil!!.node.href)
        assertEquals("https://cineby.at/movie/backrooms", pBr!!.node.href)
        assertTrue(ActivateHitTest.assertDistinctPicks(pToy, pXmen))
        assertTrue(ActivateHitTest.assertDistinctPicks(pXmen, pEvil))
        assertEquals(ActivateHitTest.Method.LOCATION_ASSIGN, pToy.method)
    }

    @Test
    fun neverPicksFirstRailLinkWhenPointerOnSecond() {
        val first = poster("first", "https://x.test/movie/first", 50.0, "First")
        val second = poster("second", "https://x.test/movie/second", 200.0, "Second")
        val pick = ActivateHitTest.pick(230.0, 180.0, listOf(first, second), cssW, cssH)
        assertEquals("second", pick!!.node.id)
        assertNotEquals("https://x.test/movie/first", pick.node.href)
    }

    @Test
    fun externalHttpLinkUsesAssign() {
        val a = ActivateHitTest.Node(
            id = "cineby",
            tag = "A",
            href = "https://cineby.at/",
            text = "Cineby",
            className = "",
            rect = ActivateHitTest.Rect(100.0, 100.0, 180.0, 130.0),
            depth = 3,
        )
        val pick = ActivateHitTest.pick(120.0, 110.0, listOf(a), cssW, cssH, currentHost = "fmhy.net")
        assertEquals(ActivateHitTest.Method.LOCATION_ASSIGN, pick!!.method)
        assertEquals("https://cineby.at/", pick.node.href)
    }

    @Test
    fun railSizedContainerRejectedEvenIfContainsPoint() {
        val rail = ActivateHitTest.Node(
            id = "rail",
            tag = "DIV",
            href = "https://x.test/movie/wrong",
            text = "row",
            className = "items",
            rect = ActivateHitTest.Rect(0.0, 0.0, 900.0, 200.0),
            depth = 1,
        )
        // tag A but rail-sized box
        val big = rail.copy(id = "bigA", tag = "A", href = "https://x.test/movie/wrong")
        val small = poster("ok", "https://x.test/movie/ok", 100.0, "OK")
        val pick = ActivateHitTest.pick(160.0, 180.0, listOf(big, small), cssW, cssH)
        assertEquals("ok", pick!!.node.id)
    }

    @Test
    fun collectJsDoesNotStampTabindex() {
        val js = InteractionIndex.COLLECT_JS
        assertFalse(js.contains("setAttribute('tabindex'"))
        assertFalse(js.contains("setAttribute(\"tabindex\""))
    }

    @Test
    fun productionActivateJsUsesShippedContentHrefPolicy() {
        val routerPath = listOf(
            "app/src/main/java/com/keenzero/app/input/RemoteInputRouter.kt",
            "src/main/java/com/keenzero/app/input/RemoteInputRouter.kt",
        ).map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: error("RemoteInputRouter.kt not found for structural check")
        val text = routerPath.readText()
        assertTrue(
            "production activate must inject ActivateHitTest.CONTENT_HREF_JS_REGEX",
            text.contains("ActivateHitTest.CONTENT_HREF_JS_REGEX"),
        )
        assertTrue(
            "production must call ActivateHitTest on activate results",
            text.contains("ActivateHitTest.isContentHref"),
        )
        assertTrue(text.contains("focus_input"))
        assertTrue(text.contains("elementsFromPoint") || text.contains("elementFromPoint"))
        assertFalse(
            "must not take first content link without geometry",
            text.contains("return links[0]"),
        )
        // No scroll freeze in activate (viewport fight regression).
        assertFalse(text.contains("__keenScrollAuth.freeze"))
    }
}
