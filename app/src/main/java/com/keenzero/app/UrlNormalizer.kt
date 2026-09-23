package com.keenzero.app

/**
 * Address-bar URL normalisation. HTTP(S) and explicit magnet links are accepted.
 */
object UrlNormalizer {

    /** The bundled open-source notices, served by the WebView asset loader. */
    const val LICENCES_URL = "https://appassets.androidplatform.net/assets/licenses/notices.html"

    /** Address-bar words that open [LICENCES_URL]. Both spellings, since both get typed. */
    private val LICENCES_ALIASES = setOf("about:licences", "about:licenses", "about:credits")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.lowercase() in LICENCES_ALIASES) return LICENCES_URL
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
