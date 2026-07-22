package com.keenzero.app.web

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import com.keenzero.app.BuildConfig

/**
 * Fail-closed WebView settings for Phase 0 plain-runtime baseline.
 * No blocker, no site packs, no broad JS bridges.
 */
object HardenedWebSettings {

    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView) {
        val s = webView.settings
        // Streaming web requires JS; this is intentional and documented.
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        // Multiple windows enabled so WebChromeClient.onCreateWindow can
        // quarantine every new-window request. Windows are never attached to
        // the visible hierarchy; PopupQuarantine classifies then destroys.
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        // Present as regular Chrome rather than an embedded WebView. The default
        // WebView UA carries a "; wv" token (and a "Version/4.0" marker) that many
        // sites, sign-in flows and bot-challenge services treat as an automated /
        // embedded client — serving degraded or blocked experiences. Stripping those
        // tokens is a standard, widely-used compatibility fix and changes nothing
        // else about the UA (same Android/Chrome version, still mobile).
        s.userAgentString = s.userAgentString
            .replace("; wv", "")
            .replace(Regex("""Version/[\d.]+ """), "")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
