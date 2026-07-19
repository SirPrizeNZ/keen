package com.keenzero.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural: EXIT_PLAYBACK_MODE must peel document fullscreen
 * (PlaybackOrchestrator OPTIONAL_FULLSCREEN_JS path).
 */
class KeenActivityBackStackTest {

    @Test
    fun exitPlaybackModePeelsDocumentFullscreen() {
        val f = listOf(
            File("src/main/java/com/keenzero/app/KeenActivity.kt"),
            File("app/src/main/java/com/keenzero/app/KeenActivity.kt"),
        ).first { it.exists() }
        val text = f.readText()
        assertTrue(text.contains("exitAllHtmlFullscreen"))
        // EXIT_PLAYBACK branch must call peel before exitPlaybackMode
        val playIdx = text.indexOf("Action.EXIT_PLAYBACK_MODE")
        assertTrue(playIdx > 0)
        val slice = text.substring(playIdx, (playIdx + 800).coerceAtMost(text.length))
        assertTrue(
            "EXIT_PLAYBACK_MODE must call exitAllHtmlFullscreen before exitPlaybackMode",
            slice.indexOf("exitAllHtmlFullscreen") >= 0 &&
                slice.indexOf("exitAllHtmlFullscreen") < slice.indexOf("exitPlaybackMode"),
        )
        assertTrue(text.contains("exitFullscreen") || text.contains("webkitExitFullscreen"))
        assertTrue(text.contains("EXIT_DOCUMENT_FULLSCREEN_JS"))
    }
}
