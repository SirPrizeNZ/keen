package com.keenzero.app.navigation

import com.keenzero.app.playback.PopupQuarantine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowRequestBrokerTest {
    private var now = 5_000L
    private val ledger = ActivationLedger(clock = { now })
    private val broker = WindowRequestBroker(PopupQuarantine(), clock = { now })

    @Test
    fun blocksAutomaticPopupWithoutActivation() {
        val d = broker.decide(
            targetUrl = "https://random.example/x",
            isUserGesture = false,
            pageOrigin = "https://site.example",
            grant = null,
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.BLOCK, d.action)
        assertEquals("no_activation", d.reason)
    }

    @Test
    fun blocksAdvertisingEvenWithGrant() {
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://ads.example/x")
        val d = broker.decide(
            targetUrl = "https://ads.example/x",
            isUserGesture = true,
            pageOrigin = "https://site.example",
            grant = ledger.peek(),
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.BLOCK, d.action)
        assertEquals("ad_host", d.reason)
    }

    @Test
    fun deliberateUncertainRequiresConfirmationNotSilentDrop() {
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://expected.example/")
        val d = broker.decide(
            targetUrl = "https://other.example/landing",
            isUserGesture = true,
            pageOrigin = "https://site.example",
            grant = ledger.peek(),
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.REQUIRE_CONFIRMATION, d.action)
        assertTrue(d.consumeGrant)
        assertEquals("other.example", d.destinationHost)
    }

    @Test
    fun hrefMatchOpensCurrentSession() {
        val href = "https://dest.example/watch"
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", href)
        val d = broker.decide(
            targetUrl = href,
            isUserGesture = true,
            pageOrigin = "https://site.example",
            grant = ledger.peek(),
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.OPEN_CURRENT_SESSION, d.action)
        assertEquals("grant_href_match", d.reason)
    }

    @Test
    fun sameOriginDeliberateOpens() {
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://site.example/a")
        val d = broker.decide(
            targetUrl = "https://site.example/b",
            isUserGesture = true,
            pageOrigin = "https://site.example",
            grant = ledger.peek(),
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.OPEN_CURRENT_SESSION, d.action)
        // Auth same-origin verdict or deliberate same-origin path — both open in-session.
        assertTrue(d.reason == "same_origin_deliberate" || d.reason == "auth_same_origin")
    }

    @Test
    fun blankWithoutActivationBlocked() {
        val d = broker.decide(
            targetUrl = "about:blank",
            isUserGesture = false,
            pageOrigin = "https://site.example",
            grant = null,
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.BLOCK, d.action)
    }

    @Test
    fun blankWithGrantProvisionalCapture() {
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", null)
        val d = broker.decide(
            targetUrl = "about:blank",
            isUserGesture = false,
            pageOrigin = "https://site.example",
            grant = ledger.peek(),
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.PROVISIONAL_CAPTURE, d.action)
        assertFalse(d.consumeGrant)
    }

    @Test
    fun sameOriginAuthAllowed() {
        val d = broker.decide(
            targetUrl = "https://site.example/oauth/callback",
            isUserGesture = true,
            pageOrigin = "https://site.example",
            grant = null,
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.OPEN_CURRENT_SESSION, d.action)
        assertEquals("auth_same_origin", d.reason)
    }

    @Test
    fun secondConsumeAfterFirstFails() {
        ledger.record(ActivationLedger.Type.LINK, "https://site.example", "https://a.example/")
        val g1 = ledger.consume()
        assertTrue(g1 != null)
        val d = broker.decide(
            targetUrl = "https://b.example/",
            isUserGesture = false,
            pageOrigin = "https://site.example",
            grant = ledger.peek(),
            playIntentActive = false,
            playOrigin = null,
        )
        assertEquals(WindowRequestBroker.Action.BLOCK, d.action)
        assertEquals("no_activation", d.reason)
    }
}
