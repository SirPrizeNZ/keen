package com.keenzero.app.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A layer that can have a circular hole punched through it, growing from the centre.
 *
 * This is how the loading surface leaves: instead of revealing the player *over* the
 * surface, the surface stops drawing where the circle is and the picture below shows
 * through. Same gesture on screen, opposite mechanism — and the difference matters.
 *
 * The obvious implementation, `ViewAnimationUtils.createCircularReveal` on the player
 * lifted above this layer, cannot work here. The player renders through a SurfaceView,
 * which is composited by the system outside the view hierarchy: a reveal clips the
 * View's own drawing but not the surface, and lifting the player above this layer let
 * the surface's full-screen punch-out clear these pixels entirely. What showed around
 * the growing circle was then the window background rather than this layer's black — a
 * pale halo against a dark opening shot, which is exactly what it looked like.
 *
 * Clipping out the hole here needs no lift, no elevation games and no cooperation from
 * the surface: everything below in the hierarchy shows through the hole, which is the
 * normal, well-defined way content appears under a SurfaceView-backed player.
 */
class CircleCutoutFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val cutout = Path()

    /** Radius of the hole in pixels. Zero (or less) draws the layer whole. */
    var cutoutRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            rebuildCutout()
            invalidate()
        }

    private fun rebuildCutout() {
        cutout.reset()
        if (cutoutRadius > 0f) {
            cutout.addCircle(width / 2f, height / 2f, cutoutRadius, Path.Direction.CW)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCutout()
    }

    // draw(), not dispatchDraw(): the background is this layer's opaque black, and it has
    // to be cut away along with the spinner and counters drawn on top of it. dispatchDraw
    // would leave the black behind and punch a hole through the children only.
    override fun draw(canvas: Canvas) {
        if (cutoutRadius <= 0f) {
            super.draw(canvas)
            return
        }
        val save = canvas.save()
        canvas.clipOutPath(cutout)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}
