package com.keenzero.app.torrent

data class HttpByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1

    companion object {
        fun parse(header: String?, size: Long): HttpByteRange? {
            if (size <= 0 || header.isNullOrBlank()) return null
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true) || ',' in value) return null
            val spec = value.substringAfter('=').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            return when {
                first.isEmpty() -> {
                    val suffix = last.toLongOrNull() ?: return null
                    if (suffix <= 0) return null
                    val length = suffix.coerceAtMost(size)
                    HttpByteRange(size - length, size - 1)
                }
                else -> {
                    val start = first.toLongOrNull() ?: return null
                    if (start < 0 || start >= size) return null
                    val end = if (last.isEmpty()) size - 1 else last.toLongOrNull() ?: return null
                    if (end < start) return null
                    HttpByteRange(start, end.coerceAtMost(size - 1))
                }
            }
        }
    }
}
