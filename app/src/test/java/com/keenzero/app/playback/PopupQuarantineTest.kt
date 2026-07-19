package com.keenzero.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupQuarantineTest {
    private val q = PopupQuarantine()

    @Test
    fun destroysAdvertisingRegardlessOfPlayIntent() {
        assertEquals(
            PopupQuarantine.Verdict.DESTROY_ADVERTISING,
            q.decide(
                targetUrl = "https://evil.example/ad",
                requestingOrigin = "https://appassets.androidplatform.net",
                playIntentActive = true,
                playOrigin = "https://appassets.androidplatform.net",
            ),
        )
        assertEquals(
            PopupQuarantine.Verdict.DESTROY_ADVERTISING,
            q.decide(
                targetUrl = "https://ads.example/x",
                requestingOrigin = "https://site.example",
                playIntentActive = false,
                playOrigin = null,
            ),
        )
    }

    @Test
    fun destroysUnknownPopupWithoutPlayIntent() {
        assertEquals(
            PopupQuarantine.Verdict.DESTROY_UNKNOWN,
            q.decide(
                targetUrl = "https://random.example/page",
                requestingOrigin = "https://site.example",
                playIntentActive = false,
                playOrigin = null,
            ),
        )
    }

    @Test
    fun preflightRejectsWithoutGestureOrPlay() {
        assertEquals(
            PopupQuarantine.Verdict.REJECT_IMMEDIATE,
            q.preflight(targetUrl = null, isUserGesture = false, playIntentActive = false),
        )
    }

    @Test
    fun preflightAllowsQuarantineForUserGestureWithoutUrl() {
        // null means construct transient quarantine for classification
        assertEquals(
            null,
            q.preflight(targetUrl = null, isUserGesture = true, playIntentActive = false),
        )
    }

    @Test
    fun allowsSameOriginAfterAuthContext() {
        assertEquals(
            PopupQuarantine.Verdict.ALLOW_AUTH_SAME_ORIGIN,
            q.decide(
                targetUrl = "https://site.example/login/callback",
                requestingOrigin = "https://site.example",
                playIntentActive = false,
                playOrigin = null,
            ),
        )
    }

    @Test
    fun allowsPlayResolutionSameFamily() {
        assertEquals(
            PopupQuarantine.Verdict.ALLOW_PLAY_RESOLUTION,
            q.decide(
                targetUrl = "https://player.example.com/embed",
                requestingOrigin = "https://www.example.com",
                playIntentActive = true,
                playOrigin = "https://www.example.com",
            ),
        )
    }

    @Test
    fun blocksExternalSchemes() {
        assertEquals(
            PopupQuarantine.Verdict.DESTROY_EXTERNAL_SCHEME,
            q.decide(
                targetUrl = "intent://scan/#Intent;end",
                requestingOrigin = "https://site.example",
                playIntentActive = true,
                playOrigin = "https://site.example",
            ),
        )
    }

    @Test
    fun playIntentDataIsActiveWithinWindow() {
        val intent = PlayIntent(
            id = "t1",
            origin = "https://x.example",
            url = "https://x.example/watch",
            focusedFingerprint = "BUTTON#real-play",
            role = "BUTTON",
            visibleText = "Play",
            expectedHref = null,
            geometry = "0,0,100x40",
            contentId = "ep-1",
            timestampElapsedMs = 1_000L,
        )
        assertTrue(intent.isActive(1_500L))
        assertTrue(!intent.isActive(1_000L + PlayIntent.ACTIVE_WINDOW_MS + 1))
    }
}
