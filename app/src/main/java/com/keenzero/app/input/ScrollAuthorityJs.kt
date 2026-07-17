package com.keenzero.app.input

/**
 * Scroll authority v5 — **inert by default**.
 *
 * v3/v4 freezes + pin loops fought remote scroll (jitter / reverse push) and
 * broke IME focus. v5 keeps the API surface so call sites compile, but does
 * **not** patch window.scrollTo or run a pin rAF unless explicitly frozen —
 * and freeze itself is a short no-op pin only (no continuous fight).
 *
 * Prefer fixing activate/IME separately. Do not reintroduce watchdog.
 */
object ScrollAuthorityJs {
    val INSTALL_JS: String = """
(function(){
  if(window.__keenScrollAuth && window.__keenScrollAuth.v>=5) return window.__keenScrollAuth;
  // No window.scrollTo / scrollIntoView patches. No rAF watchdog.
  window.__keenScrollAuth={
    v:5,
    allow:function(){},
    noteUser:function(){},
    remember:function(){},
    freeze:function(){},
    forcePin:function(){},
    arm:function(){},
    disarm:function(){},
    isAllowed:function(){ return true; },
    blocked:function(){ return 0; }
  };
  return window.__keenScrollAuth;
})();
""".trimIndent()

    fun allowJs(ms: Int = 500): String =
        "try{if(window.__keenScrollAuth)window.__keenScrollAuth.allow($ms);}catch(e){}"

    val NOTE_USER_JS: String =
        "try{if(window.__keenScrollAuth)window.__keenScrollAuth.noteUser();}catch(e){}"

    fun freezeJs(ms: Int = 2000): String =
        "try{if(window.__keenScrollAuth)window.__keenScrollAuth.freeze($ms);}catch(e){}"
}
