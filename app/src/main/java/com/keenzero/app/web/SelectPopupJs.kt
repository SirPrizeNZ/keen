package com.keenzero.app.web

/**
 * Turns an HTML `<select>` into a native Keen dialog.
 *
 * WebView renders a select as a ListPopupWindow anchored under the control. On a phone
 * that is fine; on a television it opens downwards off the bottom of the screen, the list
 * scrolls where the remote cannot follow, and a twenty-item genre filter becomes
 * unreachable. The options are handed to the native side instead and presented the same
 * way the stream file picker is: centred, D-pad native, one press to choose.
 *
 * Detection is exact — `tagName === 'SELECT'` — so nothing that is not a select can ever
 * be captured by this. Custom div/ul menus are deliberately untouched: there is no
 * reliable way to know what they are or how to commit a choice, and guessing breaks sites.
 *
 * Runs in every frame via the document-start bundle. A select inside a cross-origin frame
 * can therefore be *read*, but `evaluateJavascript` only reaches the top frame, so the
 * answer is relayed down by postMessage and applied by the frame that owns the element.
 */
object SelectPopupJs {

    /** Console needle carrying the serialised options. */
    const val OPEN_PREFIX = "KZ_SELECT_OPEN:"

    val INSTALL_JS = """
(function(){
  // Bail out inside a bot-challenge provider's own frame, where any observable
  // difference in the document can cost us the clearance cookie.
  //
  // Deliberately narrower than ChallengeFrameGuard.PREFIX: that also returns when
  // window.top._cf_chl_opt exists, which lingers on the top window of a Cloudflare
  // fronted site long after the challenge has passed — it disabled this on the whole of
  // 1337x. A provider frame is identified by its own host and path, and those documents
  // have no user-facing selects, so the host test is the right boundary here.
  try {
    var kh = (location.hostname || '').toLowerCase();
    var kp = (location.pathname || '');
    if (/(^|\.)challenges\.cloudflare\.com$|(^|\.)hcaptcha\.com$|(^|\.)recaptcha\.net$|(^|\.)arkoselabs\.com$|(^|\.)funcaptcha\.com$|(^|\.)captcha-delivery\.com$/.test(kh)) return;
    if (kp.indexOf('/cdn-cgi/challenge-platform/') === 0) return;
    if (/(^|\.)google\.com$/.test(kh) && kp.indexOf('/recaptcha/') === 0) return;
  } catch(e) { return; }
  if (window.__keenSelectInstalled) return;
  window.__keenSelectInstalled = 1;

  var pending = null;
  var seq = 0;

  function isSelect(el){
    try { return !!el && (el.tagName||'').toUpperCase() === 'SELECT'; } catch(e){ return false; }
  }

  /** Multiple-choice selects have no single answer to give back; leave them alone. */
  function convertible(el){
    try {
      if (!isSelect(el)) return false;
      if (el.multiple) return false;
      if (el.disabled) return false;
      if (typeof el.size === 'number' && el.size > 1) return false;   // already a list box
      return el.options && el.options.length > 0;
    } catch(e){ return false; }
  }

  /** The visible text a person would read as this control's name, if the page has one. */
  function labelFor(el){
    try {
      if (el.id) {
        var l = document.querySelector('label[for="' + el.id.replace(/"/g,'') + '"]');
        if (l && l.textContent) return l.textContent.trim().slice(0,80);
      }
      var up = el.closest ? el.closest('label') : null;
      if (up && up.textContent) return up.textContent.trim().slice(0,80);
    } catch(e){}
    return '';
  }

  function describe(el){
    var out = [];
    for (var i = 0; i < el.options.length && i < 300; i++){
      var o = el.options[i];
      var label = (o.label || o.text || '').trim();
      if (!label) label = '(blank)';
      // An option inside an <optgroup> reads better with its group in front of it.
      var group = '';
      try {
        if (o.parentElement && (o.parentElement.tagName||'').toUpperCase() === 'OPTGROUP') {
          group = (o.parentElement.label || '').trim();
        }
      } catch(e){}
      out.push({ i: i, label: label, group: group, disabled: !!o.disabled });
    }
    return out;
  }

  function open(el){
    if (!convertible(el)) return false;
    // Protection here is the frame test at the top, not the guard's session-wide
    // "challenge active" flag. A Cloudflare-fronted site keeps that flag raised for the
    // whole visit, which would disable this on exactly the sites that need it; the
    // provider's own frame is where footprint matters, and that bails out before this
    // script writes a single property.
    // One tap raises pointerdown, mousedown and click. Each would ask for its own dialog
    // and they would stack on top of each other; the first request owns the element until
    // it is answered or superseded by a different one.
    if (pending && pending.el === el && (Date.now() - pending.at) < 1500) return true;
    var token = 'kzsel-' + (++seq) + '-' + Date.now();
    pending = { token: token, el: el, at: Date.now() };
    var payload;
    try {
      payload = JSON.stringify({
        token: token,
        // Only a human-authored label. `name`/`id` are machine names — a category
        // chooser titled "cat" is worse than no title at all.
        name: (el.getAttribute('aria-label') || labelFor(el) || '').slice(0,80),
        selected: el.selectedIndex,
        options: describe(el)
      });
    } catch(e){ pending = null; return false; }
    try { console.warn('${OPEN_PREFIX}' + payload); } catch(e){}
    return true;
  }

  /** Commit a choice and let the page's own handlers run as if the user had picked it. */
  function apply(token, index){
    try {
      if (!pending || pending.token !== token) return false;
      var el = pending.el;
      pending = null;
      if (!el || index < 0 || index >= el.options.length) return false;
      if (el.selectedIndex === index) return true;
      el.selectedIndex = index;
      el.dispatchEvent(new Event('input',  { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    } catch(e){ return false; }
  }
  window.__keenApplySelect = apply;

  // Top frame relays the answer to whichever frame asked, since evaluateJavascript
  // cannot reach a cross-origin child directly.
  window.addEventListener('message', function(ev){
    try {
      var d = ev.data;
      if (!d || d.__keen !== 'select-apply') return;
      apply(d.token, d.index);
    } catch(e){}
  }, false);

  window.__keenRelaySelect = function(token, index){
    apply(token, index);
    try {
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length && i < 40; i++){
        try {
          frames[i].contentWindow.postMessage(
            { __keen: 'select-apply', token: token, index: index }, '*');
        } catch(e){}
      }
    } catch(e){}
  };

  // Suppress the browser's own popup and raise ours instead. pointerdown is where
  // Chromium decides to open the list, so stopping it there is what prevents both
  // appearing. The element is blurred so a stray later click cannot reopen it.
  function intercept(ev){
    try {
      var el = ev.target;
      if (!isSelect(el)) {
        el = el && el.closest ? el.closest('select') : null;
        if (!isSelect(el)) return;
      }
      if (!convertible(el)) return;
      if (open(el)) {
        ev.preventDefault();
        ev.stopPropagation();
        try { el.blur(); } catch(e){}
      }
    } catch(e){}
  }

  ['pointerdown','mousedown','touchstart','click'].forEach(function(type){
    document.addEventListener(type, intercept, true);
  });

  // Remote OK on a focused select arrives as a key event, not a pointer one.
  document.addEventListener('keydown', function(ev){
    try {
      if (ev.key !== 'Enter' && ev.keyCode !== 13 && ev.keyCode !== 23) return;
      var el = document.activeElement;
      if (!convertible(el)) return;
      if (open(el)) { ev.preventDefault(); ev.stopPropagation(); }
    } catch(e){}
  }, true);
})();
""".trimIndent()
}
