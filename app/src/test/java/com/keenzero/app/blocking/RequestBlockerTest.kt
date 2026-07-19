package com.keenzero.app.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBlockerTest {
    private val blocker = RequestBlocker.fromLines(
        sequenceOf("# comment", ".ads.example", "metrics.example"),
    )

    @Test
    fun blocksExactAndSubdomainRules() {
        assertEquals(RequestBlocker.Result.BLOCK_HOST, blocker.classify("https://ads.example/a.js", false))
        assertEquals(RequestBlocker.Result.BLOCK_HOST, blocker.classify("https://cdn.ads.example/a.js", false))
        assertEquals(RequestBlocker.Result.BLOCK_HOST, blocker.classify("https://metrics.example/p", false))
    }

    @Test
    fun neverBlocksMainFrame() {
        assertEquals(
            RequestBlocker.Result.ALLOW_MAIN_FRAME,
            blocker.classify("https://ads.example/", true),
        )
    }

    @Test
    fun doesNotSubstringMatchUnrelatedHosts() {
        assertEquals(RequestBlocker.Result.ALLOW, blocker.classify("https://notads.example/", false))
        assertEquals(RequestBlocker.Result.ALLOW, blocker.classify("https://ads.example.invalid/", false))
    }

    @Test
    fun pathHeuristicBlocksThirdPartyOnly() {
        val b = RequestBlocker.fromLines(emptySequence())
        // First-party pagead path should still trip when pageHost is other
        assertEquals(
            RequestBlocker.Result.BLOCK_PATH,
            b.classify(
                url = "https://cdn.tracker.net/pagead/js",
                isMainFrame = false,
                pageHost = "site.example",
            ),
        )
        // Same registrable family → no path block
        assertEquals(
            RequestBlocker.Result.ALLOW,
            b.classify(
                url = "https://cdn.site.example/pagead/local.js",
                isMainFrame = false,
                pageHost = "www.site.example",
            ),
        )
    }

    @Test
    fun mediaTypeSkipsPathHeuristic() {
        val b = RequestBlocker.fromLines(emptySequence())
        assertEquals(
            RequestBlocker.Result.ALLOW,
            b.classify(
                url = "https://cdn.tracker.net/pagead/stream.m3u8",
                isMainFrame = false,
                pageHost = "site.example",
                resourceTypeHint = RequestBlocker.ResourceType.MEDIA,
            ),
        )
    }

    @Test
    fun hostOfParsesWithoutUri() {
        assertEquals("ads.example", RequestBlocker.hostOf("https://ads.example/x?y=1"))
        assertEquals("cdn.ads.example", RequestBlocker.hostOf("http://cdn.ads.example:8080/p"))
        assertEquals(null, RequestBlocker.hostOf("not-a-url"))
    }

    @Test
    fun hotPathClassifyHostMatches() {
        assertTrue(blocker.classifyHost("ads.example", false, null, "https://ads.example/x", RequestBlocker.ResourceType.SCRIPT).blocks)
        assertFalse(blocker.classifyHost("ok.example", false, null, "https://ok.example/x", RequestBlocker.ResourceType.OTHER).blocks)
    }
}
