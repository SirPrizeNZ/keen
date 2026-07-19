package com.keenzero.app

/**
 * Address-bar URL normalisation. HTTP(S) and explicit magnet links are accepted.
 */
object UrlNormalizer {

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            val query = trimmed.substringAfter('?', "")
            return trimmed.takeIf {
                query.split('&').any { part ->
                    part.substringBefore('=').equals("xt", ignoreCase = true) &&
                        part.substringAfter('=', "").startsWith("urn:bt", ignoreCase = true)
                }
            }
        }

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
