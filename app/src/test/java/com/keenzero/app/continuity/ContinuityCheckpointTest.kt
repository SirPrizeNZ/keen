package com.keenzero.app.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityCheckpointTest {
    @Test
    fun roundTripsJson() {
        val cp = ContinuityCheckpoint(
            origin = "https://appassets.androidplatform.net",
            url = "https://appassets.androidplatform.net/assets/lab/vertical_slice.html",
            contentId = "ep-a2",
            title = "A2 Flower",
            playbackPositionSec = 12.5,
            durationSec = 60.0,
            fullscreen = true,
            playbackMode = true,
            playbackState = "playing",
            playerType = "html5-video",
            playerOrigin = "https://appassets.androidplatform.net",
            selectedSource = "mdn-flower",
            policyPackVersion = "1",
            timestampMs = 1_700_000_000_000L,
        )
        val restored = ContinuityCheckpoint.fromJson(cp.toJson().toString())
        assertNotNull(restored)
        assertEquals(cp.url, restored!!.url)
        assertEquals(cp.contentId, restored.contentId)
        assertEquals(cp.title, restored.title)
        assertEquals(cp.playbackPositionSec, restored.playbackPositionSec, 0.001)
        assertEquals(cp.durationSec, restored.durationSec, 0.001)
        assertTrue(restored.fullscreen)
        assertTrue(restored.playbackMode)
        assertEquals("playing", restored.playbackState)
        assertEquals("mdn-flower", restored.selectedSource)
        assertEquals("1", restored.policyPackVersion)
    }

    @Test
    fun nullOnGarbage() {
        assertEquals(null, ContinuityCheckpoint.fromJson("{not json"))
        assertEquals(null, ContinuityCheckpoint.fromJson(null))
        assertEquals(null, ContinuityCheckpoint.fromJson(""))
    }
}
