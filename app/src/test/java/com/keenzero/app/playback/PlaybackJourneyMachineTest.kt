package com.keenzero.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackJourneyMachineTest {

    @Test
    fun playToPlaybackModePath() {
        val m = PlaybackJourneyMachine()
        assertEquals(PlaybackJourneyState.BROWSING, m.state)
        assertTrue(m.transition(PlaybackJourneyState.PLAY_INTENT, "press"))
        assertTrue(m.transition(PlaybackJourneyState.RESOLVING, "nav"))
        assertTrue(m.transition(PlaybackJourneyState.PLAYING, "media"))
        assertTrue(m.transition(PlaybackJourneyState.PLAYBACK_MODE, "keen"))
        assertEquals(PlaybackJourneyState.PLAYBACK_MODE, m.state)
    }

    @Test
    fun playIntentMayEnterPlaybackModeBeforeMediaConfirm() {
        val m = PlaybackJourneyMachine()
        assertTrue(m.transition(PlaybackJourneyState.PLAY_INTENT, "press"))
        assertTrue(m.transition(PlaybackJourneyState.PLAYBACK_MODE, "play_press_sync"))
        assertEquals(PlaybackJourneyState.PLAYBACK_MODE, m.state)
    }

    @Test
    fun rejectsIllegalJump() {
        val m = PlaybackJourneyMachine()
        assertFalse(m.transition(PlaybackJourneyState.PLAYING, "skip"))
        assertEquals(PlaybackJourneyState.BROWSING, m.state)
    }

    @Test
    fun recoveryFromPlaying() {
        val m = PlaybackJourneyMachine(PlaybackJourneyState.PLAYBACK_MODE)
        assertTrue(m.transition(PlaybackJourneyState.RECOVERING, "renderer"))
        assertTrue(m.transition(PlaybackJourneyState.RESTORING, "recreate"))
        assertTrue(m.transition(PlaybackJourneyState.PLAYBACK_MODE, "resume"))
    }

    @Test
    fun playIntentExpiryDoesNotRequireSessionEnd() {
        // Session can remain in PLAYING after intent conceptually expires.
        val m = PlaybackJourneyMachine()
        assertTrue(m.transition(PlaybackJourneyState.PLAY_INTENT, "press"))
        assertTrue(m.transition(PlaybackJourneyState.PLAYING, "confirmed"))
        assertTrue(m.transition(PlaybackJourneyState.PLAYBACK_MODE, "mode"))
        assertEquals(PlaybackJourneyState.PLAYBACK_MODE, m.state)
    }
}
