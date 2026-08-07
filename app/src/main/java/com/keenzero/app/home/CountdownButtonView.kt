package com.keenzero.app.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A solid button that fills left to right as a countdown runs down.
 *
 * The fill is the timer: a lighter block sweeps across the button, and when it reaches
 * the far edge the action fires on its own. It reads as "this is about to happen, press
 * me now or don't" without a number ticking down, which on a ten-foot screen is easier
 * to take in at a glance than digits.
 *
 * The fill is drawn as a second rounded rect clipped to the button's own shape, so the
 * lighter block keeps the corner radius at both ends instead of showing a square edge as
 * it arrives. Progress is driven by a plain [ValueAnimator] rather than by polling
 * playback position: the countdown is a promise about wall-clock time, and a stalled
 * stream should not stretch it.
 */
class CountdownButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BASE_COLOR }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val body = RectF()
    private val fill = RectF()
    private val density = resources.displayMetrics.density
    private val radius = 8f * density

    /** 0..1 of the countdown elapsed; 1 means the action is due. */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** Fires when the fill completes, never when the countdown is cancelled. */
    var onCountdownComplete: (() -> Unit)? = null

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Deliberately not focusable. While the film is up the activity routes every key
        // to the PlayerView and pulls focus back to it on each event, so a focusable
        // button here would be unreachable and would fight that model. The activity hands
        // this view the OK press directly for as long as the offer is showing.
        isFocusable = false
        edgePaint.strokeWidth = 2f * density
        textPaint.textSize = 14f * density
    }

    /**
     * Run the fill over [durationMs], then fire.
     *
     * Restarting mid-flight is deliberate — the caller re-arms rather than tracking
     * whether a countdown is already up.
     */
    fun startCountdown(durationMs: Long) {
        cancelCountdown()
        animator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = (durationMs * (1f - progress)).toLong().coerceAtLeast(1L)
            // Linear, and only linear. An eased countdown misreports how much time is
            // left — the whole point of the fill is that its position is the clock.
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onCountdownComplete?.invoke()
                }
            })
            start()
        }
    }

    /** Stop the fill where it stands and reset it; the action does not fire. */
    fun cancelCountdown() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        body.set(0f, 0f, width.toFloat(), height.toFloat())
        val inset = edgePaint.strokeWidth
        body.inset(inset, inset)
        canvas.drawRoundRect(body, radius, radius, basePaint)

        if (progress > 0f) {
            // Clipped to the button so the leading edge of the fill is square while it
            // travels and rounds off only as it meets the far corner.
            canvas.save()
            val clip = android.graphics.Path().apply {
                addRoundRect(body, radius, radius, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clip)
            fill.set(body.left, body.top, body.left + body.width() * progress, body.bottom)
            canvas.drawRoundRect(fill, radius, radius, fillPaint)
            canvas.restore()
        }

        // A permanent outline, since there is no focus state to show one: over a bright
        // frame the solid body alone can sit too close to the picture to read as a button.
        canvas.drawRoundRect(body, radius, radius, edgePaint)

        if (label.isNotEmpty()) {
            // Centre on cap height, not on the font's line box: the label is short and
            // all-caps-ish, so metric centring parks it visibly low.
            val metrics = textPaint.fontMetrics
            val baseline = body.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, body.centerX(), baseline, textPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelCountdown()
    }

    private companion object {
        /** Solid resting colour, and the lighter block that fills across it. */
        const val BASE_COLOR = 0xFF2A3038.toInt()
        const val FILL_COLOR = 0xFF6F7B8C.toInt()
    }
}
