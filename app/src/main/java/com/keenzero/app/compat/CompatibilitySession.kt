package com.keenzero.app.compat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.keenzero.app.input.CursorOverlay
import com.keenzero.app.torrent.TorrentDownloadIntercept
import java.util.concurrent.atomic.AtomicInteger

/**
 * An isolated, stock-configured WebView used only for approved compatibility origins.
 *
 * This is a *separate instance*, never the normal Keen WebView with protections toggled
 * off. Nothing here mutates shared state: no static flags, no shared WebSettings object,
 * no changes to BlockingRuntime. When the session ends, the instance is destroyed and the
 * normal WebView resumes with every protection exactly as it was.
 *
 * What "stock" means here, concretely:
 *  - the WebView's own user-agent string, including the `wv` token — no Chrome cosplay;
 *  - no user-agent metadata / Sec-CH-UA override;
 *  - no `addDocumentStartJavaScript`, no JS bridge;
 *  - no request interception, so no blocking and no header rewriting;
 *  - hardware accelerated, real Mali rendering.
 *
 * The remote is served by [CompatibilityRemoteController], which works entirely through
 * native input events, so usability costs the page nothing observable.
 *
 * **The one scripting exception**, and why it does not weaken the above: a `.torrent`
 * download runs a single `evaluateJavascript` to read the file through the page (see
 * [com.keenzero.app.torrent.TorrentDownloadIntercept.fetchInPage]). It fires on an
 * explicit user action, long after the challenge has been cleared, and installs nothing —
 * no document-start script, no bridge, no persistent object. The environment a challenge
 * inspects at load time is untouched, which is the property this class exists to protect.
 */
