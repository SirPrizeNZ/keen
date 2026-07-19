package com.keenzero.app.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentPlayerPageTest {
    @Test
    fun containsOnlyLocalStreamVideoAndEscapesTitle() {
        val page = TorrentPlayerPage.html("A <Video>")
        assertTrue(page.contains("<video controls autoplay playsinline src=\"/stream\">"))
        assertTrue(page.contains("A &lt;Video&gt;"))
        assertFalse(page.contains("magnet:?"))
    }
}
