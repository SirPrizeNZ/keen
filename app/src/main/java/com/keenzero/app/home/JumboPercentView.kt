package com.keenzero.app.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Full-bleed background numeral for the loading overlay. Digit changes roll
 * odometer-style: only the columns whose digit actually changed move — a static
 * leading "0" in "05" stays put while the units column rolls 1, 2, 3… — so the
 * number never pulses or shifts as a whole when a single digit ticks over. Each
 * glyph sits in a fixed-width cell (widest digit), so a value never slides
 * sideways as its digits change width (0 → 1 is a big swing).
 *
 * A vertical fade is fixed in view space: numbers dissolve into the black
 * background as a rolling digit climbs out the top and materialise as the next
 * digit rises in from the bottom — same colour throughout, the fade is purely
 * positional. Height comes from a fixed reference glyph set, not the digits on
 * screen, so the size never jitters frame to frame.
 */
class JumboPercentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Weight-based, not a family-name lookup: guaranteed heaviest weight
        // regardless of whether "sans-serif-black" resolves on a given device.
        typeface = Typeface.create(Typeface.DEFAULT, 900, false)
    }
    private val bounds = Rect()

    private var currentText = ""
    private var previousText = ""
    private var rollAnimator: ValueAnimator? = null
    private var roll = 1f // 1f == settled; <1f == a digit change is rolling

    fun setPercentText(value: String) {
        if (value == currentText) return
        val isFirst = currentText.isEmpty()
        previousText = currentText
        currentText = value
        rollAnimator?.cancel()
        if (isFirst) {
            roll = 1f
            invalidate()
            return
        }
        roll = 0f
        rollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROLL_DURATION_MS
            // Reactive out of the gate, easing to a soft stop — no overshoot pulse.
            interpolator = EASE
            addUpdateListener { roll = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildFadeShader(h.toFloat())
    }

    /**
     * Vertical fade fixed in view space: transparent at the very top and bottom
     * edges, full strength through the central reading band. Glyphs are drawn at
     * absolute Y (no canvas translate) so they slide through this stationary
     * gradient — dissolving into the background as they climb out the top and
     * appearing as they rise in from the bottom.
     */
    private fun buildFadeShader(h: Float) {
        if (h <= 0f) return
        val solid = Color.argb(BASE_ALPHA, 255, 255, 255)
        val clear = Color.argb(0, 255, 255, 255)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(clear, solid, solid, clear),
            floatArrayOf(0f, FADE_FRACTION, 1f - FADE_FRACTION, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val text = currentText
        if (text.isEmpty() || w <= 0f || h <= 0f) return
        if (paint.shader == null) buildFadeShader(h)

        // Size to the clear reading band, not the whole view, so a settled number
        // sits fully inside the un-faded centre.
        val bandH = h * (1f - 2f * FADE_FRACTION)
        val targetH = bandH * FILL_FRACTION

        val probe = 200f
        paint.textSize = probe
        paint.getTextBounds(REFERENCE_GLYPHS, 0, REFERENCE_GLYPHS.length, bounds)
        if (bounds.height() <= 0) return
        var size = probe * (targetH / bounds.height())
        paint.textSize = size

        // Keep the widest value inside the width budget (e.g. a 3-digit "100").
        val maxW = w * WIDTH_FILL_FRACTION
        if (text.length * widestDigit() > maxW) {
            size *= maxW / (text.length * widestDigit())
            paint.textSize = size
        }
        val cell = widestDigit()

        // Baseline centres the reference ink block so every value shares one
        // vertical centre line and none drift as the digits change.
        paint.getTextBounds(REFERENCE_GLYPHS, 0, REFERENCE_GLYPHS.length, bounds)
        val inkH = bounds.height().toFloat()
        val baseline = (h - inkH) / 2f - bounds.top
        val travel = inkH * TRAVEL_SLOTS

        val old = previousText
        when {
            roll >= 1f || old.isEmpty() -> drawRow(canvas, text, w, cell, baseline)

            old.length == text.length -> {
                // Per-column odometer: unchanged columns stay put, changed ones roll.
                val x0 = (w - text.length * cell) / 2f
                for (i in text.indices) {
                    val cellLeft = x0 + i * cell
                    if (old[i] != text[i]) {
                        drawGlyph(canvas, old[i], cellLeft, cell, baseline - roll * travel)
                        drawGlyph(canvas, text[i], cellLeft, cell, baseline + (1f - roll) * travel)
                    } else {
                        drawGlyph(canvas, text[i], cellLeft, cell, baseline)
                    }
                }
            }

            else -> {
                // Digit count changed (e.g. 99 → 100): scroll the whole number as one block.
                drawRow(canvas, old, w, cell, baseline - roll * travel)
                drawRow(canvas, text, w, cell, baseline + (1f - roll) * travel)
            }
        }
    }

    private fun widestDigit(): Float {
        var max = 0f
        for (d in '0'..'9') max = maxOf(max, paint.measureText(d.toString()))
        return max
    }

    private fun drawRow(canvas: Canvas, text: String, w: Float, cell: Float, baseline: Float) {
        val x0 = (w - text.length * cell) / 2f
        for (i in text.indices) drawGlyph(canvas, text[i], x0 + i * cell, cell, baseline)
    }

    private fun drawGlyph(canvas: Canvas, ch: Char, cellLeft: Float, cell: Float, baseline: Float) {
        val s = ch.toString()
        val gw = paint.measureText(s)
        canvas.drawText(s, cellLeft + (cell - gw) / 2f, baseline, paint)
    }

    private companion object {
        // Fraction of the height taken by each (top and bottom) fade zone.
        const val FADE_FRACTION = 0.22f
        // Numeral height as a fraction of the clear reading band between the fades.
        const val FILL_FRACTION = 0.92f
        const val WIDTH_FILL_FRACTION = 0.82f
        // ~0x0E — very faint, reads as texture well behind the spinner.
        const val BASE_ALPHA = 14
        const val ROLL_DURATION_MS = 380L
        // How far, in reference-ink heights, a rolling digit travels out/in.
        const val TRAVEL_SLOTS = 1.0f
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
        const val REFERENCE_GLYPHS = "0123456789"
    }
}
