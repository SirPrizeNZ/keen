package com.keenzero.app.web

import android.os.Message
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.keenzero.app.diagnostics.NavigationEvent
import com.keenzero.app.navigation.ActivationLedger
import com.keenzero.app.navigation.WindowRequestBroker
import com.keenzero.app.playback.PopupQuarantine

/**
 * Chrome client with activation-aware new-window policy.
 *
 * Provisional WebViews are never attached to the visible hierarchy.
 * High-risk deliberate mismatches require native confirmation (never silent drop).
 */
class KeenWebChromeClient(
    private val fullscreenHost: FrameLayout,
    private val onFullscreen: (Boolean) -> Unit,
    private val onTitle: (String?) -> Unit,
    private val onConsole: (String) -> Unit,
    private val onEvent: (NavigationEvent) -> Unit,
    private val popupQuarantine: PopupQuarantine,
    private val windowBroker: WindowRequestBroker,
    private val activationLedger: ActivationLedger,
    private val requestingOrigin: () -> String?,
    private val playIntentActive: () -> Boolean,
    private val playOrigin: () -> String?,
    private val onApprovedMainLoad: (String) -> Unit,
    private val onRequireConfirmation: (url: String, host: String?, reason: String) -> Unit,
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
        val startElapsed = SystemClock.elapsedRealtime()
        val grant = activationLedger.peek()
        val decision = windowBroker.decide(
            targetUrl = null,
            isUserGesture = isUserGesture,
            pageOrigin = requestingOrigin(),
            grant = grant,
            playIntentActive = playIntentActive(),
            playOrigin = playOrigin(),
        )

        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "WINDOW_BROKER",
                detail = "action=${decision.action} reason=${decision.reason} gesture=$isUserGesture " +
                    "grant=${grant?.type} play=${playIntentActive()}",
            ),
        )

        when (decision.action) {
            WindowRequestBroker.Action.BLOCK -> {
                if (decision.consumeGrant) activationLedger.consume()
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "POPUP_REJECT_IMMEDIATE",
                        detail = "broker=${decision.reason} noQuarantineWebView=1",
                    ),
                )
                return false
            }
            WindowRequestBroker.Action.OPEN_CURRENT_SESSION,
            WindowRequestBroker.Action.REQUIRE_CONFIRMATION,
            WindowRequestBroker.Action.PROVISIONAL_CAPTURE,
            -> {
                // Need provisional capture when URL is not yet known.
            }
        }

        // Hidden provisional WebView — never added to container.
        val quarantine = WebView(parent.context)
        quarantine.settings.javaScriptEnabled = true
        quarantine.settings.domStorageEnabled = false
        quarantine.settings.mediaPlaybackRequiresUserGesture = true
        quarantine.settings.javaScriptCanOpenWindowsAutomatically = false
        quarantine.settings.setSupportMultipleWindows(false)
        quarantine.settings.allowFileAccess = false
        quarantine.settings.allowContentAccess = false
        quarantine.settings.setGeolocationEnabled(false)
        quarantine.visibility = View.GONE

        var decided = false
        var timeoutRunnable: Runnable? = null

        fun destroyProvisional() {
            try {
                quarantine.stopLoading()
                quarantine.webChromeClient = null
                quarantine.webViewClient = WebViewClient()
                quarantine.destroy()
            } catch (_: Throwable) {
            }
        }

        fun finishQuarantine(targetUrl: String?) {
            if (decided) return
            decided = true
            timeoutRunnable?.let { parent.removeCallbacks(it) }
            val lifetimeMs = SystemClock.elapsedRealtime() - startElapsed
            val origin = requestingOrigin()
            val g = activationLedger.peek()
            val final = windowBroker.decide(
                targetUrl = targetUrl,
                isUserGesture = isUserGesture,
                pageOrigin = origin,
                grant = g,
                playIntentActive = playIntentActive(),
                playOrigin = playOrigin(),
            )
            if (final.consumeGrant) activationLedger.consume()

            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "POPUP_QUARANTINE_DECISION",
                    url = targetUrl,
                    detail = "action=${final.action} reason=${final.reason} gesture=$isUserGesture " +
                        "origin=$origin lifetimeMs=$lifetimeMs host=${final.destinationHost}",
                ),
            )
            destroyProvisional()

            when (final.action) {
                WindowRequestBroker.Action.OPEN_CURRENT_SESSION -> {
                    if (!targetUrl.isNullOrBlank() && !targetUrl.equals("about:blank", true)) {
                        onApprovedMainLoad(targetUrl)
                        onEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "POPUP_APPROVED_MAIN_LOAD",
                                url = targetUrl,
                                detail = final.reason,
                            ),
                        )
                    } else {
                        onEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "POPUP_DESTROYED",
                                url = targetUrl,
                                detail = "empty_after_open reason=${final.reason}",
                            ),
                        )
                    }
                }
                WindowRequestBroker.Action.REQUIRE_CONFIRMATION -> {
                    if (!targetUrl.isNullOrBlank() && !targetUrl.equals("about:blank", true)) {
                        onEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "POPUP_REQUIRE_CONFIRMATION",
                                url = targetUrl,
                                detail = final.reason,
                            ),
                        )
                        onRequireConfirmation(targetUrl, final.destinationHost, final.reason)
                    } else {
                        onEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "POPUP_DESTROYED",
                                url = targetUrl,
                                detail = "confirm_empty reason=${final.reason}",
                            ),
                        )
                    }
                }
                WindowRequestBroker.Action.BLOCK,
                WindowRequestBroker.Action.PROVISIONAL_CAPTURE,
                -> {
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "POPUP_DESTROYED",
                            url = targetUrl,
                            detail = final.reason,
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
                if (!url.isNullOrBlank() && !url.equals("about:blank", true)) {
                    finishQuarantine(url)
                }
            }
        }

        val transport = resultMsg?.obj as? WebView.WebViewTransport
        if (transport == null) {
            destroyProvisional()
            return false
        }
        transport.webView = quarantine
        resultMsg.sendToTarget()
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "POPUP_QUARANTINE_OPEN",
                detail = "gesture=$isUserGesture provisional=1 visible=0",
            ),
        )
        val quarantineTimeoutMs = 1_800L
        val tr = Runnable {
            if (!decided) {
                activationLedger.clear()
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "POPUP_TIMEOUT",
                        detail = "provisional_timeout_ms=$quarantineTimeoutMs",
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
        // Pointer + Activity key routing own input. Block D-pad focus/selection inside
        // the HTML player surface so keys never drive DOM outlines under fullscreen.
        fullscreenHost.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        fullscreenHost.isFocusable = false
        fullscreenHost.isFocusableInTouchMode = false
        fullscreenHost.visibility = View.VISIBLE
        fullscreenHost.removeAllViews()
        if (view != null) {
            suppressFocusTree(view)
            fullscreenHost.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            // Keep video painting under Keen pointer layer (sibling above host).
            fullscreenHost.elevation = 8f
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

    /** Active HTML custom-view surface (for pointer click/hover while fullscreen). */
    val fullscreenCustomView: View?
        get() = customView

    private fun exitFullscreenInternal() {
        fullscreenHost.removeAllViews()
        fullscreenHost.visibility = View.GONE
        fullscreenHost.elevation = 0f
        customView = null
        try {
            customCallback?.onCustomViewHidden()
        } catch (_: Throwable) {
        }
        customCallback = null
        onFullscreen(false)
    }

    private fun suppressFocusTree(root: View) {
        root.isFocusable = false
        root.isFocusableInTouchMode = false
        root.isClickable = root.isClickable // leave click for synthetic touches
        if (root is ViewGroup) {
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            for (i in 0 until root.childCount) {
                suppressFocusTree(root.getChildAt(i))
            }
        }
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
