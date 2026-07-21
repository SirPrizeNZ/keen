package com.keenzero.app.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * 20-bar glowing "chase" loader — a native-Canvas port of a CSS pen: thin
 * vertical bars in a row, each running its own 8s cycle staggered 0.1s apart
 * (a 2s ripple across the row before it starts repeating): stand bright green
 * and glowing, fall flat around their own base while cycling through a full
 * hue rotation while down, then cut to black and reset before the next loop.
 *
 * Colour is intentionally kept here — green plus the rainbow hue-cycle while
 * flat — unlike the rest of this app's strict grayscale. That's a deliberate,
 * explicit exception for this one element, not an oversight.
 */
class SpinnerBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class BarState(val rotationDeg: Float, val hueRotate: Float, val alpha: Float)

    private var running = false
    private var startTimeMs = 0L
    private var tickerAnimator: ValueAnimator? = null
    private var collapseAnimator: ValueAnimator? = null
    private var collapseAlpha = 1f

    init {
        // BlurMaskFilter (the glow) is ignored on a hardware-accelerated canvas.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()
    private val hsv = floatArrayOf(BASE_HUE, 1f, 1f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || collapseAlpha <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val barW = w / (BAR_COUNT + (BAR_COUNT - 1) * GAP_RATIO)
        val gap = barW * GAP_RATIO
        val barH = h * BAR_HEIGHT_RATIO
        val baseY = h * BASE_Y_RATIO
        val corner = barW / 2f
        val elapsed = System.currentTimeMillis() - startTimeMs

        for (i in 0 until BAR_COUNT) {
            val localMs = floorMod(elapsed - i * STAGGER_MS, CYCLE_MS)
            val phase = localMs / CYCLE_MS.toFloat()
            val state = barState(phase)
            if (state.alpha <= 0f) continue

            val cx = i * (barW + gap) + barW / 2f
            rect.set(cx - barW / 2f, baseY - barH, cx + barW / 2f, baseY)

            hsv[0] = (BASE_HUE + state.hueRotate).let { if (it >= 360f) it - 360f else it }
            val alphaInt = (state.alpha * collapseAlpha * 255).toInt().coerceIn(0, 255)
            val color = Color.HSVToColor(alphaInt, hsv)
            barPaint.color = color
            glowPaint.color = color

            canvas.save()
            canvas.rotate(state.rotationDeg, cx, baseY)
            canvas.drawRoundRect(rect, corner, corner, glowPaint)
            canvas.drawRoundRect(rect, corner, corner, barPaint)
            canvas.restore()
        }
    }

    private fun barState(phase: Float): BarState = when {
        phase < 0.2f -> BarState(0f, 0f, 1f)
        phase < 0.4f -> BarState(90f * ((phase - 0.2f) / 0.2f), 0f, 1f)
        phase < 0.8f -> BarState(90f, 360f * ((phase - 0.4f) / 0.4f), 1f)
        phase < 0.9f -> {
            val t = (phase - 0.8f) / 0.1f
            BarState(90f * (1f - t), 360f, 1f - t)
        }
        else -> BarState(0f, 0f, 0f)
    }

    private fun floorMod(x: Long, m: Long): Long {
        val r = x % m
        return if (r < 0) r + m else r
    }

    private fun startTicker() {
        if (tickerAnimator?.isRunning == true) return
        tickerAnimator?.cancel()
        // No per-frame state here — onDraw computes phase from wall-clock time and
        // re-invalidates itself; this just keeps the draw loop alive.
        tickerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    fun startIndeterminate() = ensureRunning()

    fun setProgress(@Suppress("UNUSED_PARAMETER") fraction: Float) = ensureRunning()

    private fun ensureRunning() {
        collapseAnimator?.cancel()
        collapseAnimator = null
        collapseAlpha = 1f
        if (running) return
        running = true
        startTimeMs = System.currentTimeMillis()
        startTicker()
        invalidate()
    }

    /** Loading is finishing: fade the whole chase out instead of cutting it off mid-cycle. */
    fun collapse() {
        if (collapseAnimator?.isRunning == true) return
        collapseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener { collapseAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Full reset for a brand-new loading session. */
    fun stop() {
        tickerAnimator?.cancel(); tickerAnimator = null
        collapseAnimator?.cancel(); collapseAnimator = null
        running = false
        collapseAlpha = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private companion object {
        const val BAR_COUNT = 20
        const val CYCLE_MS = 8000L
        const val STAGGER_MS = 100L
        const val GAP_RATIO = 3f // gap = barWidth * 3, matches the source pen's 5px bar / 15px gap
        const val BAR_HEIGHT_RATIO = 0.4f
        const val BASE_Y_RATIO = 0.75f
        const val BASE_HUE = 120f // pure green, #0f0
    }
}
