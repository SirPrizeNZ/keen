package com.keenzero.app.web

/**
 * Zero-footprint bail-out for bot-challenge provider frames.
 *
 * Document-start scripts are injected with `setOf("*")`, i.e. into EVERY frame,
 * including Cloudflare's Turnstile widget. Turnstile's integrity check reacts to
 * *any* observable difference in its document — a `window.__keenFoo` property or a
 * `console.warn` is enough to make the verification pass but never hand back
 * `cf_clearance`, which is the "Successful → reload → empty checkbox" loop.
 *
 * So the check must write nothing and log nothing: prepend [PREFIX] as the first
 * statement inside every injected IIFE. In a challenge frame it returns before a
 * single property is touched; everywhere else (dlhd.st and friends) it is a
 * regex test and the ad/overlay logic runs exactly as before.
 */
object ChallengeFrameGuard {

    private const val D = "$"

    /** Boolean expression: true when the current frame belongs to a challenge provider. */
    const val TEST: String =
        "(function(){try{" +
            // about:srcdoc / about:blank frames inherit the parent's hostname, so the
            // host test below cannot see them — and Cloudflare's interstitial builds
            // exactly those (observed via CDP: "KZ_FRAME_BOOT:sub ?srcdoc" inside a live
            // challenge). `_cf_chl_opt` exists only on a real cdn-cgi interstitial
            // document, never on an ordinary page that merely embeds a widget, so this
            // stays out of dlhd.st and friends.
            "try{ if(window.top && window.top._cf_chl_opt) return true; }catch(e0){}" +
            "var h=(location.hostname||'').toLowerCase();var p=(location.pathname||'');" +
            "return /(^|\\.)challenges\\.cloudflare\\.com$D" +
            "|(^|\\.)hcaptcha\\.com$D" +
            "|(^|\\.)recaptcha\\.net$D" +
            "|(^|\\.)arkoselabs\\.com$D" +
            "|(^|\\.)funcaptcha\\.com$D" +
            "|(^|\\.)captcha-delivery\\.com$D/.test(h)" +
            "||(/(^|\\.)google\\.com$D/.test(h)&&p.indexOf('/recaptcha/')===0)" +
            "||p.indexOf('/cdn-cgi/challenge-platform/')===0;" +
            "}catch(e){return true;}})()"

    /**
     * First statement of every injected IIFE. Valid only directly inside a function body.
     * Fails closed: if anything throws, [TEST] yields true and we stay out of the frame.
     */
    const val PREFIX: String = "if($TEST) return;"
}
