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
) {
    // Product default: pointer first.
    private var mode = Mode.POINTER
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
            toggleMode()
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

        val edge = 64f * density
        val scrollPx = (EDGE_SCROLL_DP * density * dt).toInt().coerceAtLeast(1)
        val (vw, vh) = cursor.viewportSize()
        if (vw <= 0 || vh <= 0) return
        val pageBottom = vh.toFloat()
        val inTopZone = cursor.cursorY <= chromeH + edge
        val inBottomZone = cursor.cursorY >= pageBottom - edge
        val inLeftZone = cursor.cursorX <= edge
        val inRightZone = cursor.cursorX >= vw - edge

        // Horizontal: JS rails under pointer; native WebView only at screen edges (not both).
        if (holdLeft || holdRight) {
            val dx = if (holdLeft) -scrollPx else scrollPx
            if (inLeftZone || inRightZone) {
                webView.scrollBy(dx, 0)
            } else {
                scrollUnderPointer(webView, pageX, pageY, dx, 0, forceWindow = false)
            }
        }
        // Vertical: ONE authority only.
        // - Mid page: nested/window via JS under pointer
        // - Screen edges: native webView.scrollBy only (JS + native fought and felt like push-up)
        if (holdUp || holdDown) {
            val dy = if (holdUp) -scrollPx else scrollPx
            if (inTopZone || inBottomZone) {
                webView.scrollBy(0, dy)
            } else {
                scrollUnderPointer(webView, pageX, pageY, 0, dy, forceWindow = true)
            }
        }
    }

    /**
     * Scroll the nearest overflow container under the pointer (horizontal rails, etc.).
     * [pageX]/[pageY] are WebView/content coordinates (chrome already subtracted).
     * Applies a single scroll path — never window.scrollBy AND scrollTop together.
     */
    private var lastDocScrollAt = 0L
    private fun scrollUnderPointer(
        webView: WebView,
        pageX: Float,
        pageY: Float,
        dx: Int,
        dy: Int,
        forceWindow: Boolean,
    ) {
        if (dx == 0 && dy == 0) return
        val now = SystemClock.elapsedRealtime()
        // ~1 scroll inject per frame; avoid stacking async evaluateJavascript.
        if (now - lastDocScrollAt < 14L) return
        lastDocScrollAt = now
        val x = pageX.toInt()
        val y = pageY.toInt()
        val fw = if (forceWindow) 1 else 0
        webView.evaluateJavascript(
            """(function(px,py,dx,dy,forceWindow){
              try{
                function canScrollX(n){
                  if(!n||n===document||n===document.documentElement) return false;
                  var st=getComputedStyle(n);
                  var ox=st.overflowX||st.overflow;
                  if(!(ox==='auto'||ox==='scroll'||ox==='overlay')) return false;
                  return n.scrollWidth > n.clientWidth + 4;
                }
                function canScrollY(n){
                  if(!n||n===document) return false;
                  // Prefer real overflow containers; documentElement/body only as last resort
                  // (avoids fighting WebView native scroll on the same axis).
                  var isDoc = (n===document.scrollingElement||n===document.documentElement||n===document.body);
                  var st=getComputedStyle(n);
                  var oy=st.overflowY||st.overflow;
                  if(!(oy==='auto'||oy==='scroll'||oy==='overlay')) {
                    if(isDoc) return (n.scrollHeight||0) > (n.clientHeight||0) + 4;
                    return false;
                  }
                  return n.scrollHeight > n.clientHeight + 4;
                }
                var target=document.elementFromPoint(px,py);
                var n=target;
                var hops=0;
                var scrolled=false;
                // Prefer nested (non-document) scrollers first.
                while(n && hops<16){
                  var isDoc = (n===document.scrollingElement||n===document.documentElement||n===document.body);
                  if(dx!==0 && !isDoc && canScrollX(n)){
                    var before=n.scrollLeft;
                    n.scrollLeft = before + dx;
                    if(n.scrollLeft!==before){ scrolled=true; break; }
                  }
                  if(dy!==0 && !isDoc && canScrollY(n)){
                    var beforeY=n.scrollTop;
                    n.scrollTop = beforeY + dy;
                    if(n.scrollTop!==beforeY){ scrolled=true; break; }
                  }
                  if(dx!==0 && n.classList && (
                      n.classList.contains('rail')||n.classList.contains('row')||
                      n.hasAttribute('data-keen-rail')||
                      /carousel|slider|scroll-x|horizontal/i.test(n.className||'')
                    )){
                    if(n.scrollWidth > n.clientWidth + 4){
                      n.scrollLeft = (n.scrollLeft||0) + dx;
                      scrolled=true; break;
                    }
                  }
                  n=n.parentElement; hops++;
                }
                // Document-level: single apply (window.scrollBy OR scrollTop — never both).
                // forceWindow / vertical: always fall through to document if no nested scroller.
                // Horizontal mid-page without a rail: do nothing (avoid sideways page jump).
                if(!scrolled && (forceWindow || dy!==0 || !target)){
                  if(dx!==0 && dy===0 && !forceWindow && target){
                    // mid-page horizontal with target but no rail — skip
                  } else if(window.scrollBy){
                    window.scrollBy(dx,dy);
                  } else {
                    var el=document.scrollingElement||document.documentElement||document.body;
                    if(el){
                      if(dy) el.scrollTop=(el.scrollTop||0)+dy;
                      if(dx) el.scrollLeft=(el.scrollLeft||0)+dx;
                    }
                  }
                }
              }catch(e){}
            })($x,$y,$dx,$dy,$fw);""",
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
        val r = Runnable {
            if (centreHeld && !modeToggledByLongPress) {
                modeToggledByLongPress = true
                toggleMode()
            }
        }
        longPressRunnable = r
        mainHandler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelLongPressToggle() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun activate(webView: WebView) {
        if (mode == Mode.DOM_FOCUS) {
            webView.evaluateJavascript(INSPECT_JS) { inspectRaw ->
                val fingerprint = unwrap(inspectRaw)
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
        webView.evaluateJavascript(
            """(function(){
              var el=document.elementFromPoint($x,$y);
              if(!el) return null;
              var t=(el.innerText||el.getAttribute('aria-label')||el.id||'').trim().slice(0,80);
              var role=el.getAttribute('role')||el.tagName;
              var play=/(^|\\s)play(\\s|$)|▶|watch|start/i.test(t)||el.id==='real-play'||el.dataset.keenPlay==='1';
              return JSON.stringify({play:play,role:role,text:t,id:el.id||'',href:el.href||'',fp:(el.tagName+'#'+(el.id||'')+'.'+(el.className||'')).slice(0,120)});
            })();""",
        ) { inspectRaw ->
            val fingerprint = unwrap(inspectRaw)
            if (looksLikePlay(fingerprint)) {
                onPlayLikeActivation?.invoke(fingerprint)
            }
        }
        val down = pointerEvent(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN, x, y)
        val up = pointerEvent(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHSCREEN, x, y)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
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
        setMode(if (mode == Mode.DOM_FOCUS) Mode.POINTER else Mode.DOM_FOCUS)
    }

    private fun setMode(next: Mode) {
        if (mode == next) return
        // Clear continuous motion when leaving pointer.
        holdLeft = false
        holdRight = false
        holdUp = false
        holdDown = false
        velX = 0f
        velY = 0f
        holdStartedAt = 0L
        stopFrameLoop()
        mode = next
        if (next == Mode.POINTER) {
            cursor.showAtCentre()
        } else {
            cursor.hide()
            indexDirty = true
        }
        onModeChanged(if (next == Mode.POINTER) "Pointer" else "DOM")
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
        /** Continuous pointer speed (dp/sec). */
        const val SPEED_MIN_DP = 220f
        const val SPEED_MAX_DP = 980f
        const val ACCEL_MS = 420f
        const val EDGE_SCROLL_DP = 520f

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
