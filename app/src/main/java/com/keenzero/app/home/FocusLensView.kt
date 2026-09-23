package com.keenzero.app.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The focus mark behind the player's centre controls: a disc of frosted film.
 *
 * One of these serves the whole row. It glides to whichever button has focus, so every
 * control gets the same circle, where Media3's own highlight was a circle on some buttons
 * and a square on others. Inside the disc is the picture behind it, blurred and taken
 * darker than the scrim around it, so the white icon sits on a pool of smoked glass.
 * Dark, not light: a pale disc fought the white icons for contrast. No rim: the edge of
 * the blur is the edge of the disc.
 *
 * The picture comes from [setSample], a few dozen pixels already blurred and scaled up
 * here with filtering. Without one (paused before the first copy, or a surface that
 * cannot be read) the disc draws the tint alone, which is still a clear circle.
 */
class FocusLensView(context: Context) : View(context) {

    private val clip = Path()
    private val bounds = RectF()
    private var sample: Bitmap? = null

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VEIL }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EMPTY }

    init {
        // Decorative: the button underneath is what holds focus and is announced.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    /** The blurred picture to show inside the disc, or null for the veil alone. */
    fun setSample(bitmap: Bitmap?) {
        sample = bitmap
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addOval(bounds, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val save = canvas.save()
        canvas.clipPath(clip)
        val image = sample
        if (image != null && !image.isRecycled) {
            canvas.drawBitmap(image, null, bounds, imagePaint)
            canvas.drawOval(bounds, veilPaint)
        } else {
            canvas.drawOval(bounds, emptyPaint)
        }
        canvas.restoreToCount(save)
    }

    private companion object {
        /** Smokes the blurred film. The disc shows the film without the controller's 60%
         * scrim, so anything under 60% here comes out lighter than its surroundings on a
         * bright scene. 65% keeps it just darker than the scrim while letting the film's
         * colour through; 75% read as too dark on the box. */
        const val VEIL = 0xA6000000.toInt()
        /** No picture to show: a plain dark disc. */
        const val EMPTY = 0x99000000.toInt()
    }
}
