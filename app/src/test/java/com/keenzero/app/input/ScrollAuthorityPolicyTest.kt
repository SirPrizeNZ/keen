package com.keenzero.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAuthorityPolicyTest {

    @Test
    fun defaultDeniesSiteYankToTop() {
        val p = ScrollAuthorityPolicy { 1_000L }
        p.remember(0.0, 480.0)
        assertFalse(p.shouldApplySiteScroll(fromY = 480.0, toY = 0.0, now = 1_000L))
        assertEquals(480.0, p.restoreTarget(1_000L).second, 0.01)
    }

    @Test
    fun grantAllowsKeenEdgeScroll() {
        val p = ScrollAuthorityPolicy { 1_000L }
        p.remember(0.0, 100.0)
        p.noteUser(1_000L)
        assertTrue(p.isAllowed(1_050L))
        assertTrue(p.shouldApplySiteScroll(fromY = 100.0, toY = 200.0, now = 1_050L))
    }

    @Test
    fun freezeBlocksGrantAndSiteScroll() {
        val p = ScrollAuthorityPolicy { 1_000L }
        p.freeze(0.0, 468.0, ScrollAuthorityPolicy.FREEZE_ON_NAVIGATE_MS, 1_000L)
        assertTrue(p.isFrozen(1_100L))
        assertFalse(p.isAllowed(1_100L))
        p.noteUser(1_100L) // must not unlock while frozen
        assertFalse(p.isAllowed(1_100L))
        assertFalse(p.shouldApplySiteScroll(fromY = 468.0, toY = 0.0, now = 1_100L))
        assertEquals(468.0, p.restoreTarget(1_100L).second, 0.01)
    }

    @Test
    fun nativeIntentIsNotScrollGrantConstant() {
        assertTrue(ScrollAuthorityPolicy.NATIVE_INTENT_IS_NOT_SCROLL_GRANT)
    }

    @Test
    fun installJsV5IsInertNoViewportFight() {
        val js = ScrollAuthorityJs.INSTALL_JS
        assertTrue(js.contains("v:5"))
        // Must stay inert: API stubs only, no real pin loop / scroll patch
        assertFalse(js.contains("rawWrite"))
        assertFalse(js.contains("freezeUntil"))
        assertFalse(js.contains("requestAnimationFrame"))
        assertTrue(js.contains("freeze:function(){}"))
    }

    @Test
    fun tinyJitterAllowedWithoutGrant() {
        val p = ScrollAuthorityPolicy { 1_000L }
        p.remember(0.0, 100.0)
        assertTrue(p.shouldApplySiteScroll(fromY = 100.0, toY = 105.0, now = 1_000L))
    }
}
