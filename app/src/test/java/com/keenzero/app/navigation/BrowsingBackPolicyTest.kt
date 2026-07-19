package com.keenzero.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsingBackPolicyTest {

    @Test
    fun fullscreenExitsBeforeHistoryEvenInBrowsingSurface() {
        val a = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.BROWSING,
            htmlCustomViewActive = true,
            documentFullscreen = false,
            webViewCanGoBack = true,
            atBrowseEntry = false,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.EXIT_FULLSCREEN, a)
    }

    @Test
    fun documentFullscreenExitsBeforePlaybackModePeel() {
        val a = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.PLAYBACK_MODE,
            htmlCustomViewActive = false,
            documentFullscreen = true,
            webViewCanGoBack = true,
            atBrowseEntry = false,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.EXIT_FULLSCREEN, a)
    }

    @Test
    fun playbackModeThenHistory() {
        val exitPlay = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.PLAYBACK_MODE,
            htmlCustomViewActive = false,
            documentFullscreen = false,
            webViewCanGoBack = true,
            atBrowseEntry = false,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.EXIT_PLAYBACK_MODE, exitPlay)

        val hist = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.BROWSING,
            htmlCustomViewActive = false,
            documentFullscreen = false,
            webViewCanGoBack = true,
            atBrowseEntry = false,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.HISTORY_BACK, hist)
    }

    @Test
    fun moviePageWithoutNativeHistoryStillHistoryBackNotHome() {
        // SPA movie detail: WebView.canGoBack() false but not at session entry.
        val a = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.BROWSING,
            htmlCustomViewActive = false,
            documentFullscreen = false,
            webViewCanGoBack = false,
            atBrowseEntry = false,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.HISTORY_BACK, a)
    }

    @Test
    fun onlyAtSessionEntryReturnHome() {
        val a = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.BROWSING,
            htmlCustomViewActive = false,
            documentFullscreen = false,
            webViewCanGoBack = false,
            atBrowseEntry = true,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.RETURN_HOME, a)
    }

    @Test
    fun homeSystemExit() {
        val a = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.HOME,
            htmlCustomViewActive = false,
            documentFullscreen = false,
            webViewCanGoBack = false,
            atBrowseEntry = true,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.SYSTEM_EXIT, a)
    }

    @Test
    fun isSameBrowseEntryNormalizesSlash() {
        assertTrue(
            BrowsingBackPolicy.isSameBrowseEntry(
                "https://cineby.at/",
                "https://cineby.at",
            ),
        )
        assertFalse(
            BrowsingBackPolicy.isSameBrowseEntry(
                "https://cineby.at/",
                "https://cineby.at/movie/backrooms",
            ),
        )
    }

    @Test
    fun playbackWithDocumentFullscreenFlagPeelsFullscreenFirst() {
        val a = BrowsingBackPolicy.decide(
            surface = BrowsingBackPolicy.Surface.PLAYBACK_MODE,
            htmlCustomViewActive = false,
            documentFullscreen = true,
            webViewCanGoBack = true,
            atBrowseEntry = false,
            urlBarFocused = false,
        )
        assertEquals(BrowsingBackPolicy.Action.EXIT_FULLSCREEN, a)
    }
}
