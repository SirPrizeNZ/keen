package com.keenzero.app.blocking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ensures the shipped host list is a real catalogue, not a dozen hand-picked domains.
 */
class RequestBlockerScaleTest {

    @Test
    fun coreHosts_isMultiThousandAndBlocksKnownAdFarms() {
        val path = File("src/main/assets/blocking/core-hosts.txt")
        assertTrue("core-hosts.txt must exist at $path", path.isFile)
        val lines = path.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(
            "expected multi-thousand host catalogue, got ${lines.size}",
            lines.size >= 2_000,
        )
        val blocker = RequestBlocker.fromLines(lines.asSequence())
        // Known farms / networks must die at network layer (subresource).
        assertTrue(blocker.classify("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", false).blocks)
        assertTrue(blocker.classify("https://hai8g.com/4/1.js", false).blocks)
        assertTrue(blocker.classify("https://cdn.popads.net/pop.js", false).blocks)
        // Main frame never blocked here (NavigationFirewall owns top-level).
        assertFalse(blocker.classify("https://hai8g.com/land", true).blocks)
        // Ordinary streaming origin subresources should not die just for being main-site.
        assertFalse(
            blocker.classify(
                "https://www.cineby.at/static/app.js",
                isMainFrame = false,
                pageHost = "www.cineby.at",
            ).blocks,
        )
    }
}
