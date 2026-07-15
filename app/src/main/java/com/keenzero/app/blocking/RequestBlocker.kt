package com.keenzero.app.blocking

import java.net.URI

/** Conservative host-only classifier for requests surfaced by WebView. */
class RequestBlocker private constructor(
    private val exactHosts: Set<String>,
    private val domainHosts: Set<String>,
) {
    fun classify(url: String?, isMainFrame: Boolean): Result {
        if (isMainFrame) return Result.ALLOW_MAIN_FRAME
        val host = try {
            url?.let(::URI)?.host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return Result.ALLOW_UNKNOWN

        if (host in exactHosts) return Result.BLOCK_HOST
        if (domainHosts.any { host == it || host.endsWith(".$it") }) return Result.BLOCK_HOST
        return Result.ALLOW
    }

    enum class Result(val blocks: Boolean) {
        ALLOW(false),
        ALLOW_MAIN_FRAME(false),
        ALLOW_UNKNOWN(false),
        BLOCK_HOST(true),
    }

    companion object {
        val EMPTY = RequestBlocker(emptySet(), emptySet())

        fun fromLines(lines: Sequence<String>): RequestBlocker {
            val exact = linkedSetOf<String>()
            val domains = linkedSetOf<String>()
            lines.map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { rule ->
                    if (rule.startsWith('.')) domains += rule.drop(1).lowercase()
                    else exact += rule.lowercase()
                }
            return RequestBlocker(exact, domains)
        }
    }
}
