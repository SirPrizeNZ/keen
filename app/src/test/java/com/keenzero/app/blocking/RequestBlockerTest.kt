package com.keenzero.app.blocking

import org.junit.Assert.assertEquals
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
}
