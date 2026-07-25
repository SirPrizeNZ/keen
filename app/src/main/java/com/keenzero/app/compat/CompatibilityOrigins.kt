package com.keenzero.app.compat

import android.net.Uri

/**
 * Decides which origins get the isolated compatibility WebView.
 *
 * Membership is earned at runtime rather than shipped: [ChallengeLoopDetector] recognises
 * a genuine Cloudflare verification loop and Keen switches that origin over, recording it
 * in [CompatibilityOriginStore]. A hard-coded list would go stale and would not help
 * whichever site the user visits next; detection does.
 *
 * The protection against a hostile site talking its way out of Keen's ad blocking is not
 * a curated list but the evidence required: real Cloudflare response headers, an origin
 * that is not pinned to normal mode, and an entry that expires.
 */
object CompatibilityOrigins {

    /** Origins promoted after a detected loop; set once at startup. */
    @Volatile
    var store: CompatibilityOriginStore? = null

    /** `keen_no_compat` forces every origin back to the normal WebView for A/B testing. */
    fun isApproved(url: String?): Boolean {
        if (com.keenzero.app.diagnostics.ExperimentFlags.isOn(
                com.keenzero.app.diagnostics.ExperimentFlags.NO_COMPAT,
            )
        ) {
            return false
        }
        val host = hostOf(url) ?: return false
        return store?.isAllowed(host) == true
    }

    /** Registrable host for the promoted entry a URL belongs to, else null. */
    fun approvedHostFor(url: String?): String? {
        val host = hostOf(url) ?: return null
        return if (store?.isAllowed(host) == true) host else null
    }

    /**
     * True when [to] leaves the origin [from] was in — the signal to tear the
     * compatibility session down and hand control back to the normal WebView.
     */
    fun leavesOrigin(from: String?, to: String?): Boolean {
        val fromHost = hostOf(from) ?: return true
        val toHost = hostOf(to) ?: return true
        return toHost != fromHost && !toHost.endsWith(".$fromHost")
    }

    private fun hostOf(url: String?): String? = try {
        url?.let { Uri.parse(it).host?.lowercase()?.removePrefix("www.") }
    } catch (_: Throwable) {
        null
    }
}
