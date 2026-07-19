package com.keenzero.app.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-level checks for the in-page modal scroll controller source.
 * Full physical behaviour is validated on device; this guards regressions in
 * the JS payload contract.
 */
class ModalScrollJsTest {

    @Test
    fun install_exposes_hitTest_and_v9() {
        val js = ModalScrollJs.INSTALL_JS
        assertTrue(js.contains("bindAndStart"))
        assertTrue(js.contains("hitTest") || js.contains("rectOf"))
        assertTrue(js.contains("__v:9") || js.contains("__v>=9"))
        assertTrue(js.contains("looksTinyControl"))
    }

    @Test
    fun install_stops_raf_at_boundary() {
        val js = ModalScrollJs.INSTALL_JS
        assertTrue(js.contains("stopAnim('boundary')") || js.contains("stopAnim(\"boundary\")"))
        assertTrue(js.contains("requestAnimationFrame"))
        // Must not leave dir spinning without cancel path.
        assertTrue(js.contains("cancelAnimationFrame"))
    }

    @Test
    fun install_lifecycle_stops() {
        val js = ModalScrollJs.INSTALL_JS
        assertTrue(js.contains("pagehide"))
        assertTrue(js.contains("visibilitychange"))
        assertTrue(js.contains("'blur'") || js.contains("\"blur\""))
    }

    @Test
    fun install_rejects_document_scroller() {
        val js = ModalScrollJs.INSTALL_JS
        assertTrue(js.contains("document.scrollingElement") || js.contains("documentElement"))
        assertTrue(js.contains("isDoc"))
        assertTrue(js.contains("looksSidebar"))
    }

    @Test
    fun callBindAndStart_wraps_direction() {
        val call = ModalScrollJs.callBindAndStart(-1)
        assertTrue(call.contains("bindAndStart(-1)"))
        assertTrue(call.contains("__keenModalScroll"))
    }

    @Test
    fun ime_fallback_skips_empty_and_is_single_path() {
        val js = ModalScrollJs.IME_FALLBACK_SUBMIT_JS
        assertTrue(js.contains("empty_no_submit"))
        assertTrue(js.contains("requestSubmit"))
        // Exactly one method path returns; must not chain form + button.
        assertTrue(js.contains("method:'requestSubmit'") || js.contains("method:\"requestSubmit\""))
        assertTrue(js.contains("method:'button'") || js.contains("method:\"button\""))
    }

    @Test
    fun ime_observe_reports_results() {
        val js = ModalScrollJs.IME_OBSERVE_JS
        assertTrue(js.contains("resultsVisible"))
        assertTrue(js.contains("modalActive"))
    }

    @Test
    fun ime_submit_signal_fields() {
        val s = ImeSubmitSignal(
            source = "performEditorAction",
            action = 3,
            baseHandled = true,
            timestampMs = 123L,
        )
        assertTrue(s.baseHandled)
        assertFalse(s.source.isEmpty())
        assertTrue(s.action == 3)
    }
}
