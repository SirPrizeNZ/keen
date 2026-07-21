package com.keenzero.app.home

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

/**
 * Focus indicator that animates a border *inward* — the stroke eases from 0 to a
 * target width on focus and back to 0 on blur — instead of scaling the whole
 * view up. Drawn with an antialiased Paint (not clipToOutline), so ovals and
 * rounded rectangles both stay smooth, and inset by half the stroke so the outer
 * edge stays pinned to the view while the border thickens toward the centre.
 */
class BorderDrawable(
    color: Int,
    private val cornerRadiusPx: Float,
    private val oval: Boolean,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
    }
    private val rect = RectF()
    private var animator: ValueAnimator? = null

    private var strokeWidthPx = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    /** Ease the border to [targetPx] (on focus) or back to 0 (on blur). */
    fun animateTo(focused: Boolean, targetPx: Float) {
        animator?.cancel()
        val target = if (focused) targetPx else 0f
        animator = ValueAnimator.ofFloat(strokeWidthPx, target).apply {
            duration = if (focused) 500L else 400L
            interpolator = DecelerateInterpolator()
            addUpdateListener { strokeWidthPx = it.animatedValue as Float }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val w = strokeWidthPx
        if (w <= 0.25f) return
        paint.strokeWidth = w
        val inset = w / 2f
        val b = bounds
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (oval) {
            canvas.drawOval(rect, paint)
        } else {
            val r = (cornerRadiusPx - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
