package com.keenzero.app.supervisor

/**
 * Correlated envelope for every async mutation that may affect product state.
 * Events whose generation IDs do not match the live supervisor are dropped.
 */
data class EventEnvelope(
    val sessionId: String,
    val navigationId: Long,
    val playIntentId: String?,
    val playbackSessionId: String?,
    val sequenceNumber: Long,
    val timestampElapsedMs: Long,
    val type: String,
    val payload: String? = null,
)

/**
 * Generation counters owned by [KeenSupervisor].
 */
class EventGeneration(
    val sessionId: String = java.util.UUID.randomUUID().toString(),
) {
    @Volatile var navigationId: Long = 0L
        private set
    @Volatile var playIntentId: String? = null
    @Volatile var playbackSessionId: String? = null
    @Volatile var sequenceNumber: Long = 0L
        private set

    fun nextSequence(): Long {
        sequenceNumber += 1
        return sequenceNumber
    }

    fun bumpNavigation() {
        navigationId += 1
        playIntentId = null
        // Keep playbackSessionId until explicitly cleared so mid-nav play resolution
        // can still match; clear via [clearPlayback].
    }

    fun setPlayIntent(id: String) {
        playIntentId = id
    }

    fun setPlaybackSession(id: String) {
        playbackSessionId = id
    }

    fun clearPlayback() {
        playIntentId = null
        playbackSessionId = null
    }

    fun envelope(
        type: String,
        timestampElapsedMs: Long,
        payload: String? = null,
        requirePlayIntent: Boolean = false,
        requirePlaybackSession: Boolean = false,
    ): EventEnvelope? {
        if (requirePlayIntent && playIntentId == null) return null
        if (requirePlaybackSession && playbackSessionId == null) return null
        return EventEnvelope(
            sessionId = sessionId,
            navigationId = navigationId,
            playIntentId = playIntentId,
            playbackSessionId = playbackSessionId,
            sequenceNumber = nextSequence(),
            timestampElapsedMs = timestampElapsedMs,
            type = type,
            payload = payload,
        )
    }

    fun accepts(event: EventEnvelope): Boolean {
        if (event.sessionId != sessionId) return false
        if (event.navigationId != navigationId) return false
        if (event.playIntentId != null && playIntentId != null && event.playIntentId != playIntentId) {
            return false
        }
        if (event.playbackSessionId != null && playbackSessionId != null &&
            event.playbackSessionId != playbackSessionId
        ) {
            return false
        }
        return true
    }
}
