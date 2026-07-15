package com.keenzero.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionIndexTest {

    @Test
    fun rebuildCapsCandidates() {
        val idx = InteractionIndex(maxCandidates = 10)
        val items = (0 until 50).joinToString(",") { i ->
            """{"id":"c$i","left":${i * 10.0},"top":0,"width":40,"height":40,"tag":"BUTTON","role":"button","text":"t$i"}"""
        }
        val raw = """{"items":[$items],"focusedId":"c0"}"""
        val n = idx.rebuildFromJson(raw, 0L, 5L)
        assertEquals(10, n)
        assertEquals(10, idx.size)
        assertTrue(idx.memoryEstimateBytes > 0)
        assertEquals(5L, idx.rebuildDurationMs)
    }

    @Test
    fun selectRightUsesCachedGeometry() {
        val idx = InteractionIndex()
        val raw = """
            {"items":[
              {"id":"a","left":0,"top":0,"width":40,"height":40},
              {"id":"b","left":100,"top":0,"width":40,"height":40},
              {"id":"c","left":200,"top":0,"width":40,"height":40}
            ],"focusedId":"a"}
        """.trimIndent()
        idx.rebuildFromJson(raw, 0L, 1L)
        val next = idx.select("right")
        assertNotNull(next)
        assertEquals("b", next!!.id)
        val next2 = idx.select("right")
        assertEquals("c", next2!!.id)
    }

    @Test
    fun selectDownPrefersVertical() {
        val idx = InteractionIndex()
        // "side" is nearly same row (dy≈0) so not a valid "down" candidate (dy must be > 2).
        val raw = """
            {"items":[
              {"id":"a","left":50,"top":0,"width":40,"height":40},
              {"id":"side","left":200,"top":0,"width":40,"height":40},
              {"id":"below","left":55,"top":100,"width":40,"height":40}
            ],"focusedId":"a"}
        """.trimIndent()
        idx.rebuildFromJson(raw, 0L, 1L)
        assertEquals("below", idx.select("down")!!.id)
    }

    @Test
    fun candidateSelectorCoversSpaRolesWithoutSiteAllowlist() {
        // Structural contract: product collector must discover generic SPA shells.
        val sel = InteractionIndex.CANDIDATE_SELECTOR
        assertTrue("role=button", sel.contains("role=\"button\"") || sel.contains("[role=\"button\"]"))
        assertTrue("role=link", sel.contains("role=\"link\"") || sel.contains("[role=\"link\"]"))
        assertTrue("onclick", sel.contains("[onclick]"))
        assertTrue("native anchors", sel.contains("a[href]"))
        // No site-specific domain allowlists in the selector.
        assertTrue("no site pack domains", !sel.contains("lordflix") && !sel.contains("cineby"))
        assertTrue("collect js embeds selector", InteractionIndex.COLLECT_JS.contains("role"))
    }

    @Test
    fun selectDownCanReachOffscreenCatalogueRow() {
        val idx = InteractionIndex()
        // "below" is offscreen (below fold); product must still select it so FOCUS can scrollIntoView.
        val raw = """
            {"items":[
              {"id":"nav","left":10,"top":10,"width":80,"height":30,"offscreen":false,"href":"https://fmhy.net/nav"},
              {"id":"side","left":200,"top":10,"width":80,"height":30,"offscreen":false,"href":"https://fmhy.net/side"},
              {"id":"cineby","left":20,"top":1200,"width":200,"height":40,"offscreen":true,"tag":"A","text":"Cineby","href":"https://cineby.at/"}
            ],"focusedId":"nav"}
        """.trimIndent()
        idx.rebuildFromJson(raw, 0L, 2L)
        val next = idx.select("down")
        assertNotNull(next)
        assertEquals("cineby", next!!.id)
    }

    @Test
    fun selectDownPrefersExternalCatalogueOverLocalChromeBelow() {
        val idx = InteractionIndex()
        // On-screen local link is nearer; external catalogue row is further but preferred on DOWN.
        val raw = """
            {"items":[
              {"id":"home","left":10,"top":0,"width":80,"height":30,"offscreen":false,"href":"https://fmhy.net/"},
              {"id":"local","left":10,"top":80,"width":80,"height":30,"offscreen":false,"href":"https://fmhy.net/video"},
              {"id":"ext","left":10,"top":400,"width":120,"height":30,"offscreen":false,"href":"https://cineby.at/","text":"Cineby"}
            ],"focusedId":"home"}
        """.trimIndent()
        idx.rebuildFromJson(raw, 0L, 2L)
        assertEquals("ext", idx.select("down")!!.id)
    }

    @Test
    fun selectDownFromHreflessGridDoesNotJumpToDistantExternal() {
        // Fixture-like: centre button has no href; an external link sits far right.
        // DOWN must stay on the grid (S), not teleport to the external.
        val idx = InteractionIndex()
        val raw = """
            {"items":[
              {"id":"g11","left":100,"top":100,"width":40,"height":40,"offscreen":false,"tag":"BUTTON","text":"C"},
              {"id":"g21","left":100,"top":160,"width":40,"height":40,"offscreen":false,"tag":"BUTTON","text":"S"},
              {"id":"g12","left":160,"top":100,"width":40,"height":40,"offscreen":false,"tag":"BUTTON","text":"E"},
              {"id":"ext","left":500,"top":200,"width":120,"height":30,"offscreen":false,"href":"https://example.com/","text":"blank"}
            ],"focusedId":"g11"}
        """.trimIndent()
        idx.rebuildFromJson(raw, 0L, 2L)
        assertEquals("g21", idx.select("down")!!.id)
        idx.setFocused("g11")
        assertEquals("g12", idx.select("right")!!.id)
    }

    @Test
    fun rebuildAcceptsRoleButtonSpaCards() {
        val idx = InteractionIndex()
        val raw = """
            {"items":[
              {"id":"card1","left":10,"top":10,"width":120,"height":160,"tag":"DIV","role":"button","text":"Title A"},
              {"id":"card2","left":150,"top":10,"width":120,"height":160,"tag":"DIV","role":"button","text":"Title B"},
              {"id":"nav","left":10,"top":200,"width":80,"height":32,"tag":"A","role":"link","text":"Home"}
            ],"focusedId":"card1"}
        """.trimIndent()
        assertEquals(3, idx.rebuildFromJson(raw, 0L, 3L))
        val next = idx.select("right")
        assertNotNull(next)
        assertEquals("card2", next!!.id)
    }

    @Test
    fun noDomScanContract_selectIsOOfCap() {
        // Selecting never requires rebuild — pure in-memory.
        val idx = InteractionIndex(maxCandidates = 256)
        val items = (0 until 200).joinToString(",") { i ->
            """{"id":"c$i","left":${(i % 20) * 50.0},"top":${(i / 20) * 50.0},"width":40,"height":40}"""
        }
        val n = idx.rebuildFromJson("""{"items":[$items],"focusedId":"c0"}""", 0L, 12L)
        assertTrue("rebuild respects cap", n <= 256 && idx.size <= 256)
        // Contract: select is pure in-memory over the capped list (no rebuild side effects).
        val versionBefore = idx.indexVersion
        repeat(100) {
            idx.select("right")
            idx.select("down")
            idx.select("left")
            idx.select("up")
        }
        assertTrue("select must not rebuild index", idx.indexVersion == versionBefore)
        assertTrue("index remains capped after selects", idx.size <= 256)
    }
}
