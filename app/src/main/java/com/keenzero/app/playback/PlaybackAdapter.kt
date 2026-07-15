package com.keenzero.app.playback

/**
 * Playback backend seam. Web remains default for MVP.
 * Native media is only valid when auth/cookies/headers/subs/tracks/quality/
 * position/source refresh/DRM continuity can be preserved — not universal extraction.
 */
interface PlaybackAdapter {
    val name: String
}

/** Default: media plays inside the single System WebView session. */
class WebPlaybackAdapter : PlaybackAdapter {
    override val name: String = "web"
}

/**
 * Stub only. Do not enable for MVP without a measured, narrow, authorised case.
 */
class NativeMediaAdapter : PlaybackAdapter {
    override val name: String = "native-media-stub"
    val enabled: Boolean = false
}
