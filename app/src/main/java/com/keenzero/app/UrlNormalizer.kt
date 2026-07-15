package com.keenzero.app

/**
 * Phase 0 home-bar URL normalisation. Only http(s) destinations are accepted.
 */
object UrlNormalizer {

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val candidate = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            "://" in trimmed -> return null
            ":" in trimmed.substringBefore('/') -> return null // e.g. javascript:, intent:
            else -> "https://$trimmed"
        }

        return if (
            candidate.startsWith("https://", ignoreCase = true) ||
            candidate.startsWith("http://", ignoreCase = true)
        ) {
            candidate
        } else {
            null
        }
    }
}
