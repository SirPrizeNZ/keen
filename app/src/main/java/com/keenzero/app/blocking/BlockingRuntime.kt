package com.keenzero.app.blocking

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import android.util.Log
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature

/** Process-wide immutable classifier, compiled off the UI thread and atomically swapped. */
object BlockingRuntime {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "keen-blocking-init").apply { isDaemon = true }
    }
    private val blocker = AtomicReference(RequestBlocker.EMPTY)
    private val ready = AtomicBoolean(false)
    private val blocked = AtomicInteger(0)
    private val serviceWorkerInstalled = AtomicBoolean(false)

    fun initialize(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val compiled = app.assets.open("blocking/core-hosts.txt").bufferedReader().use { reader ->
                RequestBlocker.fromLines(reader.lineSequence())
            }
            blocker.set(compiled)
            ready.set(true)
        }
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
        val result = blocker.get().classify(request.url?.toString(), request.isForMainFrame)
        if (!result.blocks) return null
        blocked.incrementAndGet()
        onBlocked(request.url.toString(), result.name)
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "Blocked by Keen Zero",
            mapOf("Cache-Control" to "no-store", "Content-Length" to "0"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    fun snapshot(): Snapshot = Snapshot(
        ready = ready.get(),
        blockedRequests = blocked.get(),
        serviceWorkerInterception = serviceWorkerInstalled.get(),
        visibility = "WebView callbacks${if (serviceWorkerInstalled.get()) " plus service-worker callbacks" else ""}; engine-private requests are not observable",
    )

    data class Snapshot(
        val ready: Boolean,
        val blockedRequests: Int,
        val serviceWorkerInterception: Boolean,
        val visibility: String,
    )

    private fun installServiceWorkerInterception() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    intercept(request) { url, reason ->
                        Log.i("KeenZero", "service_worker_block reason=$reason url=$url")
                    }
            },
        )
        serviceWorkerInstalled.set(true)
    }
}
