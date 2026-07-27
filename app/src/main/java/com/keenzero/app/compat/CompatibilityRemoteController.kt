package com.keenzero.app.compat

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import com.keenzero.app.input.CursorOverlay
import kotlin.math.abs
import kotlin.math.min

/**
 * D-pad control for the compatibility WebView, implemented entirely natively.
 *
 * Normal Keen mode drives the remote through injected JavaScript (InteractionIndex,
 * ActivateHitTest, ModalScrollJs). None of that can be used here: the whole point of
 * compatibility mode is that the page sees an unmodified WebView, and an injected cursor
 * or an `element.click()` is exactly the kind of page-visible modification a challenge
 * can observe. So every interaction below is a real Android input event delivered to the
 * WebView from outside the web content:
 *
 *  - direction keys move a native [CursorOverlay] drawn *above* the WebView;
 *  - centre/OK becomes a genuine ACTION_DOWN/ACTION_UP touch pair at the cursor;
 *  - scrolling is [WebView.scrollBy], not `window.scrollBy`.
 *
 * The page cannot tell this apart from a real pointer, because it is one.
 */
class CompatibilityRemoteController(
    private val webView: WebView,
    private val cursor: CursorOverlay,
    private val onBack: () -> Boolean,
    /** Bounds of the K logo in the cursor's coordinate space, or null when hidden. */
    private val homeButtonRect: () -> android.graphics.RectF? = { null },
    /** Pointer OK on the K logo: return to the home surface. */
    private val onHomeActivate: () -> Unit = {},
    /** Bounds of the favourite star in the cursor's coordinate space, or null. */
    private val starButtonRect: () -> android.graphics.RectF? = { null },
    /** Pointer OK on the star: toggle the favourite. */
    private val onFavouriteActivate: () -> Unit = {},
    /** Height of Keen's chrome bar above the WebView, or 0 when it is hidden. */
    private val chromeHeightPx: () -> Int = { 0 },
    /** Pointer OK in the chrome band but off the logo/star: open the address bar. */
    private val onUrlBarActivate: () -> Unit = {},
) {

    private val handler = Handler(Looper.getMainLooper())

    /** Held-direction state, for acceleration and edge scrolling. */
    private var heldKey = 0
    private var heldSince = 0L
    private var moving = false

    private val density = webView.resources.displayMetrics.density

    /** True once a tap is in flight, so a key repeat cannot open a second one. */
    private var tapInFlight = false

    var attached = false
        private set

    fun attach() {
        attached = true
        cursor.showAtCentre()
        CompatibilityDiag.event("dpad_attached", fields = arrayOf("cursorVisible" to true))
    }

    fun detach() {
        attached = false
        stopMoving()
        handler.removeCallbacksAndMessages(null)
        cursor.hide()
        CompatibilityDiag.event("dpad_detached")
    }

    /**
     * @return true when the key was consumed. Back is deliberately never consumed here —
     * Keen and Android must keep their normal back handling.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (!attached) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> handleDirection(event)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> handleCentre(event)

            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> consumeOnDown(event) { scrollPage(1) }

            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> consumeOnDown(event) { scrollPage(-1) }

            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) onBack() else false
            }

            else -> false
        }
    }

    // ---------------------------------------------------------------- movement

    private fun handleDirection(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (heldKey != event.keyCode) {
                    heldKey = event.keyCode
                    heldSince = SystemClock.elapsedRealtime()
                }
                cursor.wake()
                if (!moving) {
                    moving = true
                    handler.post(moveTick)
                }
            }

            KeyEvent.ACTION_UP -> if (heldKey == event.keyCode) stopMoving()
        }
        return true
    }

    private fun stopMoving() {
        heldKey = 0
        moving = false
        handler.removeCallbacks(moveTick)
    }

    /**
     * One movement frame. Speed ramps from [BASE_SPEED_DP] to [MAX_SPEED_DP] over
     * [RAMP_MS] of holding, so a short tap nudges precisely (needed to land on a
     * checkbox) while a long hold crosses the screen without exhausting the user.
     */
    private val moveTick = object : Runnable {
        override fun run() {
            if (!moving || heldKey == 0) return
            val held = SystemClock.elapsedRealtime() - heldSince
            // Same motion curve as Keen's normal pointer (RemoteInputRouter): a short
            // constant-speed crawl so taps and brief holds land predictably on small
            // controls, then a smoothstep ease into cruise. The first version ramped
            // 320→1500 dp/s with no crawl, which overshot everything.
            val t = ((held - PRECISION_CRAWL_MS) / ACCEL_MS).coerceIn(0f, 1f)
            val speedDp = SPEED_MIN_DP + (SPEED_MAX_DP - SPEED_MIN_DP) * smoothstep(t)
            val step = speedDp * density * (FRAME_MS / 1000f)

            var dx = 0f
            var dy = 0f
            when (heldKey) {
                KeyEvent.KEYCODE_DPAD_UP -> dy = -step
                KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
                KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
                KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
            }

            val beforeX = cursor.cursorX
            val beforeY = cursor.cursorY
            cursor.move(dx, dy)
            cursor.wake()

            // The cursor is clamped to the viewport. When it cannot move any further in
            // the held direction, the intent is clearly "keep going" — so scroll the page
            // instead. This is what makes long pages reachable with only four keys.
            val blockedY = dy != 0f && abs(cursor.cursorY - beforeY) < 0.5f
            val blockedX = dx != 0f && abs(cursor.cursorX - beforeX) < 0.5f
            val scrollStep = (EDGE_SCROLL_DP * density * (FRAME_MS / 1000f))
                .toInt()
                .coerceAtLeast(1)
            if (blockedY) webView.scrollBy(0, if (dy > 0) scrollStep else -scrollStep)
            if (blockedX) webView.scrollBy(if (dx > 0) scrollStep else -scrollStep, 0)

            handler.postDelayed(this, FRAME_MS.toLong())
        }
    }

    private fun scrollPage(direction: Int) {
        val amount = (webView.height * PAGE_SCROLL_FRACTION).toInt().coerceAtLeast(1)
        webView.scrollBy(0, direction * amount)
        cursor.wake()
    }

    private inline fun consumeOnDown(event: KeyEvent, action: () -> Unit): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) action()
        return true
    }

    // ------------------------------------------------------------------- click

    private fun handleCentre(event: KeyEvent): Boolean {
        // Act on release: acting on ACTION_DOWN fires repeatedly while the key is held.
        if (event.action != KeyEvent.ACTION_UP) return true
        dispatchNativeTap()
        return true
    }

    /**
     * A real tap at the cursor, in WebView-local coordinates.
     *
     * No `element.click()`, no `document.elementFromPoint`, no synthetic DOM event — the
     * WebView receives the same MotionEvent pair a finger would produce, and Chromium
     * does its own hit-testing from there. That is what lets the Cloudflare checkbox see
     * a trusted, user-activated pointer event.
     */
    private fun dispatchNativeTap() {
        if (tapInFlight) return
        // Keen's chrome bar sits above the WebView, so an OK anywhere in that band is a
        // press on Keen's own chrome, never a tap into the page — the same rule the
        // normal pointer follows (RemoteInputRouter). Resolved before any MotionEvent is
        // built, so the page never sees it. Without the band check, only the logo and
        // star responded and the address bar could not be opened at all: its tap was
        // clamped into the top row of the page instead.
        val chromeH = chromeHeightPx().coerceAtLeast(0).toFloat()
        if (chromeH > 0f && cursor.cursorY <= chromeH + CHROME_BAND_PAD_PX) {
            homeButtonRect()?.let { rect ->
                if (hits(rect)) {
                    cursor.wake()
                    CompatibilityDiag.event("dpad_home_activate")
                    onHomeActivate()
                    return
                }
            }
            starButtonRect()?.let { rect ->
                if (hits(rect)) {
                    cursor.wake()
                    CompatibilityDiag.event("dpad_favourite_activate")
                    onFavouriteActivate()
                    return
                }
            }
            cursor.wake()
            CompatibilityDiag.event("dpad_url_bar_activate")
            onUrlBarActivate()
            return
        }
        tapInFlight = true
        cursor.wake()

        // The cursor overlay and the WebView are siblings in different containers, so
        // their origins do not necessarily coincide. Translating through screen space
        // keeps the tap under the dot the user can see; using the raw cursor coordinates
        // put every tap ~15px off, which is the difference between hitting a checkbox
        // and missing it.
        if (com.keenzero.app.diagnostics.ExperimentFlags.isOn(
                com.keenzero.app.diagnostics.ExperimentFlags.ADD_ROUTER_JS,
            )
        ) {
            // The router re-indexes on interaction; mirror that cadence so the page sees
            // the same footprint it would in normal mode.
            webView.evaluateJavascript(com.keenzero.app.input.InteractionIndex.COLLECT_JS, null)
        }
        val (x, y) = cursorInWebViewSpace()
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()

        handler.postDelayed({
            val up = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                x,
                y,
                0,
            )
            webView.dispatchTouchEvent(up)
            up.recycle()
            tapInFlight = false
            CompatibilityDiag.event(
                "dpad_native_tap",
                fields = arrayOf("x" to x.toInt(), "y" to y.toInt()),
            )
        }, TAP_DURATION_MS)
    }

    /** Cursor inside [rect], with the same forgiveness the normal pointer allows. */
    private fun hits(rect: android.graphics.RectF): Boolean =
        cursor.cursorX >= rect.left - BUTTON_HIT_PAD_PX &&
            cursor.cursorX <= rect.right + BUTTON_HIT_PAD_PX &&
            cursor.cursorY >= rect.top - BUTTON_HIT_PAD_PX &&
            cursor.cursorY <= rect.bottom + BUTTON_HIT_PAD_PX

    /** Cursor position expressed in the WebView's own coordinate space. */
    private fun cursorInWebViewSpace(): Pair<Float, Float> {
        val cursorLoc = IntArray(2)
        val webLoc = IntArray(2)
        cursor.getLocationOnScreen(cursorLoc)
        webView.getLocationOnScreen(webLoc)
        val x = cursor.cursorX + (cursorLoc[0] - webLoc[0])
        val y = cursor.cursorY + (cursorLoc[1] - webLoc[1])
        return x.coerceIn(0f, webView.width.toFloat()) to y.coerceIn(0f, webView.height.toFloat())
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private companion object {
        /** ~60fps movement loop. */
        const val FRAME_MS = 16

        // Mirrors RemoteInputRouter's constants so compatibility mode feels identical
        // to the rest of Keen. Change these together with that file, not on their own.
        const val SPEED_MIN_DP = 110f
        const val SPEED_MAX_DP = 460f
        const val PRECISION_CRAWL_MS = 160f
        const val ACCEL_MS = 700f
        const val EDGE_SCROLL_DP = 520f

        const val PAGE_SCROLL_FRACTION = 0.85f

        /** Long enough to read as a deliberate tap, short enough not to be a long-press. */
        const val TAP_DURATION_MS = 60L

        /** Matches RemoteInputRouter's chrome-band slop and STAR/HOME_HIT_PAD_PX. */
        const val CHROME_BAND_PAD_PX = 4f
        const val BUTTON_HIT_PAD_PX = 28f
    }
}
