package com.keenzero.app.torrent

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Recognising a `.torrent` the page offered, and getting hold of its bytes.
 *
 * Shared by **both** WebView modes on purpose. This logic first existed only in
 * `WebViewHost`, so compatibility mode — which had no `DownloadListener` at all — silently
 * dropped every `.torrent` download. That is not a corner case: compatibility mode is
 * where Cloudflare-challenged trackers end up (ext.to promotes to it on `http_403`
 * within two loads), so the one place `.torrent` support was missing was the place torrent
 * sites actually run. Keeping the behaviour here means the two modes cannot drift apart
 * again.
 */
object TorrentDownloadIntercept {

    /** How long the page gets to hand over a .torrent before we fetch it natively. */
    private const val IN_PAGE_TIMEOUT_MS = 15_000L

    /** Matches the streaming service's own cap; a bigger file is not a .torrent. */
    private const val MAX_BYTES = 20 * 1024 * 1024

    /** Does this download look like a torrent file rather than ordinary content? */
    fun isTorrentDownload(url: String?, contentDisposition: String?, mimetype: String?): Boolean {
        if (mimetype?.lowercase() == "application/x-bittorrent") return true
        val path = try {
            android.net.Uri.parse(url ?: return false).path?.lowercase()
        } catch (_: Throwable) {
            null
        }
        if (path?.endsWith(".torrent") == true) return true
        return contentDisposition?.lowercase()?.contains(".torrent") == true
    }

    /** Cookies for [url], for the native fallback fetch. Never throws. */
    fun cookiesFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (_: Throwable) {
        null
    }

    /**
     * Read a .torrent using the page's own network stack, handing the bytes back as
     * base64. Calls [done] with null when that is not possible, meaning "fetch it
     * yourself over plain HTTP".
     *
     * Refetching the URL natively is the obvious implementation and it is the one that
     * fails. A site behind a Cloudflare managed challenge (ext.to answers a bare request
     * with `HTTP 403` and `cf-mitigated: challenge`) hands out clearance tied to far more
     * than the cookie we can copy across: TLS fingerprint, header order, the client hints
     * named in `critical-ch`. `HttpURLConnection` matches none of it, so the "download"
     * came back as a challenge page, bdecode rejected it, and the user saw an error on a
     * link that works in the browser one line above. Issuing the request from inside the
     * page sidesteps all of it — same socket, same jar, clearance already granted.
     *
     * Same-origin in practice: a cross-origin download carries no CORS headers, the fetch
     * throws, and we fall back to the native path — acceptable, because a cross-origin
     * host is usually a plain file mirror with no challenge in front of it.
     *
     * **On compatibility mode's "no injected JavaScript" rule:** this is a one-shot
     * `evaluateJavascript` on an explicit user action, long after the challenge has been
     * cleared and the page has settled. It adds no document-start script and no JS
     * bridge, so nothing about the environment the challenge inspects changes. That is a
     * narrower exception than it looks, and it is the only way the bytes can be had.
     */
    fun fetchInPage(wv: WebView, url: String, done: (String?) -> Unit) {
        var settled = false
        fun finish(base64: String?) {
            if (settled) return
            settled = true
            done(base64)
        }
        // The page can hang on the request for ever; the wait must not.
        wv.postDelayed({ finish(null) }, IN_PAGE_TIMEOUT_MS)
        val js = """
            (async () => {
              try {
                const r = await fetch(${org.json.JSONObject.quote(url)}, {
                  credentials: 'include',
                  redirect: 'follow',
                });
                if (!r.ok) return null;
                const buf = new Uint8Array(await r.arrayBuffer());
                if (!buf.length || buf.length > $MAX_BYTES) return null;
                let s = '';
                for (let i = 0; i < buf.length; i++) s += String.fromCharCode(buf[i]);
                return btoa(s);
              } catch (e) {
                return null;
              }
            })()
        """.trimIndent()
        try {
            wv.evaluateJavascript(js) { raw ->
                // evaluateJavascript hands back a JSON value: "null", or the base64 in
                // quotes. Base64's alphabet needs no JSON escaping, so unwrapping the
                // quotes is the whole decode.
                val value = raw?.trim()
                finish(
                    if (value == null || value == "null" || value.length < 3) {
                        null
                    } else {
                        value.removeSurrounding("\"").takeIf { it.isNotBlank() }
                    },
                )
            }
        } catch (_: Throwable) {
            finish(null)
        }
    }
}
