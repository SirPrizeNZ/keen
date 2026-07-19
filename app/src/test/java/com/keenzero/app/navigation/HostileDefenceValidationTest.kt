package com.keenzero.app.navigation

import com.keenzero.app.blocking.RequestBlocker
import com.keenzero.app.playback.PlayIntent
import com.keenzero.app.playback.PopupQuarantine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic local validation matrix for hostile-defence smart path (cases 1–17, 6, 14).
 * Records structured decision fields required by the validation protocol.
 */
class HostileDefenceValidationTest {

    data class Record(
        val caseId: Int,
        val name: String,
        val expected: String,
        val actual: String,
        val reason: String,
        val activationType: String?,
        val grantConsumed: Boolean,
        val destinationHost: String?,
        val visibleUserResult: String,
        val pass: Boolean,
    )

    private val clock = AtomicLong(10_000L)
    private fun now() = clock.get()
    private fun advance(ms: Long) { clock.addAndGet(ms) }

    private val quarantine = PopupQuarantine()
    private val ledger = ActivationLedger(clock = { now() })
    private val broker = WindowRequestBroker(quarantine, clock = { now() })
    private val firewall = NavigationFirewall(clock = { now() }, quarantine = quarantine, activationLedger = ledger)
    private val blocker = RequestBlocker.fromLines(
        sequenceOf(
            ".doubleclick.net",
            ".googlesyndication.com",
            ".ads.example",
            "metrics.example",
        ),
    )

    private val results = mutableListOf<Record>()

    private fun rec(
        caseId: Int,
        name: String,
        expected: String,
        actual: String,
        reason: String,
        activationType: String?,
        grantConsumed: Boolean,
        host: String?,
        visible: String,
    ) {
        results += Record(
            caseId, name, expected, actual, reason, activationType, grantConsumed, host, visible,
            pass = expected == actual,
        )
        assertEquals("case $caseId $name", expected, actual)
    }

