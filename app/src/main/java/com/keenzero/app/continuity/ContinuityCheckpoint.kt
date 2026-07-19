package com.keenzero.app.continuity

import org.json.JSONObject

/**
 * Semantic continuity state. WebView history alone is insufficient for restart resume.
 */
data class ContinuityCheckpoint(
    val origin: String? = null,
    val url: String? = null,
    val contentId: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val scrollY: Int = 0,
    val focusedFingerprint: String? = null,
    val playerType: String? = null,
    val playerOrigin: String? = null,
    val selectedSource: String? = null,
    val playbackPositionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val fullscreen: Boolean = false,
    val playbackMode: Boolean = false,
    val playbackState: String? = null,
    val journeyState: String? = null,
    val subtitleTrack: String? = null,
    val audioTrack: String? = null,
    val qualityPreference: String? = null,
    val adapterVersion: String = "1",
    val policyPackVersion: String = "1",
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("origin", origin)
        .put("url", url)
        .put("contentId", contentId)
        .put("title", title)
        .put("season", season)
        .put("episode", episode)
        .put("scrollY", scrollY)
        .put("focusedFingerprint", focusedFingerprint)
        .put("playerType", playerType)
        .put("playerOrigin", playerOrigin)
        .put("selectedSource", selectedSource)
        .put("playbackPositionSec", playbackPositionSec)
        .put("durationSec", durationSec)
        .put("fullscreen", fullscreen)
        .put("playbackMode", playbackMode)
        .put("playbackState", playbackState)
        .put("journeyState", journeyState)
        .put("subtitleTrack", subtitleTrack)
        .put("audioTrack", audioTrack)
        .put("qualityPreference", qualityPreference)
        .put("adapterVersion", adapterVersion)
        .put("policyPackVersion", policyPackVersion)
        .put("timestampMs", timestampMs)

    companion object {
        private val MEDIA_JOURNEY_STATES = setOf(
            "PLAY_INTENT",
            "RESOLVING",
            "PLAYING",
            "PLAYBACK_MODE",
            "PAUSED",
            "ENDED",
            "RESTORING",
            "RECOVERING",
        )

        fun fromJson(raw: String?): ContinuityCheckpoint? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                ContinuityCheckpoint(
                    origin = o.nullableString("origin"),
                    url = o.nullableString("url"),
                    contentId = o.nullableString("contentId"),
                    title = o.nullableString("title"),
                    season = if (o.has("season") && !o.isNull("season")) o.optInt("season") else null,
                    episode = if (o.has("episode") && !o.isNull("episode")) o.optInt("episode") else null,
                    scrollY = o.optInt("scrollY", 0),
                    focusedFingerprint = o.nullableString("focusedFingerprint"),
                    playerType = o.nullableString("playerType"),
                    playerOrigin = o.nullableString("playerOrigin"),
                    selectedSource = o.nullableString("selectedSource"),
                    playbackPositionSec = o.optDouble("playbackPositionSec", 0.0),
                    durationSec = o.optDouble("durationSec", 0.0),
                    fullscreen = o.optBoolean("fullscreen", false),
                    playbackMode = o.optBoolean("playbackMode", o.optBoolean("fullscreen", false)),
                    playbackState = o.nullableString("playbackState"),
                    journeyState = o.nullableString("journeyState"),
                    subtitleTrack = o.nullableString("subtitleTrack"),
                    audioTrack = o.nullableString("audioTrack"),
                    qualityPreference = o.nullableString("qualityPreference"),
                    adapterVersion = o.optString("adapterVersion", "1"),
                    policyPackVersion = o.optString("policyPackVersion", "1"),
                    timestampMs = o.optLong("timestampMs", 0L),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun JSONObject.nullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key, "")
            return v.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    fun requiresMediaRestore(): Boolean =
        playbackMode ||
            playbackPositionSec > 0.0 ||
            playerType != null ||
            journeyState in MEDIA_JOURNEY_STATES
}
