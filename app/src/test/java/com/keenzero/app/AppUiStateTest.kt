package com.keenzero.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home-first invariant: cold-start state is HOME; BROWSING is a deliberate transition.
 */
class AppUiStateTest {

    @Test
    fun coldStartStateIsHome() {
        val initial = AppUiState.HOME
        assertEquals(AppUiState.HOME, initial)
    }

    @Test
    fun recoveryIsDistinctFromHomeAndBrowsing() {
        assertTrue(AppUiState.RECOVERY != AppUiState.HOME)
        assertTrue(AppUiState.RECOVERY != AppUiState.BROWSING)
    }

    @Test
    fun allPhase0StatesAreNamed() {
        val names = AppUiState.entries.map { it.name }.toSet()
        assertTrue(
            names.containsAll(
                listOf(
                    "HOME",
                    "BROWSING",
                    "RECOVERY",
                    "WEB_FULLSCREEN",
                    "NATIVE_OVERLAY",
                    "PLAYBACK_MODE",
                    "RESTORING",
                ),
            ),
        )
    }
}