class CompatibilitySession(
    private val context: Context,
    private val container: ViewGroup,
    private val cursorHost: ViewGroup,
    private val onLeaveOrigin: (String) -> Unit,
    private val onBack: () -> Boolean,
    /** magnet: link → native torrent streaming, same as the normal WebView path. */
    private val onMagnet: (String) -> Unit = {},
    /**
     * Site offered a .torrent download → fetch + stream natively, as in normal mode.
     * [base64] is the file's bytes when the page could read them for us, else null.
     */
    private val onTorrentFile: (
        (url: String, cookies: String?, userAgent: String?, base64: String?) -> Unit
    )? = null,
    /** Bounds of the K logo in the cursor's coordinate space, or null when hidden. */
    private val homeButtonRect: () -> android.graphics.RectF? = { null },
    /** Pointer OK on the K logo: return to the home surface. */
    private val onHomeActivate: () -> Unit = {},
    /** Bounds of the favourite star in the cursor's coordinate space, or null. */
    private val starButtonRect: () -> android.graphics.RectF? = { null },
    /** Pointer OK on the star: toggle the favourite for the current page. */
    private val onFavouriteActivate: () -> Unit = {},
    /** Height of Keen's chrome bar above this WebView, or 0 when it is hidden. */
    private val chromeHeightPx: () -> Int = { 0 },
    /** Pointer OK in the chrome band but off the logo/star: focus the address bar. */
    private val onUrlBarActivate: () -> Unit = {},
) {

    val instanceId: Int = NEXT_ID.incrementAndGet()

    private var webView: WebView? = null
    private var cursor: CursorOverlay? = null
    private var controller: CompatibilityRemoteController? = null
    private val handler = Handler(Looper.getMainLooper())

    /** The approved registrable host this session is bound to. */
    var boundHost: String? = null
        private set

    val isActive: Boolean get() = webView != null

    // --------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    fun start(url: String) {
        if (webView != null) {
            load(url)
            return
        }
        boundHost = CompatibilityOrigins.approvedHostFor(url)

        // Diagnostic: guarantee a challenge instead of riding a previous clearance.
        if (com.keenzero.app.diagnostics.ExperimentFlags.isOn(com.keenzero.app.diagnostics.ExperimentFlags.RESET_VERIFICATION)) {
            clearChallengeCookies()
            android.util.Log.i(com.keenzero.app.diagnostics.ExperimentFlags.TAG, "compat: challenge cookies CLEARED for $boundHost")
        }

        val wv = WebView(context)
        // Genuine hardware acceleration: no software layer type anywhere in this class.
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.mediaPlaybackRequiresUserGesture = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // Popups reach onCreateWindow so the narrow challenge-only policy below applies.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }
        // Deliberately NOT set: userAgentString, user-agent metadata, allowFileAccess
        // overrides, mixed-content policy changes. Stock values are the whole point, and
        // the identity must not change once navigation begins.

        // Media Integrity: restore the platform default rather than whatever Keen may
        // prefer elsewhere. We only observe its state; we never spoof a result.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) {
            CompatibilityDiag.event("media_integrity", instanceId, "status" to "supported_default")
        } else {
            CompatibilityDiag.event("media_integrity", instanceId, "status" to "unsupported")
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = client
        wv.webChromeClient = chrome

        // A .torrent download is the other half of what a torrent index is for, and this
        // listener was missing entirely: compatibility mode handled `magnet:` and let
        // every `.torrent` fall on the floor. Cloudflare-challenged trackers are promoted
        // *into* this mode, so the sites most likely to offer a .torrent were the ones
        // that could never open one. Downloads that are not torrents stay refused —
        // nothing here starts writing files the user did not ask for.
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (TorrentDownloadIntercept.isTorrentDownload(url, contentDisposition, mimetype)) {
                CompatibilityDiag.event(
                    "torrent_download_intercepted",
                    instanceId,
                    "mime" to (mimetype ?: ""),
                )
                val cookies = TorrentDownloadIntercept.cookiesFor(url)
                TorrentDownloadIntercept.fetchInPage(wv, url) { base64 ->
                    CompatibilityDiag.event(
                        "torrent_in_page_fetch",
                        instanceId,
                        "result" to if (base64 == null) "miss" else "bytes=${base64.length / 4 * 3}",
                    )
                    handler.post { onTorrentFile?.invoke(url, cookies, userAgent, base64) }
                }
            } else {
                CompatibilityDiag.event("download_refused", instanceId)
            }
        }

        container.addView(wv)
        webView = wv

        val c = CursorOverlay(context)
        c.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        cursorHost.addView(c)
        cursor = c

        val ctrl = CompatibilityRemoteController(
            webView = wv,
            cursor = c,
            onBack = onBack,
            homeButtonRect = homeButtonRect,
            onHomeActivate = onHomeActivate,
            starButtonRect = starButtonRect,
            onFavouriteActivate = onFavouriteActivate,
            chromeHeightPx = chromeHeightPx,
            onUrlBarActivate = onUrlBarActivate,
        )
        ctrl.attach()
        controller = ctrl

        wv.requestFocus()

        val pkg = try {
            WebViewCompat.getCurrentWebViewPackage(context)
        } catch (_: Throwable) {
            null
        }
        CompatibilityDiag.event(
            "compat_enter",
            instanceId,
            "host" to boundHost,
            "provider" to pkg?.packageName,
            "providerVersion" to pkg?.versionName,
            "uaHash" to CompatibilityDiag.uaHash(s.userAgentString),
            "uaMetadataOverridden" to false,
            "jsBridge" to false,
            "documentStartScript" to false,
            "hardwareAccelerated" to (wv.layerType == View.LAYER_TYPE_HARDWARE),
            "firstPartyCookies" to cm.acceptCookie(),
            "thirdPartyCookies" to true,
            "dpadAttached" to ctrl.attached,
        )

        load(url)
    }

    fun load(url: String) {
        webView?.loadUrl(url)
    }

    fun handleKey(event: android.view.KeyEvent): Boolean =
        controller?.handleKey(event) ?: false

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    fun onPause() {
        webView?.onPause()
    }

    fun onResume() {
        webView?.onResume()
    }

    /**
     * Site-scoped verification reset: clears only this origin's challenge state, then
     * rebuilds the session. Every other site is untouched — this never calls
     * removeAllCookies or a global WebStorage wipe.
     */
    fun resetVerification(reloadUrl: String?) {
        val cleared = clearChallengeCookies()
        CompatibilityDiag.event(
            "verification_reset", instanceId, "host" to boundHost, "cookiesCleared" to cleared,
        )
        val target = reloadUrl ?: boundHost?.let { "https://$it/" } ?: return
        destroy()
        start(target)
    }

    /** Expire only this origin's Cloudflare cookies. Returns how many were cleared. */
    private fun clearChallengeCookies(): Int {
        val host = boundHost ?: return 0
        val cm = CookieManager.getInstance()
        val origins = listOf("https://$host", "https://www.$host")
        var cleared = 0
        for (origin in origins) {
            val existing = cm.getCookie(origin) ?: continue
            for (pair in existing.split(';')) {
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty()) continue
                if (name != "cf_clearance" && !name.startsWith("cf_chl") && name != "__cf_bm") continue
                // Expire in place: scoped to this origin, never a global cookie flush.
                for (domain in listOf(host, ".$host")) {
                    cm.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=$domain")
                }
                cleared++
            }
        }
        cm.flush()
        WebStorage.getInstance().deleteOrigin("https://$host")
        return cleared
    }

    fun destroy() {
        controller?.detach()
        controller = null
        cursor?.let { c ->
            (c.parent as? ViewGroup)?.removeView(c)
        }
        cursor = null
        webView?.let { wv ->
            try {
                container.removeView(wv)
                wv.stopLoading()
                wv.webChromeClient = null
                wv.webViewClient = WebViewClient()
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
        webView = null
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        CompatibilityDiag.event("compat_exit", instanceId, "host" to boundHost)
        boundHost = null
    }

    // ------------------------------------------------------------------ client

    private val client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            // magnet: is the entire point of a torrent index — hand it to Keen's native
            // streaming exactly as the normal WebView does. Never treated as "leaving
            // the origin": the user stays on the page, the torrent opens over the top.
            if (url.startsWith("magnet:?", ignoreCase = true)) {
                CompatibilityDiag.event("magnet_intercepted", instanceId)
                handler.post { onMagnet(url) }
                return true
            }
            // A link straight to a .torrent, handled here rather than as a navigation.
            //
            // Trackers commonly host the file on a separate domain (ext.to serves its
            // through tfiles.org), so the origin check below would claim it first and end
            // compatibility mode to "navigate" to a file that is not a page. That did
            // eventually reach the torrent pipeline via the normal WebView, but only by
            // tearing down a session whose Cloudflare clearance had already been won.
            // Taking it here keeps the page, and the clearance, exactly where they are.
            //
            // KILL SWITCH: `adb shell touch /data/local/tmp/keen_no_compat_torrent`
            // (then reopen the page) restores the old behaviour — the branch below wins
            // and the navigation leaves compatibility mode as it did before.
            if (request.isForMainFrame &&
                TorrentDownloadIntercept.isTorrentDownload(url, null, null) &&
                !java.io.File("/data/local/tmp/keen_no_compat_torrent").exists()
            ) {
                CompatibilityDiag.event("torrent_link_intercepted", instanceId, "to" to hostOnly(url))
                val cookies = TorrentDownloadIntercept.cookiesFor(url)
                val wv = view
                if (wv == null) {
                    handler.post { onTorrentFile?.invoke(url, cookies, null, null) }
                } else {
                    // Cross-origin, so the in-page read usually cannot see the response and
                    // returns null; the service then fetches it natively, which is fine for
                    // a plain file mirror. Same-origin links still get the page's own
                    // network stack, which is the only thing that works behind a challenge.
                    // Posted rather than run inline: evaluateJavascript re-entering the
                    // WebView from inside its own navigation callback is asking for
                    // trouble, and the answer is not needed until the next loop anyway.
                    handler.post {
                        TorrentDownloadIntercept.fetchInPage(wv, url) { base64 ->
                            CompatibilityDiag.event(
                                "torrent_link_fetch",
                                instanceId,
                                "result" to if (base64 == null) "native_fallback" else "in_page",
                            )
                            handler.post { onTorrentFile?.invoke(url, cookies, wv.settings.userAgentString, base64) }
                        }
                    }
                }
                return true
            }
            // Leaving the approved origin ends compatibility mode. The normal WebView
            // takes the navigation, with all protections back in force.
            if (request.isForMainFrame && CompatibilityOrigins.leavesOrigin(boundHost?.let { "https://$it" }, url)) {
                CompatibilityDiag.event("leave_origin", instanceId, "to" to hostOnly(url))
                handler.post { onLeaveOrigin(url) }
                return true
            }
            // Everything else stays internal: returning false keeps redirect chains and
            // the challenge's own Set-Cookie → redirect sequence intact.
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            CompatibilityDiag.event(
                "page_started",
                instanceId,
                "host" to hostOnly(url),
                "path" to pathOnly(url),
                "mainFrame" to true,
                "challengeUrl" to isChallengeUrl(url),
            )
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val cm = CookieManager.getInstance()
            val names = try {
                cm.getCookie(url)?.split(';')
                    ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
                    .orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
            cm.flush()
            // Additive bisect: reintroduce the D-pad's page-visible indexing JS, the
            // one part of RemoteInputRouter a page can actually observe. The router's
            // other work (playback, firewall, chrome bar) is native and invisible, so
            // wiring the whole router would test several variables at once.
            if (com.keenzero.app.diagnostics.ExperimentFlags.isOn(com.keenzero.app.diagnostics.ExperimentFlags.ADD_ROUTER_JS)) {
                view?.evaluateJavascript(
                    com.keenzero.app.input.InteractionIndex.COLLECT_JS,
                    null,
                )
                android.util.Log.i(com.keenzero.app.diagnostics.ExperimentFlags.TAG, "compat: router indexing JS INJECTED")
            }
            CompatibilityDiag.event(
                "page_finished",
                instanceId,
                "host" to hostOnly(url),
                "path" to pathOnly(url),
                "cookieCount" to names.size,
                "cfClearance" to names.contains("cf_clearance"),
                "challengeUrl" to isChallengeUrl(url),
                "cursorVisible" to (cursor?.visibility == View.VISIBLE),
                "dpadAttached" to (controller?.attached == true),
            )
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?,
        ) {
            if (request?.isForMainFrame != true) return
            CompatibilityDiag.event(
                "http_error",
                instanceId,
                "host" to hostOnly(request.url?.toString()),
                "status" to errorResponse?.statusCode,
            )
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            // Classified separately from a server rejection: a dead renderer that we
            // silently replaced would look exactly like "the challenge reloaded".
            CompatibilityDiag.event(
                "renderer_gone",
                instanceId,
                "didCrash" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() else null,
            )
            destroy()
            return true
        }
    }

    // ------------------------------------------------------------------ popups

    private val chrome = object : WebChromeClient() {

        /**
         * Hidden provisional popup, allowed to survive only if its first real destination
         * is the challenge platform. Everything else — ads, trackers, unrelated hosts,
         * file:/data:/intent:/javascript:, and anything unclassified — is destroyed.
         * No visible popup is ever created, and the normal WebView is never replaced.
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val parent = view ?: return false
            val probe = WebView(parent.context)
            probe.settings.javaScriptEnabled = true
            probe.settings.domStorageEnabled = false
            probe.settings.setSupportMultipleWindows(false)
            probe.settings.javaScriptCanOpenWindowsAutomatically = false
            probe.settings.allowFileAccess = false
            probe.settings.allowContentAccess = false
            probe.visibility = View.GONE

            var settled = false
            fun finish(reason: String, url: String?) {
                if (settled) return
                settled = true
                CompatibilityDiag.event(
                    "popup_destroyed",
                    instanceId,
                    "reason" to reason,
                    "host" to hostOnly(url),
                )
                try {
                    probe.stopLoading()
                    probe.webViewClient = WebViewClient()
                    probe.destroy()
                } catch (_: Throwable) {
                }
            }

            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return true
                    if (isBlankish(url)) return false // still waiting for a real target
                    if (!isChallengeDestination(url)) {
                        finish("not_challenge", url)
                        return true
                    }
                    CompatibilityDiag.event("popup_allowed", instanceId, "host" to hostOnly(url))
                    return false
                }

                override fun onPageStarted(v: WebView?, url: String?, f: Bitmap?) {
                    if (url == null || isBlankish(url)) return
                    if (!isChallengeDestination(url)) finish("not_challenge", url)
                }
            }

            // Hard timeout: a provisional window never lingers.
            handler.postDelayed({ finish("timeout", null) }, POPUP_TIMEOUT_MS)

            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: run {
                finish("no_transport", null)
                return false
            }
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun isBlankish(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.isEmpty() || u == "about:blank" || u == "about:srcdoc"
    }

    /** Only the challenge platform is allowed to own a popup in compatibility mode. */
    private fun isChallengeDestination(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.startsWith("https://")) return false
        val host = hostOnly(url) ?: return false
        if (host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com")) {
            return true
        }
        val bound = boundHost ?: return false
        val sameOrigin = host == bound || host.endsWith(".$bound")
        return sameOrigin && (pathOnly(url)?.startsWith("/cdn-cgi/challenge-platform/") == true)
    }

    private fun isChallengeUrl(url: String?): Boolean {
        val u = url?.lowercase() ?: return false
        return u.contains("/cdn-cgi/challenge-platform/") || u.contains("challenges.cloudflare.com")
    }

    private fun hostOnly(url: String?): String? = try {
        url?.let { android.net.Uri.parse(it).host?.lowercase() }
    } catch (_: Throwable) {
        null
    }

    private fun pathOnly(url: String?): String? = try {
        url?.let { android.net.Uri.parse(it).path }
    } catch (_: Throwable) {
        null
    }

    private companion object {
        val NEXT_ID = AtomicInteger(0)
        const val POPUP_TIMEOUT_MS = 4_000L
    }
}
