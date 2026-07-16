package com.keenzero.app.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Remote navigation for D-pad remotes.
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
    /** Hide soft keyboard (search Enter / commit). */
    private val onHideKeyboard: () -> Unit = {},
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

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (mode != Mode.POINTER || !hasDirectionHeld()) {
                frameCallbackRegistered = false
                lastFrameNs = 0L
                return
            }
            val wv = boundWebView
            if (wv == null) {
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

        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_MENU ||
                event.keyCode == KeyEvent.KEYCODE_INFO ||
                event.keyCode == KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU)
        ) {
            onUserInput()
            // Menu still allowed to toggle only when not locked to media pointer.
            if (!mediaPointerLock) toggleMode()
            return true
        }
        if (event.keyCode in SELECT_KEYS) return handleSelect(webView, event)

        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> return false
        }

        if (mode == Mode.POINTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        onUserInput()
                        // After long idle, key-up may have been lost — reset stale holds
                        // for this axis before engaging the new press.
                        clearStaleHoldsIfNeeded()
                        setDirectionHeld(dir, true)
                    }
                    // Always (re)start the frame loop — recovers after onPause/idle stall
                    // when frameCallbackRegistered was left true without callbacks.
                    ensureFrameLoop(force = true)
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    setDirectionHeld(dir, false)
                    if (!hasDirectionHeld()) {
                        stopFrameLoop()
                        velX = 0f
                        velY = 0f
                    }
                    return true
                }
                else -> return true
            }
        }

        // DOM path only needs DOWN.
        if (event.action != KeyEvent.ACTION_DOWN) return false
        onUserInput()
        if (indexDirty || interactionIndex.size == 0) {
            requestRebuild(webView, force = false)
            webView.evaluateJavascript("(${LEGACY_MOVE_FALLBACK})('$dir')") { result ->
                if (unwrap(result) == "false") setMode(Mode.POINTER)
                markIndexDirty("after_fallback_move")
            }
        } else {
            val next = interactionIndex.select(dir)
            if (next != null) {
                val id = next.id.replace("'", "\\'")
                webView.evaluateJavascript("(${InteractionIndex.FOCUS_JS})('$id')") {
                    markIndexDirty("after_focus_scroll")
                    requestRebuild(webView, force = false)
                }
            } else {
                webView.evaluateJavascript("(${SCROLL_EDGE_JS})('$dir')") {
                    markIndexDirty("scroll")
                    requestRebuild(webView, force = false)
                }
            }
        }
        return true
    }

    private fun setDirectionHeld(dir: String, held: Boolean) {
        when (dir) {
            "left" -> holdLeft = held
            "right" -> holdRight = held
            "up" -> holdUp = held
            "down" -> holdDown = held
        }
        if (held && !hasDirectionHeld()) {
            // was empty before this set — shouldn't happen
        }
        if (held && (holdLeft || holdRight || holdUp || holdDown)) {
            if (holdStartedAt == 0L) holdStartedAt = SystemClock.elapsedRealtime()
        }
        if (!hasDirectionHeld()) {
            holdStartedAt = 0L
        } else if (held && holdStartedAt == 0L) {
            holdStartedAt = SystemClock.elapsedRealtime()
        }
        recomputeVelocity()
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
        val nx = cursor.cursorX + velX * dt
        val ny = cursor.cursorY + velY * dt
        cursor.setPosition(nx, ny)

        val chromeH = chromeHeightPx().coerceAtLeast(0).toFloat()
        // Pointer coords are full-shell; WebView content is below the chrome.
        val pageX = cursor.cursorX
        val pageY = (cursor.cursorY - chromeH).coerceAtLeast(0f)
        if (cursor.cursorY < chromeH - 2f) {
            // Cursor in URL chrome: native WebView scroll only (no dual JS path).
            if (holdUp) {
                val scrollPx = (EDGE_SCROLL_DP * density * dt).toInt().coerceAtLeast(1)
                webView.scrollBy(0, -scrollPx)
            }
            return
        }

        // Generous edge so TV D-pad reaches page-scroll zone without pixel hunting.
        val edge = 96f * density
        val scrollPx = (EDGE_SCROLL_DP * density * dt).toInt().coerceAtLeast(1)
        val (vw, vh) = cursor.viewportSize()
        if (vw <= 0 || vh <= 0) return
        val pageBottom = vh.toFloat()
        val inTopZone = cursor.cursorY <= chromeH + edge
        val inBottomZone = cursor.cursorY >= pageBottom - edge
        val inLeftZone = cursor.cursorX <= edge
        val inRightZone = cursor.cursorX >= vw - edge

        // Horizontal: Netflix-style rails under pointer mid-page; page scroll only at L/R edges.
        if (holdLeft || holdRight) {
            val dx = if (holdLeft) -scrollPx else scrollPx
            if (inLeftZone || inRightZone) {
                webView.scrollBy(dx, 0)
            } else {
                scrollUnderPointer(webView, pageX, pageY, dx, 0, forceWindow = false)
            }
        }
        // Vertical — simple rules:
        // 1) Nested scroller under the pointer (rails / in-page lists)
        // 2) Search/modal sheet list (only if sheet has a text input + list)
        // 3) Else at screen edge → page scroll (native + document)
        if (holdUp || holdDown) {
            val dy = if (holdUp) -scrollPx else scrollPx
            val atPageEdge = (holdUp && inTopZone) || (holdDown && inBottomZone)
            scrollVertical(webView, pageX, pageY, dy, atPageEdge)
        }
    }

    /** Suppress page edge-scroll only right after a search-sheet list actually moved. */
    private var suppressPageScrollUntil = 0L
    private var lastDocScrollAt = 0L

    private fun scrollVertical(
        webView: WebView,
        pageX: Float,
        pageY: Float,
        dy: Int,
        atPageEdge: Boolean,
    ) {
        if (dy == 0) return
        val now = SystemClock.elapsedRealtime()
        val x = pageX.toInt()
        val y = pageY.toInt()

        val runJs = now - lastDocScrollAt >= 14L
        if (runJs) {
            lastDocScrollAt = now
            webView.evaluateJavascript(
                """(function(px,py,dy,edge){
                  try{
                    function isDoc(n){
                      return !n||n===document||n===document.scrollingElement||
                        n===document.documentElement||n===document.body;
                    }
                    function hit(n){
                      if(!n||!n.getBoundingClientRect) return false;
                      var r=n.getBoundingClientRect();
                      return px>=r.left-2&&px<=r.right+2&&py>=r.top-2&&py<=r.bottom+2;
                    }
                    function oy(n){
                      try{var s=getComputedStyle(n);return (s.overflowY||s.overflow||'').toLowerCase();}
                      catch(e){return '';}
                    }
                    function applyY(n,a){
                      if(isDoc(n)||!a) return false;
                      var b=n.scrollTop|0;
                      n.scrollTop=b+a;
                      if((n.scrollTop|0)!==b) return true;
                      try{if(n.scrollBy)n.scrollBy(0,a);}catch(e){}
                      return (n.scrollTop|0)!==b;
                    }
                    function nestedY(n){
                      if(isDoc(n)||!hit(n)||!(n.scrollHeight>n.clientHeight+2)) return false;
                      var o=oy(n);
                      if(o==='auto'||o==='scroll'||o==='overlay') return true;
                      if(/overflow-y-auto|overflow-y-scroll|overscroll-contain|overflow-auto/.test(String(n.className||''))) return true;
                      return o==='hidden'&&n.scrollHeight>n.clientHeight+8;
                    }
                    // 1) under pointer
                    var el=document.elementFromPoint(px,py), p=el, h=0;
                    while(p&&h<32){
                      if(nestedY(p)&&applyY(p,dy)) return 'nested';
                      p=p.parentElement; h++;
                    }
                    // 2) Search sheet ONLY: fixed full-screen host that contains a text input
                    //    AND an overflow list (bcine search). Nav bars alone do not match.
                    var vw=window.innerWidth||1, vh=window.innerHeight||1, va=vw*vh;
                    var all=document.querySelectorAll('body *'), lim=Math.min(all.length,400);
                    var best=null, bestSc=-1;
                    for(var i=0;i<lim;i++){
                      var e=all[i], st;
                      try{st=getComputedStyle(e);}catch(ex){continue;}
                      if(st.position!=='fixed'||st.display==='none'||st.visibility==='hidden') continue;
                      var r=e.getBoundingClientRect();
                      if(r.width*r.height<va*0.5||r.height<vh*0.5) continue;
                      // Must look like a search UI
                      var hasSearch=false;
                      try{
                        var inputs=e.querySelectorAll('input,textarea,[role="searchbox"],[contenteditable="true"]');
                        for(var ii=0;ii<inputs.length;ii++){
                          var inp=inputs[ii];
                          var t=(inp.getAttribute('type')||'text').toLowerCase();
                          if(inp.tagName==='TEXTAREA'||inp.isContentEditable||t===''||t==='text'||t==='search'){
                            var ir=inp.getBoundingClientRect();
                            if(ir.width>40&&ir.height>16){ hasSearch=true; break; }
                          }
                        }
                      }catch(e2){}
                      if(!hasSearch) continue;
                      var kids=e.querySelectorAll('div,ul,ol,section');
                      for(var k=0;k<Math.min(kids.length,160);k++){
                        var c=kids[k], cr=c.getBoundingClientRect();
                        if(cr.height<40||cr.width*cr.height>va*0.85) continue;
                        if(!(c.scrollHeight>c.clientHeight+4)) continue;
                        var o=oy(c), cls=String(c.className||'');
                        if(!(o==='auto'||o==='scroll'||o==='overlay'||o==='hidden'||
                             /overflow-y-auto|overscroll-contain|overflow-auto/.test(cls))) continue;
                        var sc=(c.scrollHeight-c.clientHeight)+(hit(c)?2000:0)+(edge?500:0);
                        if(sc>bestSc){bestSc=sc;best=c;}
                      }
                    }
                    if(best&&applyY(best,dy)) return 'modal';
                    // 3) Page scroll inside JS when at edge (document-level)
                    if(edge){
                      try{window.scrollBy(0,dy);}catch(e3){}
                      try{
                        var se=document.scrollingElement||document.documentElement||document.body;
                        if(se) se.scrollTop=(se.scrollTop||0)+dy;
                      }catch(e4){}
                      return 'page';
                    }
                    return 'none';
                  }catch(e){return 'none';}
                })($x,$y,$dy,${if (atPageEdge) 1 else 0});""",
            ) { raw ->
                val kind = unwrap(raw) ?: "none"
                if (kind == "modal") {
                    suppressPageScrollUntil = SystemClock.elapsedRealtime() + 600L
                }
            }
        }

        // Native WebView edge scroll — independent of JS (most TV WebView sites need this).
        // Only skip while a search sheet list is actively scrolling.
        if (atPageEdge && now >= suppressPageScrollUntil) {
            webView.scrollBy(0, dy)
        }
    }

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
        if (now - lastDocScrollAt < 14L) return
        lastDocScrollAt = now
        val x = pageX.toInt()
        val y = pageY.toInt()
        webView.evaluateJavascript(
            """(function(px,py,dx){
              try{
                function isDoc(n){
                  return !n||n===document||n===document.scrollingElement||
                    n===document.documentElement||n===document.body;
                }
                function hit(n){
                  if(!n||!n.getBoundingClientRect) return false;
                  var r=n.getBoundingClientRect();
                  return px>=r.left-2&&px<=r.right+2&&py>=r.top-2&&py<=r.bottom+2;
                }
                function ox(n){
                  try{var s=getComputedStyle(n);return (s.overflowX||s.overflow||'').toLowerCase();}
                  catch(e){return '';}
                }
                var el=document.elementFromPoint(px,py), n=el, h=0;
                while(n&&h<28){
                  if(!isDoc(n)&&hit(n)&&n.scrollWidth>n.clientWidth+2){
                    var o=ox(n);
                    var rail=n.classList&&(n.classList.contains('rail')||n.classList.contains('row')||n.hasAttribute('data-keen-rail'));
                    if(o==='auto'||o==='scroll'||o==='overlay'||rail||/carousel|slider|scroll-x|horizontal|overflow-x/.test(String(n.className||''))){
                      var b=n.scrollLeft|0; n.scrollLeft=b+dx;
                      if((n.scrollLeft|0)!==b) return true;
                    }
                  }
                  n=n.parentElement; h++;
                }
                return false;
              }catch(e){return false;}
            })($x,$y,$dx);""",
            null,
        )
    }

    private fun handleSelect(webView: WebView, event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centreHeld = true
                    modeToggledByLongPress = false
                    onUserInput()
                    scheduleLongPressToggle()
                }
            }
            KeyEvent.ACTION_UP -> {
                cancelLongPressToggle()
                if (centreHeld && !modeToggledByLongPress) {
                    activate(webView)
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
        val r = Runnable {
            if (centreHeld && !modeToggledByLongPress && !mediaPointerLock) {
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
            if (mode != Mode.POINTER) {
                setMode(Mode.POINTER)
            } else if (cursor.visibility != android.view.View.VISIBLE) {
                cursor.showAtCentre()
            }
        }
    }

    fun isMediaPointerLocked(): Boolean = mediaPointerLock

    private fun cancelLongPressToggle() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun activate(webView: WebView) {
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
        // Pointer is drawn over full shell: top chrome = address bar hit.
        val chromeH = chromeHeightPx().coerceAtLeast(0)
        val x = cursor.cursorX
        val yShell = cursor.cursorY
        if (yShell <= chromeH + 4f) {
            onUrlBarActivate()
            return
        }
        // WebView is laid out below chrome — offset touch Y.
        val y = (yShell - chromeH).coerceAtLeast(0f)

        // Search / text-field Enter UX:
        // Live sites (bcine etc.) already show typeahead results. Enter should
        // commit: dismiss IME, blur the field, leave pointer free to scroll results.
        // Prefer focused field (activeElement) over hit-test — typing often leaves
        // the pointer where the search icon was, not over the input.
        webView.evaluateJavascript(SEARCH_COMMIT_JS.format(x.toInt(), y.toInt())) { raw ->
            val kind = unwrap(raw)
            if (kind != null && (kind.startsWith("search_commit") || kind == "input_blur")) {
                onHideKeyboard()
                onUserInput()
                // Nudge pointer into the results panel when we have a Y hint.
                if (kind.startsWith("search_commit")) {
                    val (vw, vh) = cursor.viewportSize()
                    val parts = kind.split(':')
                    val hintY = parts.getOrNull(1)?.toFloatOrNull()
                    val targetY = if (hintY != null && hintY > 0f) {
                        // JS returns content Y; convert to shell Y (chrome offset).
                        hintY + chromeH
                    } else {
                        cursor.cursorY + 96f * density
                    }
                    val ny = targetY.coerceIn(chromeH + 24f, (vh - 24f).coerceAtLeast(chromeH + 24f))
                    val nx = cursor.cursorX.coerceIn(24f, (vw - 24f).coerceAtLeast(24f))
                    cursor.setPosition(nx, ny)
                }
                return@evaluateJavascript
            }
            // Normal click path.
            webView.evaluateJavascript(
                """(function(){
                  var el=document.elementFromPoint($x,$y);
                  if(!el) return null;
                  var t=(el.innerText||el.getAttribute('aria-label')||el.id||'').trim().slice(0,80);
                  var role=el.getAttribute('role')||el.tagName;
                  var href='';
                  try{
                    var a=el.closest?el.closest('a[href]'):null;
                    href=(a&&a.href)||el.href||el.getAttribute('href')||'';
                  }catch(e){}
                  var play=/(^|\\s)play(\\s|$)|▶|watch|start/i.test(t)||el.id==='real-play'||el.dataset.keenPlay==='1';
                  var form=!!(el.closest&&el.closest('form'));
                  return JSON.stringify({play:play,form:form,role:role,text:t,id:el.id||'',href:href,fp:(el.tagName+'#'+(el.id||'')+'.'+(el.className||'')).slice(0,120)});
                })();""",
            ) { inspectRaw ->
                val fingerprint = unwrap(inspectRaw)
                onDeliberateActivation?.invoke(fingerprint)
                if (looksLikePlay(fingerprint)) {
                    onPlayLikeActivation?.invoke(fingerprint)
                }
                onHideKeyboard()
                val down = pointerEvent(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN, x, y)
                val up = pointerEvent(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHSCREEN, x, y)
                webView.dispatchTouchEvent(down)
                webView.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
        }
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
        setMode(if (mode == Mode.DOM_FOCUS) Mode.POINTER else Mode.DOM_FOCUS)
    }

    private fun setMode(next: Mode) {
        val target = if (mediaPointerLock && next == Mode.DOM_FOCUS) Mode.POINTER else next
        if (mode == target) {
            if (target == Mode.POINTER && cursor.visibility != android.view.View.VISIBLE) {
                cursor.showAtCentre()
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
        stopFrameLoop()
        mode = target
        if (target == Mode.POINTER) {
            cursor.showAtCentre()
        } else {
            cursor.hide()
            indexDirty = true
        }
        onModeChanged(if (target == Mode.POINTER) "Pointer" else "DOM")
    }

    private enum class Mode { DOM_FOCUS, POINTER }

    private companion object {
        val SELECT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        const val LONG_PRESS_MS = 450L
        const val REBUILD_DEBOUNCE_MS = 280L
        /** Continuous pointer speed (dp/sec). Tuned for TV D-pad — smooth but not rushed. */
        const val SPEED_MIN_DP = 120f
        const val SPEED_MAX_DP = 480f
        const val ACCEL_MS = 520f
        const val EDGE_SCROLL_DP = 380f

        /**
         * Search/typeahead Enter: keep results open, drop IME focus, report where
         * results live so native code can park the pointer for scrolling.
         *
         * Prefer document.activeElement (what the user typed into) over hit-test.
         * Never dispatch Escape — that closes many search overlays.
         * Returns: "search_commit:<contentY>" | "search_commit" | "input_blur" | null
         * Format args: pageX, pageY (WebView coords, unused when focused).
         */
        val SEARCH_COMMIT_JS = """
            (function(){
              var px=%d, py=%d;
              try{
                function isTextual(el){
                  if(!el||el.nodeType!==1) return false;
                  var tag=(el.tagName||'').toUpperCase();
                  if(tag==='TEXTAREA'||el.isContentEditable) return true;
                  if(tag==='INPUT'){
                    var type=(el.getAttribute('type')||'text').toLowerCase();
                    return type===''||type==='text'||type==='search'||type==='url'||type==='email';
                  }
                  var role=(el.getAttribute('role')||'').toLowerCase();
                  return role==='searchbox'||role==='combobox'||role==='textbox';
                }
                function findInput(){
                  var a=document.activeElement;
                  if(isTextual(a)) return a;
                  if(a&&a.closest){
                    var c=a.closest('input,textarea,[contenteditable="true"],[role="searchbox"],[role="combobox"],[role="textbox"]');
                    if(isTextual(c)) return c;
                  }
                  var el=document.elementFromPoint(px,py);
                  if(!el) return null;
                  if(isTextual(el)) return el;
                  if(el.closest){
                    var c2=el.closest('input,textarea,[contenteditable="true"],[role="searchbox"],[role="combobox"],[role="textbox"]');
                    if(isTextual(c2)) return c2;
                  }
                  return null;
                }
                function looksOverlay(n){
                  if(!n||!n.getAttribute) return false;
                  var role=(n.getAttribute('role')||'').toLowerCase();
                  if(role==='dialog'||role==='listbox'||role==='menu'||role==='combobox'||role==='search') return true;
                  var idc=((n.id||'')+' '+String(n.className||'')).toLowerCase();
                  return /modal|dialog|popup|overlay|dropdown|popover|search|suggest|result|combobox|listbox|drawer|typeahead|autocomplete/.test(idc);
                }
                function canScrollY(n){
                  if(!n||n===document||n===document.body||n===document.documentElement) return false;
                  try{
                    var st=getComputedStyle(n);
                    var oy=st.overflowY||st.overflow;
                    if(oy==='auto'||oy==='scroll'||oy==='overlay'){
                      return n.scrollHeight > n.clientHeight + 4;
                    }
                    var pos=st.position;
                    if((pos==='fixed'||pos==='absolute') && n.scrollHeight > n.clientHeight + 8) return true;
                  }catch(e){}
                  return false;
                }
                function resultsHintY(input){
                  // Prefer a scrollable panel under/near the field (typeahead list).
                  var root=input;
                  var hops=0;
                  while(root && hops<12){
                    if(looksOverlay(root)||canScrollY(root)) break;
                    root=root.parentElement; hops++;
                  }
                  if(!root) root=input.parentElement||document.body;
                  var best=null, bestScore=-1;
                  var nodes=root.querySelectorAll?root.querySelectorAll('*'):[];
                  var ir=input.getBoundingClientRect?input.getBoundingClientRect():null;
                  for(var i=0;i<nodes.length && i<200;i++){
                    var n=nodes[i];
                    if(!canScrollY(n) && !(n.querySelector && n.querySelector('a[href],li,[role="option"]'))) continue;
                    var r=n.getBoundingClientRect();
                    if(r.width<40||r.height<40) continue;
                    // Prefer panels below the input or large overlay bodies.
                    var below=(ir? r.top>=ir.top-8 : true);
                    var score=(canScrollY(n)?1000:0)+r.height+(below?200:0);
                    if(score>bestScore){ bestScore=score; best=n; }
                  }
                  if(best){
                    var br=best.getBoundingClientRect();
                    return Math.round(br.top + Math.min(80, br.height*0.35));
                  }
                  if(ir) return Math.round(ir.bottom + 48);
                  return Math.round(py + 96);
                }
                var input=findInput();
                if(!input) return null;
                var val=(input.value!=null?String(input.value): (input.textContent||'')).trim();
                // Blur only — keep typeahead DOM mounted. Do NOT Escape (closes overlays).
                try{ input.blur(); }catch(e){}
                try{
                  if(document.activeElement && document.activeElement!==document.body && document.activeElement.blur){
                    document.activeElement.blur();
                  }
                }catch(e2){}
                if(val.length>0){
                  var hy=resultsHintY(input);
                  return 'search_commit:'+hy;
                }
                return 'input_blur';
              }catch(e){ return null; }
            })();
        """.trimIndent()

        val UNLOCK_SCROLL_JS = """
            (function(){
              try{
                document.documentElement.style.overflow='';
                document.body.style.overflow='';
                document.documentElement.style.position='';
                document.body.style.position='';
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
            const vw=window.innerHeight||0;
            const scored=nodes.filter(n=>n!==current).map(n=>{const r=n.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
              const valid=(command==='left'&&x<cx)||(command==='right'&&x>cx)||(command==='up'&&y<cy)||(command==='down'&&y>cy);
              const off=r.bottom<0||r.top>vw;
              return {n,valid,d:(command==='left'||command==='right'?Math.abs(x-cx)*3+Math.abs(y-cy):Math.abs(y-cy)*3+Math.abs(x-cx))+(off?400:0)};
            }).filter(x=>x.valid).sort((a,b)=>a.d-b.d);
            const next=(scored[0]||{n:current}).n;
            if(!next.hasAttribute('tabindex')) next.setAttribute('tabindex','-1');
            next.scrollIntoView({block:'nearest',inline:'nearest',behavior:'auto'});
            next.focus({preventScroll:true});
            return true;
          }
        """.trimIndent().replace("\n", " ")

        val SCROLL_EDGE_JS = """
          function(command){
            var el=document.activeElement;
            var sc=el && el.closest && el.closest('[data-keen-rail],.rail,.row,.scroll-y,main,[role=main]');
            var step=Math.max(120, Math.floor((window.innerHeight||600)*0.28));
            if(!sc){ window.scrollBy(0, command==='down'?step:command==='up'?-step:0); return true; }
            if(command==='left') sc.scrollBy(-step,0);
            else if(command==='right') sc.scrollBy(step,0);
            else if(command==='up') sc.scrollBy(0,-step);
            else sc.scrollBy(0,step);
            return true;
          }
        """.trimIndent().replace("\n", " ")
    }
}
