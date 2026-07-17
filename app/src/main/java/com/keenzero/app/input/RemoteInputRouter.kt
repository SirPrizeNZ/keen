package com.keenzero.app.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import com.keenzero.app.BuildConfig
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Remote navigation for D-pad remotes.
 *
 * Modes are mutually exclusive:
 * - POINTER: D-pad moves the native cursor; edge zones scroll the page; no DOM focus.
 * - DOM_FOCUS: D-pad selects candidates / falls back to viewport scroll; pointer hidden.
 *
 * Pointer mode uses **continuous frame-timed motion** while a direction is held
 * (not discrete ~50px key-repeat jumps). Default mode is POINTER.
 */
class RemoteInputRouter(
    private val cursor: CursorOverlay,
    private val onUserInput: () -> Unit,
    private val onModeChanged: (String) -> Unit,
    private val onInputResponse: (kind: String, latencyMs: Long) -> Unit,
    private val onPlayLikeActivation: ((fingerprintJson: String?) -> Unit)? = null,
    private val onIndexStats: ((statsJson: String) -> Unit)? = null,
    private val chromeHeightPx: () -> Int = { 0 },
    private val onUrlBarActivate: () -> Unit = {},
    /** Record single-use activation before synthetic click / DOM activate. */
    private val onDeliberateActivation: ((fingerprintJson: String?) -> Unit)? = null,
    /** HTML custom-view surface while fullscreen (pointer click/hover target). */
    private val mediaTouchTarget: () -> View? = { null },
    /** Hide soft keyboard (search Enter / commit). */
    private val onHideKeyboard: () -> Unit = {},
    /** Show soft keyboard after pointer OK on an in-page search/text field. */
    private val onShowKeyboard: () -> Unit = {},
) {
    // Product default: pointer first.
    private var mode = Mode.POINTER
    /**
     * While video is fullscreen / Keen playback surface is active, keep POINTER
     * so the user can hit player chrome (subs, quality, audio) without DOM focus.
     */
    private var mediaPointerLock = false
    private var centreHeld = false
    private var modeToggledByLongPress = false
    val interactionIndex = InteractionIndex()
    private var indexDirty = true
    private var rebuildPending = false
    private var lastRebuildRequestAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // Continuous pointer velocity (screen px / second), integrated every vsync.
    private var velX = 0f
    private var velY = 0f
    private var holdLeft = false
    private var holdRight = false
    private var holdUp = false
    private var holdDown = false
    private var holdStartedAt = 0L
    private var lastFrameNs = 0L
    private var frameCallbackRegistered = false
    private var boundWebView: WebView? = null
    private val density: Float
        get() = cursor.resources.displayMetrics.density

    // Long-lived touch drag for **main-page edge scroll only** (never modal lists).
    private var pageDragActive = false
    private var pageDragDownTime = 0L
    private var pageDragX = 0f
    private var pageDragFingerY = 0f
    private var pageDragWebView: WebView? = null

    // Horizontal rail drag (nested carousels under pointer — cineby/bcine rows).
    private var hDragActive = false
    private var hDragDownTime = 0L
    private var hDragX = 0f
    private var hDragY = 0f
    private var hDragWebView: WebView? = null

    /** Direct DOM modal ownership via window.__keenModalScroll (not coordinates). */
    private var modalOwner = ModalOwner.NONE
    private var lastImeSubmitAt = 0L
    private var lastModalLogAt = 0L
    private var bindAttemptToken = 0
    /** Generation for modal steal/claim; stale callbacks must not overwrite. */
    private var claimGen = 0
    private var modalVerticalHeld = false
    /** Key was released while a blocking claim was still in-flight. */
    private var pendingStopAfterClaim = false
    private var maxHoldFailsafe: Runnable? = null
    private var lastImeUrl: String? = null
    private var loggedFirstMoveGen = -1
    private var keyboardLikelyVisible = false
    /**
     * After IME / successful bind, prefer modal steal on vertical presses for a short window.
     * Never blocks ordinary page pointer motion while NONE.
     */
    private var modalLikelyUntil = 0L
    /** Throttle background modal probes so every D-pad tick does not spam JS. */
    private var lastOpportunisticClaimAt = 0L
    private var lastRectRefreshAt = 0L
    /** Cached results-list rect in WebView CSS pixels (below chrome). */
    private var modalListLeft = 0f
    private var modalListTop = 0f
    private var modalListRight = 0f
    private var modalListBottom = 0f
    private var modalListRectValid = false
    /** True while Up/Down is driving in-page list scroll (pointer Y frozen). */
    private var modalListScrolling = false
    /** Delayed switch from pointer-aim → list-scroll on a held vertical key. */
    private var modalHoldScrollRunnable: Runnable? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // While list-scrolling, vertical is owned by in-page rAF — skip pointer frame
            // unless left/right is also held.
            if (modalListScrolling && !holdLeft && !holdRight && !holdUp && !holdDown) {
                // List rAF only; no pointer axes held.
                frameCallbackRegistered = false
                lastFrameNs = 0L
                return
            }
            if (modalListScrolling && !holdLeft && !holdRight) {
                // Scrolling list; vertical holds cleared — do not tick pointer.
                frameCallbackRegistered = false
                lastFrameNs = 0L
                return
            }
            if (mode != Mode.POINTER || !hasDirectionHeld()) {
                endPageDrag()
                frameCallbackRegistered = false
                lastFrameNs = 0L
                return
            }
            val wv = boundWebView
            if (wv == null) {
                endPageDrag()
                frameCallbackRegistered = false
                return
            }
            val dt = if (lastFrameNs == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            }
            lastFrameNs = frameTimeNanos
            tickPointer(wv, dt)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // Cursor visible from the start in default pointer mode.
        cursor.showAtCentre()
        onModeChanged("Pointer")
    }

    fun markIndexDirty(reason: String = "event") {
        indexDirty = true
    }

    /** Navigation committed — drop modal ownership (target is gone). */
    fun onNavigationCommitted() {
        clearModalOwnership(boundWebView)
    }

    fun labModeName(): String = mode.name

    fun labCursorSnapshot(): Map<String, Any?> = mapOf(
        "mode" to mode.name,
        "cursorX" to cursor.cursorX,
        "cursorY" to cursor.cursorY,
        "indexSize" to interactionIndex.size,
        "focusedId" to interactionIndex.focused,
    )

    fun requestRebuild(webView: WebView, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRebuildRequestAt < REBUILD_DEBOUNCE_MS) return
        if (rebuildPending) return
        lastRebuildRequestAt = now
        rebuildPending = true
        val started = SystemClock.elapsedRealtime()
        webView.evaluateJavascript(InteractionIndex.COLLECT_JS) { raw ->
            rebuildPending = false
            val unwrapped = unwrap(raw)
            val ended = SystemClock.elapsedRealtime()
            val count = interactionIndex.rebuildFromJson(unwrapped, started, ended)
            indexDirty = false
            onIndexStats?.invoke(interactionIndex.statsJson().toString())
            onInputResponse("index_rebuild", ended - started)
            count
        }
    }

    fun handle(webView: WebView, event: KeyEvent): Boolean {
        boundWebView = webView

        // Wake faded cursor only in pointer mode (or media lock) so DOM mode stays clean.
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            (mode == Mode.POINTER || mediaPointerLock)
        ) {
            cursor.wake()
        }

        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_MENU ||
                event.keyCode == KeyEvent.KEYCODE_INFO ||
                event.keyCode == KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU)
        ) {
            onUserInput()
            // Menu still allowed to toggle only when not locked to media pointer.
            if (!mediaPointerLock) toggleMode()
            traceConsume("menu_toggle", event)
            return true
        }
        if (event.keyCode in SELECT_KEYS) {
            val consumed = handleSelect(webView, event)
            if (consumed) traceConsume("select_${mode.name.lowercase()}", event)
            return consumed
        }

        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> return false
        }

        // While video is up, never let direction keys fall into DOM focus navigation.
        if (mediaPointerLock && mode != Mode.POINTER) {
            setMode(Mode.POINTER)
        }

        // Results list bound: vertical = aim pointer first; hold (~280ms) → scroll list.
        // Works in pointer + DOM so an accidental mode switch cannot kill list control.
        if ((dir == "up" || dir == "down") && modalOwner == ModalOwner.ACTIVE) {
            return handleModalVerticalAimOrScroll(webView, dir, event)
        }

        if (mode == Mode.POINTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        onUserInput()
                        clearStaleHoldsIfNeeded()
                        setDirectionHeld(dir, true)
                        // Immediate motion — same path as left/right (continuous frame loop).
                        tickPointer(webView, 1f / 45f)
                        tickPointer(webView, 1f / 45f)
                        // After IME: discover quality results list (does not freeze pointer).
                        if (dir == "up" || dir == "down") {
                            opportunisticModalBind(webView)
                        }
                    }
                    ensureFrameLoop(force = true)
                    traceConsume("pointer_dir", event)
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    setDirectionHeld(dir, false)
                    if (dir == "left" || dir == "right") {
                        endHorizontalDrag()
                    }
                    if (!hasDirectionHeld()) {
                        endPageDrag()
                        endHorizontalDrag()
                        stopFrameLoop()
                        velX = 0f
                        velY = 0f
                    } else if (!holdUp && !holdDown) {
                        endPageDrag()
                    }
                    if (dir == "up" || dir == "down") {
                        stopModalListScroll(webView)
                    }
                    traceConsume("pointer_dir_up", event)
                    return true
                }
                else -> return true
            }
        }

        // DOM mode: consume ALL direction events so WebView spatial nav never runs.
        if (event.action != KeyEvent.ACTION_DOWN) {
            traceConsume("dom_dir_non_down", event)
            return true
        }
        if (event.repeatCount > 0 && event.repeatCount % 2 != 0) {
            return true
        }
        onUserInput()
        if (dir == "up" || dir == "down") {
            opportunisticModalBind(webView)
        }
        handleDomDirection(webView, dir)
        traceConsume("dom_dir", event)
        return true
    }

    /**
     * Android TV IME Search/Go/Done/Enter — called from [KeenWebView] InputConnection.
     * InputConnection runs on a binder/IME thread; **all WebView work must hop to main**.
     *
     * Fallback submit is permitted only when [ImeSubmitSignal.baseHandled] is false and
     * a bounded observe window shows no navigation / modal / page handling.
     */
    fun onImeSubmit(webView: WebView, signal: ImeSubmitSignal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onImeSubmit(webView, signal) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastImeSubmitAt < 300L) return
        lastImeSubmitAt = now
        boundWebView = webView
        keyboardLikelyVisible = true
        // Prefer modal capture for a short window after search submit.
        modalLikelyUntil = now + MODAL_LIKELY_WINDOW_MS
        onUserInput()
        modalLog(
            "ime_action_received",
            detail = "source=${signal.source} action=${signal.action} baseHandled=${signal.baseHandled} ts=${signal.timestampMs}",
            reason = signal.source,
        )
        modalLog(
            "ime_base_handled",
            detail = "baseHandled=${signal.baseHandled}",
            reason = if (signal.baseHandled) "original_handled" else "original_unhandled",
        )
        try {
            webView.evaluateJavascript(ModalScrollJs.INSTALL_JS, null)
        } catch (_: Throwable) {
        }
        // Snapshot URL for navigation detection.
        try {
            lastImeUrl = webView.url
        } catch (_: Throwable) {
            lastImeUrl = null
        }

        // Always: blur field, hide TV keyboard, restore browser focus — after a brief
        // window so the original editor action can run first.
        mainHandler.postDelayed({
            if (boundWebView !== webView) return@postDelayed
            try {
                webView.evaluateJavascript(ModalScrollJs.IME_SUBMIT_JS) { raw ->
                    modalLog("ime_blur", unwrap(raw) ?: "null", reason = "post_ime_blur")
                }
            } catch (_: Throwable) {
            }
            onHideKeyboard()
            keyboardLikelyVisible = false
            try {
                webView.requestFocus()
            } catch (_: Throwable) {
            }

            // Probes must not set BINDING / block D-pad.
            scheduleModalBindAttempts(webView)

            if (signal.baseHandled) {
                // Original handled — never double-submit.
                return@postDelayed
            }

            // Bounded observe window (~400 ms) before exactly one fallback.
            mainHandler.postDelayed({
                if (boundWebView !== webView) return@postDelayed
                maybeImeFallback(webView, signal)
            }, 400L)
        }, 90L)
    }

    private fun maybeImeFallback(webView: WebView, signal: ImeSubmitSignal) {
        if (modalOwner == ModalOwner.ACTIVE) {
            modalLog("ime_fallback_skip", reason = "modal_already_active")
            onHideKeyboard()
            return
        }
        val urlNow = try {
            webView.url
        } catch (_: Throwable) {
            null
        }
        if (urlNow != null && lastImeUrl != null && urlNow != lastImeUrl) {
            modalLog("ime_fallback_skip", reason = "navigation_began", detail = "from=$lastImeUrl to=$urlNow")
            onHideKeyboard()
            scheduleModalBindAttempts(webView)
            return
        }
        try {
            webView.evaluateJavascript(ModalScrollJs.IME_OBSERVE_JS) { raw ->
                val s = unwrap(raw) ?: "{}"
                try {
                    val o = org.json.JSONObject(s)
                    if (o.optBoolean("modalActive", false) || o.optInt("resultsVisible", 0) > 0) {
                        modalLog("ime_fallback_skip", detail = s, reason = "results_or_modal_visible")
                        scheduleModalBindAttempts(webView)
                        onHideKeyboard()
                        return@evaluateJavascript
                    }
                    if (o.optBoolean("empty", true)) {
                        modalLog("ime_fallback_skip", detail = s, reason = "empty_query")
                        // Hide keyboard, blur already done — leave page stable.
                        onHideKeyboard()
                        return@evaluateJavascript
                    }
                } catch (_: Exception) {
                }
                // Exactly one fallback submit.
                try {
                    webView.evaluateJavascript(ModalScrollJs.IME_FALLBACK_SUBMIT_JS) { fr ->
                        modalLog("ime_fallback", unwrap(fr) ?: "", reason = "original_unhandled")
                        onHideKeyboard()
                        scheduleModalBindAttempts(webView)
                    }
                } catch (_: Throwable) {
                    onHideKeyboard()
                }
            }
        } catch (_: Throwable) {
            onHideKeyboard()
        }
    }

    /**
     * Post-search probes. Must **not** set [ModalOwner.BINDING] (that deadens D-pad).
     * Stale tokens cannot overwrite a newer claim; failed probes cannot clear ACTIVE.
     */
    private fun scheduleModalBindAttempts(webView: WebView) {
        val token = ++bindAttemptToken
        val delays = longArrayOf(0L, 100L, 250L, 500L, 900L)
        for (d in delays) {
            mainHandler.postDelayed({
                if (token != bindAttemptToken) return@postDelayed
                // Never interfere with user claim or established ownership.
                if (modalOwner == ModalOwner.ACTIVE || modalOwner == ModalOwner.BINDING) return@postDelayed
                webView.evaluateJavascript(ModalScrollJs.callBind()) { raw ->
                    if (token != bindAttemptToken) return@evaluateJavascript
                    if (modalOwner == ModalOwner.ACTIVE || modalOwner == ModalOwner.BINDING) return@evaluateJavascript
                    val s = unwrap(raw) ?: return@evaluateJavascript
                    try {
                        val o = org.json.JSONObject(s)
                        if (isQualityModalPayload(s)) {
                            val before = modalOwner
                            modalOwner = ModalOwner.ACTIVE
                            modalLikelyUntil = SystemClock.elapsedRealtime() + MODAL_LIKELY_WINDOW_MS
                            if (mode != Mode.POINTER) {
                                setMode(Mode.POINTER)
                            }
                            cacheModalListRect(s)
                            snapPointerIntoModalList()
                            modalLog(
                                "bind_ok",
                                detail = s,
                                ownerBefore = before,
                                reason = "probe_delay_$d",
                            )
                        } else if (d == delays.last()) {
                            modalLog("bind_fail", detail = s, reason = "probe_exhausted_or_low_quality")
                        }
                    } catch (_: Exception) {
                    }
                }
            }, d)
        }
    }

    /**
     * True only for a real results list: large enough, scroll room, not chrome/control junk.
     * Without this gate, a 20px KBD node latches ACTIVE and Up/Down dies while Left/Right work.
     */
    private fun isQualityModalPayload(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            val o = org.json.JSONObject(raw)
            if (!o.optBoolean("ok", false)) return false
            val tag = o.optString("tag", "").uppercase()
            if (tag in JUNK_MODAL_TAGS) return false
            val ch = o.optInt("ch", 0)
            val sh = o.optInt("sh", 0)
            val room = when {
                o.has("max") -> o.optInt("max", 0)
                sh > 0 && ch > 0 -> sh - ch
                else -> 0
            }
            if (ch > 0 && ch < 100) return false
            if (room > 0 && room < 40) return false
            // Prefer evidence of motion; allow quality-sized boundary hold.
            val moved = o.optBoolean("moved", false)
            val boundary = o.optBoolean("boundary", false)
            if (moved) return true
            if (boundary && ch >= 100 && room >= 40) return true
            // bind() without a kick may omit moved — accept size alone.
            if (!o.has("moved") && ch >= 100 && room >= 48) return true
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * After IME only: bind a quality results list, cache its rect, and put the
     * pointer into the list so Up/Down immediately scrolls content for OK-select.
     */
    private fun opportunisticModalBind(webView: WebView) {
        if (modalOwner == ModalOwner.ACTIVE || modalOwner == ModalOwner.BINDING) return
        val now = SystemClock.elapsedRealtime()
        if (now >= modalLikelyUntil) return
        if (now - lastOpportunisticClaimAt < 120L) return
        lastOpportunisticClaimAt = now

        val token = ++claimGen
        webView.evaluateJavascript(ModalScrollJs.callBind()) { raw ->
            if (token != claimGen) return@evaluateJavascript
            if (modalOwner == ModalOwner.ACTIVE) return@evaluateJavascript
            val s = unwrap(raw) ?: return@evaluateJavascript
            if (!isQualityModalPayload(s)) {
                modalLog("bind_fail", detail = s, reason = "low_quality_reject")
                return@evaluateJavascript
            }
            val before = modalOwner
            modalOwner = ModalOwner.ACTIVE
            modalLikelyUntil = SystemClock.elapsedRealtime() + MODAL_LIKELY_WINDOW_MS
            // Never leave DOM mode on a results sheet — long OK was flipping mode alone.
            if (mode != Mode.POINTER) {
                setMode(Mode.POINTER)
            }
            cacheModalListRect(s)
            snapPointerIntoModalList()
            modalLog("bind_ok", detail = s, ownerBefore = before, reason = "opportunistic_bind")
            webView.evaluateJavascript(BLUR_ACTIVE_INPUT_JS, null)
            onHideKeyboard()
            keyboardLikelyVisible = false
        }
    }

    private fun cacheModalListRect(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val o = org.json.JSONObject(raw)
            val rect = o.optJSONObject("rect") ?: o
            if (!rect.has("left") || !rect.has("top")) return
            modalListLeft = rect.optDouble("left", 0.0).toFloat()
            modalListTop = rect.optDouble("top", 0.0).toFloat()
            modalListRight = rect.optDouble("right", 0.0).toFloat()
            modalListBottom = rect.optDouble("bottom", 0.0).toFloat()
            if (modalListRight <= modalListLeft || modalListBottom <= modalListTop) {
                val w = rect.optDouble("width", 0.0).toFloat()
                val h = rect.optDouble("height", 0.0).toFloat()
                if (w > 0f && h > 0f) {
                    modalListRight = modalListLeft + w
                    modalListBottom = modalListTop + h
                }
            }
            modalListRectValid =
                modalListRight > modalListLeft + 40f && modalListBottom > modalListTop + 40f
            lastRectRefreshAt = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            modalListRectValid = false
        }
    }

    /** Shell cursor → WebView page coords, then hit-test cached list rect. */
    private fun pointerOverModalList(): Boolean {
        if (modalOwner != ModalOwner.ACTIVE || !modalListRectValid) return false
        val chromeH = chromeHeightPx().coerceAtLeast(0).toFloat()
        val pageX = cursor.cursorX
        val pageY = (cursor.cursorY - chromeH).coerceAtLeast(0f)
        val pad = 28f * density
        return pageX >= modalListLeft - pad &&
            pageX <= modalListRight + pad &&
            pageY >= modalListTop - pad &&
            pageY <= modalListBottom + pad
    }

    /** After search bind: put cursor in the middle of the results list. */
    private fun snapPointerIntoModalList() {
        if (!modalListRectValid) return
        val chromeH = chromeHeightPx().coerceAtLeast(0).toFloat()
        val cx = (modalListLeft + modalListRight) * 0.5f
        val cy = (modalListTop + modalListBottom) * 0.5f + chromeH
        cursor.setPosition(cx, cy)
        cursor.wake()
        modalLog(
            "owner_transition",
            reason = "snap_pointer_into_list",
            detail = "x=${cx.toInt()} y=${cy.toInt()} listTop=$modalListTop listBot=$modalListBottom",
        )
    }

    /**
     * Modal ACTIVE vertical D-pad:
     * - **Tap / short press:** move the pointer (aim at a result for OK)
     * - **Hold (~280ms):** switch to continuous list scroll under the pointer
     * Left/Right always move the pointer.
     */
    private fun handleModalVerticalAimOrScroll(webView: WebView, dir: String, event: KeyEvent): Boolean {
        val d = if (dir == "up") -1 else 1
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                onUserInput()
                if (event.repeatCount == 0) {
                    // Force pointer mode so the cursor is visible for aiming.
                    if (mode != Mode.POINTER) {
                        setMode(Mode.POINTER)
                    }
                    // 1) Aim immediately (same continuous motion as free pointer).
                    modalListScrolling = false
                    modalVerticalHeld = false
                    setDirectionHeld(dir, true)
                    tickPointer(webView, 1f / 45f)
                    tickPointer(webView, 1f / 45f)
                    ensureFrameLoop(force = true)
                    modalLog(
                        "start",
                        direction = dir,
                        reason = "dpad_aim_pointer",
                        detail = "ptr=${cursor.cursorX.toInt()},${cursor.cursorY.toInt()} " +
                            "list=$modalListLeft,$modalListTop-$modalListRight,$modalListBottom",
                    )
                    // 2) If still held after threshold → scroll list, freeze Y.
                    cancelModalHoldScrollArmed()
                    val armedDir = dir
                    val r = Runnable {
                        val stillHeld =
                            (armedDir == "up" && holdUp) || (armedDir == "down" && holdDown)
                        if (!stillHeld || modalOwner != ModalOwner.ACTIVE) return@Runnable
                        // Switch aim → scroll.
                        holdUp = false
                        holdDown = false
                        velY = 0f
                        endPageDrag()
                        if (!holdLeft && !holdRight) {
                            stopFrameLoop()
                            velX = 0f
                        } else {
                            recomputeVelocity()
                        }
                        modalListScrolling = true
                        modalVerticalHeld = true
                        modalLog(
                            "start",
                            direction = armedDir,
                            reason = "hold_switch_to_list_scroll",
                            detail = "ptr=${cursor.cursorX.toInt()},${cursor.cursorY.toInt()}",
                        )
                        startModalListScroll(webView, d, armedDir)
                        maybeRefreshModalRect(webView)
                    }
                    modalHoldScrollRunnable = r
                    mainHandler.postDelayed(r, HOLD_TO_LIST_SCROLL_MS)
                }
                // Key-repeat while aiming: frame loop already drives pointer.
                // While list-scrolling: in-page rAF continues; no restart.
                return true
            }
            KeyEvent.ACTION_UP -> {
                cancelModalHoldScrollArmed()
                val wasScrolling = modalListScrolling
                if (wasScrolling) {
                    modalLog("stop", direction = dir, reason = "list_scroll_key_up")
                    stopModalListScroll(webView)
                }
                setDirectionHeld(dir, false)
                if (!hasDirectionHeld()) {
                    endPageDrag()
                    stopFrameLoop()
                    velX = 0f
                    velY = 0f
                } else if (!holdUp && !holdDown) {
                    endPageDrag()
                }
                if (!wasScrolling) {
                    modalLog(
                        "stop",
                        direction = dir,
                        reason = "aim_pointer_key_up",
                        detail = "ptr=${cursor.cursorX.toInt()},${cursor.cursorY.toInt()}",
                    )
                }
                return true
            }
            else -> return true
        }
    }

    private fun cancelModalHoldScrollArmed() {
        modalHoldScrollRunnable?.let { mainHandler.removeCallbacks(it) }
        modalHoldScrollRunnable = null
    }

    private fun startModalListScroll(webView: WebView, direction: Int, dirLabel: String) {
        // bindAndStart: re-validate/bind + immediate kick in one JS turn.
        webView.evaluateJavascript(ModalScrollJs.callBindAndStart(direction)) { raw ->
            val s = unwrap(raw) ?: """{"ok":false,"reason":"null_js"}"""
            cacheModalListRect(s)
            try {
                val o = org.json.JSONObject(s)
                val ok = o.optBoolean("ok", false)
                val moved = o.optBoolean("moved", false)
                val boundary = o.optBoolean("boundary", false)
                val reason = o.optString("reason", if (ok) "ok" else "fail")
                if (!ok && !moved) {
                    modalLog(
                        "bind_fail",
                        detail = s,
                        direction = dirLabel,
                        reason = "list_scroll_js_fail:$reason",
                    )
                    // Lost list (closed/navigated) — drop ownership so vertical is free pointer again.
                    if (reason == "no_target" || reason.contains("no_target")) {
                        clearModalOwnership(webView)
                        if (mode == Mode.POINTER) {
                            setDirectionHeld(dirLabel, true)
                            tickPointer(webView, 1f / 45f)
                            ensureFrameLoop(force = true)
                        }
                        return@evaluateJavascript
                    }
                    // Still try plain start once more (controller may need reinstall).
                    webView.evaluateJavascript(ModalScrollJs.callStart(direction)) { raw2 ->
                        val s2 = unwrap(raw2) ?: "{}"
                        modalLog("start", detail = s2, direction = dirLabel, reason = "list_scroll_retry")
                        try {
                            val o2 = org.json.JSONObject(s2)
                            if (o2.optBoolean("moved", false)) {
                                modalLog("first_movement", detail = s2, direction = dirLabel, reason = "list_scroll_retry")
                                armMaxHoldFailsafe(webView)
                            } else if (!o2.optBoolean("ok", false)) {
                                // Give vertical back to pointer so D-pad is never dead.
                                modalListScrolling = false
                                modalVerticalHeld = false
                                clearModalOwnership(webView)
                                if (mode == Mode.POINTER) {
                                    setDirectionHeld(dirLabel, true)
                                    tickPointer(webView, 1f / 45f)
                                    ensureFrameLoop(force = true)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    return@evaluateJavascript
                }
                if (moved && loggedFirstMoveGen != claimGen) {
                    loggedFirstMoveGen = claimGen
                    modalLog("first_movement", detail = s, direction = dirLabel, reason = "list_scroll")
                } else {
                    modalLog("start", detail = s, direction = dirLabel, reason = "list_scroll")
                }
                if (boundary) {
                    modalLog("boundary", detail = s, direction = dirLabel, reason = "list_boundary")
                }
                armMaxHoldFailsafe(webView)
            } catch (e: Exception) {
                modalLog("bind_fail", detail = s, direction = dirLabel, reason = "json_parse:${e.message}")
            }
        }
    }

    private fun looksQualityMoved(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            val o = org.json.JSONObject(raw)
            o.optBoolean("ok", false) && o.optBoolean("moved", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun stopModalListScroll(webView: WebView) {
        cancelModalHoldScrollArmed()
        if (!modalListScrolling && !modalVerticalHeld) return
        modalListScrolling = false
        modalVerticalHeld = false
        cancelMaxHoldFailsafe()
        webView.evaluateJavascript(ModalScrollJs.callStop()) { raw ->
            modalLog("stop", detail = unwrap(raw) ?: "null", reason = "list_scroll_stop")
        }
    }

    private fun maybeRefreshModalRect(webView: WebView) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRectRefreshAt < 700L) return
        lastRectRefreshAt = now
        webView.evaluateJavascript(ModalScrollJs.callTargetRect()) { raw ->
            val s = unwrap(raw) ?: return@evaluateJavascript
            cacheModalListRect(s)
        }
    }

    private fun modalJsStop(webView: WebView) {
        cancelMaxHoldFailsafe()
        webView.evaluateJavascript(ModalScrollJs.callStop()) { raw ->
            modalLog("stop", detail = unwrap(raw) ?: "null", reason = "stop")
        }
    }

    private fun armMaxHoldFailsafe(webView: WebView) {
        cancelMaxHoldFailsafe()
        val r = Runnable {
            if (modalListScrolling || modalVerticalHeld) {
                modalLog("stop", reason = "max_hold_failsafe_5s")
                stopModalListScroll(webView)
            }
        }
        maxHoldFailsafe = r
        mainHandler.postDelayed(r, MAX_HOLD_FAILSAFE_MS)
    }

    private fun cancelMaxHoldFailsafe() {
        maxHoldFailsafe?.let { mainHandler.removeCallbacks(it) }
        maxHoldFailsafe = null
    }

    /**
     * Release-safe low-volume diagnostic logging for physical TV validation.
     * Does not log every animation frame.
     */
    private fun modalLog(
        event: String,
        detail: String = "",
        ownerBefore: ModalOwner? = null,
        direction: String? = null,
        reason: String? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        // Mild throttle for high-frequency start events only.
        if (event == "start" || event == "first_movement") {
            if (now - lastModalLogAt < 40L) return
        }
        lastModalLogAt = now
        val detailObj = try {
            if (detail.startsWith("{")) org.json.JSONObject(detail) else null
        } catch (_: Exception) {
            null
        }
        val tag = detailObj?.optString("tag", "") ?: ""
        val id = detailObj?.optString("id", "") ?: ""
        val cls = detailObj?.optString("cls", "") ?: ""
        val stBefore = detailObj?.opt("before") ?: detailObj?.opt("st") ?: ""
        val stAfter = detailObj?.opt("after") ?: detailObj?.opt("st") ?: ""
        val sh = detailObj?.opt("sh") ?: ""
        val ch = detailObj?.opt("ch") ?: ""
        val gen = detailObj?.opt("gen") ?: ""
        val msg = buildString {
            append("version=${BuildConfig.VERSION_NAME}")
            append(" event=$event")
            append(" direction=${direction ?: "-"}")
            append(" ownerBefore=${ownerBefore ?: "-"}")
            append(" ownerAfter=$modalOwner")
            append(" target=$tag#${id}.$cls")
            append(" scrollTop=$stBefore->$stAfter")
            append(" scrollHeight=$sh")
            append(" clientHeight=$ch")
            append(" pointer=${cursor.cursorX.toInt()},${cursor.cursorY.toInt()}")
            append(" inputMode=${mode.name}")
            append(" keyboardVisible=$keyboardLikelyVisible")
            append(" gen=$gen")
            if (!reason.isNullOrBlank()) append(" reason=$reason")
            if (detail.isNotBlank() && detailObj == null) append(" detail=${detail.take(200)}")
        }
        Log.i("KeenModalScroll", msg)
    }

    private fun handleDomDirection(webView: WebView, dir: String) {
        if (indexDirty || interactionIndex.size == 0) {
            requestRebuild(webView, force = false)
            webView.evaluateJavascript("(${LEGACY_MOVE_FALLBACK})('$dir')") { result ->
                val ok = unwrap(result)
                if (ok == "false" || ok == null) {
                    // No directional candidate — bounded viewport scroll (not DOM focus).
                    scrollViewportBounded(webView, dir)
                }
                markIndexDirty("after_fallback_move")
            }
            return
        }
        val next = interactionIndex.select(dir)
        if (next != null) {
            val id = next.id.replace("'", "\\'")
            webView.evaluateJavascript("(${InteractionIndex.FOCUS_JS})('$id')") {
                markIndexDirty("after_focus_scroll")
                requestRebuild(webView, force = false)
            }
        } else {
            scrollViewportBounded(webView, dir)
            markIndexDirty("scroll")
            requestRebuild(webView, force = false)
        }
    }

    /**
     * Single-owner page scroll for DOM mode: native WebView when it can scroll,
     * otherwise document.scrollingElement only. Never both in one call.
     */
    private fun scrollViewportBounded(webView: WebView, dir: String) {
        val h = webView.height.coerceAtLeast(1)
        val w = webView.width.coerceAtLeast(1)
        val stepY = (h * DOM_SCROLL_FRACTION).toInt().coerceIn(80, h)
        val stepX = (w * DOM_SCROLL_FRACTION).toInt().coerceIn(80, w)
        when (dir) {
            "up" -> scrollPageVertical(webView, -stepY)
            "down" -> scrollPageVertical(webView, stepY)
            "left" -> {
                if (webView.canScrollHorizontally(-1)) webView.scrollBy(-stepX, 0)
                else webView.evaluateJavascript(
                    "(function(dx){var se=document.scrollingElement||document.documentElement;if(se)se.scrollLeft=(se.scrollLeft||0)+dx;})(${-stepX});",
                    null,
                )
            }
            "right" -> {
                if (webView.canScrollHorizontally(1)) webView.scrollBy(stepX, 0)
                else webView.evaluateJavascript(
                    "(function(dx){var se=document.scrollingElement||document.documentElement;if(se)se.scrollLeft=(se.scrollLeft||0)+dx;})($stepX);",
                    null,
                )
            }
        }
    }

    /** Exactly one vertical owner: native View if capable, else document only. */
    private fun scrollPageVertical(webView: WebView, dy: Int) {
        if (dy == 0) return
        // No per-frame JS grant spam (caused lag). Scroll authority v4 only freezes on navigate.
        val dir = if (dy > 0) 1 else -1
        if (webView.canScrollVertically(dir)) {
            webView.scrollBy(0, dy)
            return
        }
        webView.evaluateJavascript(
            """(function(dy){
              try{
                var se=document.scrollingElement||document.documentElement||document.body;
                if(!se) return;
                var before=se.scrollTop|0;
                se.scrollTop=before+dy;
                if((se.scrollTop|0)===before){
                  window.scrollBy(0,dy);
                }
              }catch(e){}
            })($dy);""",
            null,
        )
    }

    private fun traceConsume(subsystem: String, event: KeyEvent) {
        if (!BuildConfig.DEBUG) return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        Log.d(
            TRACE_TAG,
            "consume subsystem=$subsystem mode=$mode mediaLock=$mediaPointerLock key=${event.keyCode}",
        )
    }

    private fun setDirectionHeld(dir: String, held: Boolean) {
        when (dir) {
            "left" -> holdLeft = held
            "right" -> holdRight = held
            "up" -> holdUp = held
            "down" -> holdDown = held
        }
        if (held && (holdLeft || holdRight || holdUp || holdDown)) {
            if (holdStartedAt == 0L) holdStartedAt = SystemClock.elapsedRealtime()
        }
        if (!hasDirectionHeld()) {
            holdStartedAt = 0L
            endPageDrag()
            endHorizontalDrag()
        } else if (!holdUp && !holdDown) {
            endPageDrag()
        }
        if (!holdLeft && !holdRight) {
            endHorizontalDrag()
        }
        if (held && holdStartedAt == 0L && hasDirectionHeld()) {
            holdStartedAt = SystemClock.elapsedRealtime()
        }
        recomputeVelocity()
    }

    /** Drop modal ownership (sheet closed / navigation / explicit clear). */
    private fun clearModalOwnership(webView: WebView? = boundWebView) {
        val before = modalOwner
        modalVerticalHeld = false
        modalListScrolling = false
        modalListRectValid = false
        cancelModalHoldScrollArmed()
        modalOwner = ModalOwner.NONE
        pendingStopAfterClaim = false
        modalLikelyUntil = 0L
        // Invalidate all outstanding probes and in-flight claims.
        bindAttemptToken++
        claimGen++
        cancelMaxHoldFailsafe()
        webView?.evaluateJavascript(ModalScrollJs.callClear(), null)
        modalLog(
            "target_invalid",
            ownerBefore = before,
            reason = "cleared",
        )
        modalLog(
            "owner_transition",
            ownerBefore = before,
            reason = "clear_modal_ownership",
        )
    }

    private fun hasDirectionHeld(): Boolean =
        holdLeft || holdRight || holdUp || holdDown

    private fun recomputeVelocity() {
        var dx = 0f
        var dy = 0f
        if (holdLeft) dx -= 1f
        if (holdRight) dx += 1f
        if (holdUp) dy -= 1f
        if (holdDown) dy += 1f
        if (dx == 0f && dy == 0f) {
            velX = 0f
            velY = 0f
            return
        }
        // Normalize diagonal so diagonal isn't faster.
        val len = hypot(dx, dy)
        dx /= len
        dy /= len
        val heldMs = if (holdStartedAt == 0L) 0L else SystemClock.elapsedRealtime() - holdStartedAt
        // Ease from slow precise crawl → fast cruise (Apple-TV-like).
        val t = (heldMs / ACCEL_MS).coerceIn(0f, 1f)
        val speed = (SPEED_MIN_DP + (SPEED_MAX_DP - SPEED_MIN_DP) * smoothstep(t)) * density
        velX = dx * speed
        velY = dy * speed
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private fun ensureFrameLoop(force: Boolean = false) {
        val nowNs = System.nanoTime()
        val stalled = frameCallbackRegistered && lastFrameNs != 0L &&
            (nowNs - lastFrameNs) > 200_000_000L // >200ms without a frame
        if (frameCallbackRegistered && !force && !stalled) return
        // Drop any orphan callback, then post fresh (critical after idle / onPause).
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackRegistered = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackRegistered = false
        lastFrameNs = 0L
    }

    /** Clear stuck D-pad holds / frame loop (pause or recovery). */
    fun resetPointerMotion() {
        holdLeft = false
        holdRight = false
        holdUp = false
        holdDown = false
        velX = 0f
        velY = 0f
        holdStartedAt = 0L
        clearModalOwnership(boundWebView)
        endPageDrag()
        endHorizontalDrag()
        stopFrameLoop()
    }

    /** Call from Activity.onResume after screensaver / idle. */
    fun onHostResumed(webView: WebView?) {
        boundWebView = webView
        resetPointerMotion()
        if (mode == Mode.POINTER) {
            if (cursor.visibility != android.view.View.VISIBLE) {
                cursor.showAtCentre()
            }
            // Unlock scroll traps left by dismissed modals.
            webView?.evaluateJavascript(UNLOCK_SCROLL_JS, null)
        }
    }

    private var lastInputAt = SystemClock.elapsedRealtime()
    private fun clearStaleHoldsIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        // If we haven't seen input for a while, previous holds are untrustworthy.
        if (now - lastInputAt > 2_000L) {
            holdLeft = false
            holdRight = false
            holdUp = false
            holdDown = false
            velX = 0f
            velY = 0f
            holdStartedAt = 0L
            stopFrameLoop()
        }
        lastInputAt = now
    }

    private fun tickPointer(webView: WebView, dt: Float) {
        recomputeVelocity()
        if (velX == 0f && velY == 0f) return

        val chromeH = if (mediaPointerLock) 0f else chromeHeightPx().coerceAtLeast(0).toFloat()
        val edge = EDGE_ZONE_DP * density
        val (vw, vh) = cursor.viewportSize()
        if (vw <= 0 || vh <= 0) return

        var nx = cursor.cursorX + velX * dt
        var ny = cursor.cursorY + velY * dt

        // Edge scroll (not during HTML custom-view / playback media lock).
        val scrollPx = (EDGE_SCROLL_DP * density * dt).toInt().coerceAtLeast((10f * density).toInt())
        val pageX = cursor.cursorX
        val pageY = (cursor.cursorY - chromeH).coerceAtLeast(0f)
        val inTopZone = cursor.cursorY <= chromeH + edge
        val inBottomZone = cursor.cursorY >= vh - edge
        val inLeftZone = cursor.cursorX <= edge
        val inRightZone = cursor.cursorX >= vw - edge

        if (!mediaPointerLock) {
            if (cursor.cursorY < chromeH - 2f) {
                // In URL chrome: only scroll page when holding up (reveal content).
                if (holdUp) scrollPageVertical(webView, -scrollPx)
                cursor.setPosition(nx, ny)
                if (cursor.alpha < 0.99f) cursor.wake()
                return
            }

            val verticalHold = holdUp || holdDown
            if (verticalHold && modalOwner == ModalOwner.ACTIVE && modalListScrolling) {
                // List scroll owns vertical; do not page-drag under the sheet.
                endPageDrag()
            } else if (verticalHold && modalOwner == ModalOwner.NONE) {
                val dy = if (holdUp) -scrollPx else scrollPx
                if ((holdUp && inTopZone) || (holdDown && inBottomZone)) {
                    // Main page edge scroll only (proven path).
                    advanceAnchoredDrag(
                        webView,
                        webView.width * 0.62f,
                        webView.height * 0.55f,
                        dy,
                    )
                    val inset = edge * 0.55f
                    ny = if (holdUp) {
                        min(ny, chromeH + inset)
                    } else {
                        max(ny, vh - inset)
                    }
                } else {
                    endPageDrag()
                }
            } else if (!verticalHold) {
                endPageDrag()
            }

            // Horizontal rails: only when the cursor is on the L/R page edge and the
            // matching direction is held (or tapped while already parked on that edge).
            // Mid-page Left/Right only aims the pointer — no rail scroll, no phantom clicks.
            val edgeHScroll =
                (holdRight && inRightZone) || (holdLeft && inLeftZone)
            if (edgeHScroll) {
                val dx = if (holdLeft) -scrollPx else scrollPx
                val (localX, localY) = shellToWebViewLocal(webView, cursor.cursorX, cursor.cursorY)
                val w = webView.width.toFloat().coerceAtLeast(1f)
                val h = webView.height.toFloat().coerceAtLeast(1f)
                // Probe just inside the content so elementFromPoint hits the row, not chrome margin.
                val probeInset = edge * 0.85f
                val probeX = when {
                    inLeftZone -> max(localX, probeInset).coerceIn(8f, w * 0.25f)
                    else -> min(localX, w - probeInset).coerceIn(w * 0.75f, w - 8f)
                }
                val probeY = localY.coerceIn(h * 0.08f, h * 0.92f)
                endHorizontalDrag()
                // Only nested content rails (movie rows). Never pan the WebView/document —
                // that drags the hero banner and video player stage sideways.
                scrollUnderPointer(webView, probeX, probeY, dx, 0, forceWindow = false)
                // Park cursor in the edge band so hold / repeat keeps revealing the rail.
                val inset = edge * 0.45f
                nx = if (holdLeft) {
                    min(nx, inset)
                } else {
                    max(nx, vw - inset)
                }
            } else {
                endHorizontalDrag()
                if (!holdLeft && !holdRight) hScrollMissStreak = 0
            }
        } else {
            // Fullscreen / playback: pointer only + hover so HTML player chrome reveals.
            endPageDrag()
            endHorizontalDrag()
            dispatchMediaHover(nx, ny)
        }

        cursor.setPosition(nx, ny)
        // Keep cursor solid while moving (throttled — wake every frame is expensive).
        if (cursor.alpha < 0.99f) cursor.wake()
    }

    /**
     * Long-lived touch drag planted at (anchorX, anchorY).
     * Chromium picks the scroller under that point. Nested list uses list-centre
     * anchors so UP never depends on the pointer sitting in the sheet header.
     */
    /** Main-page edge scroll only — long-lived touch drag on content column. */
    private fun advanceAnchoredDrag(
        webView: WebView,
        anchorX: Float,
        anchorY: Float,
        dy: Int,
    ) {
        if (dy == 0) return
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        if (w < 32f || h < 32f) return

        val ax = anchorX.coerceIn(w * 0.08f, w * 0.92f)
        val ay = anchorY.coerceIn(h * 0.12f, h * 0.88f)
        val now = SystemClock.uptimeMillis()

        if (!pageDragActive || pageDragWebView !== webView) {
            endPageDrag()
            pageDragActive = true
            pageDragWebView = webView
            pageDragDownTime = now
            pageDragX = ax
            pageDragFingerY = ay
            val down = MotionEvent.obtain(
                pageDragDownTime, now, MotionEvent.ACTION_DOWN,
                pageDragX, pageDragFingerY, 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                webView.dispatchTouchEvent(down)
            } finally {
                down.recycle()
            }
        }

        val minY = h * 0.10f
        val maxY = h * 0.90f
        pageDragFingerY = (pageDragFingerY - dy.toFloat()).coerceIn(minY, maxY)

        if (pageDragFingerY <= minY + 2f || pageDragFingerY >= maxY - 2f) {
            endPageDrag()
            scrollPageVertical(webView, dy)
            return
        }
        val move = MotionEvent.obtain(
            pageDragDownTime, now, MotionEvent.ACTION_MOVE,
            pageDragX, pageDragFingerY, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            webView.dispatchTouchEvent(move)
        } finally {
            move.recycle()
        }
    }

    private fun endPageDrag() {
        if (!pageDragActive) return
        val wv = pageDragWebView
        val x = pageDragX
        val y = pageDragFingerY
        val downTime = pageDragDownTime
        pageDragActive = false
        pageDragWebView = null
        if (wv == null) return
        val now = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(
            downTime, now, MotionEvent.ACTION_UP, x, y, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            wv.dispatchTouchEvent(up)
        } catch (_: Throwable) {
        } finally {
            up.recycle()
        }
    }

    /**
     * Horizontal finger-drag on the rail under the pointer.
     * Hold-right → drag finger left so more content on the right is revealed (native scroll).
     */
    private fun advanceHorizontalDrag(
        webView: WebView,
        anchorX: Float,
        anchorY: Float,
        dx: Int,
    ) {
        if (dx == 0) return
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        if (w < 32f || h < 32f) return

        val ax = anchorX.coerceIn(w * 0.06f, w * 0.94f)
        val ay = anchorY.coerceIn(h * 0.10f, h * 0.90f)
        val now = SystemClock.uptimeMillis()

        if (!hDragActive || hDragWebView !== webView) {
            endHorizontalDrag()
            // Don't fight vertical page drag.
            endPageDrag()
            hDragActive = true
            hDragWebView = webView
            hDragDownTime = now
            hDragX = ax
            hDragY = ay
            val down = MotionEvent.obtain(
                hDragDownTime, now, MotionEvent.ACTION_DOWN,
                hDragX, hDragY, 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                webView.dispatchTouchEvent(down)
            } finally {
                down.recycle()
            }
        }

        val minX = w * 0.06f
        val maxX = w * 0.94f
        // Finger moves opposite content intent: +dx (show right) → finger moves left.
        hDragX = (hDragX - dx.toFloat()).coerceIn(minX, maxX)
        if (hDragX <= minX + 2f || hDragX >= maxX - 2f) {
            // Replant mid-rail so continuous hold keeps feeding Chromium.
            endHorizontalDrag()
            return
        }
        val move = MotionEvent.obtain(
            hDragDownTime, now, MotionEvent.ACTION_MOVE,
            hDragX, hDragY, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            webView.dispatchTouchEvent(move)
        } finally {
            move.recycle()
        }
    }

    private fun endHorizontalDrag() {
        if (!hDragActive) return
        val wv = hDragWebView
        val x = hDragX
        val y = hDragY
        val downTime = hDragDownTime
        hDragActive = false
        hDragWebView = null
        if (wv == null) return
        val now = SystemClock.uptimeMillis()
        // CANCEL not UP — UP on a poster is treated as a click (phantom activations).
        val cancel = MotionEvent.obtain(
            downTime, now, MotionEvent.ACTION_CANCEL, x, y, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            wv.dispatchTouchEvent(cancel)
        } catch (_: Throwable) {
        } finally {
            cancel.recycle()
        }
    }

    /** Reveal HTML player chrome without clicking (mousemove / hover). */
    private fun dispatchMediaHover(shellX: Float, shellY: Float) {
        val target = mediaTouchTarget() ?: boundWebView ?: return
        val now = SystemClock.uptimeMillis()
        val hover = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE, shellX, shellY, 0,
        ).apply { source = InputDevice.SOURCE_MOUSE }
        try {
            target.dispatchGenericMotionEvent(hover)
        } catch (_: Throwable) {
        } finally {
            hover.recycle()
        }
        // Some players listen on the document inside the original WebView.
        if (target !is WebView) {
            boundWebView?.evaluateJavascript(
                """(function(x,y){
                  try{
                    var el=document.elementFromPoint(x,y)||document.body;
                    var ev=new MouseEvent('mousemove',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window});
                    el.dispatchEvent(ev);
                  }catch(e){}
                })(${shellX.toInt()},${shellY.toInt()});""",
                null,
            )
        }
    }

    private var lastHScrollAt = 0L
    private var lastHScrollLogAt = 0L
    private var lastCardStepAt = 0L
    /** Consecutive DOM h-scroll misses (diagnostics / future fallbacks). */
    private var hScrollMissStreak = 0

    private fun scrollUnderPointer(
        webView: WebView,
        pageX: Float,
        pageY: Float,
        dx: Int,
        dy: Int,
        forceWindow: Boolean,
    ) {
        if (dx == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHScrollAt < 16L) return
        lastHScrollAt = now
        val vx = pageX.toInt()
        val vy = pageY.toInt()
        val viewW = webView.width.coerceAtLeast(1)
        val viewH = webView.height.coerceAtLeast(1)
        // Card-step is discrete; throttle so we don't thrash focus/scrollIntoView every frame.
        val allowCardStep = now - lastCardStepAt > 280L
        // pageX/pageY are WebView view pixels — convert to CSS for elementFromPoint.
        webView.evaluateJavascript(
            """(function(vx,vy,viewW,viewH,dx,allowCardStep){
              try{
                var cssW=window.innerWidth||document.documentElement.clientWidth||viewW;
                var cssH=window.innerHeight||document.documentElement.clientHeight||viewH;
                var px=vx*(cssW/viewW);
                var py=vy*(cssH/viewH);
                // Amplify step in CSS space so rails actually move on 2x pages.
                var step=Math.round(dx*(cssW/viewW));
                if(Math.abs(step)<24) step=step>=0?48:-48;
                function isDoc(n){
                  return !n||n===document||n===document.scrollingElement||
                    n===document.documentElement||n===document.body;
                }
                function ox(n){
                  try{var s=getComputedStyle(n);return (s.overflowX||s.overflow||'').toLowerCase();}
                  catch(e){return '';}
                }
                // True stages only: home hero banner + video player shell.
                // Movie-card poster rows are often 200–320px tall and MUST still H-scroll.
                function isHeroOrPlayer(n){
                  if(!n||isDoc(n)||!n.getBoundingClientRect) return false;
                  try{
                    var cls=String(n.className||'')+' '+String(n.id||'')+' '+
                      (n.getAttribute&&(n.getAttribute('class')||n.getAttribute('data-testid')||'')||'');
                    // Explicit movie/TV content rails — never treat as hero/stage.
                    if(/movieCard|movie-card|media-card|title-card|poster-card|swiper-slide|keen-rail/i.test(cls)) return false;
                    var r=n.getBoundingClientRect();
                    // Full-bleed stages only (not tall poster rows ~250–320px).
                    if(r.height>cssH*0.55) return true;
                    if(r.height>cssH*0.45 && r.width>cssW*0.90) return true;
                    // Class/id stage names — HomeBanner_* is the main-page hero (must not pan).
                    if(/HomeBanner|home-banner|hero-banner|billboard|jumbo|masthead|video-?player|media-player|player-container|player-wrapper|player-shell|plyr|jwplayer|videojs|\bvjs-|html5-video|main-slider|home-slider|promo-slider|carousel-hero|swiper-hero|hero-slider|coverflow|full-?bleed|stage-banner/i.test(cls)) return true;
                    // Bare "hero"/"banner" only when also stage-sized (avoid killing "banner row" copy).
                    if(/hero|banner/i.test(cls) && r.height>cssH*0.42 && r.width>cssW*0.85) return true;
                    if(n.tagName==='VIDEO'||n.tagName==='AUDIO') return true;
                    if(n.querySelector){
                      var media=n.querySelector('video,audio,.plyr,.jwplayer,.video-js,.html5-video-player');
                      if(media){
                        var mr=media.getBoundingClientRect();
                        if(mr.height>cssH*0.22||mr.width>cssW*0.40) return true;
                      }
                    }
                  }catch(e){}
                  return false;
                }
                // Horizontal movie/TV rows (poster rails). Allow taller poster rows.
                function isContentRail(n){
                  if(!n||isDoc(n)||!n.getBoundingClientRect) return false;
                  if(isHeroOrPlayer(n)) return false;
                  try{
                    var r=n.getBoundingClientRect();
                    // Poster rows on 960×501 CSS can be ~220–340px tall — allow up to 0.62 viewport.
                    if(r.height<40||r.height>cssH*0.62) return false;
                    if(r.width<72) return false;
                    var cls=String(n.className||'');
                    // Prefer known rail/card hosts even if slightly short/tall.
                    if(/movieCard|swiper|rail|row|scroller|strip|carousel|slider|list/i.test(cls)) return true;
                  }catch(e){ return false; }
                  return true;
                }
                function contentOverflowsX(n){
                  if(!n) return false;
                  if((n.scrollWidth||0)>(n.clientWidth||0)+2) return true;
                  try{
                    var r=n.getBoundingClientRect();
                    if(r.width<40) return false;
                    var kids=n.children||[];
                    for(var i=0;i<kids.length&&i<48;i++){
                      var cr=kids[i].getBoundingClientRect();
                      if(cr.width<8) continue;
                      if(cr.right>r.right+4||cr.left<r.left-4) return true;
                    }
                    // Nested track (common: outer clip, inner translate/flex row).
                    var deep=n.querySelector && n.querySelector('div,ul,ol,section');
                    if(deep){
                      var dr=deep.getBoundingClientRect();
                      if(dr.width>r.width+12) return true;
                      if((deep.scrollWidth||0)>(deep.clientWidth||0)+2) return true;
                    }
                  }catch(e){}
                  return false;
                }
                function isHScroll(n){
                  if(!n||isDoc(n)||!n.getBoundingClientRect||!isContentRail(n)) return false;
                  // Any content-rail node that can move via scrollLeft is a candidate.
                  if((n.scrollWidth||0)>(n.clientWidth||0)+2) return true;
                  if(!contentOverflowsX(n)) return false;
                  var o=ox(n);
                  var cls=String(n.className||'')+' '+(n.getAttribute&&n.getAttribute('class')||'');
                  var rail=n.classList&&(n.classList.contains('rail')||n.classList.contains('row')||
                    n.hasAttribute('data-keen-rail')||n.getAttribute('role')==='list'||
                    n.getAttribute('role')==='region');
                  return o==='auto'||o==='scroll'||o==='overlay'||o==='hidden'||o==='clip'||rail||
                    /carousel|slider|scroll-x|horizontal|overflow-x|swiper|slick|rail|row|scroller|strip|keen-rail|list|movies|titles/i.test(cls);
                }
                function findSwiper(n){
                  if(!n) return null;
                  // Walk up; skip stage nodes but keep searching for a nested content-rail swiper
                  // (movie rows often sit under a page layout that also has HomeBanner nearby).
                  var cur=n, h=0;
                  while(cur&&h<12){
                    if(!isHeroOrPlayer(cur)){
                      try{
                        if(cur.swiper&&typeof cur.swiper.slideNext==='function') return cur.swiper;
                        if(cur.__swiper__&&typeof cur.__swiper__.slideNext==='function') return cur.__swiper__;
                      }catch(e){}
                    }
                    cur=cur.parentElement; h++;
                  }
                  try{
                    var host=n.closest&&n.closest('.swiper,.swiper-container,[class*="swiper"]');
                    if(host&&!isHeroOrPlayer(host)){
                      if(host.swiper&&typeof host.swiper.slideNext==='function') return host.swiper;
                      if(host.__swiper__&&typeof host.__swiper__.slideNext==='function') return host.__swiper__;
                    }
                  }catch(e){}
                  return null;
                }
                function apply(n){
                  if(!n||isHeroOrPlayer(n)) return false;
                  // Allow apply on overflow hosts even if slightly outside strict rail height
                  // when they look like movie/card carousels.
                  if(!isContentRail(n)){
                    var ac=String(n.className||'');
                    if(!/movieCard|swiper|rail|row|scroller|carousel|slider|list|movies|titles/i.test(ac)) return false;
                    try{
                      var ar=n.getBoundingClientRect();
                      if(ar.height<40||ar.height>cssH*0.70||ar.width<72) return false;
                    }catch(e){ return false; }
                  }
                  // Cineby/etc: Swiper transform rails — prefer official API.
                  var sw=findSwiper(n);
                  if(sw){
                    try{
                      // Continuous hold: translateBy if available, else discrete slides (throttled by caller).
                      if(typeof sw.setTranslated==='function'&&typeof sw.getTranslate==='function'){
                        var cur=sw.getTranslate();
                        sw.setTranslated(cur-step, true, false);
                        return 'swiperTranslate';
                      }
                      if(typeof sw.translateTo==='function'&&typeof sw.getTranslate==='function'){
                        sw.translateTo(sw.getTranslate()-step, 0, false, false);
                        return 'swiperTranslateTo';
                      }
                      if(step>0){ sw.slideNext(0); return 'swiperNext'; }
                      else { sw.slidePrev(0); return 'swiperPrev'; }
                    }catch(e){}
                  }
                  var b=n.scrollLeft|0;
                  try{ n.scrollLeft=b+step; }catch(e){}
                  if((n.scrollLeft|0)!==b) return 'scrollLeft';
                  try{
                    if(typeof n.scrollBy==='function') n.scrollBy({left:step,top:0,behavior:'auto'});
                  }catch(e){}
                  if((n.scrollLeft|0)!==b) return 'scrollBy';
                  try{
                    n.dispatchEvent(new WheelEvent('wheel',{deltaX:step,deltaY:0,deltaMode:0,bubbles:true,cancelable:true,clientX:px,clientY:py,view:window}));
                    if((n.scrollLeft|0)!==b) return 'wheel';
                  }catch(e){}
                  // Inner track / swiper-wrapper
                  try{
                    var kids=n.children||[];
                    for(var i=0;i<kids.length&&i<8;i++){
                      var k=kids[i];
                      if(isHeroOrPlayer(k)||!isContentRail(k)) continue;
                      var ksw=findSwiper(k);
                      if(ksw){
                        try{
                          if(step>0) ksw.slideNext(0); else ksw.slidePrev(0);
                          return 'childSwiper';
                        }catch(e){}
                      }
                      if((k.scrollWidth||0)<=(k.clientWidth||0)+2) continue;
                      var kb=k.scrollLeft|0;
                      k.scrollLeft=kb+step;
                      if((k.scrollLeft|0)!==kb) return 'childScroll';
                    }
                  }catch(e){}
                  return false;
                }
                // 1) Ancestors from hit-test (CSS pixels) — content rails / card carousels only.
                var el=document.elementFromPoint(px,py), n=el, h=0;
                var tried=0;
                while(n&&h<48){
                  if(!isDoc(n)&&!isHeroOrPlayer(n)){
                    tried++;
                    var a=apply(n);
                    if(a) return JSON.stringify({ok:true,via:'ancestor',how:a,tag:n.tagName,cls:String(n.className||'').slice(0,40),sl:n.scrollLeft|0,sw:n.scrollWidth|0,cw:n.clientWidth|0,px:px,py:py,step:step});
                  }
                  n=n.parentElement; h++;
                }
                // 1b) Explicit Swiper hosts in the same Y band (cineby rows) — content rails only.
                try{
                  var swHosts=document.querySelectorAll('.swiper,.swiper-container,[class*="swiper-initialized"]');
                  var bestSw=null, bestSwScore=-1e9;
                  for(var si=0;si<swHosts.length&&si<40;si++){
                    var sh=swHosts[si];
                    if(!isContentRail(sh)) continue;
                    var sr=sh.getBoundingClientRect();
                    if(sr.height<48||sr.width<60) continue;
                    if(py<sr.top-20||py>sr.bottom+20) continue;
                    // Prefer short row rails over wide stages (stages already filtered).
                    var ssc=sr.width*0.15+Math.min(sr.height,220)-Math.abs((sr.top+sr.bottom)/2-py);
                    if(sr.height>cssH*0.28) ssc-=400;
                    if(ssc>bestSwScore){ bestSw=sh; bestSwScore=ssc; }
                  }
                  if(bestSw){
                    var asw=apply(bestSw);
                    if(asw) return JSON.stringify({ok:true,via:'swiperBand',how:asw,tag:bestSw.tagName,cls:String(bestSw.className||'').slice(0,40),sl:bestSw.scrollLeft|0,sw:bestSw.scrollWidth|0,cw:bestSw.clientWidth|0,px:px,py:py,step:step});
                  }
                }catch(e){}
                // 2) Row-band scan for overflow rails (content rails only).
                var all=document.querySelectorAll('div,section,ul,ol,nav,main,article');
                var best=null, bestScore=-1e9;
                for(var i=0;i<all.length&&i<700;i++){
                  var c=all[i];
                  if(!isContentRail(c)) continue;
                  var sw=c.scrollWidth|0, cw=c.clientWidth|0;
                  var hsc=isHScroll(c)||(sw>cw+4)||contentOverflowsX(c);
                  if(!hsc) continue;
                  var r=c.getBoundingClientRect();
                  if(r.height<48||r.width<50) continue;
                  if(py<r.top-16||py>r.bottom+16) continue;
                  var score=(sw-cw)+r.width*0.1+Math.min(r.height,200)-Math.abs((r.top+r.bottom)/2-py);
                  if(r.height>cssH*0.28) score-=400;
                  if(score>bestScore){ best=c; bestScore=score; }
                }
                if(best){
                  var ar=apply(best);
                  if(ar) return JSON.stringify({ok:true,via:'row',how:ar,tag:best.tagName,cls:String(best.className||'').slice(0,40),sl:best.scrollLeft|0,sw:best.scrollWidth|0,cw:best.clientWidth|0,px:px,py:py,step:step});
                }
                // 3) Poster/card step: scroll next/prev card in this row into view.
                // Works for transform carousels, overflow:hidden tracks, virtual rails.
                // Never scrollIntoView on hero/player stages (moves banner/video).
                function cardStep(dir){
                  var wantNext=dir>0;
                  var sels='a,img,[class*="card"],[class*="poster"],[class*="item"],[class*="tile"],[class*="movie"],[class*="title"],[class*="media"],li,article';
                  var nodes=document.querySelectorAll(sels);
                  var cands=[];
                  for(var i=0;i<nodes.length&&i<500;i++){
                    var n=nodes[i];
                    var r;
                    try{ r=n.getBoundingClientRect(); }catch(e){ continue; }
                    // Skip huge full-bleed heroes and tiny chrome; allow tall poster cards.
                    if(r.width<48||r.height<48||r.width>cssW*0.55||r.height>cssH*0.55) continue;
                    // Same horizontal row as pointer (Y band).
                    if(py<r.top-28||py>r.bottom+28) continue;
                    if(r.height<56&&r.width<56) continue;
                    // Skip only if the card itself is a stage (not merely near HomeBanner in DOM).
                    if(isHeroOrPlayer(n)) continue;
                    cands.push({n:n,r:r,cx:(r.left+r.right)/2});
                  }
                  if(cands.length<2) return false;
                  cands.sort(function(a,b){ return a.cx-b.cx; });
                  var target=null;
                  if(wantNext){
                    for(var j=0;j<cands.length;j++){
                      if(cands[j].r.left>px+12){ target=cands[j]; break; }
                    }
                    if(!target) target=cands[cands.length-1];
                  }else{
                    for(var k=cands.length-1;k>=0;k--){
                      if(cands[k].r.right<px-12){ target=cands[k]; break; }
                    }
                    if(!target) target=cands[0];
                  }
                  if(!target) return false;
                  // Prefer scrolling a parent content rail only — never document/hero stages.
                  var p=target.n.parentElement, moved=false, hops=0;
                  while(p&&hops<12&&!moved){
                    if(isDoc(p)||isHeroOrPlayer(p)){ p=p.parentElement; hops++; continue; }
                    var canRail=isContentRail(p)||/swiper|rail|row|scroller|carousel|slider|movieCard|list/i.test(String(p.className||''));
                    if(canRail&&(p.scrollWidth||0)>(p.clientWidth||0)+2){
                      var before=p.scrollLeft|0;
                      try{
                        var tr=target.n.getBoundingClientRect();
                        var pr=p.getBoundingClientRect();
                        p.scrollLeft=before+(tr.left-pr.left)-Math.max(24,pr.width*0.15);
                      }catch(e){ p.scrollLeft=before+step; }
                      if((p.scrollLeft|0)!==before) moved=true;
                    }
                    // Swiper API on parent row
                    if(!moved){
                      var psw=findSwiper(p);
                      if(psw){
                        try{
                          if(wantNext) psw.slideNext(0); else psw.slidePrev(0);
                          moved=true;
                        }catch(e){}
                      }
                    }
                    p=p.parentElement; hops++;
                  }
                  // scrollIntoView for poster cards — block:nearest keeps hero from yanking.
                  if(!moved){
                    try{
                      target.n.scrollIntoView({behavior:'auto',block:'nearest',inline:wantNext?'start':'end'});
                      moved=true;
                    }catch(e){}
                  }
                  return moved?{tag:target.n.tagName,cls:String(target.n.className||'').slice(0,40),cx:target.cx}:false;
                }
                if(allowCardStep){
                  var cs=cardStep(dx);
                  if(cs) return JSON.stringify({ok:true,via:'cardStep',tag:cs.tag,cls:cs.cls,cx:cs.cx,px:px,py:py,step:step});
                }
                // NEVER auto-click chevrons/buttons while holding L/R — that caused phantom presses.
                // User can aim at › and press OK deliberately.
                return JSON.stringify({ok:false,reason:'no_rail',px:px,py:py,cssW:cssW,cssH:cssH,viewW:viewW,viewH:viewH,hit:el?(el.tagName+'.'+String(el.className||'').slice(0,30)):'null',tried:tried,step:step});
              }catch(e){return JSON.stringify({ok:false,reason:String(e)});}
            })($vx,$vy,$viewW,$viewH,$dx,${if (allowCardStep) "true" else "false"});""",
        ) { raw ->
            val s = unwrap(raw) ?: return@evaluateJavascript
            val logNow = SystemClock.elapsedRealtime()
            if (logNow - lastHScrollLogAt >= 220L) {
                lastHScrollLogAt = logNow
                Log.i("KeenInput", "hscroll dx=$dx view=$vx,$vy result=${s.take(220)}")
            }
            if (s.contains("\"ok\":true")) {
                hScrollMissStreak = 0
                if (s.contains("\"via\":\"cardStep\"")) {
                    lastCardStepAt = logNow
                }
            } else {
                hScrollMissStreak = (hScrollMissStreak + 1).coerceAtMost(40)
            }
        }
    }

    private fun handleSelect(webView: WebView, event: KeyEvent): Boolean {
        val isImeEnter =
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centreHeld = true
                    modeToggledByLongPress = false
                    onUserInput()
                    // IME Done often delivers only a brief ENTER down — collapse immediately.
                    if (isImeEnter) {
                        activate(webView, imeEnter = true)
                        centreHeld = false
                        return true
                    }
                    // Long-press mode switch only on D-pad centre.
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                        scheduleLongPressToggle()
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                cancelLongPressToggle()
                if (isImeEnter) {
                    // Already handled on DOWN.
                    centreHeld = false
                    modeToggledByLongPress = false
                    return true
                }
                if (centreHeld && !modeToggledByLongPress) {
                    activate(webView, imeEnter = false)
                }
                centreHeld = false
                modeToggledByLongPress = false
            }
            else -> return false
        }
        return true
    }

    private fun scheduleLongPressToggle() {
        cancelLongPressToggle()
        // Do not mode-switch during video fullscreen — long OK is often used on player chrome.
        if (mediaPointerLock) return
        // Do not mode-switch while a search-results list is bound (OK is for selecting a row).
        if (modalOwner == ModalOwner.ACTIVE) return
        val r = Runnable {
            if (centreHeld && !modeToggledByLongPress && !mediaPointerLock &&
                modalOwner != ModalOwner.ACTIVE
            ) {
                modeToggledByLongPress = true
                toggleMode()
            }
        }
        longPressRunnable = r
        mainHandler.postDelayed(r, LONG_PRESS_MS)
    }

    /**
     * Force pointer while a video surface is active (HTML fullscreen or Keen playback mode).
     * Prevents accidental DOM focus when activating fullscreen / player controls.
     */
    fun setMediaPointerLock(locked: Boolean) {
        mediaPointerLock = locked
        if (locked) {
            cancelLongPressToggle()
            modeToggledByLongPress = false
            centreHeld = false
            // Always re-assert pointer + cursor for player UI (subs / quality / audio).
            // Cursor layer sits above HTML fullscreen — keep it awake and visible.
            if (mode != Mode.POINTER) {
                setMode(Mode.POINTER)
            } else {
                if (cursor.visibility != android.view.View.VISIBLE) {
                    cursor.showAtCentre()
                } else {
                    cursor.wake()
                }
            }
            cursor.bringToFront()
        }
    }

    fun isMediaPointerLocked(): Boolean = mediaPointerLock

    private fun cancelLongPressToggle() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun activate(webView: WebView, imeEnter: Boolean = false) {
        cursor.wake()
        if (mode == Mode.DOM_FOCUS) {
            webView.evaluateJavascript(INSPECT_JS) { inspectRaw ->
                val fingerprint = unwrap(inspectRaw)
                // Authority before page JS runs (popups must see a live grant).
                onDeliberateActivation?.invoke(fingerprint)
                if (looksLikePlay(fingerprint)) {
                    onPlayLikeActivation?.invoke(fingerprint)
                }
                webView.evaluateJavascript("(${ACTIVATE_JS})()") { result ->
                    if (unwrap(result) == "false") setMode(Mode.POINTER)
                    markIndexDirty("activate")
                }
            }
            return
        }

        // Fullscreen custom-view: click the media surface at shell coordinates (no chrome).
        if (mediaPointerLock) {
            val target = mediaTouchTarget() ?: webView
            val x = cursor.cursorX
            val y = cursor.cursorY
            onDeliberateActivation?.invoke(null)
            onHideKeyboard()
            val down = pointerEvent(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN, x, y)
            val up = pointerEvent(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHSCREEN, x, y)
            try {
                target.dispatchTouchEvent(down)
                target.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
            return
        }

        // Pointer is drawn over full shell; WebView sits under chrome — convert via screen coords.
        val chromeH = chromeHeightPx().coerceAtLeast(0)
        val yShell = cursor.cursorY
        if (yShell <= chromeH + 4f && !imeEnter) {
            onUrlBarActivate()
            return
        }
        val (x, y) = shellToWebViewLocal(webView, cursor.cursorX, yShell)

        // Keyboard Enter/Done: submit + bind modal scroller (do not force-close results).
        if (imeEnter) {
            onImeSubmit(
                webView,
                ImeSubmitSignal(
                    source = "dpad_enter",
                    action = null,
                    baseHandled = false,
                    timestampMs = SystemClock.elapsedRealtime(),
                ),
            )
            return
        }

        // Clean activate: text field → IME; real link → assign; else click under pointer.
        // No scroll freeze / pin (caused viewport fight). No hide-keyboard before pick.
        onDeliberateActivation?.invoke(null)
        val xi = x.toInt()
        val yi = y.toInt()
        val vw = webView.width.coerceAtLeast(1)
        val vh = webView.height.coerceAtLeast(1)
        webView.evaluateJavascript(
            """(function(vx,vy,viewW,viewH){
              try{
                window.__keenNativeIntent=Date.now();
                var cssW=window.innerWidth||document.documentElement.clientWidth||viewW;
                var cssH=window.innerHeight||document.documentElement.clientHeight||viewH;
                var x=vx*(cssW/viewW);
                var y=vy*(cssH/viewH);
                function goodHref(h){
                  if(!h) return false;
                  h=String(h).trim();
                  if(!h||h==='#'||h==='/'||h.indexOf('javascript:')===0) return false;
                  if(h.charAt(0)==='#') return false;
                  return true;
                }
                function isContentHref(h){ return ${ActivateHitTest.CONTENT_HREF_JS_REGEX}.test(h||''); }
                function isHttpHref(h){ return /^https?:/i.test(h||''); }
                function hrefOf(el){
                  try{
                    return el.href||(el.getAttribute&&(el.getAttribute('href')||el.getAttribute('data-href')||el.getAttribute('data-link')))||'';
                  }catch(e){ return ''; }
                }
                function isTextField(el){
                  if(!el) return false;
                  var tag=(el.tagName||'').toUpperCase();
                  if(tag==='TEXTAREA') return true;
                  if(tag==='INPUT'){
                    var ty=(el.type||'text').toLowerCase();
                    return !(ty==='button'||ty==='submit'||ty==='checkbox'||ty==='radio'||ty==='file'||ty==='hidden'||ty==='image'||ty==='reset'||ty==='range'||ty==='color');
                  }
                  if(el.isContentEditable) return true;
                  var role=(el.getAttribute&&el.getAttribute('role')||'').toLowerCase();
                  return role==='searchbox'||role==='combobox'||role==='textbox';
                }
                function stackAt(px,py){
                  var s=[];
                  try{ if(document.elementsFromPoint) s=document.elementsFromPoint(px,py)||[]; }catch(e){}
                  if(!s.length){ var o=document.elementFromPoint(px,py); if(o) s=[o]; }
                  return s;
                }
                // 1) Text / search field ONLY if pointer is actually on that field (or its label).
                // Do NOT steal movie-card OKs in a results modal just because a search input is open.
                var st=stackAt(x,y);
                for(var i=0;i<st.length&&i<20;i++){
                  var n=st[i], hops=0;
                  while(n&&hops<8){
                    if(isTextField(n)){
                      var trf=n.getBoundingClientRect();
                      // Must be on the field box itself (small pad), not "anywhere near the modal".
                      if(x>=trf.left-6&&x<=trf.right+6&&y>=trf.top-6&&y<=trf.bottom+6&&trf.width>=12&&trf.height>=12){
                        try{ n.focus({preventScroll:true}); }catch(e){ try{ n.focus(); }catch(e2){} }
                        return JSON.stringify({ok:true,method:'focus_input',play:false,href:'',
                          text:(n.getAttribute('placeholder')||n.id||'search').slice(0,40),
                          tag:n.tagName||'',x:x,y:y});
                      }
                    }
                    if(n.tagName==='LABEL'&&n.control&&isTextField(n.control)){
                      var lr=n.getBoundingClientRect();
                      if(x>=lr.left-6&&x<=lr.right+6&&y>=lr.top-6&&y<=lr.bottom+6){
                        try{ n.control.focus({preventScroll:true}); }catch(e){ try{ n.control.focus(); }catch(e2){} }
                        return JSON.stringify({ok:true,method:'focus_input',play:false,href:'',
                          text:(n.control.getAttribute('placeholder')||'search').slice(0,40),
                          tag:n.control.tagName||'',x:x,y:y});
                      }
                    }
                    n=n.parentElement; hops++;
                  }
                }

                // 2) Anchor containing pointer (smallest A with real href).
                var bestA=null, bestAArea=1e15;
                for(var j=0;j<st.length&&j<20;j++){
                  var p=st[j], h=0;
                  while(p&&h<12){
                    if(p.tagName==='A'){
                      var hh=hrefOf(p);
                      if(goodHref(hh)){
                        var r=p.getBoundingClientRect();
                        var ar=Math.max(0,r.width)*Math.max(0,r.height);
                        // Skip full-viewport fake links
                        if(r.width>cssW*0.9&&r.height>cssH*0.9){ p=p.parentElement; h++; continue; }
                        var score=ar-(isContentHref(hh)?1e9:0);
                        if(score<bestAArea){ bestAArea=score; bestA=p; }
                      }
                    }
                    p=p.parentElement; h++;
                  }
                }
                if(bestA){
                  var go=hrefOf(bestA);
                  try{ var aa=document.createElement('a'); aa.href=go; go=aa.href; }catch(e){}
                  if(bestA.target==='_blank') bestA.target='_self';
                  var same=false;
                  try{ var a3=document.createElement('a'); a3.href=go; same=a3.hostname===location.hostname; }catch(e){}
                  var pathCh=true;
                  try{ var a4=document.createElement('a'); a4.href=go; pathCh=a4.pathname!==location.pathname||a4.search!==location.search; }catch(e){}
                  if(isHttpHref(go)&&(isContentHref(go)||!same||pathCh)){
                    try{ location.assign(go); }catch(e){ try{ location.href=go; }catch(e2){} }
                    var br=bestA.getBoundingClientRect();
                    return JSON.stringify({ok:true,method:'location.assign',play:false,href:String(go).slice(0,160),
                      text:(bestA.innerText||'').trim().slice(0,40),tag:'A',x:x,y:y,
                      box:[br.left|0,br.top|0,br.width|0,br.height|0]});
                  }
                }

                // 3) Content link whose box contains the pointer (poster <a> under img).
                try{
                  var cl=document.querySelectorAll('a[href]');
                  var bestC=null, bestCArea=1e15;
                  for(var ci=0;ci<cl.length&&ci<300;ci++){
                    var cla=cl[ci];
                    var chref=hrefOf(cla);
                    if(!goodHref(chref)||!isContentHref(chref)) continue;
                    var cr=cla.getBoundingClientRect();
                    if(cr.width<20||cr.height<20||cr.width>cssW*0.55) continue;
                    if(x<cr.left-6||x>cr.right+6||y<cr.top-6||y>cr.bottom+6) continue;
                    var ca=cr.width*cr.height;
                    if(ca<bestCArea){ bestCArea=ca; bestC=cla; }
                  }
                  if(bestC){
                    var g2=hrefOf(bestC);
                    try{ var ab=document.createElement('a'); ab.href=g2; g2=ab.href; }catch(e){}
                    try{ location.assign(g2); }catch(e){ try{ location.href=g2; }catch(e2){} }
                    var cr2=bestC.getBoundingClientRect();
                    return JSON.stringify({ok:true,method:'location.assign',play:false,href:String(g2).slice(0,160),
                      text:(bestC.innerText||bestC.getAttribute('aria-label')||'').trim().slice(0,40),
                      tag:'A',x:x,y:y,box:[cr2.left|0,cr2.top|0,cr2.width|0,cr2.height|0]});
                  }
                }catch(e){}

                // 4) Rail chevron / next-prev under pointer — before card steal.
                // Cineby row › arrows often ignore synthetic click; need Swiper API + trusted touch.
                function chevronOf(start){
                  var cn=start, hh=0;
                  while(cn&&hh<10){
                    try{
                      var ccls=String(cn.className||'')+' '+
                        (cn.getAttribute&&(cn.getAttribute('aria-label')||cn.getAttribute('title')||cn.getAttribute('class')||'')||'');
                      var crole=(cn.getAttribute&&cn.getAttribute('role')||'').toLowerCase();
                      var ctag=(cn.tagName||'').toUpperCase();
                      if(/swiper-button-next|swiper-button-prev|slick-next|slick-prev|carousel-next|carousel-prev|carousel-control|chevron|arrow-right|arrow-left|slide-next|slide-prev|nav-next|nav-prev|keen-chevron/i.test(ccls)) return cn;
                      var al=(cn.getAttribute&&(cn.getAttribute('aria-label')||cn.getAttribute('title'))||'');
                      if(/^(next|previous|prev|forward|back)$/i.test(al.trim())||/next slide|previous slide|scroll right|scroll left/i.test(al)) return cn;
                      if((ctag==='BUTTON'||crole==='button'||ctag==='A') && cn.querySelector){
                        var hasSvg=!!cn.querySelector('svg,path,polyline,i,[class*="icon"],[class*="Icon"]');
                        var txt=(cn.innerText||cn.textContent||'').trim();
                        var br=cn.getBoundingClientRect();
                        if(hasSvg&&!txt&&br.width>=16&&br.width<=80&&br.height>=16&&br.height<=80){
                          // Edge-of-row chevrons sit near L/R of viewport or rail.
                          var nearEdge=br.left<cssW*0.14||br.right>cssW*0.86;
                          if(nearEdge||/next|prev|arrow|chevron|swiper-button/i.test(ccls)) return cn;
                        }
                        // Common glyph-only chevrons: ‹ › ◀ ▶
                        if(/^[‹›<>〈〉◀▶◁▷«»]$/.test(txt)&&br.width<=80&&br.height<=80) return cn;
                      }
                      // Div-based swiper buttons
                      if(/swiper-button/i.test(ccls)) return cn;
                    }catch(e){}
                    cn=cn.parentElement; hh++;
                  }
                  return null;
                }
                var el0=st[0]||document.elementFromPoint(x,y);
                var ch=chevronOf(el0);
                if(ch){
                  var dir=1;
                  try{
                    var chr=ch.getBoundingClientRect();
                    var chcls=String(ch.className||'')+(ch.getAttribute&&(ch.getAttribute('aria-label')||ch.getAttribute('title')||'')||'');
                    if(chr.left+chr.width/2<cssW*0.5) dir=-1;
                    if(/prev|left|back|swiper-button-prev|slick-prev/i.test(chcls)) dir=-1;
                    if(/next|right|swiper-button-next|slick-next/i.test(chcls)) dir=1;
                  }catch(e){}
                  var via='chevronClick';
                  try{
                    var host=ch.closest&&ch.closest('.swiper,.swiper-container,[class*="swiper"],[class*="carousel"],[class*="slider"]');
                    var sw=null;
                    if(host){ sw=host.swiper||host.__swiper__||null; }
                    if(!sw){
                      // Row host near the chevron Y band
                      var cand=document.querySelectorAll('.swiper,.swiper-container,[class*="swiper-initialized"]');
                      for(var si=0;si<cand.length&&si<30;si++){
                        var sh=cand[si];
                        var sr=sh.getBoundingClientRect();
                        if(sr.height<40||sr.width<80) continue;
                        if(y<sr.top-24||y>sr.bottom+24) continue;
                        if(sh.swiper||sh.__swiper__){ sw=sh.swiper||sh.__swiper__; host=sh; break; }
                      }
                    }
                    if(sw){
                      try{
                        if(dir>0&&typeof sw.slideNext==='function'){ sw.slideNext(); via='chevronSwiperNext'; }
                        else if(dir<0&&typeof sw.slidePrev==='function'){ sw.slidePrev(); via='chevronSwiperPrev'; }
                      }catch(e){}
                    }
                  }catch(e){}
                  try{
                    ch.dispatchEvent(new MouseEvent('pointerdown',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0,pointerType:'touch',isPrimary:true}));
                    ch.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0}));
                    ch.dispatchEvent(new MouseEvent('pointerup',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0,pointerType:'touch',isPrimary:true}));
                    ch.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0}));
                    ch.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0}));
                  }catch(e){}
                  try{ if(typeof ch.click==='function') ch.click(); }catch(e){}
                  var crb=ch.getBoundingClientRect?ch.getBoundingClientRect():{left:0,top:0,width:0,height:0};
                  // Always request trusted touch — synthetic events alone often no-op on row arrows.
                  return JSON.stringify({ok:true,method:via,play:false,href:'',needTouch:true,dir:dir,
                    text:(ch.innerText||ch.getAttribute('aria-label')||'chevron').trim().slice(0,40),
                    tag:ch.tagName||'',cls:String(ch.className||'').slice(0,40),x:x,y:y,
                    box:[crb.left|0,crb.top|0,crb.width|0,crb.height|0]});
                }

                // 5) Plain click on topmost element under pointer (SPA cards / player chrome).
                var el=el0;
                if(!el||el===document.documentElement||el===document.body){
                  return JSON.stringify({ok:false,reason:'no_el',x:x,y:y,needTouch:true});
                }
                // Video player UI (Cineby etc.): icon buttons ignore synthetic click — need trusted touch.
                // Timeline/seek already works via click; trusted touch is also safe there.
                function hasMedia(){
                  try{ return !!document.querySelector('video,audio'); }catch(e){ return false; }
                }
                function inPlayerUi(){
                  if(!hasMedia()) return false;
                  try{
                    // Bottom control deck (time bar + icon row).
                    if(y>cssH*0.52) return true;
                    var v=document.querySelector('video');
                    if(v){
                      var vr=v.getBoundingClientRect();
                      if(vr.height>48&&x>=vr.left-12&&x<=vr.right+12&&y>=vr.top-12&&y<=vr.bottom+56) return true;
                    }
                    // Explicit player chrome classes.
                    var p=el, ph=0;
                    while(p&&ph<8){
                      var pc=String(p.className||'');
                      if(/player|plyr|jwplayer|video-js|vjs-|media-control|control-bar|bottom-0 left-0 right-0/i.test(pc)) return true;
                      p=p.parentElement; ph++;
                    }
                  }catch(e){}
                  return false;
                }
                function iconControlOf(start){
                  var n=start, h=0;
                  while(n&&h<8){
                    try{
                      var tag=(n.tagName||'').toUpperCase();
                      var cls=String(n.className||'');
                      var role=(n.getAttribute&&n.getAttribute('role')||'').toLowerCase();
                      var txt=(n.innerText||n.textContent||'').trim();
                      var al=(n.getAttribute&&(n.getAttribute('aria-label')||n.getAttribute('title'))||'');
                      if(tag==='VIDEO'||tag==='AUDIO') return n;
                      if(tag==='INPUT'){
                        var ty=(n.type||'').toLowerCase();
                        if(ty==='range'||ty==='checkbox'||ty==='button'||ty==='submit') return n;
                      }
                      if(tag==='BUTTON'||role==='button'){
                        if(/tabbable|rounded-full|player|control|vjs-|plyr|jwplayer|icon-btn|btn-icon/i.test(cls)) return n;
                        if(/play|pause|mute|volume|fullscreen|settings|subtitle|caption|pip|cast|seek|skip/i.test(al+txt+cls)) return n;
                        if(n.querySelector&&n.querySelector('svg,path,i,[class*="icon"]')&&txt.length<4){
                          var br=n.getBoundingClientRect();
                          if(br.width>=14&&br.width<=100&&br.height>=14&&br.height<=100) return n;
                        }
                      }
                    }catch(e){}
                    n=n.parentElement; h++;
                  }
                  return null;
                }
                var playerUi=inPlayerUi();
                var icon=iconControlOf(el);
                var t=el;
                if(icon){
                  t=icon;
                }else if(playerUi){
                  // Prefer real controls from the hit stack — never steal a movie card under the player.
                  for(var si=0;si<st.length&&si<16;si++){
                    var sn=st[si];
                    var stg=(sn.tagName||'').toUpperCase();
                    var srole=(sn.getAttribute&&sn.getAttribute('role')||'').toLowerCase();
                    if(stg==='BUTTON'||srole==='button'||stg==='INPUT'||stg==='VIDEO'||stg==='AUDIO'){ t=sn; break; }
                    if(stg==='A'&&goodHref(hrefOf(sn))){ t=sn; break; }
                  }
                }else{
                  // Browse: prefer card/button under point for SPA.
                  try{
                    var card=el.closest&&el.closest('a,button,[role="button"],[class*="card"],[class*="poster"],[class*="movie"]');
                    if(card) t=card;
                  }catch(e){}
                }
                if(t.tagName==='A'&&t.target==='_blank') t.target='_self';
                try{
                  t.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0,pointerType:'touch',pointerId:1,isPrimary:true,pressure:0.5}));
                }catch(e){
                  try{ t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0})); }catch(e2){}
                }
                try{
                  t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0}));
                  t.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0}));
                  t.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0}));
                }catch(e){}
                try{
                  t.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0,pointerType:'touch',pointerId:1,isPrimary:true,pressure:0}));
                }catch(e){}
                try{ if(typeof t.click==='function') t.click(); }catch(e){}
                var tr=t.getBoundingClientRect?t.getBoundingClientRect():{left:0,top:0,width:0,height:0};
                var needTouch=!!(playerUi||icon||(t.tagName==='BUTTON')||(t.tagName==='VIDEO')||(t.tagName==='AUDIO')||
                  /tabbable|rounded-full|player|control-bar/i.test(String(t.className||'')));
                var method=needTouch?(playerUi||icon?'playerControl':'clickTouch'):'click';
                return JSON.stringify({ok:true,method:method,play:false,href:String(hrefOf(t)||'').slice(0,160),
                  needTouch:needTouch,playerUi:!!playerUi,
                  text:(t.innerText||t.getAttribute('aria-label')||'').trim().slice(0,40),
                  tag:t.tagName||'',cls:String(t.className||'').slice(0,40),x:x,y:y,
                  box:[tr.left|0,tr.top|0,tr.width|0,tr.height|0]});
              }catch(e){ return JSON.stringify({ok:false,reason:String(e),needTouch:true}); }
            })($xi,$yi,$vw,$vh);""",
        ) { raw ->
            val s = unwrap(raw)
            // Keep ActivateHitTest referenced (R8) + diagnostics.
            val policyNote = try {
                if (!s.isNullOrBlank() && s.startsWith("{")) {
                    val o = org.json.JSONObject(s)
                    val href = o.optString("href", "")
                    "good=${ActivateHitTest.isGoodHref(href)} content=${ActivateHitTest.isContentHref(href)} " +
                        "http=${ActivateHitTest.isHttpHref(href)} method=${o.optString("method")}"
                } else {
                    "no_json"
                }
            } catch (_: Exception) {
                "parse_err"
            }
            Log.i(
                "KeenInput",
                "activate shell=${cursor.cursorX.toInt()},${cursor.cursorY.toInt()} local=$xi,$yi " +
                    "wv=${webView.width}x${webView.height} policy=[$policyNote] result=${s?.take(280)}",
            )
            val focusInput = s != null && s.contains("\"method\":\"focus_input\"")
            if (focusInput) {
                keyboardLikelyVisible = true
                onShowKeyboard()
            } else if (s != null && (
                    s.contains("location.assign") ||
                        (s.contains("\"method\":\"click\"") && !keyboardLikelyVisible)
                    )
            ) {
                onHideKeyboard()
                keyboardLikelyVisible = false
            }
            if (looksLikePlay(s)) {
                onPlayLikeActivation?.invoke(s)
            }
            if (!s.isNullOrBlank() && s.startsWith("{")) {
                onDeliberateActivation?.invoke(s)
            }
            val needTouch = !focusInput && (
                s.isNullOrBlank() ||
                    s.contains("\"needTouch\":true") ||
                    s.contains("\"needTouch\": true") ||
                    s.contains("no_el") ||
                    s.contains("chevronClick") ||
                    s.contains("chevronSwiper") ||
                    s.contains("playerControl") ||
                    s.contains("clickTouch") ||
                    // Player icon buttons / chrome from prior sessions always need a real touch.
                    (s.contains("\"method\":\"click\"") && (
                        s.contains("tabbable") ||
                            s.contains("rounded-full") ||
                            s.contains("\"tag\":\"BUTTON\"") ||
                            s.contains("\"tag\":\"VIDEO\"")
                        ))
                )
            if (needTouch) {
                webView.evaluateJavascript("window.__keenNativeIntent=Date.now();", null)
                // Slight press duration helps sites that gate on touch hold / pointer capture.
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                val up = MotionEvent.obtain(
                    downTime,
                    downTime + 48L,
                    MotionEvent.ACTION_UP,
                    x,
                    y,
                    0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    webView.dispatchTouchEvent(down)
                    webView.postDelayed({
                        try {
                            webView.dispatchTouchEvent(up)
                        } finally {
                            up.recycle()
                        }
                    }, 48L)
                } catch (_: Throwable) {
                    try {
                        webView.dispatchTouchEvent(up)
                    } catch (_: Throwable) {
                    }
                    up.recycle()
                } finally {
                    down.recycle()
                }
                Log.i(
                    "KeenInput",
                    "activate trusted_touch local=$xi,$yi reason=${
                        when {
                            s?.contains("playerControl") == true -> "playerControl"
                            s?.contains("chevron") == true -> "chevron"
                            s?.contains("tabbable") == true -> "tabbable"
                            s?.contains("BUTTON") == true -> "button"
                            s?.contains("needTouch") == true -> "needTouch"
                            else -> "fallback"
                        }
                    }",
                )
            }
        }
    }

    /** Convert cursor-overlay coordinates into WebView local pixels (screen-absolute). */
    private fun shellToWebViewLocal(webView: WebView, shellX: Float, shellY: Float): Pair<Float, Float> {
        val cursorLoc = IntArray(2)
        val wvLoc = IntArray(2)
        try {
            cursor.getLocationOnScreen(cursorLoc)
            webView.getLocationOnScreen(wvLoc)
        } catch (_: Throwable) {
            val chromeH = chromeHeightPx().coerceAtLeast(0).toFloat()
            return shellX to (shellY - chromeH).coerceAtLeast(0f)
        }
        val screenX = cursorLoc[0] + shellX
        val screenY = cursorLoc[1] + shellY
        val localX = (screenX - wvLoc[0]).coerceIn(0f, (webView.width - 1).coerceAtLeast(0).toFloat())
        val localY = (screenY - wvLoc[1]).coerceIn(0f, (webView.height - 1).coerceAtLeast(0).toFloat())
        return localX to localY
    }

    private fun looksLikePlay(fingerprintJson: String?): Boolean {
        if (fingerprintJson.isNullOrBlank()) return false
        return try {
            org.json.JSONObject(fingerprintJson).optBoolean("play", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun unwrap(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            try {
                org.json.JSONObject("{\"v\":$raw}").getString("v")
            } catch (_: Exception) {
                raw.trim('"')
            }
        } else {
            raw
        }
    }

    private fun pointerEvent(
        action: Int,
        eventSource: Int,
        x: Float,
        y: Float,
    ): MotionEvent = MotionEvent.obtain(
        SystemClock.uptimeMillis(),
        SystemClock.uptimeMillis(),
        action,
        x,
        y,
        0,
    ).apply { source = eventSource }

    private fun toggleMode() {
        if (mediaPointerLock) {
            // Refuse DOM while video UI needs the pointer.
            if (mode != Mode.POINTER) setMode(Mode.POINTER)
            return
        }
        // Refuse DOM while results list is open — selection needs the pointer cursor.
        if (modalOwner == ModalOwner.ACTIVE) {
            if (mode != Mode.POINTER) setMode(Mode.POINTER)
            return
        }
        setMode(if (mode == Mode.DOM_FOCUS) Mode.POINTER else Mode.DOM_FOCUS)
    }

    private fun setMode(next: Mode) {
        val target = if (mediaPointerLock && next == Mode.DOM_FOCUS) Mode.POINTER else next
        if (mode == target) {
            if (target == Mode.POINTER) {
                if (cursor.visibility != android.view.View.VISIBLE) {
                    cursor.showAtCentre()
                } else {
                    cursor.wake()
                }
            }
            return
        }
        // Clear continuous motion when leaving pointer.
        holdLeft = false
        holdRight = false
        holdUp = false
        holdDown = false
        velX = 0f
        velY = 0f
        holdStartedAt = 0L
        endPageDrag()
        stopFrameLoop()
        mode = target
        if (target == Mode.POINTER) {
            if (cursor.visibility != View.VISIBLE || (cursor.cursorX == 0f && cursor.cursorY == 0f)) {
                cursor.showAtCentre()
            } else {
                cursor.wake()
            }
        } else {
            cursor.hide()
            indexDirty = true
        }
        onModeChanged(if (target == Mode.POINTER) "Pointer" else "DOM")
    }

    private enum class Mode { DOM_FOCUS, POINTER }

    private enum class ModalOwner { NONE, BINDING, ACTIVE }

    private companion object {
        val SELECT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        const val LONG_PRESS_MS = 450L
        const val REBUILD_DEBOUNCE_MS = 280L
        const val TRACE_TAG = "KeenInput"
        /** Continuous pointer speed (dp/sec). Tuned for TV D-pad — smooth but not rushed. */
        const val SPEED_MIN_DP = 120f
        const val SPEED_MAX_DP = 480f
        const val ACCEL_MS = 520f
        /** Edge-scroll speed while D-pad held in an edge zone (dp/sec). */
        const val EDGE_SCROLL_DP = 520f
        /** Edge band where page/rail scroll engages (dp). Wider = easier TV edge aim. */
        const val EDGE_ZONE_DP = 110f
        /** DOM single-step scroll as fraction of viewport. */
        const val DOM_SCROLL_FRACTION = 0.26f
        /** Lost key-up failsafe — never leave modal rAF running indefinitely. */
        const val MAX_HOLD_FAILSAFE_MS = 5_000L
        /** After IME / bind, vertical prefers modal steal for this long. */
        const val MODAL_LIKELY_WINDOW_MS = 12_000L
        /** Hold vertical this long while results are open → list scroll (tap = aim). */
        const val HOLD_TO_LIST_SCROLL_MS = 280L
        /** Tags that must never own modal vertical (kills Up/Down). */
        val JUNK_MODAL_TAGS = setOf(
            "KBD", "BUTTON", "SPAN", "A", "INPUT", "LABEL", "OPTION",
            "SELECT", "TEXTAREA", "SVG", "PATH", "IMG", "I", "EM", "STRONG",
            "CODE", "HEADER", "NAV",
        )

        val BLUR_ACTIVE_INPUT_JS = """
            (function(){
              try{
                var a=document.activeElement;
                if(!a||a===document.body) return;
                var tag=(a.tagName||'').toUpperCase();
                var role=(a.getAttribute&&a.getAttribute('role')||'').toLowerCase();
                if(tag==='INPUT'||tag==='TEXTAREA'||a.isContentEditable||role==='searchbox'||role==='combobox'||role==='textbox'){
                  a.blur();
                }
              }catch(e){}
            })();
        """.trimIndent()

        // Clear scroll locks left by dismissed modals only.
        // NEVER force position:static on html/body — that breaks sticky site nav
        // (fmhy header/search reflow) and yanks the viewport to the top.
        val UNLOCK_SCROLL_JS = """
            (function(){
              try{
                var y=window.scrollY||window.pageYOffset||0;
                var x=window.scrollX||window.pageXOffset||0;
                var b=document.body, d=document.documentElement;
                if(b){
                  var bo=getComputedStyle(b).overflow;
                  if(bo==='hidden'||bo==='clip') b.style.overflow='';
                }
                if(d){
                  var dox=getComputedStyle(d).overflow;
                  if(dox==='hidden'||dox==='clip') d.style.overflow='';
                }
                if(window.__keenModalScroll&&window.__keenModalScroll.clear) window.__keenModalScroll.clear();
                if(Math.abs((window.scrollY||0)-y)>8) window.scrollTo(x,y);
              }catch(e){}
            })();
        """.trimIndent()

        val INSPECT_JS = """
          (function(){
            const el=document.activeElement;
            if(!el||el===document.body) return null;
            const t=(el.innerText||el.getAttribute('aria-label')||el.value||el.id||'').trim().slice(0,80);
            const role=el.getAttribute('role')||el.tagName;
            const r=el.getBoundingClientRect();
            const play=el.dataset.keenPlay==='1'||el.id==='real-play'||/(^|\s)play(\s|$)|▶|watch now|start watching/i.test(t)
              || (role==='button' && /play/i.test(t));
            const fake=el.dataset.keenFakePlay==='1'||el.classList.contains('fake-play');
            return JSON.stringify({
              play: play && !fake,
              fake: !!fake,
              role: role,
              text: t,
              id: el.id||'',
              href: el.href||el.getAttribute('href')||'',
              fp: (el.tagName+'#'+(el.id||'')+'.'+(String(el.className||'').replace(/\s+/g,'.'))).slice(0,160),
              geometry: Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+'x'+Math.round(r.height),
              contentId: el.dataset.contentId||window.__keenContentId||null
            });
          })();
        """.trimIndent()

        val ACTIVATE_JS = """
          function(){
            var current=document.activeElement;
            if(!current||current===document.body) return false;
            window.__keenNativeIntent=Date.now();
            if(current.tagName==='A' && current.target==='_blank') current.target='_self';
            if(current.form && current.form.target==='_blank') current.form.target='_self';
            current.click(); return true;
          }
        """.trimIndent().replace("\n", " ")

        val LEGACY_MOVE_FALLBACK = """
          function(command) {
            const selector='a[href],button,input,select,textarea,[tabindex],[role=button],[role=link],[onclick],video,audio,[data-keen-focus]';
            const usable=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>2&&r.height>2&&s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0'&&!e.disabled};
            const nodes=[...document.querySelectorAll(selector)].filter(usable).slice(0,256);
            if (!nodes.length) return false;
            let current=document.activeElement;
            if (!nodes.includes(current)) current=nodes[0];
            if(command==='left'||command==='right'){
              const rail=current.closest && current.closest('[data-keen-rail],.rail,.row');
              if(rail){
                const items=[...rail.querySelectorAll(selector)].filter(usable);
                const idx=items.indexOf(current);
                if(idx>=0){
                  const next=command==='right'?items[idx+1]:items[idx-1];
                  if(next){ if(!next.hasAttribute('tabindex')) next.setAttribute('tabindex','-1'); next.scrollIntoView({block:'nearest',inline:'nearest',behavior:'auto'}); next.focus({preventScroll:true}); return true; }
                }
              }
            }
            const a=current.getBoundingClientRect(); const cx=a.left+a.width/2,cy=a.top+a.height/2;
            const vh=window.innerHeight||0;
            const scored=nodes.filter(n=>n!==current).map(n=>{const r=n.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
              const valid=(command==='left'&&x<cx)||(command==='right'&&x>cx)||(command==='up'&&y<cy)||(command==='down'&&y>cy);
              const off=r.bottom<0||r.top>vh;
              return {n,valid,d:(command==='left'||command==='right'?Math.abs(x-cx)*3+Math.abs(y-cy):Math.abs(y-cy)*3+Math.abs(x-cx))+(off?400:0)};
            }).filter(x=>x.valid).sort((a,b)=>a.d-b.d);
            if(!scored.length) return false;
            const next=scored[0].n;
            if(!next.hasAttribute('tabindex')) next.setAttribute('tabindex','-1');
            next.scrollIntoView({block:'nearest',inline:'nearest',behavior:'auto'});
            next.focus({preventScroll:true});
            return true;
          }
        """.trimIndent().replace("\n", " ")
    }
}
