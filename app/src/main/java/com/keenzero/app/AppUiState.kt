package com.keenzero.app

/**
 * Visible app UI states. Playback journey states live in
 * [com.keenzero.app.playback.PlaybackJourneyState]; this enum is the Activity shell.
 */
enum class AppUiState {
    HOME,
    BROWSING,
    NATIVE_OVERLAY,
    /** HTML custom-view fullscreen (secondary). */
    WEB_FULLSCREEN,
    /** Keen-owned immersive playback surface (primary). */
    PLAYBACK_MODE,
    RESTORING,
    RECOVERY,
}
