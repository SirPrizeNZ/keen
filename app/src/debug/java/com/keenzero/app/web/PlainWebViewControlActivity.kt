package com.keenzero.app.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.keenzero.app.BuildConfig
import com.keenzero.app.R
import com.keenzero.app.diagnostics.LabSignal
import org.json.JSONObject
import java.io.File

/**
 * Layer A baseline control: stock System WebView with JS/DOM enabled and
 * **no** Keen NavigationFirewall, RequestBlocker, PopupQuarantine, or remote router.
 *
 * Debug/lab only. Used to separate Keen application logic from engine/site behaviour.
 * Not a product surface.
 */
class PlainWebViewControlActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var lastUrl: String? = null
    private var lastTitle: String? = null
    private var lastError: String? = null
    private var pageFinishedCount: Int = 0
    private var receivedErrorCount: Int = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        setContentView(R.layout.activity_plain_control)
        webView = findViewById(R.id.plainWebView)
        status = findViewById(R.id.plainStatus)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.loadsImagesAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                lastUrl = url
                lastError = null
                status.text = "plain loading: $url"
                LabSignal.emit("plain_page_started", mapOf("url" to url))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                lastUrl = url
                lastTitle = view?.title
                pageFinishedCount += 1
                status.text = "plain loaded: $url"
                LabSignal.emit(
                    "plain_page_finished",
                    mapOf(
                        "url" to url,
                        "title" to view?.title,
                        "pageFinishedCount" to pageFinishedCount,
                    ),
                )
                writeSnapshot("page_finished")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                receivedErrorCount += 1
                lastError = "${error?.errorCode}: ${error?.description}"
                LabSignal.emit(
                    "plain_page_error",
                    mapOf(
                        "url" to request.url?.toString(),
                        "error" to lastError,
                    ),
                )
                writeSnapshot("page_error")
            }
        }
        webView.webChromeClient = WebChromeClient()

        LabSignal.emit(
            "plain_control_ready",
            mapOf(
                "package" to packageName,
                "webview" to (WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"),
            ),
        )
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_EXPORT, false)) {
            writeSnapshot("export")
            LabSignal.emit("plain_export_done", mapOf("url" to lastUrl, "error" to lastError))
            return
        }
        val url = intent.getStringExtra(EXTRA_URL)
            ?: intent.dataString
            ?: return
        lastUrl = url
        lastError = null
        pageFinishedCount = 0
        receivedErrorCount = 0
        status.text = "plain open: $url"
        LabSignal.emit("plain_open_url", mapOf("url" to url))
        webView.loadUrl(url)
    }

    private fun writeSnapshot(reason: String) {
        val o = JSONObject()
            .put("layer", "plain_webview")
            .put("reason", reason)
            .put("t", System.currentTimeMillis())
            .put("url", lastUrl)
            .put("title", lastTitle)
            .put("lastError", lastError)
            .put("pageFinishedCount", pageFinishedCount)
            .put("receivedErrorCount", receivedErrorCount)
            .put("packageName", packageName)
            .put(
                "webview",
                JSONObject()
                    .put("package", WebView.getCurrentWebViewPackage()?.packageName)
                    .put("versionName", WebView.getCurrentWebViewPackage()?.versionName)
                    .put("versionCode", WebView.getCurrentWebViewPackage()?.longVersionCode),
            )
            .put(
                "device",
                JSONObject()
                    .put("model", Build.MODEL)
                    .put("release", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull())
                    .put("processIs64Bit", android.os.Process.is64Bit()),
            )
        try {
            val dir = File(filesDir, "evidence/plain-control")
            dir.mkdirs()
            File(dir, "latest.json").writeText(o.toString(2))
        } catch (_: Exception) {
            // Lab best-effort only.
        }
        LabSignal.emitJson("plain_snapshot", o)
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.keenzero.app.extra.PLAIN_URL"
        const val EXTRA_EXPORT = "com.keenzero.app.extra.PLAIN_EXPORT"
    }
}
