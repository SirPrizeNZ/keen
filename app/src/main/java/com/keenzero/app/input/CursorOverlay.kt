package com.keenzero.app.input

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight native cursor above the WebView (and HTML fullscreen).
 * Positions are set every frame by [RemoteInputRouter] for continuous smooth motion.
 * Fades out smoothly after [IDLE_FADE_MS] of no activity.
 */
class CursorOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val radius = 5.4f * density

    /**
     * The cursor inverts whatever is under it rather than being painted a fixed colour.
     *
     * A white dot is invisible on a white page, which is most of the web — Wikipedia
     * being the case that surfaced it. DIFFERENCE against white gives black, against
     * black gives white, and against a mid-tone gives its opposite, so the dot is
     * legible on any background without having to know what the background is.
     *
     * Two consequences are load-bearing:
     *  - the view must NOT have its own layer (see [hasOverlappingRendering] and the
     *    absent setLayerType), or the blend would apply against the layer's own empty
     *    pixels instead of the page beneath it;
     *  - the idle fade cannot use View.alpha for the same reason, so it is applied to
     *    these paints instead — see [renderAlpha].
     */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        blendMode = android.graphics.BlendMode.DIFFERENCE
        isDither = false
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        blendMode = android.graphics.BlendMode.DIFFERENCE
        isDither = false
    }

    /** 0..1, applied to the paints because View.alpha would force an offscreen layer. */
    private var renderAlpha = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                fill.alpha = (255 * clamped).toInt()
                ring.alpha = (77 * clamped).toInt()
                invalidate()
            }
        }
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    private var centreWhenLaidOut = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fadeAnimator: ValueAnimator? = null
    private var lastActivityAt = 0L
    private val idleFadeRunnable = Runnable { startIdleFade() }

    init {
        isClickable = false
        isFocusable = false
        // No layer: the DIFFERENCE blend has to reach the page underneath.
        visibility = GONE
        alpha = 1f
    }

    /**
     * False so View.alpha (and any parent's) never triggers an offscreen save layer,
     * which would isolate the blend from the content it is supposed to invert.
     */
    override fun hasOverlappingRendering(): Boolean = false

    fun showAtCentre() {
        centreWhenLaidOut = cursorX == 0f && cursorY == 0f
        visibility = VISIBLE
        wake()
        applyPendingCentre()
        post(::applyPendingCentre)
        invalidate()
    }

    fun hide() {
        cancelFade()
        mainHandler.removeCallbacks(idleFadeRunnable)
        visibility = GONE
        renderAlpha = 1f
    }

    /**
     * Any remote activity: snap to full opacity and restart the 3s idle timer.
     */
    fun wake() {
        lastActivityAt = SystemClock.elapsedRealtime()
        cancelFade()
        if (visibility != VISIBLE) visibility = VISIBLE
        if (renderAlpha < 0.99f) renderAlpha = 1f
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.postDelayed(idleFadeRunnable, IDLE_FADE_MS)
    }

    /** Absolute placement (used by continuous frame loop). */
    fun setPosition(x: Float, y: Float) {
        if (centreWhenLaidOut) applyPendingCentre()
        if (width == 0 || height == 0) {
            cursorX = x
            cursorY = y
            return
        }
        val margin = radius * 1.5f
        cursorX = min(max(x, margin), max(margin, width - margin))
        cursorY = min(max(y, margin), max(margin, height - margin))
        invalidate()
    }

    fun move(dx: Float, dy: Float): Pair<Float, Float> {
        setPosition(cursorX + dx, cursorY + dy)
        return cursorX to cursorY
    }

    fun viewportSize(): Pair<Int, Int> = width to height

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (renderAlpha <= 0.01f) return
        canvas.drawCircle(cursorX, cursorY, radius + ring.strokeWidth, ring)
        canvas.drawCircle(cursorX, cursorY, radius, fill)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPendingCentre()
        // Fullscreen enter/exit can change bounds — keep last position clamped.
        if (w > 0 && h > 0 && !centreWhenLaidOut) {
            setPosition(cursorX, cursorY)
        }
    }

    override fun onDetachedFromWindow() {
        cancelFade()
        mainHandler.removeCallbacks(idleFadeRunnable)
        super.onDetachedFromWindow()
    }

    private fun startIdleFade() {
        if (visibility != VISIBLE) return
        // Another wake landed after schedule — reschedule.
        val idleFor = SystemClock.elapsedRealtime() - lastActivityAt
        if (idleFor < IDLE_FADE_MS - 50L) {
            mainHandler.postDelayed(idleFadeRunnable, IDLE_FADE_MS - idleFor)
            return
        }
        cancelFade()
        fadeAnimator = ValueAnimator.ofFloat(renderAlpha, 0f).apply {
            duration = FADE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                renderAlpha = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun cancelFade() {
        fadeAnimator?.cancel()
        fadeAnimator = null
    }

    private fun applyPendingCentre() {
        if (!centreWhenLaidOut || width == 0 || height == 0) return
        cursorX = width / 2f
        cursorY = height / 2f
        centreWhenLaidOut = false
        invalidate()
    }

    companion object {
        const val IDLE_FADE_MS = 3_000L
        const val FADE_DURATION_MS = 450L
    }
}
