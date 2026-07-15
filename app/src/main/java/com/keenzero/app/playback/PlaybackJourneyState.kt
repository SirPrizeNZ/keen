package com.keenzero.app.playback

/**
 * Explicit playback journey states for the controlled vertical slice.
 *
 * Ownership:
 * - [PlayIntent] is short-lived navigation authority (expires after resolution window).
 * - [PlaybackSession] is long-lived across PLAYING…ENDED and owns checkpointing,
 *   audio, playback mode, restoration and recovery.
 */
enum class PlaybackJourneyState {
    BROWSING,
    PLAY_INTENT,
    RESOLVING,
    PLAYING,
    PLAYBACK_MODE,
    PAUSED,
    ENDED,
    RESTORING,
    RECOVERING,
    FAILED,
}

/**
 * Legal transitions for the journey machine. Invalid transitions return false.
 */
object PlaybackJourneyTransitions {
    private val edges: Map<PlaybackJourneyState, Set<PlaybackJourneyState>> = mapOf(
        PlaybackJourneyState.BROWSING to setOf(
            PlaybackJourneyState.PLAY_INTENT,
            PlaybackJourneyState.RESTORING,
            PlaybackJourneyState.RECOVERING,
            PlaybackJourneyState.FAILED,
        ),
        PlaybackJourneyState.PLAY_INTENT to setOf(
            PlaybackJourneyState.RESOLVING,
            PlaybackJourneyState.PLAYING,
            // Native Playback Mode may enter within one frame of Play, before media confirms.
            PlaybackJourneyState.PLAYBACK_MODE,
            PlaybackJourneyState.BROWSING,
            PlaybackJourneyState.FAILED,
            PlaybackJourneyState.RECOVERING,
        ),
        PlaybackJourneyState.RESOLVING to setOf(
            PlaybackJourneyState.PLAYING,
            PlaybackJourneyState.PLAYBACK_MODE,
            PlaybackJourneyState.BROWSING,
            PlaybackJourneyState.FAILED,
            PlaybackJourneyState.RECOVERING,
        ),
        PlaybackJourneyState.PLAYING to setOf(
            PlaybackJourneyState.PLAYBACK_MODE,
            PlaybackJourneyState.PAUSED,
            PlaybackJourneyState.ENDED,
            PlaybackJourneyState.FAILED,
            PlaybackJourneyState.RECOVERING,
            PlaybackJourneyState.BROWSING,
        ),
        PlaybackJourneyState.PLAYBACK_MODE to setOf(
            PlaybackJourneyState.PLAYING,
            PlaybackJourneyState.PAUSED,
            PlaybackJourneyState.ENDED,
            PlaybackJourneyState.BROWSING,
            PlaybackJourneyState.FAILED,
            PlaybackJourneyState.RECOVERING,
            PlaybackJourneyState.RESOLVING,
        ),
        PlaybackJourneyState.PAUSED to setOf(
            PlaybackJourneyState.PLAYING,
            PlaybackJourneyState.PLAYBACK_MODE,
            PlaybackJourneyState.ENDED,
            PlaybackJourneyState.BROWSING,
            PlaybackJourneyState.FAILED,
            PlaybackJourneyState.RECOVERING,
        ),
        PlaybackJourneyState.ENDED to setOf(
            PlaybackJourneyState.BROWSING,
            PlaybackJourneyState.PLAY_INTENT,
            PlaybackJourneyState.RECOVERING,
        ),
        PlaybackJourneyState.RESTORING to setOf(
            PlaybackJourneyState.PLAYING,
            PlaybackJourneyState.PLAYBACK_MODE,
            PlaybackJourneyState.PAUSED,
            PlaybackJourneyState.FAILED,
            PlaybackJourneyState.RECOVERING,
            PlaybackJourneyState.BROWSING,
        ),
        PlaybackJourneyState.RECOVERING to setOf(
            PlaybackJourneyState.RESTORING,
            PlaybackJourneyState.BROWSING,
            PlaybackJourneyState.PLAYING,
            PlaybackJourneyState.PLAYBACK_MODE,
            PlaybackJourneyState.FAILED,
        ),
        PlaybackJourneyState.FAILED to setOf(
            PlaybackJourneyState.BROWSING,
            PlaybackJourneyState.RECOVERING,
            PlaybackJourneyState.RESTORING,
        ),
    )

    fun canTransition(from: PlaybackJourneyState, to: PlaybackJourneyState): Boolean {
        if (from == to) return true
        return edges[from]?.contains(to) == true
    }
}

/** Mutable holder used by [PlaybackOrchestrator] and UI. */
class PlaybackJourneyMachine(
    initial: PlaybackJourneyState = PlaybackJourneyState.BROWSING,
    private val onTransition: ((from: PlaybackJourneyState, to: PlaybackJourneyState, reason: String) -> Unit)? = null,
) {
    var state: PlaybackJourneyState = initial
        private set

    fun transition(to: PlaybackJourneyState, reason: String): Boolean {
        if (!PlaybackJourneyTransitions.canTransition(state, to)) {
            onTransition?.invoke(state, to, "REJECTED:$reason")
            return false
        }
        val from = state
        state = to
        onTransition?.invoke(from, to, reason)
        return true
    }

    fun force(to: PlaybackJourneyState, reason: String) {
        val from = state
        state = to
        onTransition?.invoke(from, to, "FORCE:$reason")
    }

    fun reset() {
        state = PlaybackJourneyState.BROWSING
    }
}
