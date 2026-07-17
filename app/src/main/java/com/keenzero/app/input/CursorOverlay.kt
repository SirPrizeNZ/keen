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
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isDither = false
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        isDither = false
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
        setLayerType(LAYER_TYPE_HARDWARE, null)
        visibility = GONE
        alpha = 1f
    }

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
        alpha = 1f
    }

    /**
     * Any remote activity: snap to full opacity and restart the 3s idle timer.
     */
    fun wake() {
        lastActivityAt = SystemClock.elapsedRealtime()
        cancelFade()
        if (visibility != VISIBLE) visibility = VISIBLE
        if (alpha < 0.99f) alpha = 1f
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
        if (alpha <= 0.01f) return
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
        fadeAnimator = ValueAnimator.ofFloat(alpha.coerceIn(0f, 1f), 0f).apply {
            duration = FADE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                alpha = anim.animatedValue as Float
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
