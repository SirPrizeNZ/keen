package com.keenzero.app.input

/**
 * Pure scroll-ownership policy (shipped logic).
 * JS install in [ScrollAuthorityJs] must match this state machine.
 *
 * Rules:
 * - Default DENY site viewport moves.
 * - Grant only via [allow] / [noteUser] (Keen D-pad).
 * - [freeze] pins Y during activate→navigate; freeze denies grants and site scroll.
 * - Deliberate click intent (__keenNativeIntent) is NOT a scroll grant.
 */
class ScrollAuthorityPolicy(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var allowUntilMs: Long = 0L
    private var freezeUntilMs: Long = 0L
    var lastX: Double = 0.0
        private set
    var lastY: Double = 0.0
        private set
    var freezeX: Double = 0.0
        private set
    var freezeY: Double = 0.0
        private set
    var blockedCount: Int = 0
        private set

    fun remember(x: Double, y: Double) {
        lastX = x
        lastY = y
    }

    fun isFrozen(now: Long = clockMs()): Boolean = now < freezeUntilMs

    fun isAllowed(now: Long = clockMs()): Boolean {
        if (isFrozen(now)) return false
        return now < allowUntilMs
    }

    fun allow(ms: Long, now: Long = clockMs()) {
        if (isFrozen(now)) return
        allowUntilMs = now + ms.coerceAtLeast(50L)
    }

    fun noteUser(now: Long = clockMs()) {
        allow(NOTE_USER_MS, now)
    }

    /** Pin current position for [ms] — used right before location.assign. */
    fun freeze(x: Double, y: Double, ms: Long, now: Long = clockMs()) {
        freezeX = x
        freezeY = y
        lastX = x
        lastY = y
        freezeUntilMs = now + ms.coerceAtLeast(100L)
        allowUntilMs = 0L
    }

    /**
     * Whether a site-requested main-viewport jump should apply.
     * Tiny jitter allowed; large jumps denied unless granted.
     */
    fun shouldApplySiteScroll(
        fromY: Double,
        toY: Double,
        fromX: Double = lastX,
        toX: Double = lastX,
        now: Long = clockMs(),
        jitterPx: Double = JITTER_PX,
    ): Boolean {
        if (isFrozen(now)) {
            blockedCount++
            return false
        }
        if (isAllowed(now)) return true
        val dy = kotlin.math.abs(toY - fromY)
        val dx = kotlin.math.abs(toX - fromX)
        if (dy < jitterPx && dx < jitterPx) return true
        blockedCount++
        return false
    }

    /** Target position when denying a site yank. */
    fun restoreTarget(now: Long = clockMs()): Pair<Double, Double> =
        if (isFrozen(now)) freezeX to freezeY else lastX to lastY

    companion object {
        const val NOTE_USER_MS = 520L
        const val FREEZE_ON_NAVIGATE_MS = 2500L
        const val JITTER_PX = 8.0
        /** Install must not treat native intent as scroll grant. */
        const val NATIVE_INTENT_IS_NOT_SCROLL_GRANT = true
    }
}
