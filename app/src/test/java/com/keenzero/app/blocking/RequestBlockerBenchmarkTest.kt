package com.keenzero.app.blocking

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom

/** Local JVM match-latency budget (not Android ARMv7 device). */
class RequestBlockerBenchmarkTest {
    private val blocker = RequestBlocker.fromLines(
        sequenceOf(
            ".doubleclick.net",
            ".googlesyndication.com",
            ".adnxs.com",
            ".scorecardresearch.com",
            ".ads.example",
        ),
    )

    @Test
    fun matchLatencyBudgetJvm() {
        val urls = listOf(
            "https://cdn.site.example/app.js",
            "https://www.site.example/api/v1",
            "https://doubleclick.net/gampad/ads",
            "https://cdn.tracker.net/pagead/js",
            "https://media.site.example/v.m3u8",
            "https://metrics.example/pixel",
        )
        val samples = LongArray(5_000)
        // Warmup
        repeat(500) {
            val u = urls[it % urls.size]
            blocker.classify(u, false, "site.example", RequestBlocker.ResourceType.OTHER)
        }
        for (i in samples.indices) {
            val u = urls[i % urls.size]
            val t0 = System.nanoTime()
            blocker.classify(u, false, "site.example", RequestBlocker.ResourceType.SCRIPT)
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        fun pct(p: Double) = samples[((samples.size - 1) * p).toInt()] / 1_000.0
        val p50 = pct(0.50)
        val p95 = pct(0.95)
        val p99 = pct(0.99)
        val maxUs = samples.last() / 1_000.0
        println("REQUEST_MATCH_US p50=$p50 p95=$p95 p99=$p99 max=$maxUs n=${samples.size}")
        // Generous JVM budget — document only; Android device may differ.
        assertTrue("p95 should be under 500us on JVM microbench, was $p95", p95 < 500.0)
        assertTrue("p99 should be under 2000us on JVM microbench, was $p99", p99 < 2_000.0)
    }
}
