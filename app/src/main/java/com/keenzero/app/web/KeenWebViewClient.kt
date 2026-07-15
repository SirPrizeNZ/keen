package com.keenzero.app.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.keenzero.app.diagnostics.NavigationEvent
import com.keenzero.app.navigation.NavigationFirewall
import com.keenzero.app.blocking.BlockingRuntime
import com.keenzero.app.sitepacks.SitePackRuntime
import org.json.JSONObject

class KeenWebViewClient(
    private val assetLoader: LabAssetLoader,
    private val firewall: NavigationFirewall,
    private val onEvent: (NavigationEvent) -> Unit,
    private val onUrlChanged: (String?) -> Unit,
    private val onRendererGone: (JSONObject) -> Boolean,
    private val onPageFinishedExtra: ((WebView, String?) -> Unit)? = null,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString()
        val decision = firewall.decide(request)
        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "shouldOverrideUrlLoading",
                url = url,
                detail = "decision=$decision mainFrame=${request?.isForMainFrame} gesture=${request?.hasGesture()}",
                isMainFrame = request?.isForMainFrame,
            ),
        )
        if (decision.blocks) {
            onEvent(
                NavigationEvent(
                    t = System.currentTimeMillis(),
                    type = decision.name,
                    url = url,
                    detail = "navigation policy",
                ),
            )
            return true
        }
        return decision.blocks
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val safeRequest = request ?: return null
        assetLoader.intercept(safeRequest)?.let { return it }
        return BlockingRuntime.intercept(safeRequest) { url, reason ->
            onEvent(
                NavigationEvent(
                    t = System.currentTimeMillis(),
                    type = "BLOCK_NETWORK_REQUEST",
                    url = url,
                    detail = reason,
                    isMainFrame = false,
                ),
            )
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        firewall.recordCommittedUrl(url)
        onUrlChanged(url)
        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "onPageStarted",
                url = url,
            ),
        )
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onUrlChanged(url)
        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "onPageFinished",
                url = url,
            ),
        )
        val repairs = SitePackRuntime.repairsFor(url)
        if (view != null && repairs.isNotEmpty()) {
            val selectors = repairs.flatMap { it.selectors }.distinct()
            val quoted = selectors.joinToString(",") { org.json.JSONObject.quote(it) }
            view.evaluateJavascript(
                "[$quoted].forEach(function(s){document.querySelectorAll(s).forEach(function(e){e.style.setProperty('display','none','important');});});",
                null,
            )
            onEvent(
                NavigationEvent(
                    t = System.currentTimeMillis(),
                    type = "SITE_PACK_APPLIED",
                    url = url,
                    detail = "packs=${repairs.joinToString { it.packId }} rules=${selectors.size}",
                    isMainFrame = true,
                ),
            )
        }
        if (view != null) {
            onPageFinishedExtra?.invoke(view, url)
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) {
            onEvent(
                NavigationEvent(
                    t = System.currentTimeMillis(),
                    type = "onReceivedError",
                    url = request.url?.toString(),
                    detail = "code=${error?.errorCode} desc=${error?.description}",
                    isMainFrame = true,
                ),
            )
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        if (request?.isForMainFrame == true) {
            onEvent(
                NavigationEvent(
                    t = System.currentTimeMillis(),
                    type = "onReceivedHttpError",
                    url = request.url?.toString(),
                    detail = "status=${errorResponse?.statusCode}",
                    isMainFrame = true,
                ),
            )
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "onReceivedSslError",
                url = error?.url,
                detail = "primary=${error?.primaryError}",
            ),
        )
        // Non-negotiable: cancel certificate errors.
        handler?.cancel()
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val payload = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("url", view?.url)
            .put(
                "didCrash",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() else null,
            )
            .put(
                "rendererPriorityAtExit",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.rendererPriorityAtExit()
                } else {
                    null
                },
            )
        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "onRenderProcessGone",
                url = view?.url,
                detail = payload.toString(),
            ),
        )
        return onRendererGone(payload)
    }
}
