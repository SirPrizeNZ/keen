package com.keenzero.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.DownloadListener
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.widget.FrameLayout
import com.keenzero.app.continuity.ContinuityCheckpoint
import com.keenzero.app.diagnostics.NavigationEvent
import com.keenzero.app.diagnostics.MemoryPressureDiagnostics
import com.keenzero.app.input.RemoteInputRouter
import com.keenzero.app.input.CursorOverlay
import com.keenzero.app.navigation.NavigationFirewall
import com.keenzero.app.blocking.BlockingRuntime
import com.keenzero.app.playback.PlayIntent
import com.keenzero.app.playback.PlaybackOrchestrator
import com.keenzero.app.playback.PlaybackJourneyState
import com.keenzero.app.playback.PopupQuarantine
import com.keenzero.app.navigation.ActivationLedger
import com.keenzero.app.navigation.WindowRequestBroker
import org.json.JSONObject
import java.util.UUID

/**
 * Owns the single live WebView. Creation is deferred until the user deliberately
 * opens a URL. Renderer death destroys this instance; it is never reused.
 */
class WebViewHost(
    private val context: Context,
    private val container: FrameLayout,
    /** Full browse shell overlay host so pointer can reach the URL chrome. */
    private val cursorHost: ViewGroup,
    private val fullscreenHost: FrameLayout,
    private val onEvent: (NavigationEvent) -> Unit,
    private val onUrlChanged: (String?) -> Unit,
    private val onFullscreen: (Boolean) -> Unit,
    private val onPlaybackMode: (Boolean) -> Unit,
    private val onRendererGone: (JSONObject) -> Unit,
    private val onInputModeChanged: (String) -> Unit,
    private val onCheckpoint: (ContinuityCheckpoint) -> Unit,
    private val onPlaybackConfirmed: (PlaybackOrchestrator.PlaybackSnapshot) -> Unit = {},
    private val onPlaybackActive: (Boolean) -> Unit = {},
    private val onJourneyState: (PlaybackJourneyState) -> Unit = {},
    private val onProgress: (Int) -> Unit = {},
    /** Height of URL chrome in px (for pointer hit-test / web touch offset). */
    private val chromeHeightPx: () -> Int = { 0 },
    private val onUrlBarActivate: () -> Unit = {},
    /** Star's current bounds in shell coordinates, or null when it isn't showing —
     * lets RemoteInputRouter tell "pointer OK on the star" apart from the rest of
     * the chrome bar, which otherwise always opens the URL bar's keyboard. */
    private val starButtonRectPx: () -> android.graphics.RectF? = { null },
    private val onFavouriteActivate: () -> Unit = {},
    /** High-risk deliberate navigation: show Open host? (never silent drop). */
    private val onConfirmNavigation: ((url: String, host: String, reason: String) -> Unit)? = null,
    /** magnet: link activated in-page → start native torrent streaming. */
    private val onMagnetIntent: ((magnet: String) -> Unit)? = null,
    /** Site offered a .torrent download → fetch + stream natively (cookies for auth'd trackers). */
    private val onTorrentFileIntent: ((url: String, cookies: String?, userAgent: String?) -> Unit)? = null,
) {
    var webView: WebView? = null
        private set

    var chromeClient: KeenWebChromeClient? = null
        private set

    private var inputRouter: RemoteInputRouter? = null
    private var cursorOverlay: CursorOverlay? = null
    private var firewall: NavigationFirewall? = null
    private var playback: PlaybackOrchestrator? = null
    private var restorePositionSec: Double? = null
    private val popupQuarantine = PopupQuarantine()
    private var hostileSweepGeneration: Int = 0
    private val hostileHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lifecycleHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Last observed restore settlement path: "seek" | "natural" | null. */
    @Volatile
    var lastRestoreMethod: String? = null
        private set

    val isCreated: Boolean
        get() = webView != null

    val currentUrl: String?
        get() = webView?.url

    val journeyState: PlaybackJourneyState
        get() = playback?.journeyState ?: PlaybackJourneyState.BROWSING

    val isPlaybackMode: Boolean
        get() = playback?.isPlaybackMode == true

    fun setRestorePosition(positionSec: Double?) {
        restorePositionSec = positionSec
        lastRestoreMethod = null
    }

    fun beginRestore(checkpoint: ContinuityCheckpoint) {
        lastRestoreMethod = null
        playback?.beginRestore(checkpoint)
        setRestorePosition(checkpoint.playbackPositionSec)
    }

    fun noteRestoreMethod(method: String) {
        val m = method.lowercase()
        lastRestoreMethod = when {
            m.startsWith("seek") -> "seek"
            m.startsWith("natural") -> "natural"
            m == "player_api" || m == "direct" || m == "spa_state" -> m
            else -> m.take(32)
        }
    }

    /** Pull window.__keenRestoreMethod into lastRestoreMethod (logcat path independent). */
    fun refreshRestoreMethodFromPage(onDone: ((String?) -> Unit)? = null) {
        val wv = webView ?: run {
            onDone?.invoke(lastRestoreMethod)
            return
        }
        wv.evaluateJavascript(
            "(function(){return window.__keenRestoreMethod||null;})();",
        ) { raw ->
            val m = unwrapJs(raw)?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
            if (m != null) noteRestoreMethod(m)
            onDone?.invoke(lastRestoreMethod)
        }
    }

    fun exitPlaybackMode(reason: String) {
        playback?.exitPlaybackMode(reason)
    }

    /**
     * Keep pointer mode during HTML fullscreen / Keen playback surface so the user
     * can reach player controls (subtitles, quality, audio) without DOM focus.
     */
    fun setMediaPointerLock(locked: Boolean) {
        inputRouter?.setMediaPointerLock(locked)
    }

    fun onBackground(onFreshCheckpointComplete: () -> Unit = {}) {
        var completed = false
        val pauseWebView = {
            if (completed) {
                Unit
            } else {
                completed = true
                try {
                    webView?.onPause()
                } catch (_: Throwable) {
                }
                onFreshCheckpointComplete()
            }
        }
        val activePlayback = playback
        if (activePlayback != null) {
            val timeout = Runnable {
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "CONTINUITY_FRESH_SAMPLE_TIMEOUT",
                        url = currentUrl,
                    ),
                )
                activePlayback.onBackground()
                pauseWebView()
            }
            lifecycleHandler.postDelayed(timeout, BACKGROUND_SAMPLE_TIMEOUT_MS)
            activePlayback.checkpointFresh("app_background_fresh") {
                lifecycleHandler.removeCallbacks(timeout)
                pauseWebView()
            }
        } else {
            pauseWebView()
        }
        // Drop stuck D-pad holds / frame loop so resume starts clean.
        inputRouter?.resetPointerMotion()
    }

    fun trimMemory(level: Int) {
        if (!MemoryPressureDiagnostics.shouldReleaseRecreatableState(level)) return
        hostileSweepGeneration++
        hostileHandler.removeCallbacksAndMessages(null)
        inputRouter?.dropRecreatableState()
        try {
            // false releases WebView's in-memory resource/image cache without
            // discarding the disk cache needed for efficient restore.
            webView?.clearCache(false)
        } catch (_: Throwable) {
        }
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "MEMORY_TRIM_RELEASE",
                url = currentUrl,
                detail = "level=$level webViewMemoryCache=cleared interactionIndex=cleared hostileSweep=stopped",
            ),
        )
    }

    fun onForeground() {
        try {
            webView?.onResume()
            // Do not use pauseTimers()/resumeTimers() — process-global and freezes JS.
        } catch (_: Throwable) {
        }
        inputRouter?.onHostResumed(webView)
        webView?.let { armHostileOverlayGuard(it) }
    }

    fun checkpointBeforeDestroy() {
        playback?.checkpointNow(reason = "before_renderer_replace")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureCreated(): WebView {
        webView?.let { return it }

        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "webview_create_start",
            ),
        )

        val firewall = NavigationFirewall()
        this.firewall = firewall
        val assetLoader = LabAssetLoader(context)
        val cursor = CursorOverlay(context)
        val playback = PlaybackOrchestrator(
            context = context,
            onEvent = onEvent,
            onCheckpoint = onCheckpoint,
            onPlaybackConfirmed = onPlaybackConfirmed,
            onPlaybackMode = onPlaybackMode,
            onPlaybackActive = onPlaybackActive,
            onJourneyState = onJourneyState,
        )
        this.playback = playback

        val inputRouter = RemoteInputRouter(
            cursor = cursor,
            onUserInput = {
                firewall.recordUserInput()
                onEvent(NavigationEvent(System.currentTimeMillis(), "remote_input"))
            },
            onModeChanged = onInputModeChanged,
            onInputResponse = { kind, latencyMs ->
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "input_response",
                        detail = "kind=$kind latencyMs=$latencyMs",
                    ),
                )
            },
            onPlayLikeActivation = { fingerprintJson ->
                emitPlayIntent(fingerprintJson, firewall, playback)
            },
            onIndexStats = { stats ->
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "INTERACTION_INDEX",
                        detail = stats,
                    ),
                )
            },
            chromeHeightPx = chromeHeightPx,
            onUrlBarActivate = onUrlBarActivate,
            starButtonRectPx = starButtonRectPx,
            onFavouriteActivate = onFavouriteActivate,
            onDeliberateActivation = { fingerprintJson ->
                recordDeliberateActivation(fingerprintJson, firewall)
            },
            // HTML custom-view is a separate surface — pointer clicks/hovers must hit it.
            mediaTouchTarget = { chromeClient?.fullscreenCustomView },
            onHideKeyboard = {
                try {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    val token = webView?.windowToken ?: cursor.windowToken
                    imm?.hideSoftInputFromWindow(token, 0)
                    // Never clearFocus here — it tears down WebEditText InputConnection
                    // and cancels the keyboard (ImeTracker PHASE_WM_SHOW_IME_RUNNER cancelled).
                } catch (_: Throwable) {
                }
            },
            onShowKeyboard = {
                try {
                    val wv = webView
                    if (wv != null) {
                        // Do NOT clearFocus — that restarts InputConnection as inputType=NULL
                        // and cancels the TV IME mid-show (ImeTracker onCancelled).
                        wv.isFocusable = true
                        wv.isFocusableInTouchMode = true
                        if (!wv.hasFocus()) wv.requestFocus()
                        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as? android.view.inputmethod.InputMethodManager
                        wv.post {
                            try {
                                imm?.showSoftInput(
                                    wv,
                                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
                                )
                            } catch (_: Throwable) {
                            }
                        }
                        wv.postDelayed({
                            try {
                                if (wv.hasFocus()) {
                                    imm?.showSoftInput(
                                        wv,
                                        android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                                    )
                                }
                            } catch (_: Throwable) {
                            }
                        }, 200L)
                    }
                } catch (_: Throwable) {
                }
            },
        )
        this.inputRouter = inputRouter
        val wv = KeenWebView(context, inputRouter)
        HardenedWebSettings.apply(wv)
        // Accept cookies, including third-party. Third-party cookies default to OFF
        // on modern WebView, which breaks embedded sign-in/SSO iframes and the
        // handshake used by bot-challenge services (the clearance cookie is set from
        // a challenge iframe on a different origin). Tracker/ad requests are already
        // blocked upstream, so this restores legitimate functionality without opening
        // a tracking hole. Cookies are flushed to disk in flushSession().
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        BlockingRuntime.ensureServiceWorkerInterception()

        val windowBroker = WindowRequestBroker(popupQuarantine)
        val chrome = KeenWebChromeClient(
            fullscreenHost = fullscreenHost,
            onFullscreen = onFullscreen,
            onTitle = { /* optional */ },
            onConsole = { msg ->
                onEvent(
                    NavigationEvent(
                        t = System.currentTimeMillis(),
                        type = "console",
                        detail = msg.take(500),
                    ),
                )
                if (msg.contains("KZ_LATENCY_TELEMETRY:")) {
                    val jsonStr = msg.substringAfter("KZ_LATENCY_TELEMETRY:")
                    try {
                        val obj = org.json.JSONObject(jsonStr)
                        val t6 = System.currentTimeMillis()
                        obj.put("t6", t6)
                        onEvent(
                            NavigationEvent(
                                t = t6,
                                type = "INPUT_LATENCY_CORRELATED",
                                detail = obj.toString()
                            )
                        )
                    } catch (_: Exception) {}
                }
                if (msg.contains("KZ_BLOCK_WINDOW_OPEN") || msg.contains("KZ_QUARANTINE")) {
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "POPUP_QUARANTINED",
                            detail = msg.take(300),
                        ),
                    )
                }
                if (msg.contains("KZ_AUDIBLE_PLAYBACK")) {
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "AUDIBLE_PLAYBACK_SIGNAL",
                            detail = msg.take(200),
                        ),
                    )
                }
                if (msg.contains("KZ_RESTORE_SEEK:")) {
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "RESTORE_SEEK_APPLIED",
                            detail = msg.substringAfter("KZ_RESTORE_SEEK:").take(80),
                        ),
                    )
                }
                if (msg.contains("KZ_SEEK_PROBE_JSON:")) {
                    val raw = msg.substringAfter("KZ_SEEK_PROBE_JSON:").take(800)
                    com.keenzero.app.diagnostics.LabSignal.emit(
                        "seek_probe_result",
                        mapOf("raw" to raw),
                    )
                    try {
                        val o = org.json.JSONObject(raw)
                        com.keenzero.app.diagnostics.LabSignal.emitJson("seek_probe", o)
                    } catch (_: Exception) {
                    }
                }
                if (msg.contains("KZ_SEEK_PROBE:")) {
                    com.keenzero.app.diagnostics.LabSignal.emit(
                        "seek_probe_line",
                        mapOf("line" to msg.substringAfter("KZ_SEEK_PROBE:").take(200)),
                    )
                }
                if (msg.contains("KZ_RESTORE_SETTLED:")) {
                    val settled = msg.substringAfter("KZ_RESTORE_SETTLED:").take(80)
                    // Orchestrator emits "seek:<pos>" or "natural:<pos>:target:<t>"
                    when {
                        settled.startsWith("seek:") -> noteRestoreMethod("seek")
                        settled.startsWith("natural:") -> noteRestoreMethod("natural")
                        else -> noteRestoreMethod(settled.substringBefore(":").ifBlank { "unknown" })
                    }
                    val actual = settled.substringAfter(":").substringBefore(":").toDoubleOrNull()
                    com.keenzero.app.diagnostics.LabSignal.emit(
                        "restore_settled_console",
                        mapOf(
                            "method" to (lastRestoreMethod ?: "unknown"),
                            "actual" to actual,
                            "detail" to settled,
                        ),
                    )
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "RESTORE_SETTLED",
                            detail = settled.let { d ->
                                if (d.startsWith("seek:")) "method=seek actual=" + d.removePrefix("seek:")
                                else if (d.startsWith("natural:")) {
                                    val a = d.removePrefix("natural:").substringBefore(":")
                                    "method=natural actual=$a"
                                } else "method=? $d"
                            },
                        ),
                    )
                }
            },
            onEvent = onEvent,
            popupQuarantine = popupQuarantine,
            windowBroker = windowBroker,
            activationLedger = firewall.activationLedger,
            requestingOrigin = {
                webView?.url?.let { originOf(it) }
            },
            playIntentActive = { playback.isPlayIntentActive },
            playOrigin = { playback.activeSession?.origin },
            onApprovedMainLoad = { url ->
                webView?.loadUrl(url)
            },
            onRequireConfirmation = { url, host, reason ->
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "NAV_CONFIRM_PROMPT",
                        url = url,
                        detail = "host=$host reason=$reason",
                    ),
                )
                onConfirmNavigation?.invoke(url, host ?: "unknown", reason)
            },
            onProgress = onProgress,
        )
        chromeClient = chrome
        wv.webChromeClient = chrome
        wv.webViewClient = KeenWebViewClient(
            assetLoader = assetLoader,
            firewall = firewall,
            onEvent = onEvent,
            onUrlChanged = onUrlChanged,
            onRendererGone = { detail ->
                playback.beginRecovering()
                checkpointBeforeDestroy()
                destroy("renderer_gone")
                onRendererGone(detail)
                true
            },
            onMagnet = onMagnetIntent,
            onPageFinishedExtra = { view, url ->
                val pos = restorePositionSec
                if (pos != null && pos > 0) {
                    view.evaluateJavascript("window.__keenRestorePosition=$pos;", null)
                }
                // Kill QR / full-page interstitials immediately and keep sweeping.
                armHostileOverlayGuard(view)
                // Modal scroll controller (document-start may be unavailable on some devices).
                view.evaluateJavascript(com.keenzero.app.input.ModalScrollJs.INSTALL_JS, null)
                // Scroll authority: sites cannot yank viewport; Keen grants movement.
                view.evaluateJavascript(com.keenzero.app.input.ScrollAuthorityJs.INSTALL_JS, null)
                inputRouter?.onNavigationCommitted()
                playback.attach(view)
                playback.onPageSettled(view, url)
                inputRouter.markIndexDirty("page_finished")
                inputRouter.requestRebuild(view, force = true)
            },
        )
        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (isTorrentDownload(url, contentDisposition, mimetype)) {
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "TORRENT_DOWNLOAD_INTERCEPTED",
                        url = url,
                        detail = "mime=$mimetype disposition=${contentDisposition?.take(120)}",
                    ),
                )
                val cookies = try {
                    CookieManager.getInstance().getCookie(url)
                } catch (_: Throwable) {
                    null
                }
                onTorrentFileIntent?.invoke(url, cookies, userAgent)
            } else {
                onEvent(NavigationEvent(System.currentTimeMillis(), "BLOCK_DOWNLOAD", url = url))
            }
        })
        installDocumentStartProtection(wv)
        playback.attach(wv)

        container.removeAllViews()
        container.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Cursor on full-shell host so it can move into the URL bar region.
        try {
            (cursor.parent as? ViewGroup)?.removeView(cursor)
        } catch (_: Throwable) {
        }
        cursorHost.addView(
            cursor,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        cursorOverlay = cursor
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.requestFocus()
        webView = wv

        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "webview_create_done",
                detail = "userAgent=${wv.settings.userAgentString.take(160)}",
            ),
        )
        return wv
    }

    private fun isTorrentDownload(url: String?, contentDisposition: String?, mimetype: String?): Boolean {
        if (mimetype?.lowercase() == "application/x-bittorrent") return true
        val path = try {
            android.net.Uri.parse(url ?: return false).path?.lowercase()
        } catch (_: Throwable) {
            null
        }
        if (path?.endsWith(".torrent") == true) return true
        return contentDisposition?.lowercase()?.contains(".torrent") == true
    }

    private fun originOf(url: String): String? = try {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
    } catch (_: Exception) {
        null
    }

    private fun recordDeliberateActivation(
        fingerprintJson: String?,
        firewall: NavigationFirewall,
    ) {
        val o = try {
            if (fingerprintJson.isNullOrBlank()) null else JSONObject(fingerprintJson)
        } catch (_: Exception) {
            null
        }
        val href = o?.optString("href", "")?.takeIf { it.isNotBlank() && it != "null" }
        val role = o?.optString("role", "")?.takeIf { it.isNotBlank() }
        val fp = o?.optString("fp", "")?.takeIf { it.isNotBlank() }
        val type = when {
            o?.optBoolean("play", false) == true -> ActivationLedger.Type.PLAY
            o?.optBoolean("form", false) == true -> ActivationLedger.Type.FORM
            !href.isNullOrBlank() -> ActivationLedger.Type.LINK
            role?.equals("button", ignoreCase = true) == true -> ActivationLedger.Type.UNKNOWN
            else -> ActivationLedger.Type.UNKNOWN
        }
        firewall.activationLedger.record(
            type = type,
            sourceOrigin = webView?.url?.let { originOf(it) },
            expectedHref = href,
            elementRole = role,
            fingerprint = fp,
        )
        firewall.recordUserInput()
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "ACTIVATION_GRANT",
                url = webView?.url,
                detail = "type=$type href=${href?.take(120)} role=$role",
            ),
        )
    }

    private fun emitPlayIntent(
        fingerprintJson: String?,
        firewall: NavigationFirewall,
        playback: PlaybackOrchestrator,
    ) {
        val o = try {
            if (fingerprintJson.isNullOrBlank()) null else JSONObject(fingerprintJson)
        } catch (_: Exception) {
            null
        }
        if (o?.optBoolean("fake", false) == true) {
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "PLAY_INTENT_REJECTED",
                    url = webView?.url,
                    detail = "fake_control ${o.optString("text")}",
                ),
            )
            return
        }
        if (o?.optBoolean("play", false) != true) return

        val url = webView?.url
        val origin = url?.let { originOf(it) }
        fun jsStr(key: String): String? =
            o.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        val intent = PlayIntent(
            id = UUID.randomUUID().toString(),
            origin = origin,
            url = url,
            focusedFingerprint = jsStr("fp"),
            role = jsStr("role"),
            visibleText = jsStr("text"),
            expectedHref = jsStr("href"),
            geometry = jsStr("geometry"),
            contentId = jsStr("contentId"),
            timestampElapsedMs = SystemClock.elapsedRealtime(),
        )
        firewall.recordPlayIntent(intent)
        webView?.evaluateJavascript(
            "window.__keenNativeIntent=Date.now();window.__keenPlayIntentId='${intent.id}';",
            null,
        )
        playback.onPlayIntent(intent)
    }

    fun load(url: String) {
        ensureCreated().loadUrl(url)
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    /**
     * SPA sites often use history.pushState without a native WebView back entry
     * WebView reports. Always try JS history.back() when native canGoBack is false.
     */
    fun historyBack() {
        val wv = webView ?: return
        if (wv.canGoBack()) {
            wv.goBack()
        } else {
            wv.evaluateJavascript(
                """(function(){try{if(history.length>1){history.back();return true;}return false;}catch(e){return false;}})();""",
                null,
            )
        }
    }

    fun handleRemoteKey(event: KeyEvent): Boolean =
        webView?.let { inputRouter?.handle(it, event) } == true

    /**
     * Debug/lab only: deterministic vertical-slice Play without D-pad flakiness.
     * Records a formal PlayIntent, then activates the fixture Play control.
     */
    fun labDrivePlay(contentId: String = "ep-a2", onDone: ((Boolean) -> Unit)? = null) {
        val wv = webView ?: run {
            onDone?.invoke(false)
            return
        }
        val fw = firewall
        val pb = playback
        if (fw == null || pb == null) {
            onDone?.invoke(false)
            return
        }
        val selectJs = """
            (function(){
              var card=document.querySelector('[data-content-id="$contentId"]');
              if(card){ card.click(); }
              var play=document.getElementById('real-play');
              if(!play) return JSON.stringify({ok:false,reason:'no-play'});
              play.focus();
              var r=play.getBoundingClientRect();
              return JSON.stringify({
                ok:true, play:true, fake:false, role:'button', text:'Play',
                id:'real-play', href:'', fp:'BUTTON#real-play',
                geometry:Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+'x'+Math.round(r.height),
                contentId:window.__keenContentId||'$contentId'
              });
            })();
        """.trimIndent()
        wv.evaluateJavascript(selectJs) { raw ->
            val unwrapped = unwrapJs(raw)
            emitPlayIntent(unwrapped, fw, pb)
            wv.evaluateJavascript(
                """(function(){
                  var play=document.getElementById('real-play');
                  if(!play) return false;
                  window.__keenNativeIntent=Date.now();
                  play.click();
                  return true;
                })();""",
            ) { clicked ->
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "LAB_DRIVE_PLAY",
                        url = wv.url,
                        detail = "contentId=$contentId clicked=$clicked fingerprint=$unwrapped",
                    ),
                )
                onDone?.invoke(clicked != "false" && clicked != "null")
            }
        }
    }

    /** Debug/lab: seek active video and force a checkpoint sample soon after. */
    fun labSeek(positionSec: Double, onDone: ((Boolean) -> Unit)? = null) {
        labSeekInternal(positionSec, pauseAfter = false, resumeAfter = false, onDone = onDone)
    }

    /**
     * Seek then pause so a durable mid-play checkpoint cannot race to natural end.
     */
    fun labSeekAndPause(positionSec: Double, onDone: ((Boolean) -> Unit)? = null) {
        labSeekInternal(positionSec, pauseAfter = true, resumeAfter = false, onDone = onDone)
    }

    /**
     * Seek then resume advancing playback. Used for multi-position continuity proof
     * where the last durable checkpoint must come from the normal checkpoint path
     * while media is still advancing (not a cooperative pause+force-save).
     */
    fun labSeekAndPlay(positionSec: Double, onDone: ((Boolean) -> Unit)? = null) {
        labSeekInternal(positionSec, pauseAfter = false, resumeAfter = true, onDone = onDone)
    }

    private fun labSeekInternal(
        positionSec: Double,
        pauseAfter: Boolean,
        resumeAfter: Boolean,
        onDone: ((Boolean) -> Unit)?,
    ) {
        val wv = webView ?: run {
            onDone?.invoke(false)
            return
        }
        val afterJs = when {
            pauseAfter -> "try{v.pause();}catch(e){}"
            resumeAfter -> "try{v.muted=false;v.volume=1;v.playbackRate=1;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}"
            else -> ""
        }
        // Seek must wait for the media 'seeked' event or currentTime stays near 0/playhead.
        wv.evaluateJavascript(
            """(function(){
              var v=document.querySelector('video');
              if(!v) return JSON.stringify({ok:false,reason:'no-video'});
              window.__keenLabSeekDone=false;
              window.__keenLabSeekActual=-1;
              try{
                v.loop=true;
                var target=$positionSec;
                window.__keenLabSeekTarget=target;
                var afterSeek=function(){
                  try{
                    window.__keenLabSeekActual=v.currentTime||0;
                    window.__keen.lastT=v.currentTime;
                    window.__keenLabSeekDone=true;
                    $afterJs
                    console.warn('KZ_LAB_SEEK:'+v.currentTime+':target:'+target+':dur:'+(v.duration||0)+':paused:'+v.paused);
                  }catch(e){ window.__keenLabSeekDone=true; console.warn('KZ_LAB_SEEK_ERR:'+e); }
                };
                var tries=0;
                var doSeek=function(){
                  tries++;
                  try{
                    // Goldfish/WebView often ignores currentTime while playing — pause first.
                    try{ v.pause(); }catch(e){}
                    if(v.seekable && v.seekable.length>0){
                      var end=v.seekable.end(v.seekable.length-1);
                      if(target>end && end>0) target=Math.max(0,end-0.25);
                    }
                    var onSeeked=function(){
                      setTimeout(function(){
                        var a=v.currentTime||0;
                        console.warn('KZ_LAB_SEEK_CHECK:'+a+':target:'+target+':try:'+tries);
                        if(Math.abs(a-target)>0.75 && tries<8){
                          doSeek();
                        } else {
                          afterSeek();
                        }
                      }, 50);
                    };
                    v.addEventListener('seeked', onSeeked, {once:true});
                    // Do NOT write window.__keenRestorePosition here — that arms the
                    // continuity restore injector on the next page_settled and races setup.
                    window.__keenLabSeekTarget=target;
                    v.currentTime=target;
                    // Fallback if seeked is silent
                    setTimeout(function(){
                      if(!window.__keenLabSeekDone){
                        var a=v.currentTime||0;
                        if(Math.abs(a-target)>0.75 && tries<8) doSeek();
                        else onSeeked();
                      }
                    }, 600);
                  }catch(e){ afterSeek(); }
                };
                if(v.readyState>=1) doSeek();
                else {
                  v.addEventListener('loadedmetadata', doSeek, {once:true});
                  v.addEventListener('canplay', doSeek, {once:true});
                }
                return JSON.stringify({ok:true,readyState:v.readyState,dur:v.duration||0,target:target});
              }catch(e){ return JSON.stringify({ok:false,err:String(e)}); }
            })();""",
        ) { raw ->
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    when {
                        pauseAfter -> "LAB_SEEK_PAUSE"
                        resumeAfter -> "LAB_SEEK_PLAY"
                        else -> "LAB_SEEK"
                    },
                    url = wv.url,
                    detail = "target=$positionSec result=$raw",
                ),
            )
            fun pollSeekDone(left: Int) {
                wv.evaluateJavascript(
                    "(function(){return JSON.stringify({done:!!window.__keenLabSeekDone,t:window.__keenLabSeekActual});})();",
                ) { status ->
                    val unwrapped = unwrapJs(status) ?: status
                    val done = unwrapped?.contains("\"done\":true") == true ||
                        unwrapped?.contains("\"done\": true") == true
                    val actual = try {
                        val tMatch = Regex(""""t"\s*:\s*([0-9.]+)""").find(unwrapped ?: "")
                        tMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    } catch (_: Exception) {
                        null
                    }
                    if (done || left <= 0) {
                        val seekOk = actual != null && kotlin.math.abs(actual - positionSec) <= 0.75
                        if (seekOk) {
                            noteRestoreMethod("seek")
                        }
                        if (!resumeAfter) {
                            playback?.checkpointNow(reason = if (pauseAfter) "lab_seek_pause" else "lab_seek")
                        } else {
                            playback?.checkpointNow(reason = "lab_seek_play_sample")
                        }
                        onEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "LAB_SEEK_RESULT",
                                url = wv.url,
                                detail = "target=$positionSec actual=$actual seekOk=$seekOk",
                            ),
                        )
                        onDone?.invoke(seekOk)
                    } else {
                        wv.postDelayed({ pollSeekDone(left - 1) }, 200)
                    }
                }
            }
            wv.postDelayed({ pollSeekDone(20) }, 300)
        }
    }

    /** Lab: ensure video is playing (advancing). */
    fun labEnsurePlaying(onDone: ((Boolean) -> Unit)? = null) {
        val wv = webView ?: run {
            onDone?.invoke(false)
            return
        }
        wv.evaluateJavascript(
            """(function(){
              var v=document.querySelector('video');
              if(!v) return false;
              try{
                v.loop=true;
                v.muted=false;
                v.volume=1;
                v.playbackRate=1;
                if(v.ended){ v.currentTime=Math.max(0,(v.currentTime||0)-0.05); }
                var p=v.play();
                if(p&&p.catch) p.catch(function(){});
                return !v.paused;
              }catch(e){ return false; }
            })();""",
        ) { raw -> onDone?.invoke(raw == "true") }
    }

    /**
     * Lab: prove time is advancing by waiting on the media clock inside the page.
     * Returns JSON {t0,t1,delta,playing}.
     */
    fun labProveAdvance(waitMs: Long = 5_200L, callback: (Double?, Double?, Boolean) -> Unit) {
        val wv = webView ?: run {
            callback(null, null, false)
            return
        }
        wv.evaluateJavascript(
            """(function(){
              var v=document.querySelector('video');
              if(!v) return JSON.stringify({ok:false});
              try{
                v.loop=true; v.muted=false; v.volume=1; v.playbackRate=1;
                if(v.paused){ var p=v.play(); if(p&&p.catch) p.catch(function(){}); }
                var t0=v.currentTime||0;
                return JSON.stringify({ok:true,t0:t0});
              }catch(e){ return JSON.stringify({ok:false,err:String(e)}); }
            })();""",
        ) { raw0 ->
            val t0 = try {
                val s = unwrapJs(raw0) ?: raw0
                JSONObject(s ?: "{}").optDouble("t0", Double.NaN)
            } catch (_: Exception) {
                Double.NaN
            }
            wv.postDelayed({
                wv.evaluateJavascript(
                    """(function(){
                      var v=document.querySelector('video');
                      if(!v) return JSON.stringify({ok:false});
                      try{
                        if(v.paused){ v.play(); }
                        var t1=v.currentTime||0;
                        return JSON.stringify({ok:true,t1:t1,playing:!v.paused&&!v.ended,loop:!!v.loop});
                      }catch(e){ return JSON.stringify({ok:false}); }
                    })();""",
                ) { raw1 ->
                    try {
                        val s = unwrapJs(raw1) ?: raw1
                        val o = JSONObject(s ?: "{}")
                        val t1 = o.optDouble("t1", Double.NaN)
                        callback(
                            t0.takeIf { !it.isNaN() },
                            t1.takeIf { !it.isNaN() },
                            o.optBoolean("playing", false),
                        )
                    } catch (_: Exception) {
                        callback(null, null, false)
                    }
                }
            }, waitMs)
        }
    }

    /** Lab: request orchestrator checkpoint sample (uses normal onCheckpoint → ContinuityStore). */
    fun labForceCheckpointSample() {
        playback?.checkpointNow(reason = "lab_durable_sample")
    }

    /**
     * Lab: let media advance naturally until currentTime >= [targetSec], then pause and checkpoint.
     * Used when WebView seek is unreliable (goldfish H.264 often ignores currentTime).
     */
    fun labWaitAdvanceTo(
        targetSec: Double,
        timeoutMs: Long = 25_000L,
        onDone: ((Boolean, Double?) -> Unit)? = null,
    ) {
        val wv = webView ?: run {
            onDone?.invoke(false, null)
            return
        }
        val tStart = android.os.SystemClock.elapsedRealtime()
        fun tick() {
            wv.evaluateJavascript(
                """(function(){
                  var v=document.querySelector('video');
                  if(!v) return JSON.stringify({ok:false});
                  try{
                    v.loop=true; v.muted=false; v.volume=1;
                    if(v.paused){ var p=v.play(); if(p&&p.catch)p.catch(function(){}); }
                    return JSON.stringify({ok:true,t:v.currentTime||0,playing:!v.paused,dur:v.duration||0});
                  }catch(e){ return JSON.stringify({ok:false}); }
                })();""",
            ) { raw ->
                val t = try {
                    val s = unwrapJs(raw) ?: raw
                    org.json.JSONObject(s ?: "{}").optDouble("t", Double.NaN)
                } catch (_: Exception) {
                    Double.NaN
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - tStart
                if (!t.isNaN() && t + 0.05 >= targetSec) {
                    // Keep playing so advancedBeforeTermination can be proven honestly.
                    // (Previously paused here, which forced advancedBefore=false on every cycle.)
                    playback?.checkpointNow(reason = "lab_natural_position")
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "LAB_NATURAL_POSITION",
                            url = wv.url,
                            detail = "target=$targetSec actual=$t elapsedMs=$elapsed playing=keep",
                        ),
                    )
                    onDone?.invoke(true, t)
                } else if (elapsed > timeoutMs) {
                    onDone?.invoke(false, t.takeIf { !it.isNaN() })
                } else {
                    wv.postDelayed({ tick() }, 250)
                }
            }
        }
        labEnsurePlaying { tick() }
    }

    /** Sample current video position for restoration metrics. */
    fun labSamplePosition(callback: (Double?, Boolean, Boolean) -> Unit) {
        val wv = webView ?: run {
            callback(null, false, false)
            return
        }
        wv.evaluateJavascript(
            """(function(){
              var v=document.querySelector('video');
              if(!v) return JSON.stringify({t:null,playing:false,audible:false});
              var audible=!v.muted && v.volume>0 && !v.paused && v.currentTime>0.05;
              return JSON.stringify({t:v.currentTime||0,playing:!v.paused&&!v.ended,audible:audible,muted:!!v.muted});
            })();""",
        ) { raw ->
            try {
                val json = unwrapJs(raw) ?: run {
                    callback(null, false, false)
                    return@evaluateJavascript
                }
                val o = JSONObject(json)
                val t = if (o.isNull("t")) null else o.optDouble("t", Double.NaN)
                callback(
                    t?.takeIf { !it.isNaN() },
                    o.optBoolean("playing", false),
                    o.optBoolean("audible", false),
                )
            } catch (_: Exception) {
                callback(null, false, false)
            }
        }
    }

    /** Force index rebuild and return stats JSON asynchronously via events. */
    fun labRebuildIndex() {
        val wv = webView ?: return
        inputRouter?.requestRebuild(wv, force = true)
    }

    /**
     * Debug/lab only: enumerate interactive candidates + focused element for remote harness.
     * Observation only — does not click, focus, or navigate on behalf of the remote.
     */
    fun labDumpRemoteSnapshot(onDone: (org.json.JSONObject) -> Unit) {
        val wv = webView
        if (wv == null) {
            onDone(
                org.json.JSONObject()
                    .put("ok", false)
                    .put("reason", "no_webview")
                    .put("url", currentUrl)
                    .put("t", System.currentTimeMillis()),
            )
            return
        }
        inputRouter?.requestRebuild(wv, force = true)
        wv.evaluateJavascript(REMOTE_DUMP_JS) { raw ->
            val unwrapped = unwrapJs(raw)
            val root = try {
                org.json.JSONObject(unwrapped ?: "{}")
            } catch (_: Exception) {
                org.json.JSONObject().put("parseError", true).put("raw", unwrapped)
            }
            root.put("ok", true)
            root.put("url", currentUrl ?: wv.url)
            root.put("t", System.currentTimeMillis())
            val router = inputRouter
            if (router != null) {
                val snap = router.labCursorSnapshot()
                root.put("inputMode", snap["mode"])
                root.put("cursorX", snap["cursorX"])
                root.put("cursorY", snap["cursorY"])
                root.put("indexSize", snap["indexSize"])
                root.put("indexFocusedId", snap["focusedId"])
            } else {
                root.put("inputMode", "NONE")
            }
            // Pointer-mode focus: element under the native cursor (no harness click).
            if (router?.labModeName() == "POINTER") {
                val cx = router.labCursorSnapshot()["cursorX"]
                val cy = router.labCursorSnapshot()["cursorY"]
                wv.evaluateJavascript(
                    """(function(){
                      var x=${cx}, y=${cy};
                      var el=document.elementFromPoint(x,y);
                      if(!el) return null;
                      var r=el.getBoundingClientRect();
                      var href=el.href||el.getAttribute('href')||'';
                      return JSON.stringify({
                        candidateId: (el.dataset&&el.dataset.keenIdx)||el.id||null,
                        tag: el.tagName,
                        text: (el.innerText||el.value||'').trim().slice(0,80),
                        href: href,
                        boundsCssPx:{left:r.left,top:r.top,right:r.right,bottom:r.bottom},
                        pointer:true, x:x, y:y
                      });
                    })();""",
                ) { ptrRaw ->
                    val p = unwrapJs(ptrRaw)
                    if (!p.isNullOrBlank() && p != "null") {
                        try {
                            root.put("pointerElement", org.json.JSONObject(p))
                            if (root.isNull("focused") || root.opt("focused") == org.json.JSONObject.NULL) {
                                root.put("focused", org.json.JSONObject(p))
                            }
                        } catch (_: Exception) {
                        }
                    }
                    onDone(root)
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "REMOTE_DUMP",
                            url = currentUrl ?: wv.url,
                            detail = "candidates=${root.optJSONArray("candidates")?.length() ?: 0} mode=${root.optString("inputMode")}",
                        ),
                    )
                }
                return@evaluateJavascript
            }
            onDone(root)
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "REMOTE_DUMP",
                    url = currentUrl ?: wv.url,
                    detail = "candidates=${root.optJSONArray("candidates")?.length() ?: 0}",
                ),
            )
        }
    }

    fun flushSession() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
    }

    private fun unwrapJs(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            try {
                JSONObject("{\"v\":$raw}").getString("v")
            } catch (_: Exception) {
                raw.trim('"')
            }
        } else {
            raw
        }
    }

    fun destroy(reason: String) {
        val wv = webView ?: return
        val chrome = chromeClient
        val cursor = cursorOverlay
        firewall?.clearActivation()
        firewall?.clearPlayIntent()
        BlockingRuntime.clearPageHost()
        if (reason != "renderer_gone") {
            playback?.checkpointNow(reason = "webview_destroy:$reason")
        }
        playback?.destroy()
        playback = null
        webView = null
        chromeClient = null
        inputRouter = null
        cursorOverlay = null
        firewall = null
        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "webview_destroy",
                url = try {
                    wv.url
                } catch (_: Throwable) {
                    null
                },
                detail = reason,
            ),
        )
        try {
            chrome?.exitFullscreenIfNeeded()
        } catch (_: Throwable) {
        }
        try {
            flushSession()
            container.removeView(wv)
            cursor?.let { c ->
                try {
                    (c.parent as? ViewGroup)?.removeView(c)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        try {
            wv.stopLoading()
            wv.webChromeClient = null
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun installDocumentStartProtection(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        // window.open quarantine + hostile QR/interstitial guard (coreflix-class popups).
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            // NEVER return null from window.open — SPA movie sites treat null as "popup blocked"
            // and scroll-home / abort content navigation (cineby-class). Ads get a dead stub;
            // same-origin / content paths navigate same-tab. HostileOverlayGuard overwrites with
            // the full policy; this early patch is the fail-safe if that install is skipped.
            """(function(){
              function keenEarlyStub(){
                var w={closed:false,opener:null,name:''};
                w.close=function(){ w.closed=true; };
                w.focus=function(){}; w.blur=function(){}; w.postMessage=function(){};
                w.location={href:'about:blank',assign:function(){},replace:function(){},reload:function(){}};
                w.document={write:function(){},writeln:function(){},close:function(){},open:function(){return this;}};
                return w;
              }
              function isAdHost(url){
                try{
                  var h=new URL(url,location.href).hostname;
                  return /(doubleclick\.net|googlesyndication|adservice\.google|ads\.example|pop\.example|evil\.example|tracker\.example|adnxs\.com|popads|propellerads|exoclick|juicyads)/i.test(h);
                }catch(e){ return true; }
              }
              function isContentPath(u){
                return /\/movie\/|\/tv\/|\/show\/|\/title\/|\/watch\/|\/play\/|\/v\/|\/embed\/|\/film\/|\/series\//i.test(u||'');
              }
              window.open=function(url){
                try{
                  var href=typeof url==='string'?url:String(url||'');
                  console.warn('KZ_WINDOW_OPEN_NATIVE_PATH '+href.slice(0,120));
                  if(!href || href==='about:blank' || href.indexOf('javascript:')===0){
                    return keenEarlyStub();
                  }
                  var a=document.createElement('a'); a.href=href;
                  var host=(a.hostname||'').toLowerCase();
                  var same=!host||host===location.hostname;
                  if(isAdHost(a.href)){
                    console.warn('KZ_QUARANTINE:ad '+a.href.slice(0,120));
                    return keenEarlyStub();
                  }
                  if(same || isContentPath(a.pathname)||isContentPath(a.href)){
                    try{ location.assign(a.href); }catch(e){}
                    return keenEarlyStub();
                  }
                  // Cross-origin non-ad: stub only (no second WebView on TV).
                  console.warn('KZ_BLOCK_WINDOW_OPEN '+a.href.slice(0,120));
                  return keenEarlyStub();
                }catch(e){ return keenEarlyStub(); }
              };
              document.addEventListener('click',function(event){
                const link=event.target && event.target.closest && event.target.closest('a[target="_blank"]');
                if(link){
                  try{
                    if(isAdHost(link.href)){ event.preventDefault(); console.warn('KZ_QUARANTINE:blank-ad'); return; }
                    link.target='_self';
                  }catch(e){}
                }
              },true);
            })();""" + "\n" + HostileOverlayGuard.DOCUMENT_START_JS +
                "\n" + com.keenzero.app.input.ModalScrollJs.INSTALL_JS +
                "\n" + com.keenzero.app.input.ScrollAuthorityJs.INSTALL_JS,
            setOf("*"),
        )
    }

    /**
     * After navigation: install once, then cheap sweeps only.
     * Never re-inject the full DOCUMENT_START bundle on a timer — that reflowed
     * the page ~5–6s after load and fought user scroll (push-up sensation).
     */
    private fun armHostileOverlayGuard(view: WebView) {
        // Full install once per navigation (idempotent JS if already present).
        view.evaluateJavascript(HostileOverlayGuard.DOCUMENT_START_JS, null)
        val gen = ++hostileSweepGeneration
        var ticks = 0
        val tick = object : Runnable {
            override fun run() {
                if (gen != hostileSweepGeneration) return
                if (webView !== view) return
                // Sweep only — do not re-arm / re-inject full script.
                view.evaluateJavascript(HostileOverlayGuard.SWEEP_JS, null)
                ticks++
                if (ticks < 25) { // 25 * 1200ms ≈ 30s late-QR window
                    hostileHandler.postDelayed(this, 1200L)
                }
            }
        }
        hostileHandler.postDelayed(tick, 400L)
    }

    private companion object {
        private const val BACKGROUND_SAMPLE_TIMEOUT_MS = 750L

        /**
         * Observation-only candidate dump for remote harness.
         * Does not click, focus, or navigate.
         */
        val REMOTE_DUMP_JS = """
            (function(){
              var CAP=256;
              var sel='${com.keenzero.app.input.InteractionIndex.CANDIDATE_SELECTOR}';
              var nodes=document.querySelectorAll(sel);
              var seen=Object.create(null);
              var items=[];
              var vw=window.innerWidth||0, vh=window.innerHeight||0;
              var order=0;
              function pushEl(e,i){
                if(!e) return;
                var disabled=!!e.disabled || e.getAttribute('aria-disabled')==='true';
                if(disabled) return;
                var r=e.getBoundingClientRect();
                var s=getComputedStyle(e);
                if(r.width<=2||r.height<=2||s.visibility==='hidden'||s.display==='none'||s.opacity==='0') return;
                var id=e.dataset.keenIdx || e.id || (e.tagName+'-'+i+'-'+Math.round(r.left)+'-'+Math.round(r.top));
                if(!e.dataset.keenIdx) e.dataset.keenIdx=id;
                id=e.dataset.keenIdx;
                if(seen[id]) return;
                seen[id]=1;
                // Observation only — do not stamp tabindex (ghost focus rings on nav).
                var href=e.href || e.getAttribute('href') || e.getAttribute('data-href') || e.getAttribute('data-link') || '';
                var origin='';
                try{ if(href) origin=new URL(href, location.href).origin; }catch(ex){}
                var text=(e.innerText||e.getAttribute('aria-label')||e.getAttribute('title')||e.value||'').trim().replace(/\s+/g,' ').slice(0,80);
                var vis=r.bottom>0&&r.top<vh&&r.right>0&&r.left<vw;
                items.push({
                  candidateId:id,
                  tag:e.tagName,
                  role:e.getAttribute('role')||e.tagName,
                  text:text,
                  href:href,
                  origin:origin,
                  boundsCssPx:{left:r.left,top:r.top,right:r.right,bottom:r.bottom},
                  width:r.width,height:r.height,
                  cx:r.left+r.width/2, cy:r.top+r.height/2,
                  viewportVisible:!!vis,
                  occluded:false,
                  disabled:disabled,
                  tabIndex:e.tabIndex,
                  frameDepth:0,
                  documentOrder:order++,
                  targetBlank: (e.tagName==='A' && e.target==='_blank')
                });
              }
              for(var i=0;i<nodes.length && items.length<CAP;i++){ pushEl(nodes[i],i); }
              if(items.length<CAP){
                var extras=document.querySelectorAll('div,li,span,article,section,img');
                for(var j=0;j<extras.length && items.length<CAP;j++){
                  var el=extras[j];
                  var st=getComputedStyle(el);
                  if(st.cursor!=='pointer') continue;
                  if(el.closest('a,button,input,select,textarea,[role=button],[role=link]')) continue;
                  var rr=el.getBoundingClientRect();
                  if(rr.width<24||rr.height<24||rr.width*rr.height>400000) continue;
                  pushEl(el,10000+j);
                }
              }
              var ae=document.activeElement;
              var focused=null;
              if(ae && ae!==document.body && ae!==document.documentElement){
                var fr=ae.getBoundingClientRect();
                var fh=ae.href||ae.getAttribute('href')||'';
                var fo='';
                try{ if(fh) fo=new URL(fh, location.href).origin; }catch(ex){}
                focused={
                  candidateId: (ae.dataset&&ae.dataset.keenIdx)||ae.id||null,
                  tag:ae.tagName,
                  role:ae.getAttribute('role')||ae.tagName,
                  text:(ae.innerText||ae.value||'').trim().replace(/\s+/g,' ').slice(0,80),
                  href:fh,
                  origin:fo,
                  boundsCssPx:{left:fr.left,top:fr.top,right:fr.right,bottom:fr.bottom}
                };
              }
              return JSON.stringify({
                pageUrl: location.href,
                pageTitle: document.title||'',
                readyState: document.readyState,
                hash: location.hash||'',
                search: location.search||'',
                candidates: items,
                focused: focused,
                totalNodes: document.getElementsByTagName('*').length,
                bodyTextSample: (document.body&&document.body.innerText||'').replace(/\s+/g,' ').slice(0,160)
              });
            })();
        """.trimIndent()
    }
}
