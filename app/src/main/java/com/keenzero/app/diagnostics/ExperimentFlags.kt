package com.keenzero.app.diagnostics

import android.util.Log
import java.io.File

/**
 * Runtime bisect switches for the Cloudflare investigation.
 *
 * Each flag removes exactly one Keen surface from the normal WebView, so the challenge
 * loop can be attributed to a specific surface instead of guessed at. They are files in
 * `/data/local/tmp/`, readable only via adb, and are read **once per WebView creation** —
 * flip a flag, restart the app, and the next WebView is built the new way. No rebuild.
 *
 * All flags absent = stock Keen behaviour, exactly as before this investigation.
 *
 * ```
 * adb shell touch /data/local/tmp/<flag>     # enable
 * adb shell rm -f  /data/local/tmp/<flag>    # disable
 * adb shell ls /data/local/tmp | grep keen   # what is currently on
 * ```
 *
 * These are diagnostic switches, not features: while one is on, the protection it removes
 * is off for *every* site, dlhd.st included. They are meant to be flipped back off.
 */
object ExperimentFlags {

    /** Kill switch: force approved origins back onto the normal Keen WebView. */
    const val NO_COMPAT = "keen_no_compat"

    /** D-pad without page-visible JS: native cursor + MotionEvent instead of the router. */
    const val NO_ROUTER_JS = "keen_no_router_js"

    /** No document-start bundle (guard, player, scroll scripts). */
    const val NO_INJECT = "keen_no_inject"

    /** Stock WebView UA — keeps the `wv` token instead of stripping it. */
    const val STOCK_UA = "keen_stock_ua"

    /** No request interception: BlockingRuntime returns null without classifying. */
    const val NO_BLOCKING = "keen_no_blocking"

    /** No service-worker interception. */
    const val NO_SW_INTERCEPT = "keen_no_sw_intercept"

    /** Plain WebChromeClient: no popup broker, no quarantine, no fullscreen handling. */
    const val NO_POPUP_BROKER = "keen_no_popup_broker"

    // --- Additive flags: put a Keen surface back INTO the compatibility WebView, to
    // --- find which one reintroduces the loop. Only meaningful with COMPAT_MODE on.

    /** Inject the D-pad's element-indexing JS (InteractionIndex.COLLECT_JS). */
    const val ADD_ROUTER_JS = "keen_add_router_js"

    /**
     * Clear the approved origin's Cloudflare cookies on every compatibility-session
     * start, so a challenge is guaranteed. Without this, a valid `cf_clearance` from an
     * earlier pass means the site loads straight through and the test proves nothing.
     * Site-scoped: never touches dlhd.st or any other origin.
     */
    const val RESET_VERIFICATION = "keen_reset_verification"

    private val ALL = listOf(
        NO_COMPAT, NO_ROUTER_JS, NO_INJECT, STOCK_UA,
        NO_BLOCKING, NO_SW_INTERCEPT, NO_POPUP_BROKER,
        ADD_ROUTER_JS, RESET_VERIFICATION,
    )

    /**
     * Cached snapshot. [isOn] sits on hot paths — `shouldInterceptRequest` runs for every
     * subresource on every page — so it must never touch the filesystem. The flags are
     * documented as "read once per WebView creation", and [refresh] is what makes that
     * literally true instead of a per-call `File.exists()` syscall.
     */
    @Volatile
    private var snapshot: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    /** Re-read the flag files. Called at WebView creation; cheap and rare. */
    @Synchronized
    fun refresh() {
        snapshot = ALL.filterTo(mutableSetOf()) { flag ->
            try {
                File("/data/local/tmp/$flag").exists()
            } catch (_: Throwable) {
                false
            }
        }
        loaded = true
    }

    fun isOn(flag: String): Boolean {
        if (!loaded) refresh()
        return flag in snapshot
    }

    /** One line naming the exact configuration under test — the first thing to check. */
    fun logProfile(where: String) {
        refresh()
        val on = ALL.filter { isOn(it) }
        Log.i(
            TAG,
            if (on.isEmpty()) {
                "$where profile=STOCK_KEEN (no experiment flags)"
            } else {
                "$where profile=EXPERIMENT on=[${on.joinToString(",") { it.removePrefix("keen_") }}]"
            },
        )
    }

    const val TAG = "KZ_EXPERIMENT"
}
