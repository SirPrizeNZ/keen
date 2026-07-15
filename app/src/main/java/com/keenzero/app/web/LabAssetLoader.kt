package com.keenzero.app.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/** Serves the frozen lab corpus over a secure HTTPS origin, never file://. */
class LabAssetLoader(context: Context) {
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    fun intercept(request: WebResourceRequest): WebResourceResponse? =
        loader.shouldInterceptRequest(request.url)
}