    @Test
    fun fullDefenceMatrix_cases1to17() {
        // 1. Deliberate same-origin link opens
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://site.example/page")
        var d = broker.decide(
            "https://site.example/page", true, "https://site.example", ledger.peek(), false, null,
        )
        rec(1, "same_origin_link", "OPEN_CURRENT_SESSION", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "page opens")

        // 2. Deliberate cross-origin link with matching href
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://cdn.example/out")
        d = broker.decide(
            "https://cdn.example/out", true, "https://site.example", ledger.peek(), false, null,
        )
        rec(2, "cross_origin_href_match", "OPEN_CURRENT_SESSION", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "opens matched dest")

        // 3. target=_blank deliberate same-origin → open current session
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://site.example/blank-target")
        d = broker.decide(
            "https://site.example/blank-target", true, "https://site.example", ledger.peek(), false, null,
        )
        rec(3, "target_blank_same_origin", "OPEN_CURRENT_SESSION", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "in-session load")

        // 4. Scripted delays: grant TTL UNKNOWN=1000, LINK=2000
        // 500ms — still live
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://dest.example/x")
        advance(500)
        d = broker.decide(
            "https://dest.example/x", false, "https://site.example", ledger.peek(), false, null,
        )
        rec(4, "scripted_500ms", "OPEN_CURRENT_SESSION", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "opens")
        // 1.5s — LINK still live (2s TTL)
        ledger.clear()
        clock.set(10_000L)
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://dest.example/x")
        advance(1_500)
        d = broker.decide(
            "https://dest.example/x", false, "https://site.example", ledger.peek(), false, null,
        )
        rec(41, "scripted_1500ms", "OPEN_CURRENT_SESSION", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "opens")
        // 2.5s — expired
        ledger.clear()
        clock.set(10_000L)
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://dest.example/x")
        advance(2_500)
        d = broker.decide(
            "https://dest.example/x", false, "https://site.example", ledger.peek(), false, null,
        )
        rec(42, "scripted_2500ms_expired", "BLOCK", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "blocked after TTL")

        // 5. Automatic popup without activation
        ledger.clear()
        clock.set(10_000L)
        d = broker.decide(
            "https://popup.example/ad", false, "https://site.example", null, false, null,
        )
        rec(5, "auto_popup", "BLOCK", d.action.name, d.reason, null, d.consumeGrant, d.destinationHost, "no window")

        // 6. Two windows one click: first consume, second blocked
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://legit.example/")
        d = broker.decide(
            "https://legit.example/", true, "https://site.example", ledger.peek(), false, null,
        )
        if (d.consumeGrant) ledger.consume()
        rec(6, "first_window", "OPEN_CURRENT_SESSION", d.action.name, d.reason, "LINK", true, d.destinationHost, "first handled")
        d = broker.decide(
            "https://second.example/", true, "https://site.example", ledger.peek(), false, null,
        )
        // isUserGesture true but no grant → FAIL CLOSED (no confirm sheet for ad farms)
        rec(61, "second_window", "BLOCK", d.action.name, d.reason, null, d.consumeGrant, d.destinationHost, "blocked without confirm")
        // Without gesture after consume:
        d = broker.decide(
            "https://second.example/", false, "https://site.example", ledger.peek(), false, null,
        )
        rec(62, "second_window_no_gesture", "BLOCK", d.action.name, d.reason, null, d.consumeGrant, d.destinationHost, "blocked")

        // 7. about:blank after activation → provisional
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", null)
        d = broker.decide(
            "about:blank", false, "https://site.example", ledger.peek(), false, null,
        )
        rec(7, "blank_authorised", "PROVISIONAL_CAPTURE", d.action.name, d.reason, "LINK", d.consumeGrant, null, "hidden capture")
        // then legitimate URL within timeout
        d = broker.decide(
            "https://player.example/embed", true, "https://site.example", ledger.peek(), false, null,
        )
        // cross-origin with grant but no href → confirm
        rec(71, "blank_then_legit", "REQUIRE_CONFIRMATION", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "confirm sheet")

        // 8. blank remaining — timeout path: clear grant, no open
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", null)
        d = broker.decide("about:blank", false, "https://site.example", ledger.peek(), false, null)
        assertEquals(WindowRequestBroker.Action.PROVISIONAL_CAPTURE, d.action)
        ledger.clear() // timeout clear
        d = broker.decide(null, false, "https://site.example", ledger.peek(), false, null)
        rec(8, "blank_timeout", "BLOCK", d.action.name, d.reason, null, d.consumeGrant, null, "destroyed")

        // 9. blank → advertising host blocked
        ledger.clear()
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", null)
        d = broker.decide(
            "https://ads.example/click", true, "https://site.example", ledger.peek(), false, null,
        )
        rec(9, "blank_to_ad", "BLOCK", d.action.name, d.reason, "LINK", d.consumeGrant, d.destinationHost, "never visible")

        // 10. Automatic same-tab cross-origin redirect blocked
        firewall.recordCommittedUrl("https://site.example/start")
        val nav = firewall.decide(
            url = "https://hijack.example/out",
            isMainFrame = true,
            hasGesture = false,
        )
        rec(10, "auto_redirect", "BLOCK_UNINTENDED_REDIRECT", nav.name, nav.name, null, false, "hijack.example", "stay on page")

        // 11. User-selected redirect chain usable
        firewall.recordUserInput()
        val nav2 = firewall.decide(
            url = "https://next.example/hop",
            isMainFrame = true,
            hasGesture = true,
        )
        rec(11, "user_redirect_chain", "ALLOW_SAME_SESSION", nav2.name, nav2.name, null, false, "next.example", "navigates")

        // 12. Play action player window
        ledger.clear()
        val play = PlayIntent(
            id = "p1",
            origin = "https://site.example",
            url = "https://site.example/watch",
            focusedFingerprint = "BUTTON#play",
            role = "button",
            visibleText = "Play",
            expectedHref = null,
            geometry = null,
            contentId = "ep1",
            timestampElapsedMs = now(),
        )
        firewall.recordPlayIntent(play)
        d = broker.decide(
            "https://player.example.com/embed",
            true,
            "https://site.example",
            ledger.peek(),
            playIntentActive = true,
            playOrigin = "https://site.example",
        )
        rec(
            12, "play_window",
            "OPEN_CURRENT_SESSION",
            d.action.name, d.reason, "PLAY", d.consumeGrant, d.destinationHost,
            "player opens in session",
        )

        // 13. Login/auth same-origin without broad popup authority
        ledger.clear()
        d = broker.decide(
            "https://site.example/oauth/callback",
            true,
            "https://site.example",
            null,
            false,
            null,
        )
        rec(13, "auth_same_origin", "OPEN_CURRENT_SESSION", d.action.name, d.reason, null, d.consumeGrant, d.destinationHost, "auth continues")

        // 14. Unknown deliberate not silent disappear
        ledger.clear()
        ledger.record(ActivationLedger.Type.UNKNOWN, "https://site.example", null)
        d = broker.decide(
            "https://mystery.example/x", true, "https://site.example", ledger.peek(), false, null,
        )
        rec(14, "uncertain_deliberate", "REQUIRE_CONFIRMATION", d.action.name, d.reason, "UNKNOWN", d.consumeGrant, d.destinationHost, "confirm sheet")

        // 15. Third-party ad path blocked
        val br = blocker.classify(
            "https://cdn.tracker.net/pagead/js",
            isMainFrame = false,
            pageHost = "site.example",
        )
        rec(15, "third_party_ad_path", "BLOCK_PATH", br.name, br.name, null, false, "cdn.tracker.net", "empty response")

        // 16. First-party media/script/api not falsely blocked
        val media = blocker.classify(
            "https://cdn.site.example/video.m3u8",
            isMainFrame = false,
            pageHost = "www.site.example",
            resourceTypeHint = RequestBlocker.ResourceType.MEDIA,
        )
        val script = blocker.classify(
            "https://www.site.example/app.js",
            isMainFrame = false,
            pageHost = "www.site.example",
            resourceTypeHint = RequestBlocker.ResourceType.SCRIPT,
        )
        val api = blocker.classify(
            "https://api.site.example/v1/data",
            isMainFrame = false,
            pageHost = "www.site.example",
            resourceTypeHint = RequestBlocker.ResourceType.XHR,
        )
        rec(16, "first_party_media", "ALLOW", media.name, media.name, null, false, "cdn.site.example", "plays")
        rec(161, "first_party_script", "ALLOW", script.name, script.name, null, false, "www.site.example", "loads")
        rec(162, "first_party_api", "ALLOW", api.name, api.name, null, false, "api.site.example", "loads")

        // 17. SW same policy — same classifier instance (no separate path)
        val sw = blocker.classifyHost(
            "doubleclick.net",
            isMainFrame = false,
            pageHost = "site.example",
            url = "https://doubleclick.net/gampad",
            resourceTypeHint = RequestBlocker.ResourceType.SCRIPT,
        )
        rec(17, "service_worker_same_policy", "BLOCK_HOST", sw.name, sw.name, null, false, "doubleclick.net", "blocked")

        // Main-frame never blocked by host list (firewall owns)
        val mf = blocker.classify("https://ads.example/", isMainFrame = true)
        rec(18, "main_frame_host_list", "ALLOW_MAIN_FRAME", mf.name, mf.name, null, false, "ads.example", "firewall decides")

        // Print matrix for evidence
        println("=== HOSTILE DEFENCE VALIDATION MATRIX ===")
        results.forEach { r ->
            println(
                "CASE ${r.caseId} ${if (r.pass) "PASS" else "FAIL"} | ${r.name} | exp=${r.expected} act=${r.actual} | " +
                    "reason=${r.reason} actType=${r.activationType} consumed=${r.grantConsumed} host=${r.destinationHost} | ${r.visibleUserResult}",
            )
        }
        val fails = results.count { !it.pass }
        assertEquals("all matrix rows must pass", 0, fails)
    }

    @Test
    fun grantTtlUsesElapsedRealtimeSemantics() {
        // Clock is injected; proves we don't use wall clock for TTL.
        ledger.record(ActivationLedger.Type.UNKNOWN, null, null)
        advance(999)
        assertTrue(ledger.hasValid())
        advance(2)
        assertFalse(ledger.hasValid())
    }

    @Test
    fun clearOnNavigationCommit() {
        ledger.record(ActivationLedger.Type.LINK, "https://a.example", "https://a.example/x")
        firewall.clearActivation()
        assertFalse(ledger.hasValid())
    }
}
