package com.keenzero.app.compat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.keenzero.app.blocking.BlockingRuntime
import com.keenzero.app.input.CursorOverlay
import com.keenzero.app.playback.PopupQuarantine
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
 *  - no header rewriting, and no request interception beyond dropping subresources
 *    whose host is on Keen's ad/tracker list (see [client]'s shouldInterceptRequest);
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
 * Auto-fullscreen on Play ([FULLSCREEN_JS]) is the same kind of exception: one call,
 * after a deliberate Play, installing nothing.
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
    /** Playback took the screen (true) or gave it back (false): hide/show Keen's chrome. */
    private val onPlaybackMode: (Boolean) -> Unit = {},
) {

    val instanceId: Int = NEXT_ID.incrementAndGet()

    private var webView: WebView? = null
    private var cursor: CursorOverlay? = null
    private var controller: CompatibilityRemoteController? = null
    private val handler = Handler(Looper.getMainLooper())

    /** The approved registrable host this session is bound to. */
    @Volatile
    var boundHost: String? = null
        private set

    /** Chromium's HTML-fullscreen view while the page is in fullscreen, else null. */
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** Keen chrome hidden for playback, by HTML fullscreen or the play fallback. */
    private var playbackMode = false

    /** One automatic fullscreen per page: a pause/resume must not re-trigger it. */
    private var autoFullscreenDone = false

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val adQuarantine = PopupQuarantine()

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
        audioManager?.registerAudioPlaybackCallback(playbackWatcher, handler)

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

    /** Back while the page is fullscreen or in playback mode: leave that first. */
    fun exitPlaybackIfNeeded(): Boolean {
        if (customView != null) {
            exitFullscreen()
            return true
        }
        if (playbackMode) {
            setPlaybackMode(false)
            return true
        }
        return false
    }

    fun destroy() {
        audioManager?.unregisterAudioPlaybackCallback(playbackWatcher)
        exitFullscreen()
        setPlaybackMode(false)
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
                // Except to an ad host: onLeaveOrigin opens the target as if the user had
                // typed it, so a click handler sending the page to an ad network would
                // otherwise become a full-page ad. The page simply stays where it is.
                if (isAdDestination(url)) {
                    CompatibilityDiag.event("leave_origin_blocked", instanceId, "reason" to "ad_host", "to" to hostOnly(url))
                    return true
                }
                CompatibilityDiag.event("leave_origin", instanceId, "to" to hostOnly(url))
                handler.post { onLeaveOrigin(url) }
                return true
            }
            // Everything else stays internal: returning false keeps redirect chains and
            // the challenge's own Set-Cookie → redirect sequence intact.
            return false
        }

        /**
         * Ad blocking, by host only. Compatibility origins are exactly the streaming sites
         * with the worst ads, and running them with no blocking at all put "missed video
         * call" overlays over nepu.io's player. Dropping a third-party ad request changes
         * nothing a challenge can inspect, provided the challenge's own hosts and the
         * site's origin are never touched; the main frame never is.
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            if (request == null || request.isForMainFrame) return null
            val host = request.url?.host?.lowercase() ?: return null
            if (host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com")) {
                return null
            }
            val bound = boundHost
            if (bound != null && (host == bound || host.endsWith(".$bound"))) return null
            if (!BlockingRuntime.isHostBlocked(host)) return null
            android.util.Log.i("KZ_NETDIAG", "blk=true mode=compat host=$host")
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "Blocked by Keen Zero",
                mapOf("Cache-Control" to "no-store"),
                java.io.ByteArrayInputStream(ByteArray(0)),
            )
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            autoFullscreenDone = false
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
         * HTML fullscreen. Without this override WebView tells the page fullscreen is
         * unsupported, so players fell back to filling the viewport under Keen's chrome.
         */
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            controller?.tapTarget = view
            setPlaybackMode(true)
            CompatibilityDiag.event("fullscreen_enter", instanceId)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        /**
         * Hidden provisional popup, allowed to survive only if its first real destination
         * is the challenge platform. Everything else — ads, trackers, unrelated hosts,
         * file:/data:/intent:/javascript:, and anything unclassified — is destroyed.
         * No visible popup is ever created, and the normal WebView is never replaced.
         *
         * The one other way out is a `target="_blank"` link the user actually pressed.
         * fmhy.net, promoted here by a Cloudflare loop, marks every link that way, so
         * every OK on it was destroyed as "not_challenge". With no script in this
         * WebView, the proof is the hit test: the anchor under the native tap. When the
         * popup's first destination is that href, it is followed as an ordinary link
         * (out of the origin to the normal WebView, otherwise in place). A click-hijack
         * opening anything else still misses the href and is destroyed as before.
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

            val tappedHref = if (isUserGesture) tappedAnchorHref(parent) else null

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
                    if (followTappedLink(parent, tappedHref, url)) {
                        finish("user_link", url)
                        return true
                    }
                    if (!isChallengeDestination(url)) {
                        finish("not_challenge", url)
                        return true
                    }
                    CompatibilityDiag.event("popup_allowed", instanceId, "host" to hostOnly(url))
                    return false
                }

                override fun onPageStarted(v: WebView?, url: String?, f: Bitmap?) {
                    if (url == null || isBlankish(url)) return
                    if (followTappedLink(parent, tappedHref, url)) {
                        finish("user_link", url)
                        return
                    }
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

    // ------------------------------------------------------------- playback

    private fun setPlaybackMode(enter: Boolean) {
        if (playbackMode == enter) return
        playbackMode = enter
        onPlaybackMode(enter)
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        controller?.tapTarget = null
        container.removeView(view)
        try {
            customCallback?.onCustomViewHidden()
        } catch (_: Throwable) {
        }
        customCallback = null
        setPlaybackMode(false)
        CompatibilityDiag.event("fullscreen_exit", instanceId)
    }

    /**
     * Play detection with no script in the page: the platform reports this app opening
     * a media audio stream. Only counted within [PLAY_TAP_WINDOW_MS] of an OK into the
     * page, so a muted autoplay trailer or an ad that starts by itself never takes the
     * screen.
     */
    private val playbackWatcher = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (configs.isNullOrEmpty() || autoFullscreenDone || customView != null) return
            val media = configs.any {
                val usage = it.audioAttributes.usage
                usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_UNKNOWN
            }
            if (!media) return
            val tapAt = controller?.lastTapAt ?: return
            if (tapAt == 0L || android.os.SystemClock.elapsedRealtime() - tapAt > PLAY_TAP_WINDOW_MS) return
            autoFullscreenDone = true
            requestAutoFullscreen()
        }
    }

    private fun requestAutoFullscreen() {
        val wv = webView ?: return
        wv.evaluateJavascript(FULLSCREEN_JS) { result ->
            CompatibilityDiag.event("auto_fullscreen", instanceId, "result" to result)
            // HTML fullscreen arrives through onShowCustomView a moment later. If the page
            // refused it (activation expired, or the player is not reachable), still give
            // the player the whole screen by dropping Keen's chrome.
            handler.postDelayed({
                if (customView == null && webView != null) setPlaybackMode(true)
            }, FULLSCREEN_FALLBACK_MS)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** href of the link under the last tap, or null when the tap was not on a link. */
    private fun tappedAnchorHref(view: WebView): String? {
        val hit = view.hitTestResult
        if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE) return null
        return hit.extra?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /**
     * Follows [url] as an ordinary link press when it is the [tappedHref] the user OK'd.
     * Leaving the origin goes through [onLeaveOrigin], exactly like a same-tab link, so
     * the normal WebView takes it with every protection back in force.
     */
    private fun followTappedLink(parent: WebView, tappedHref: String?, url: String): Boolean {
        if (tappedHref == null || !sameLink(tappedHref, url)) return false
        // A real anchor can still be an ad: ext.to's movie pages carry <a target=_blank>
        // links to affiliate trackers (gotrackier.com), and following one loaded a
        // full-page ad. Falling through destroys the popup as not_challenge.
        if (isAdDestination(url)) return false
        handler.post {
            if (CompatibilityOrigins.leavesOrigin(boundHost?.let { "https://$it" }, url)) {
                CompatibilityDiag.event("leave_origin", instanceId, "to" to hostOnly(url))
                onLeaveOrigin(url)
            } else {
                parent.loadUrl(url)
            }
        }
        return true
    }

    /** Ad network by Keen's host list or the quarantine's ad / throwaway-host check. */
    private fun isAdDestination(url: String): Boolean {
        val host = try {
            android.net.Uri.parse(url).host?.lowercase()
        } catch (_: Throwable) {
            null
        } ?: return false
        return BlockingRuntime.isHostBlocked(host) ||
            adQuarantine.decide(url, null, false, null) == PopupQuarantine.Verdict.DESTROY_ADVERTISING
    }

    private fun sameLink(a: String, b: String): Boolean {
        fun norm(u: String) = u.trim().substringBefore('#').trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

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

        /** How long after an OK a starting audio stream still counts as that Play. */
        const val PLAY_TAP_WINDOW_MS = 8_000L

        /** Grace for onShowCustomView to arrive before falling back to chrome-hide. */
        const val FULLSCREEN_FALLBACK_MS = 600L

        /**
         * Fullscreen the player that is playing. A video in this document goes fullscreen
         * through its player box (the largest ancestor still hugging the video), so the
         * site's own controls come with it. A video in a cross-origin iframe cannot be
         * seen from here, so the largest visible iframe goes instead. Relies on the Play
         * tap's user activation, which Chromium keeps for a few seconds.
         */
        const val FULLSCREEN_JS = """(function(){
  if(document.fullscreenElement) return 'already';
  var vw=innerWidth, vh=innerHeight, target=null, kind='none';
  var vids=document.querySelectorAll('video');
  for(var i=0;i<vids.length;i++){
    var v=vids[i];
    if(v.paused||v.ended||v.readyState<2) continue;
    var r=v.getBoundingClientRect();
    if(r.width<vw*0.3) continue;
    target=v; kind='video';
    var a=v.parentElement;
    while(a&&a!==document.body&&a!==document.documentElement){
      var ar=a.getBoundingClientRect();
      if(ar.width>r.width*1.15+40||ar.height>r.height*1.35+120) break;
      target=a; kind='player'; a=a.parentElement;
    }
    break;
  }
  if(!target){
    var best=0, fr=document.querySelectorAll('iframe');
    for(var j=0;j<fr.length;j++){
      var q=fr[j].getBoundingClientRect();
      var area=Math.max(0,Math.min(q.right,vw)-Math.max(q.left,0))*Math.max(0,Math.min(q.bottom,vh)-Math.max(q.top,0));
      if(area>best&&area>vw*vh*0.2){best=area; target=fr[j]; kind='iframe';}
    }
  }
  if(!target||typeof target.requestFullscreen!=='function') return 'no_target';
  try{ var p=target.requestFullscreen(); if(p&&p.catch) p.catch(function(){}); }catch(e){ return 'threw'; }
  return kind;
})()"""
    }
}
