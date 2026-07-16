package com.keenzero.app.navigation

import android.os.SystemClock

/**
 * Single-use authority produced only by deliberate remote activation
 * (OK / Enter / real pointer click) — never by D-pad move or scroll.
 *
 * Performance: one volatile slot, no locks, no collections on the hot path.
 */
class ActivationLedger(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    @Volatile
    private var grant: ActivationGrant? = null

    enum class Type(val ttlMs: Long) {
        LINK(2_000L),
        PLAY(3_000L),
        AUTH(5_000L),
        FORM(2_500L),
        UNKNOWN(1_000L),
    }

    data class ActivationGrant(
        val type: Type,
        val sourceOrigin: String?,
        val expectedHref: String?,
        val elementRole: String?,
        val fingerprint: String?,
        val createdAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
        @Volatile var usesRemaining: Int = 1,
    ) {
        fun isLive(now: Long): Boolean =
            usesRemaining > 0 && now <= expiresAtElapsedMs
    }

    fun record(
        type: Type,
        sourceOrigin: String?,
        expectedHref: String?,
        elementRole: String? = null,
        fingerprint: String? = null,
    ): ActivationGrant {
        val now = clock()
        val g = ActivationGrant(
            type = type,
            sourceOrigin = sourceOrigin,
            expectedHref = expectedHref,
            elementRole = elementRole,
            fingerprint = fingerprint,
            createdAtElapsedMs = now,
            expiresAtElapsedMs = now + type.ttlMs,
            usesRemaining = 1,
        )
        grant = g
        return g
    }

    fun peek(now: Long = clock()): ActivationGrant? {
        val g = grant ?: return null
        if (!g.isLive(now)) {
            if (grant === g) grant = null
            return null
        }
        return g
    }

    /** Single-use consume. Returns null if expired or already used. */
    fun consume(now: Long = clock()): ActivationGrant? {
        val g = grant ?: return null
        if (!g.isLive(now)) {
            if (grant === g) grant = null
            return null
        }
        if (g.usesRemaining <= 0) return null
        g.usesRemaining = 0
        grant = null
        return g
    }

    fun clear() {
        grant = null
    }

    fun hasValid(now: Long = clock()): Boolean = peek(now) != null
}
