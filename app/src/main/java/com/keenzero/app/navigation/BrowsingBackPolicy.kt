package com.keenzero.app.navigation

/**
 * Pure Back-stack policy for TV streaming (shipped).
 * Reverse order: HTML/custom fullscreen → playback chrome → history → home.
 */
object BrowsingBackPolicy {

    enum class Surface {
        HOME,
        BROWSING,
        PLAYBACK_MODE,
        WEB_FULLSCREEN,
        NATIVE_OVERLAY,
        RECOVERY,
        RESTORING,
    }

    enum class Action {
        /** Exit HTML custom-view / document fullscreen; stay on same document. */
        EXIT_FULLSCREEN,
        /** Leave Keen playback chrome; stay on document. */
        EXIT_PLAYBACK_MODE,
        /** WebView.goBack(). */
        HISTORY_BACK,
        /** Destroy host / show home. */
        RETURN_HOME,
        /** finish() activity. */
        SYSTEM_EXIT,
        /** Clear URL bar focus / hide IME. */
        CLEAR_URL_FOCUS,
        /** Dismiss diagnostics overlay. */
        DISMISS_OVERLAY,
    }

    /**
     * @param htmlCustomViewActive chrome custom-view currently showing
     * @param documentFullscreen document.fullscreenElement present
     * @param webViewCanGoBack Android WebView.canGoBack()
     * @param atBrowseEntry true if current URL is the session entry (first openUrl)
     * @param urlBarFocused address field has focus
     */
    fun decide(
        surface: Surface,
        htmlCustomViewActive: Boolean,
        documentFullscreen: Boolean,
        webViewCanGoBack: Boolean,
        atBrowseEntry: Boolean,
        urlBarFocused: Boolean,
    ): Action {
        // Fullscreen always peels first, even if uiState lagged.
        if (htmlCustomViewActive || documentFullscreen || surface == Surface.WEB_FULLSCREEN) {
            return Action.EXIT_FULLSCREEN
        }
        if (surface == Surface.PLAYBACK_MODE) {
            return Action.EXIT_PLAYBACK_MODE
        }
        if (surface == Surface.NATIVE_OVERLAY) {
            return Action.DISMISS_OVERLAY
        }
        if (surface == Surface.HOME) {
            return Action.SYSTEM_EXIT
        }
        if (surface == Surface.RECOVERY) {
            return Action.RETURN_HOME
        }
        // BROWSING / RESTORING
        if (urlBarFocused) return Action.CLEAR_URL_FOCUS
        // Prefer in-session history (WebView or SPA history.back). Never dump to FMHY
        // chooser while still inside a site (movie page → list/home of that site).
        if (webViewCanGoBack || !atBrowseEntry) return Action.HISTORY_BACK
        return Action.RETURN_HOME
    }

    /** Compare session entry vs current (ignore trailing slash / fragment / trivial query). */
    fun isSameBrowseEntry(entryUrl: String?, currentUrl: String?): Boolean {
        if (entryUrl.isNullOrBlank()) return true
        if (currentUrl.isNullOrBlank()) return true
        fun norm(u: String): String {
            var s = u.trim().lowercase()
            s = s.substringBefore('#').trimEnd('/')
            // Keep path; drop only empty trailing
            return s
        }
        val e = norm(entryUrl)
        val c = norm(currentUrl)
        if (e == c) return true
        // Same origin root only (https://cineby.at vs https://cineby.at/)
        return e.removeSuffix("/") == c.removeSuffix("/")
    }
}
