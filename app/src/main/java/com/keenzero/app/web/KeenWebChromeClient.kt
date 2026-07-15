package com.keenzero.app.web

import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.keenzero.app.diagnostics.NavigationEvent
import com.keenzero.app.playback.PopupQuarantine

/**
 * Chrome client with real [onCreateWindow] quarantine.
 *
 * New-window requests never attach to the visible hierarchy. The temporary
 * WebView captures the first meaningful destination, classifies it, then is destroyed.
 */
class KeenWebChromeClient(
    private val fullscreenHost: FrameLayout,
    private val onFullscreen: (Boolean) -> Unit,
    private val onTitle: (String?) -> Unit,
    private val onConsole: (String) -> Unit,
    private val onEvent: (NavigationEvent) -> Unit,
    private val popupQuarantine: PopupQuarantine,
    private val requestingOrigin: () -> String?,
    private val playIntentActive: () -> Boolean,
    private val playOrigin: () -> String?,
    private val onApprovedMainLoad: (String) -> Unit,
    private val onProgress: (Int) -> Unit = {},
) : WebChromeClient() {

    private var customView: View? = null
    private var customCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgress(newProgress.coerceIn(0, 100))
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val parent = view ?: return false
        val startTime = System.currentTimeMillis()

        // Deny-first: reject without constructing a second WebView when possible.
        val pre = popupQuarantine.preflight(
            targetUrl = null,
            isUserGesture = isUserGesture,
            playIntentActive = playIntentActive(),
        )
        if (pre != null && pre.blocks) {
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "POPUP_REJECT_IMMEDIATE",
                    detail = "verdict=$pre gesture=$isUserGesture dialog=$isDialog play=${playIntentActive()} noQuarantineWebView=1",
                ),
            )
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "POPUP_DESTROYED",
                    detail = "$pre deny_first",
                ),
            )
            return false
        }

        val memBefore = com.keenzero.app.diagnostics.DeviceDiagnostics.getMemorySnapshot(parent.context)

        // Transient quarantine WebView only for ambiguous gesture/play destinations.
        // Never added to the visible hierarchy.
        val quarantine = WebView(parent.context)
        quarantine.settings.javaScriptEnabled = true
        quarantine.settings.domStorageEnabled = false
        quarantine.settings.mediaPlaybackRequiresUserGesture = true
        quarantine.settings.javaScriptCanOpenWindowsAutomatically = false
        quarantine.settings.setSupportMultipleWindows(false)
        quarantine.settings.allowFileAccess = false
        quarantine.settings.allowContentAccess = false
        quarantine.settings.setGeolocationEnabled(false)

        var decided = false
        var timeoutRunnable: Runnable? = null

        fun finishQuarantine(targetUrl: String?) {
            if (decided) return
            decided = true
            timeoutRunnable?.let { parent.removeCallbacks(it) }
            val lifetime = System.currentTimeMillis() - startTime
            val memDuring = com.keenzero.app.diagnostics.DeviceDiagnostics.getMemorySnapshot(parent.context)
            val origin = requestingOrigin()
            val verdict = popupQuarantine.decide(
                targetUrl = targetUrl,
                requestingOrigin = origin,
                playIntentActive = playIntentActive(),
                playOrigin = playOrigin(),
            )
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "POPUP_QUARANTINE_DECISION",
                    url = targetUrl,
                    detail = "verdict=$verdict gesture=$isUserGesture dialog=$isDialog origin=$origin lifetimeMs=$lifetime " +
                            "memBeforePss=${memBefore.optInt("pssKb")} memDuringPss=${memDuring.optInt("pssKb")} " +
                            "renderersBefore=${memBefore.optInt("renderers")} renderersDuring=${memDuring.optInt("renderers")}",
                ),
            )
            try {
                quarantine.stopLoading()
                quarantine.webChromeClient = null
                quarantine.webViewClient = WebViewClient()
                quarantine.destroy()
            } catch (_: Throwable) {
            }
            val memAfter = com.keenzero.app.diagnostics.DeviceDiagnostics.getMemorySnapshot(parent.context)
            when (verdict) {
                PopupQuarantine.Verdict.ALLOW_PLAY_RESOLUTION,
                PopupQuarantine.Verdict.ALLOW_AUTH_SAME_ORIGIN,
                -> {
                    if (!targetUrl.isNullOrBlank()) {
                        onApprovedMainLoad(targetUrl)
                        onEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "POPUP_APPROVED_MAIN_LOAD",
                                url = targetUrl,
                                detail = "${verdict.name} memAfterPss=${memAfter.optInt("pssKb")} renderersAfter=${memAfter.optInt("renderers")}",
                            ),
                        )
                    }
                }
                else -> {
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "POPUP_DESTROYED",
                            url = targetUrl,
                            detail = "${verdict.name} memAfterPss=${memAfter.optInt("pssKb")} renderersAfter=${memAfter.optInt("renderers")}",
                        ),
                    )
                }
            }
        }

        quarantine.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString()
                if (!u.isNullOrBlank()) {
                    finishQuarantine(u)
                    return true
                }
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (!url.isNullOrBlank()) {
                    finishQuarantine(url)
                    return true
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.isNullOrBlank() && url != "about:blank") {
                    finishQuarantine(url)
                }
            }
        }

        val transport = resultMsg?.obj as? WebView.WebViewTransport
        if (transport == null) {
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "POPUP_QUARANTINE_DECISION",
                    detail = "verdict=DESTROY_INVALID no-transport",
                ),
            )
            try {
                quarantine.destroy()
            } catch (_: Throwable) {
            }
            return false
        }
        transport.webView = quarantine
        resultMsg.sendToTarget()
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "POPUP_QUARANTINE_OPEN",
                detail = "gesture=$isUserGesture dialog=$isDialog attached=false memBeforePss=${memBefore.optInt("pssKb")} renderersBefore=${memBefore.optInt("renderers")}",
            ),
        )
        // CONSTRAINT_32: short quarantine lifetime (1s); never leave a second WebView around.
        val quarantineTimeoutMs = 1_000L
        val tr = Runnable {
            if (!decided) {
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "POPUP_TIMEOUT",
                        detail = "Destroying quarantine WebView after ${quarantineTimeoutMs}ms timeout",
                    ),
                )
                finishQuarantine(null)
            }
        }
        timeoutRunnable = tr
        parent.postDelayed(tr, quarantineTimeoutMs)
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullscreenHost.visibility = View.VISIBLE
        fullscreenHost.removeAllViews()
        if (view != null) {
            fullscreenHost.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        onFullscreen(true)
    }

    override fun onHideCustomView() {
        exitFullscreenInternal()
    }

    fun exitFullscreenIfNeeded(): Boolean {
        if (customView == null) return false
        exitFullscreenInternal()
        return true
    }

    private fun exitFullscreenInternal() {
        fullscreenHost.removeAllViews()
        fullscreenHost.visibility = View.GONE
        customView = null
        try {
            customCallback?.onCustomViewHidden()
        } catch (_: Throwable) {
        }
        customCallback = null
        onFullscreen(false)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        onTitle(title)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (consoleMessage != null) {
            onConsole(
                "${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}",
            )
        }
        return super.onConsoleMessage(consoleMessage)
    }

    val isFullscreen: Boolean
        get() = customView != null
}
