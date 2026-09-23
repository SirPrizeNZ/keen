package com.keenzero.app.playback

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView

/**
 * Reads back what the video surface is showing, a few pixels at a time.
 *
 * The torrent player renders through a SurfaceView, which the system composites outside
 * the view hierarchy, so nothing drawn in the hierarchy can see it: no blur, no colour
 * pick, no "is this frame black". PixelCopy is the supported way to ask for the surface's
 * current frame, scaled into a bitmap of our choosing. Asking for a tiny bitmap keeps it
 * cheap, and every caller here only needs a tiny one.
 *
 * Measured on the Mi Box (Amlogic, Android 14): the hardware decoder's frames are
 * readable this way, the same frames `screencap` records. A protected (DRM) surface is
 * not, and reports failure, which every caller treats as "no picture information".
 */
object SurfaceSampler {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Copy [src], in the surface view's own coordinates, into [dest], scaled to fit.
     * [done] runs on the main thread with whether the copy succeeded.
     */
    fun copy(view: SurfaceView, src: Rect?, dest: Bitmap, done: (Boolean) -> Unit) {
        val surface = view.holder.surface
        if (surface == null || !surface.isValid || view.width <= 0 || view.height <= 0 ||
            (src != null && src.isEmpty)
        ) {
            done(false)
            return
        }
        try {
            PixelCopy.request(view, src, dest, { result -> done(result == PixelCopy.SUCCESS) }, main)
        } catch (_: IllegalArgumentException) {
            // Thrown for a surface that went away between the check above and the request.
            done(false)
        }
    }

    /** Mean luma of [bitmap], 0 (black) to 1 (white), Rec. 709 weights. */
    fun meanLuma(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return 0f
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        return (sum / (pixels.size * 255.0)).toFloat()
    }

    /**
     * Blur [bitmap] in place with [passes] of a 3x3 box filter.
     *
     * Only ever run on a bitmap a few dozen pixels across, where it costs microseconds.
     * Scaled up with filtering afterwards, that reads as a wide, soft blur, which on
     * API 29 and 30 is the only blur available at all.
     */
    fun boxBlur(bitmap: Bitmap, passes: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return
        var src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        var dst = IntArray(w * h)
        repeat(passes) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= h) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx < 0 || xx >= w) continue
                            val p = src[yy * w + xx]
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            n++
                        }
                    }
                    dst[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            val t = src
            src = dst
            dst = t
        }
        bitmap.setPixels(src, 0, w, 0, 0, w, h)
    }
}
