package com.keenzero.app.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Full-bleed background numeral for the loading overlay, drawn from measured
 * ink bounds (not TextView line-height, which reserves dead space above/below
 * digits and can't be trusted to center them either). Digit changes animate
 * as a vertical rolling reel, odometer-style — only the columns whose digit
 * actually changed roll; unchanged columns (e.g. a static leading "1" in
 * "100") stay put, the way a real mechanical counter works.
 */
class JumboPercentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = BASE_ALPHA
        // Weight-based, not a family-name lookup: guaranteed heaviest weight
        // regardless of whether "sans-serif-black" resolves on a given device.
        typeface = Typeface.create(Typeface.DEFAULT, 900, false)
    }
    private val bounds = Rect()

    private var currentText = ""
    private var previousText = ""
    private var rollAnimator: ValueAnimator? = null
    private var rollProgress = 1f

    fun setPercentText(value: String) {
        if (value == currentText) return
        val isFirst = currentText.isEmpty()
        previousText = currentText
        currentText = value
        rollAnimator?.cancel()
        if (isFirst) {
            rollProgress = 1f
            invalidate()
            return
        }
        rollProgress = 0f
        rollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROLL_DURATION_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { rollProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val text = currentText
        if (text.isEmpty() || w <= 0f || h <= 0f) return

        val targetH = h * FILL_FRACTION
        val targetW = w * FILL_FRACTION

        // Height comes from a fixed reference glyph set, not the specific digits on
        // screen, so the size never jitters frame to frame as the text changes.
        val probeSize = 200f
        paint.textSize = probeSize
        paint.getTextBounds(REFERENCE_GLYPHS, 0, REFERENCE_GLYPHS.length, bounds)
        if (bounds.height() <= 0) return
        var size = probeSize * (targetH / bounds.height())
        paint.textSize = size
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() > targetW) {
            size *= targetW / bounds.width()
            paint.textSize = size
            paint.getTextBounds(text, 0, text.length, bounds)
        }

        // Centered on both axes against the *view's* bounds, not just sized to fill
        // them — with FILL_FRACTION < 1 there's slack, and it has to split evenly.
        val inkH = bounds.height().toFloat()
        val baseline = (h - inkH) / 2f - bounds.top
        // Enough travel to read as a roll, not so much that the number swings far
        // from center while it's mid-change — measured on-device, a full digit
        // height of travel (the original figure) put it up to ~70px off center.
        val rowH = inkH * 0.5f

        val widths = FloatArray(text.length) { i -> paint.measureText(text, i, i + 1) }
        val totalWidth = widths.sum()
        var x = (w - totalWidth) / 2f

        val oldText = previousText
        val canRoll = rollProgress < 1f && oldText.length == text.length

        for (i in text.indices) {
            val newChar = text[i]
            val colLeft = x
            val colRight = x + widths[i]
            if (canRoll && oldText[i] != newChar) {
                // Old digit rolls up and out, new digit rolls up into place from
                // below, both clipped to this column so neighbouring digits never
                // bleed into each other mid-roll.
                canvas.save()
                canvas.clipRect(colLeft, 0f, colRight, h)
                canvas.save()
                canvas.translate(0f, -rollProgress * rowH)
                canvas.drawText(oldText[i].toString(), colLeft, baseline, paint)
                canvas.restore()
                canvas.save()
                canvas.translate(0f, (1f - rollProgress) * rowH)
                canvas.drawText(newChar.toString(), colLeft, baseline, paint)
                canvas.restore()
                canvas.restore()
            } else {
                canvas.drawText(newChar.toString(), colLeft, baseline, paint)
            }
            x += widths[i]
        }
    }

    private companion object {
        const val FILL_FRACTION = 0.8f
        const val BASE_ALPHA = 21 // ~0x15, faded — reads as texture, not competing with the foreground
        const val ROLL_DURATION_MS = 300L
        const val REFERENCE_GLYPHS = "0123456789"
    }
}
