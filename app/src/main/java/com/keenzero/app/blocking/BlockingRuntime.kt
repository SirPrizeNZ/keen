package com.keenzero.app.blocking

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide request defence.
 *
 * Performance contract:
 * - Host list compiled off the UI thread; atomic swap.
 * - Intercept reuses a static empty body + header map (no per-block allocation of large objects).
 * - Match latency sampled into a fixed ring (no per-request log).
 * - Top-level page host cached for third-party checks (updated from WebViewClient).
 */
object BlockingRuntime {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "keen-blocking-init").apply { isDaemon = true }
    }
    private val blocker = AtomicReference(RequestBlocker.EMPTY)
    private val ready = AtomicBoolean(false)
    private val pageHost = AtomicReference<String?>(null)

    private val allowed = AtomicInteger(0)
    private val blocked = AtomicInteger(0)
    private val matchNanosSum = AtomicLong(0)
    private val matchCount = AtomicInteger(0)

    // Fixed ring of last sample latencies (ns) for p50/p95-style reads without locks.
    private const val RING = 256
    private val latencyRing = LongArray(RING)
    private val latencyIdx = AtomicInteger(0)

    private val serviceWorkerInstalled = AtomicBoolean(false)

    // Reused empty block response pieces (immutable content).
    private val EMPTY_BODY = ByteArray(0)
    private val BLOCK_HEADERS = mapOf(
        "Cache-Control" to "no-store",
        "Content-Length" to "0",
    )

    fun initialize(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val t0 = SystemClock.elapsedRealtime()
            val compiled = app.assets.open("blocking/core-hosts.txt").bufferedReader().use { reader ->
                RequestBlocker.fromLines(reader.lineSequence())
            }
            blocker.set(compiled)
            ready.set(true)
            Log.i(
                "KeenZero",
                "blocking_ready hosts_compiled_ms=${SystemClock.elapsedRealtime() - t0}",
            )
        }
    }

    /** Update top-level host for third-party classification (page start / commit). */
    fun setPageUrl(url: String?) {
        pageHost.set(url?.let { RequestBlocker.hostOf(it) })
    }

    fun clearPageHost() {
        pageHost.set(null)
    }

    /** Called only when the user deliberately creates the first WebView. */
    fun ensureServiceWorkerInterception() {
        if (serviceWorkerInstalled.get()) return
        installServiceWorkerInterception()
    }

    fun intercept(
        request: WebResourceRequest,
        onBlocked: (url: String, reason: String) -> Unit,
    ): WebResourceResponse? {
        val t0 = System.nanoTime()
        val url = request.url?.toString()
        val host = url?.let { RequestBlocker.hostOf(it) }
        val type = RequestBlocker.resourceTypeOf(request)
        val result = blocker.get().classifyHost(
            host = host,
            isMainFrame = request.isForMainFrame,
            pageHost = pageHost.get(),
            url = url,
            resourceTypeHint = type,
        )
        val dt = System.nanoTime() - t0
        recordLatency(dt)

        if (!result.blocks) {
            allowed.incrementAndGet()
            return null
        }
        blocked.incrementAndGet()
        if (url != null) onBlocked(url, result.name)
        // Fresh InputStream each time (required); body array is shared empty.
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "Blocked by Keen Zero",
            BLOCK_HEADERS,
            ByteArrayInputStream(EMPTY_BODY),
        )
    }

    fun snapshot(): Snapshot {
        val samples = drainLatencySnapshot()
        return Snapshot(
            ready = ready.get(),
            allowedRequests = allowed.get(),
            blockedRequests = blocked.get(),
            serviceWorkerInterception = serviceWorkerInstalled.get(),
            matchCount = matchCount.get(),
            matchP50Us = percentileUs(samples, 0.50),
            matchP95Us = percentileUs(samples, 0.95),
            matchP99Us = percentileUs(samples, 0.99),
            pageHost = pageHost.get(),
            visibility = "WebView + optional SW; engine-private requests not observable",
        )
    }

    data class Snapshot(
        val ready: Boolean,
        val allowedRequests: Int,
        val blockedRequests: Int,
        val serviceWorkerInterception: Boolean,
        val matchCount: Int,
        val matchP50Us: Long,
        val matchP95Us: Long,
        val matchP99Us: Long,
        val pageHost: String?,
        val visibility: String,
    )

    private fun recordLatency(nanos: Long) {
        matchNanosSum.addAndGet(nanos)
        matchCount.incrementAndGet()
        val i = latencyIdx.getAndIncrement() and (RING - 1)
        latencyRing[i] = nanos
    }

    private fun drainLatencySnapshot(): LongArray {
        // Copy ring without locking; samples may tear slightly — acceptable for metrics.
        return latencyRing.copyOf()
    }

    private fun percentileUs(samples: LongArray, p: Double): Long {
        val valid = samples.filter { it > 0L }.sorted()
        if (valid.isEmpty()) return 0L
        val idx = ((valid.size - 1) * p).toInt().coerceIn(0, valid.lastIndex)
        return valid[idx] / 1_000L
    }

    private fun installServiceWorkerInterception() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    intercept(request) { url, reason ->
                        // Bounded: counter already updated; rare SW log only.
                        if (blocked.get() % 25 == 0) {
                            Log.i("KeenZero", "sw_block reason=$reason url=${url.take(160)}")
                        }
                    }
            },
        )
        serviceWorkerInstalled.set(true)
    }
}
