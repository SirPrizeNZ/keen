package com.keenzero.app.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.keenzero.app.R

/**
 * The loading overlay's peer read-out: two figures, each over a label.
 *
 * Seeds is the swarm's count, not this box's socket count — the two answer different
 * questions and only one of them is worth a number on screen. Connections build over the
 * first ten seconds, so our own count reads "2" while a laptop shows the same torrent as
 * 55, and a readout that says 2 at the moment you press play is the one thing this screen
 * must never do: people turn the app off and never learn it was working. The rate beside
 * it is what we are actually pulling, which is the other half of "is this going to play".
 *
 * Drawn rather than laid out, because the geometry is a grid the frame specifies to the
 * unit and four views' worth of margins would only approximate it. Positions below are in
 * the frame's own units, scaled by [unit], so they can be checked against it directly.
 */
class SwarmStatsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.05f
        typeface = ResourcesCompat.getFont(context, R.font.gsflex_big)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.05f
        typeface = ResourcesCompat.getFont(context, R.font.gsflex_label)
    }
    private val seedsLabel: String = context.getString(R.string.torrent_stat_seeds)

    private var seeds = ""
    private var rateDown = ""

    /**
     * The count-up. Seeds rolls to its new figure; the rate never does.
     *
     * They look like the same kind of number and are not. Seeds is a total settling into
     * place — it arrives as one jump from a dash to forty-odd, and counting through that
     * span is what reads as "we are finding the swarm" rather than as a figure blinking
     * into existence. The rate is a live measurement resampled every 750 ms: rolling it
     * would mean the number on screen is never the rate, always a point a few hundred
     * milliseconds behind, and a digit column that never stops moving next to a percent
     * that is also moving is the distraction this screen can least afford.
     */
    private var seedsAnimator: ValueAnimator? = null
    private var seedsShown = 0
    private var seedsTarget = 0

    /**
     * Swapped outright, never rolled. The unit is not a value changing, it is the same
     * value being said a different way, and sliding it made a quiet relabelling look like
     * an event — on the one line where nothing has actually happened.
     */
    private var rateUnit = ""

    /**
     * Starts true so the very first bytes land as a big moving number rather than as
     * "0.0". Sticky in both directions — see [chooseUnit].
     */
    private var kilobytes = true

    /** Device px per frame unit, from the view's width. Everything below scales by this. */
    private var unit = 1f

    /**
     * @param seedsInSwarm the tracker and DHT figure, not our connection count. See the
     *   class note for why this readout is the swarm's and not ours.
     * @param pending true before a real breakdown has arrived: the count shows a dash
     *   rather than a zero, because zero this early is a state and not a measurement.
     */
    fun setStats(seedsInSwarm: Int, downBps: Long, pending: Boolean) {
        chooseUnit(downBps)
        if (pending) {
            // Back to a dash, and back to zero behind it, so the first real figure counts
            // up from nothing the way the swarm actually fills rather than from whatever
            // the previous stream happened to end on.
            seedsAnimator?.cancel()
            seedsShown = 0
            seedsTarget = 0
            seeds = PENDING
        } else {
            rollSeedsTo(seedsInSwarm.coerceAtLeast(0))
        }
        rateDown = formatRate(downBps)
        rateUnit = if (kilobytes) "KB/s" else "MB/s"
        invalidate()
    }

    /**
     * Roll the seed count to [target], redrawing only when the printed digits change.
     *
     * That last part is the whole performance story. A ValueAnimator hands you a new
     * fraction every frame, but this view prints an integer: counting 0 to 45 has 45
     * distinct things to show, and invalidating on all 36 frames of a 600 ms roll would
     * be 36 draws to show 45 states, most of them identical to the one before. Redrawing
     * on the integer instead makes the work proportional to the count, not to the frame
     * rate, and the draw itself is four drawText calls into a 201x58dp view with no
     * layout, no allocation and no bitmap behind it.
     *
     * Duration follows the distance so a jump from 2 to 3 does not take as long as a jump
     * from 0 to 400, and is capped so a huge swarm still lands before the wait is over.
     */
    private fun rollSeedsTo(target: Int) {
        val from = seedsShown
        val distance = kotlin.math.abs(target - from)
        // Nothing worth animating: land on the figure and, crucially, print it. One
        // number is not a roll — the animation would be over before it was legible, which
        // reads as a flicker rather than as counting — and zero steps is no change at all.
        //
        // Returning early on an unchanged target without writing the text is a trap, and
        // one this readout must not fall into. A dash reset leaves the target at zero, so
        // a torrent whose first real count is zero — a genuine drought, the STAGE_NO_PEERS
        // case the row above deliberately prints the zeros for — matches the target it
        // already had, and the dash would stand for the rest of the session. That is the
        // readout hiding the number at exactly the moment it turns bad.
        if (distance < 2) {
            seedsAnimator?.cancel()
            seedsTarget = target
            seedsShown = target
            seeds = target.toString()
            return
        }
        if (target == seedsTarget && seedsAnimator?.isRunning == true) return
        seedsTarget = target
        seedsAnimator?.cancel()
        seedsAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = (ROLL_MS_PER_STEP * distance)
                .coerceIn(ROLL_MIN_MS, ROLL_MAX_MS)
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                if (value == seedsShown) return@addUpdateListener
                seedsShown = value
                seeds = value.toString()
                invalidate()
            }
            start()
        }
    }

    /**
     * A detached view keeps no animator running. The overlay is torn down the moment
     * playback starts, and an animator outliving it would go on posting frames to a view
     * nobody is looking at, during the seconds the player is opening the container — the
     * most contended part of the whole session.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        seedsAnimator?.cancel()
        seedsAnimator = null
    }

    /**
     * Kilobytes until the stream is genuinely doing megabytes, and with a gap between the
     * two thresholds so it cannot oscillate.
     *
     * Both halves of that matter to how the wait reads. A stream opens at tens of KB/s,
     * where megabytes round to "0.1" and sit there — a number that is not moving, on the
     * one screen where the whole job is to show that something is. In kilobytes the same
     * bytes read 90, 240, 600, which is visibly a stream picking up speed. And a single
     * threshold would flip the label every tick while the rate hovered around a megabyte,
     * swapping "1024 KB/s" and "1.0 MB/s" back and forth, so the promotion happens at a
     * megabyte and the demotion only when it drops well back below one.
     */
    private fun chooseUnit(downBps: Long) {
        kilobytes = when {
            downBps >= PROMOTE_BPS -> false
            downBps < DEMOTE_BPS -> true
            else -> kilobytes
        }
    }

    private fun formatRate(bps: Long): String {
        if (bps <= 0) return "0"
        if (kilobytes) return (bps / 1024L).coerceAtLeast(1L).toString()
        val mb = bps / 1_048_576.0
        return when {
            mb >= 10 -> String.format(java.util.Locale.US, "%.0f", mb)
            mb < 0.05 -> "0"
            else -> String.format(java.util.Locale.US, "%.1f", mb)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        unit = if (w > 0) w / DESIGN_W else 1f
        numberPaint.textSize = NUMBER_SIZE * unit
        labelPaint.textSize = LABEL_SIZE * unit
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (unit <= 0f || seeds.isEmpty()) return

        val numberBaseline = baselineOf(numberPaint, NUMBER_TOP, NUMBER_LINE)
        canvas.drawText(seeds, columnCentre(0f), numberBaseline, numberPaint)
        canvas.drawText(rateDown, columnCentre(COLUMN_PITCH), numberBaseline, numberPaint)

        val labelBaseline = baselineOf(labelPaint, LABEL_TOP, LABEL_LINE)
        canvas.drawText(seedsLabel, columnCentre(0f), labelBaseline, labelPaint)
        canvas.drawText(rateUnit, columnCentre(COLUMN_PITCH), labelBaseline, labelPaint)
    }

    private fun columnCentre(x: Float): Float = (LEFT_PAD + x + COLUMN_W / 2f) * unit

    /**
     * Where the frame puts the baseline of a text box: the font's own ascent-to-descent
     * box centred inside the stated line height, with the baseline one ascent down from
     * the top of that. Worked out from the metrics rather than kept as a per-role constant
     * because the two roles here run at different line heights — 67% of type size on the
     * figures, 120% on the labels — and no single ratio describes both.
     */
    private fun baselineOf(paint: Paint, boxTop: Float, lineHeight: Float): Float {
        val fm = paint.fontMetrics
        val leading = (lineHeight * unit - (fm.descent - fm.ascent)) / 2f
        return (TOP_PAD + boxTop) * unit + leading - fm.ascent
    }

    private companion object {
        /**
         * The frame's own units. The lock-up is 468.74 wide and 126 tall; the rest of
         * [DESIGN_W] is the margin the figures spill into — they are set on a line two
         * thirds of their type size, so they overrun their own boxes by design.
         */
        const val DESIGN_W = 502f
        const val LEFT_PAD = 16f
        const val TOP_PAD = 12f

        const val COLUMN_W = 193.736f

        /**
         * Wider than the frame's 208. The frame draws a single digit in each column; a real
         * read-out says "206" and "968", and at that width the two figures very nearly
         * touch. This is the gap the frame's spacing implies once the numbers are as long
         * as they actually get.
         */
        const val COLUMN_PITCH = 275f

        const val NUMBER_SIZE = 100f
        const val LABEL_SIZE = 30.252f

        /** Deliberately tighter than the type size: the frame sets these lines at 67%. */
        const val NUMBER_LINE = 67.358f
        const val LABEL_LINE = 36.302f

        const val NUMBER_TOP = 0f
        const val LABEL_TOP = 90f

        /** Promote at a megabyte a second; drop back only well below it. */
        const val PROMOTE_BPS = 1_048_576L
        const val DEMOTE_BPS = 838_860L
        const val PENDING = "–"

        /**
         * Roll pacing. Per-step so short hops stay brisk, floored so a two-step change is
         * still visibly a count, ceilinged so a four-figure swarm does not spend the whole
         * wait counting.
         */
        const val ROLL_MS_PER_STEP = 14L
        const val ROLL_MIN_MS = 260L
        const val ROLL_MAX_MS = 900L

    }
}
