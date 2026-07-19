package com.keenzero.app.navigation

import com.keenzero.app.playback.PlayIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationFirewallTest {
    private var now = 1_000L
    private val firewall = NavigationFirewall(clock = { now })

    @Test
    fun blocksUnsolicitedCrossOriginTopNavigation() {
        firewall.recordCommittedUrl("https://appassets.androidplatform.net/assets/lab/script_nav.html")

        // Known advertising/hostile lab host is classified as advertising nav.
        assertEquals(
            NavigationFirewall.Decision.BLOCK_ADVERTISING_NAV,
            firewall.decide("https://evil.example/steal", isMainFrame = true, hasGesture = false),
        )
        // Unknown host without gesture is an unintended redirect.
        assertEquals(
            NavigationFirewall.Decision.BLOCK_UNINTENDED_REDIRECT,
            firewall.decide("https://unknown-hostile.example/steal", isMainFrame = true, hasGesture = false),
        )
    }

    @Test
    fun allowsSelectedCrossOriginNavigationAndItsShortRedirectChain() {
        firewall.recordCommittedUrl("https://directory.example/")
        firewall.recordUserInput()

        assertEquals(
            NavigationFirewall.Decision.ALLOW_SAME_SESSION,
            firewall.decide("https://catalogue.example/item", isMainFrame = true, hasGesture = false),
        )
        now += 2_000L
        assertEquals(
            NavigationFirewall.Decision.ALLOW_SAME_SESSION,
            firewall.decide("https://player.example/watch", isMainFrame = true, hasGesture = false),
        )
    }

    @Test
    fun blocksExternalSchemes() {
        assertEquals(
            NavigationFirewall.Decision.BLOCK_EXTERNAL_SCHEME,
            firewall.decide("intent://scan/#Intent;scheme=zxing;end", isMainFrame = true, hasGesture = true),
        )
    }

    @Test
    fun popupWithoutPlayIntentIsDestroyed() {
        firewall.recordCommittedUrl("https://app.example/")
        assertEquals(
            NavigationFirewall.Decision.BLOCK_POPUP,
            firewall.decideNewWindow("https://random.example/x"),
        )
    }

    @Test
    fun advertisingPopupIsAlwaysDestroyed() {
        firewall.recordCommittedUrl("https://app.example/")
        firewall.recordPlayIntent(
            PlayIntent(
                id = "p",
                origin = "https://app.example",
                url = "https://app.example/watch",
                focusedFingerprint = "BUTTON#real-play",
                role = "BUTTON",
                visibleText = "Play",
                expectedHref = null,
                geometry = null,
                contentId = "c1",
                timestampElapsedMs = now,
            ),
        )
        assertEquals(
            NavigationFirewall.Decision.BLOCK_POPUP_AD,
            firewall.decideNewWindow("https://evil.example/ad"),
        )
    }

    @Test
    fun disposableAdFarmPopupIsBlockedNotConfirmed() {
        firewall.recordCommittedUrl("https://bcine.ru/")
        // Streaming junk (hai8g.com) with a platform gesture must NOT surface Open/Cancel.
        assertEquals(
            NavigationFirewall.Decision.BLOCK_POPUP_AD,
            firewall.decideNewWindow("https://hai8g.com/4/10707248", isUserGesture = true),
        )
        assertEquals(
            NavigationFirewall.Decision.BLOCK_ADVERTISING_NAV,
            firewall.decide("https://hai8g.com/land", isMainFrame = true, hasGesture = true),
        )
    }

    @Test
    fun crossOriginPopupWithoutGrantIsFailClosed() {
        firewall.recordCommittedUrl("https://www.cineby.at/")
        // Raw site "user gesture" popups must not get a confirm dialog.
        assertEquals(
            NavigationFirewall.Decision.BLOCK_POPUP,
            firewall.decideNewWindow("https://totally-unknown-site.example/x", isUserGesture = true),
        )
    }

    @Test
    fun advertisingTopLevelNavBlockedEvenWithGesture() {
        firewall.recordCommittedUrl("https://app.example/")
        firewall.recordUserInput()
        assertEquals(
            NavigationFirewall.Decision.BLOCK_ADVERTISING_NAV,
            firewall.decide("https://ads.example/land", isMainFrame = true, hasGesture = true),
        )
    }

    @Test
    fun playIntentAllowsSameFamilyResolution() {
        firewall.recordCommittedUrl("https://www.example.com/show")
        firewall.recordPlayIntent(
            PlayIntent(
                id = "p2",
                origin = "https://www.example.com",
                url = "https://www.example.com/show",
                focusedFingerprint = "BUTTON#play",
                role = "BUTTON",
                visibleText = "Play",
                expectedHref = null,
                geometry = null,
                contentId = "ep",
                timestampElapsedMs = now,
            ),
        )
        assertEquals(
            NavigationFirewall.Decision.ALLOW_PLAY_RESOLUTION,
            firewall.decide("https://player.example.com/embed", isMainFrame = true, hasGesture = false),
        )
    }
}
