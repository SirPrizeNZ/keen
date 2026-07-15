package com.keenzero.app.supervisor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventGenerationTest {
    @Test
    fun rejectsStalePlaybackSession() {
        val g = EventGeneration(sessionId = "s1")
        g.setPlaybackSession("p1")
        val env = EventEnvelope(
            sessionId = "s1",
            navigationId = 0,
            playIntentId = null,
            playbackSessionId = "p-old",
            sequenceNumber = 1,
            timestampElapsedMs = 1,
            type = "PLAYBACK_CONFIRMED",
        )
        assertFalse(g.accepts(env))
    }

    @Test
    fun acceptsMatchingGeneration() {
        val g = EventGeneration(sessionId = "s1")
        g.bumpNavigation()
        g.setPlayIntent("pi1")
        g.setPlaybackSession("ps1")
        val env = g.envelope("PLAY_ACK", 10L, payload = "x")
        assertTrue(env != null && g.accepts(env!!))
    }
}
