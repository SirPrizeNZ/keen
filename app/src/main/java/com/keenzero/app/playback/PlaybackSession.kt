package com.keenzero.app.playback

/**
 * Long-lived playback session. Survives PlayIntent expiry for the full video lifecycle.
 * Owns identity, position, mode flags, audio prefs and checkpoint scheduling metadata.
 */
data class PlaybackSession(
    val sessionId: String,
    val contentId: String? = null,
    val title: String? = null,
    val origin: String? = null,
    val url: String? = null,
    val playerOrigin: String? = null,
    val selectedSource: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val playbackPositionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val playbackState: String? = null,
    val playbackModeActive: Boolean = false,
    val subtitleTrack: String? = null,
    val audioTrack: String? = null,
    val qualityPreference: String? = null,
    val unmuteRequested: Boolean = false,
    val audioFocusGranted: Boolean = false,
    val audibleConfirmed: Boolean = false,
    val startedAtElapsedMs: Long = 0L,
    val lastCheckpointAtElapsedMs: Long = 0L,
    val policyPackVersion: String = "1",
) {
    val isActive: Boolean
        get() = playbackState == "playing" || playbackState == "paused" || playbackModeActive
}
