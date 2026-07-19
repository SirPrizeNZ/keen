package com.keenzero.app.torrent

object TorrentPlayerPage {
    fun html(title: String): String {
        val safeTitle = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        return """<!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>$safeTitle</title>
            <style>html,body,video{width:100%;height:100%;margin:0;background:#000}video{object-fit:contain}</style>
            </head><body><video controls autoplay playsinline src="/stream"></video></body></html>""".trimIndent()
    }
}
