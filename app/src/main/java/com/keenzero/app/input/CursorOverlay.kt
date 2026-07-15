package com.keenzero.app.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight native cursor above the WebView.
 * Positions are set every frame by [RemoteInputRouter] for continuous smooth motion.
 */
class CursorOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val radius = 5.4f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isDither = false
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        isDither = false
    }
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    private var centreWhenLaidOut = false

    init {
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
        visibility = GONE
    }

    fun showAtCentre() {
        centreWhenLaidOut = cursorX == 0f && cursorY == 0f
        visibility = VISIBLE
        applyPendingCentre()
        post(::applyPendingCentre)
        invalidate()
    }

    fun hide() {
        visibility = GONE
    }

    /** Absolute placement (used by continuous frame loop). */
    fun setPosition(x: Float, y: Float) {
        if (centreWhenLaidOut) applyPendingCentre()
        if (width == 0 || height == 0) {
            cursorX = x
            cursorY = y
            return
        }
        val margin = radius * 1.5f
        cursorX = min(max(x, margin), max(margin, width - margin))
        cursorY = min(max(y, margin), max(margin, height - margin))
        invalidate()
    }

    fun move(dx: Float, dy: Float): Pair<Float, Float> {
        setPosition(cursorX + dx, cursorY + dy)
        return cursorX to cursorY
    }

    fun viewportSize(): Pair<Int, Int> = width to height

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(cursorX, cursorY, radius + ring.strokeWidth, ring)
        canvas.drawCircle(cursorX, cursorY, radius, fill)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPendingCentre()
    }

    private fun applyPendingCentre() {
        if (!centreWhenLaidOut || width == 0 || height == 0) return
        cursorX = width / 2f
        cursorY = height / 2f
        centreWhenLaidOut = false
        invalidate()
    }
}
