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
        // A heavier cut of Google Sans than anything the family ships as a static.
        // Its weight axis stops at 700, so this face is instanced at wght=700 plus
        // GRAD=200 — the grade axis thickens strokes without widening the glyphs, so
        // the digits get genuinely fatter instead of just bigger and looser. Subset to
        // the ten digits and a percent sign, which is all this view ever draws, so the
        // extra face costs 8 KB.
        typeface = androidx.core.content.res.ResourcesCompat
            .getFont(context, com.keenzero.app.R.font.google_sans_jumbo)
            ?: Typeface.create(Typeface.DEFAULT, 900, false)
    }
    private val bounds = Rect()

    private var currentText = ""
    private var previousText = ""
    private var rollAnimator: ValueAnimator? = null
    private var roll = 1f // 1f == settled; <1f == a digit change is rolling

    /**
     * Report a real percentage. The readout walks to it one unit at a time.
     *
     * Two problems this solves, both about what the number *implies* rather than what
     * it says. The source reports in coarse jumps, so the readout used to leap in tens
     * — and a number that skips reads as a number being estimated, where a number that
     * counts reads as work happening quickly. And on a large file the stream would
     * reach 99 and sit there for fifteen seconds while the last pieces landed, which
     * reads as a hang at the exact moment the user is closest to watching something.
     *
     * So: every intervening value is shown ([STEP_MS] apart), real progress is held
     * below [REAL_CEILING] so the readout never parks on 99, and between updates it
     * creeps on by itself. The last few points belong to [finish], called when playback
     * actually starts — which is the only honest thing 100 can mean.
     */
    fun setPercent(percent: Int) {
        // Paint the starting figure straight away. Otherwise the first thing drawn is
        // whatever the walker reaches after its first tick, and the view sits empty
        // until then — visible as a blank where the readout should have appeared.
        if (currentText.isEmpty()) setPercentText(displayed.toString().padStart(2, '0'))
        val capped = percent.coerceIn(0, 100).coerceAtMost(REAL_CEILING)
        if (capped <= target) {
            // Monotonic. A torrent's completion estimate can revise downward when new
            // metadata lands, and a countdown is not what "loading" should look like.
            scheduleWalk()
            return
        }
        target = capped
        scheduleWalk()
    }

    /** New session: forget the old figure so the next one counts up from nothing. */
    fun reset() {
        removeCallbacks(walkStep)
        walkPosted = false
        displayed = 0
        target = 0
        currentText = ""
        previousText = ""
        rollAnimator?.cancel()
        roll = 1f
        invalidate()
    }

    /** The stream is starting: release the reserved top and run out to 100. */
    fun finish() {
        target = 100
        scheduleWalk()
    }

    private fun scheduleWalk() {
        if (walkPosted) return
        walkPosted = true
        postDelayed(walkStep, if (displayed < target) STEP_MS else CREEP_MS)
    }

    private val walkStep = Runnable {
        walkPosted = false
        when {
            displayed < target -> displayed++
            // Nothing new from the source. Creep so the readout is never frozen, but
            // only up to a point above the last real value — enough to look alive,
            // not enough to invent progress.
            displayed < (target + CREEP_ALLOWANCE).coerceAtMost(REAL_CEILING) -> displayed++
            else -> return@Runnable
        }
        setPercentText(displayed.toString().padStart(2, '0'))
        scheduleWalk()
    }

    private var displayed = 0
    private var target = 0
    private var walkPosted = false

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
                        drawGlyph(canvas, old[i], cellLeft, cell, baseline - roll * travel, 1f - roll)
                        drawGlyph(canvas, text[i], cellLeft, cell, baseline + (1f - roll) * travel, roll)
                    } else {
                        drawGlyph(canvas, text[i], cellLeft, cell, baseline)
                    }
                }
            }

            else -> {
                // Digit count changed (e.g. 99 → 100): scroll the whole number as one block.
                drawRow(canvas, old, w, cell, baseline - roll * travel, 1f - roll)
                drawRow(canvas, text, w, cell, baseline + (1f - roll) * travel, roll)
            }
        }
    }

    private fun widestDigit(): Float {
        var max = 0f
        for (d in '0'..'9') max = maxOf(max, paint.measureText(d.toString()))
        return max
    }

    private fun drawRow(
        canvas: Canvas,
        text: String,
        w: Float,
        cell: Float,
        baseline: Float,
        fade: Float = 1f,
    ) {
        val x0 = (w - text.length * cell) / 2f
        for (i in text.indices) drawGlyph(canvas, text[i], x0 + i * cell, cell, baseline, fade)
    }

    /**
     * @param fade 1 for a settled glyph; for a rolling one it ramps with the animation so
     *   the digit dissolves as it travels. The positional gradient alone was not enough:
     *   a numeral is about twice the height of one fade zone, so it left the band still
     *   largely opaque and then vanished, which read as a hard cut rather than a fade.
     */
    private fun drawGlyph(
        canvas: Canvas,
        ch: Char,
        cellLeft: Float,
        cell: Float,
        baseline: Float,
        fade: Float = 1f,
    ) {
        val s = ch.toString()
        val gw = paint.measureText(s)
        val previous = paint.alpha
        // Modulates the shader, so this rides on top of the positional gradient.
        paint.alpha = (255f * fade.coerceIn(0f, 1f)).toInt()
        canvas.drawText(s, cellLeft + (cell - gw) / 2f, baseline, paint)
        paint.alpha = previous
    }

    private companion object {
        // Fraction of the height taken by each (top and bottom) fade zone. Narrowed
        // from 0.30 to make room for larger numerals without pushing them into the
        // fade; the roll still dissolves, over a shorter distance.
        const val FADE_FRACTION = 0.20f
        // Numeral height as a fraction of the clear reading band between the fades.
        // Together with the wider band above this puts the digits 25% taller than they
        // were (0.40h * 0.92 -> 0.60h * 0.77).
        const val FILL_FRACTION = 0.77f
        const val WIDTH_FILL_FRACTION = 0.82f
        // ~0x1A (~10% white). Was 14 (~5%), which was texture rather than a readable figure —
        // on a TV at viewing distance the number simply could not be made out. Still sits
        // behind the spinner and stats rather than competing with them.
        const val BASE_ALPHA = 26
        // Longer to match the greater distance: same speed, further to go.
        const val ROLL_DURATION_MS = 460L

        /** Gap between successive units while catching up to a new real value. */
        const val STEP_MS = 55L
        /** Gap between the self-driven creeps that keep a stalled readout moving. */
        const val CREEP_MS = 1_400L
        /** How far past the last real value the creep is allowed to wander. */
        const val CREEP_ALLOWANCE = 3
        /**
         * Real progress is never shown above this. The last few points are reserved for
         * [finish], so the readout cannot park on 99 while the final pieces land — the
         * one place the old behaviour looked most like a hang.
         */
        const val REAL_CEILING = 96
        /**
         * How far, in reference-ink heights, a rolling digit travels out and in.
         *
         * One ink height meant the outgoing digit had only cleared its own box by the
         * time the roll finished — the eye read it as a cross-dissolve in place rather
         * than as a number moving. At this distance the digit is plainly travelling,
         * which is the whole point of an odometer: the movement, not the value, is what
         * says the download is going somewhere.
         */
        const val TRAVEL_SLOTS = 2.2f
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
        const val REFERENCE_GLYPHS = "0123456789"
    }
}
