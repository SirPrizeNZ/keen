package com.keenzero.app.web

/**
 * Hostile interstitial guard v7.
 * Targets robot/QR gates **and** dating/for-you/CONTINUE ad cards (cineby-style).
 * Must not delete SPA shells (bcine #root).
 * v5: do not re-arm on every native sweep (v4 re-armed every 750ms → scroll thrash).
 * v6: adLanguage + CTA + close-X card detection; longer live sweep.
 * v7: never strip site chrome / sticky nav; preserve scrollY after sweep (fmhy header thrash).
 */
object HostileOverlayGuard {
    val DOCUMENT_START_JS: String = """
(function(){
  // Never run inside a challenge provider's own frame (see WebViewHost bundle prelude).
  if(window.__keenProviderFrame) return;
  /**
   * Hostile interstitial guard v7
   * Goal: kill robot/QR gates and dating/for-you/CONTINUE ad cards.
   * Must NOT delete SPA shell children of #root (causes "Something went wrong" on bcine/coreflix).
   * Must NOT strip sticky site nav (fmhy) or reset position/scroll to top.
   * Re-arming full script must be rare — native side only sweeps, does not re-inject this bundle.
   */
  // Human-verification widgets. Early "return false" (= do not remove), so match on the
  // provider's ORIGIN only. Matching on bot-check TEXT or generic *challenge*/*captcha*
  // class names hands ad interstitials immunity: they impersonate that wording.
  var CHALLENGE_ORIGIN=/^https?:\/\/([a-z0-9-]+\.)*(challenges\.cloudflare\.com|google\.com\/recaptcha|recaptcha\.net|hcaptcha\.com|arkoselabs\.com|funcaptcha\.com|captcha-delivery\.com)/i;
  var CHALLENGE_IFRAME='iframe[src*="challenges.cloudflare"],iframe[src*="turnstile"],iframe[src*="google.com/recaptcha"],iframe[src*="hcaptcha.com"]';
  /** Genuine challenge running here? Structural/provider evidence only, never wording. */
  function keenChallengeActive(){
    try{
      if(document.querySelector(
        'iframe[src*="challenges.cloudflare.com"],iframe[src*="/turnstile/"],' +
        'iframe[src*="google.com/recaptcha"],iframe[src*="recaptcha.net"],' +
        'iframe[src*="hcaptcha.com"],iframe[src*="arkoselabs.com"],iframe[src*="captcha-delivery.com"],' +
        'script[src*="/cdn-cgi/challenge-platform/"],iframe[src*="/cdn-cgi/challenge-platform/"],' +
        '.cf-turnstile,#challenge-stage,#challenge-running,#challenge-form,#cf-challenge-running')) return true;
      var u=String(location.href||'').toLowerCase();
      if(u.indexOf('/cdn-cgi/challenge-platform/')>=0) return true;
    }catch(e){}
    return false;
  }
  window.__keenChallengeActive=keenChallengeActive;
  function keenChallengeGate(){
    var on=keenChallengeActive();
    if(on && !window.__keenChallengeLogged){
      window.__keenChallengeLogged=1;
      try{ console.warn('KZ_CHALLENGE_ACTIVE:'+location.host+' — destructive passes paused'); }catch(e){}
    }
    if(!on && window.__keenChallengeLogged){
      window.__keenChallengeLogged=0;
      try{ console.warn('KZ_CHALLENGE_CLEARED:'+location.host+' — protection resumed'); }catch(e){}
    }
    return on;
  }
  function isCloudflareChallenge(el){
    try{
      // The element ITSELF may be the widget: Turnstile mounts its interactive
      // challenge as a fixed iframe at z-index 2147483647 covering the viewport —
      // exactly the shape of the ad-interstitial rule below. querySelector never
      // matches `el` itself, so that check alone let the sweep delete the checkbox
      // the moment it was pressed. Origin-matched, so an ad iframe can never qualify.
      if(el.tagName==='IFRAME' && CHALLENGE_ORIGIN.test(el.src||el.getAttribute('src')||'')) return true;
      var t=(el.innerText||'').toLowerCase();
      if(/just a moment|checking your browser|ray id|cf-challenge|enable javascript and cookies/i.test(t)){
        if(el.querySelector && el.querySelector(CHALLENGE_IFRAME)) return true;
      }
      if(el.querySelector && el.querySelector(CHALLENGE_IFRAME)) return true;
    }catch(e){}
    return false;
  }
  function textOf(el){
    try{
      var bits=[el.innerText||'',el.getAttribute('aria-label')||'',el.getAttribute('alt')||'',
        el.getAttribute('title')||'',String(el.className||''),el.id||''];
      var imgs=el.querySelectorAll?el.querySelectorAll('img'):[];
      for(var i=0;i<Math.min(imgs.length,8);i++){
        bits.push(imgs[i].alt||'',imgs[i].title||'',(imgs[i].src||'').slice(0,120));
      }
      return bits.join(' ').toLowerCase().replace(/\s+/g,' ').slice(0,900);
    }catch(e){ return ''; }
  }
  // Cosmetic filter for the adult-cam creatives that render INSIDE the player frame
  // (jerkmate class: "Oh hi there", "sent you a video", "undress me"). The host rotates
  // every load, so network rules alone always lag; this is the same approach Brave/uBlock
  // take — match the creative copy, not the domain. Only reached AFTER the position gate
  // below, so it can only ever remove a positioned overlay, never page content.
  function camAdLanguage(t){
    return /oh hi there|sent you a (video|photo|pic|message)|\bundress\b|wanna (chat|play|meet|see)|i'?m (online|live) now|jerkmate|meet ?and ?fuck|fuckmeet|horny (girls|singles|women)|nude (photos|pics)|girls? (near|around) you|start (chatting|video chat) now|click to (chat|see more)/i.test(t||'') ||
      // Fake-notification variants (observed on dlhd 2026-07-25): a chat bubble reading
      // "(2) Missed Messages / (00:51) Voice message" beside a photo. No CTA button, so
      // the close-X + CTA structural rule below never fires on it.
      /missed (messages?|calls?)|voice message|\d+ new messages?|unread messages?|is typing|wants to (chat|talk)|new match|incoming (call|video)/i.test(t||'');
  }
  /**
   * Generic defence for "ad rendered over the video": inside a frame that owns a player,
   * a positioned layer whose content comes from ANOTHER origin is not player UI — real
   * controls are same-origin. This catches rotated creatives regardless of their copy,
   * which is the only thing that keeps working when the host and wording change per load.
   */
  /** This frame shows the player: it owns the <video>, or it hosts the player iframe. */
  function ownsPlayer(){
    try{
      if(document.querySelector('video')) return true;
      return !!document.querySelector(
        '#playerFrame,iframe[src*="/stream-"],iframe[src*="/stream/"],iframe[src*="/premiumtv/"],iframe[src*="daddy"],iframe[id*="player" i],iframe[class*="player" i]');
    }catch(e){ return false; }
  }
  function foreignCreativeOverPlayer(el){
    try{
      // The creative runs in whichever frame the ad script was injected into — on dlhd
      // that is the TOP document (its beacons carry cbpage=dlhd.st), which holds the
      // player in an iframe and owns no <video> at all. Requiring a local <video> here
      // made this rule a no-op on exactly the frame that renders the ad.
      if(!ownsPlayer()) return false;
      if(el.querySelector && el.querySelector('video')) return false; // never the player itself
      // Nor the player iframe / its wrapper.
      try{
        if(el.querySelector && el.querySelector('#playerFrame,iframe[src*="/stream-"],iframe[src*="/premiumtv/"],iframe[src*="daddy"]')) return false;
      }catch(e2){}
      var host=location.hostname||'';
      function foreign(u){
        if(!u) return false;
        try{
          var h=new URL(u, location.href).hostname;
          if(!h||h===host) return false;
          // Same registrable domain (cdn.site.com vs site.com) is first-party enough.
          var a=h.split('.').slice(-2).join('.'), b=host.split('.').slice(-2).join('.');
          return a!==b;
        }catch(e){ return false; }
      }
      var nodes=el.querySelectorAll?el.querySelectorAll('img,iframe,a[href]'):[];
      for(var i=0;i<nodes.length&&i<12;i++){
        var n=nodes[i];
        if(foreign(n.src||n.getAttribute('src')||n.href||n.getAttribute('href')||'')) return true;
      }
      if(el.tagName==='IMG'||el.tagName==='IFRAME'){
        if(foreign(el.src||el.getAttribute('src')||'')) return true;
      }
    }catch(e){}
    return false;
  }
  function botLanguage(t){
    // Real phishing/ad interstitial language — require this (or strong QR signal).
    return /confirm you.?re not a robot|confirm you are not a robot|you.?re not a robot|you are not a robot|are you a robot|i am not a robot|not a robot|verify you are human|human verification|prove you.?re human|prove you are human|complete the captcha|click to verify|anti-?bot|bot detection|access verification|security check to continue/i.test(t||'');
  }
  function qrLanguage(t){
    return /qr[\s_-]?code|scan (the )?qr|scan (with|me|to)|open (in|with) (telegram|whatsapp|discord)|join (our )?(telegram|discord|group|channel)|download (our )?app|install app|get the app|watch on phone/i.test(t||'');
  }
  // cineby "for you" / dating / green CONTINUE interstitials (presentation-killers).
  function adLanguage(t){
    return /\bfor you\b|dating style|beautiful and stylish|sweet and cool|singles near|hot singles|meet (girls|boys|singles)|claim (now|reward)|you (have )?won|congratulations.*won|limited offer|install now|download now|get free|play now|spin now|watch free|continue watching free|\bCONTINUE\b/i.test(t||'');
  }
  function hasBigCta(el){
    try{
      var btns=el.querySelectorAll?el.querySelectorAll('button,a,[role="button"],input[type="button"],input[type="submit"]'):[];
      for(var i=0;i<btns.length&&i<12;i++){
        var b=btns[i];
        var tx=((b.innerText||b.value||b.getAttribute('aria-label')||'')+'').trim();
        if(/^(continue|ok|yes|install|download|play|claim|get|start|open)$/i.test(tx)) return true;
        if(/\b(continue|install now|download now|play now|claim now)\b/i.test(tx) && tx.length<40) return true;
        var br=b.getBoundingClientRect();
        // Large green CTA bar (cineby ad)
        if(br.width>=120&&br.height>=36&&/continue|install|download|play|claim/i.test(tx)) return true;
      }
    }catch(e){}
    return false;
  }
  function hasCloseControl(el){
    try{
      var nodes=el.querySelectorAll?el.querySelectorAll('button,a,span,div,[aria-label],[class*="close"],[class*="Close"]'):[];
      for(var i=0;i<nodes.length&&i<30;i++){
        var n=nodes[i];
        var t=((n.innerText||n.textContent||n.getAttribute('aria-label')||n.getAttribute('title')||'')+'').trim();
        var c=String(n.className||'')+' '+(n.id||'');
        if(/^(×|✕|x|close)$/i.test(t) || /close|dismiss|modal-close/i.test(c)){
          var r=n.getBoundingClientRect();
          if(r.width>0&&r.width<64&&r.height>0&&r.height<64) return true;
        }
      }
    }catch(e){}
    return false;
  }
  function hasQrMedia(el){
    try{
      if(el.tagName==='CANVAS') {
        var cr=el.getBoundingClientRect();
        if(cr.width>=80 && cr.height>=80 && Math.abs(cr.width-cr.height)<60) return true;
      }
      var canv=el.querySelectorAll?el.querySelectorAll('canvas'):[];
      for(var c=0;c<canv.length;c++){
        var r=canv[c].getBoundingClientRect();
        if(r.width>=80 && r.height>=80 && Math.abs(r.width-r.height)<60) return true;
      }
      var imgs=el.querySelectorAll?el.querySelectorAll('img'):[];
      for(var i=0;i<imgs.length;i++){
        var im=imgs[i];
        var s=((im.src||'')+' '+(im.alt||'')+' '+(im.className||'')+' '+(im.id||'')).toLowerCase();
        if(/qr|barcode|scan-me|robot-check/.test(s)) return true;
        var ir=im.getBoundingClientRect();
        // Square image + bot/qr language nearby is enough; bare square is too aggressive alone
        if(ir.width>=140 && ir.height>=140 && Math.abs(ir.width-ir.height)<40 && /qr|scan|robot|verify|captcha/.test(s+textOf(el))) return true;
      }
    }catch(e){}
    return false;
  }
  function isSpaShell(el){
    if(!el) return true;
    if(el===document.documentElement||el===document.body) return true;
    var id=(el.id||'').toLowerCase();
    if(id==='root'||id==='app'||id==='__next'||id==='__nuxt'||id==='main'||id==='app-root'||id==='application') return true;
    // Huge node with lots of real UI is the app, not a popup
    try{
      var r=el.getBoundingClientRect();
      var vw=window.innerWidth||1, vh=window.innerHeight||1;
      var cover=(r.width*r.height)/(vw*vh);
      var n=el.querySelectorAll('a[href],button,input,video,img,nav,header,main').length;
      if(cover>0.85 && n>40) return true;
    }catch(e){}
    return false;
  }
  // Site chrome (fmhy/VitePress sticky nav, search bar moves on scroll) — never strip.
  // Touching these during sticky reflow was yanking scrollY to the top.
  function isSiteChrome(el){
    if(!el||!el.closest) return false;
    try{
      if(el.closest('header,nav,[role="banner"],[role="navigation"],.VPNav,.VPNavBar,.VPLocalNav,.VPSidebar,.navbar,.top-nav,.site-header,#navbar,#header,.VPNavBarSearch,.DocSearch')) return true;
      var idc=((el.id||'')+' '+String(el.className||'')).toLowerCase();
      if(/^(vpnav|navbar|site-header|topbar|masthead)/i.test(idc)) return true;
      if(/\b(vpnav|navbar|site-header|local-nav|docsearch|search-box)\b/i.test(idc) && !/modal|overlay|popup|ad-/.test(idc)) return true;
    }catch(e){}
    return false;
  }
  function readScroll(){
    try{
      return {
        x: window.scrollX||window.pageXOffset||0,
        y: window.scrollY||window.pageYOffset||document.documentElement.scrollTop||0
      };
    }catch(e){ return {x:0,y:0}; }
  }
  function restoreScroll(sx,sy){
    try{
      var now=readScroll();
      // Only correct big unwanted jumps (sticky-nav thrash / overlay remove).
      if(Math.abs((now.y||0)-(sy||0))>24 || Math.abs((now.x||0)-(sx||0))>24){
        window.scrollTo(sx||0, sy||0);
      }
    }catch(e){}
  }
  function looksHostileOverlay(el){
    if(!el||isSpaShell(el)||isSiteChrome(el)) return false;
    if(isCloudflareChallenge(el)) return false;
    // Never delete ancestors of main SPA root
    try{
      if(el.querySelector && (el.querySelector('#root')||el.querySelector('#app')||el.querySelector('#__next'))) return false;
    }catch(e){}

    var s;
    try{ s=getComputedStyle(el); }catch(e){ return false; }
    if(!s || s.display==='none' || s.visibility==='hidden') return false;
    var op=parseFloat(s.opacity); if(isFinite(op) && op<0.05) return false;
    var pos=s.position;
    // Popups are almost always fixed (sometimes absolute). Never treat sticky site chrome as ads.
    if(pos!=='fixed' && pos!=='absolute') return false;
    if(pos==='sticky') return false;

    var r=el.getBoundingClientRect();
    var vw=window.innerWidth||0, vh=window.innerHeight||0;
    if(vw<80||vh<80) return false;
    if(r.width<80||r.height<80) return false;
    // Thin full-width top bars (sticky/fixed site nav) — not ads.
    if(pos==='fixed' && r.height>0 && r.height<=120 && r.width>=vw*0.7 && r.top<=80) return false;
    var cover=(r.width*r.height)/(vw*vh);
    var z=parseInt(s.zIndex,10); if(!isFinite(z)) z=0;
    // Injected extreme-z top layer (ad interstitial, e.g. dlhd z-index:300000 overlay that
    // fills with jerkmate/"Anna"/dating creatives on the play tap). Real site UI/players do
    // not use z>=100000; only strip if it does NOT contain the video/player embed.
    if((pos==='fixed'||pos==='absolute') && z>=100000 && cover>=0.12){
      try{
        if(!(el.querySelector && el.querySelector('video,#player,#playerFrame,iframe[src*="stream-"],iframe[src*="daddy"],iframe[src*="/stream/"],iframe[src*="/premiumtv/"]'))) return true;
      }catch(e){ return true; }
    }
    var t=textOf(el);
    var bot=botLanguage(t);
    var qrl=qrLanguage(t);
    var qrm=hasQrMedia(el);
    var ad=adLanguage(t);
    var cta=hasBigCta(el);
    var closeX=hasCloseControl(el);

    // PRIMARY: bot/robot confirmation language on a positioned layer
    if(bot) return true;

    // PRIMARY: adult-cam / fake-notification creative copy on a positioned layer. These
    // render inside the player frame from a per-load random host, so the DOM is the
    // reliable place to catch them; the copy never appears in real player chrome.
    if(camAdLanguage(t)){
      try{ console.warn('KZ_REMOVE_CAM_AD:'+location.host+':'+t.slice(0,60)); }catch(e){}
      return true;
    }

    // PRIMARY: any third-party creative layered over the video. Copy-independent, so it
    // survives creative rotation. Bounded so it can never eat the player or a full-frame
    // shell, and it only applies in frames that actually own a <video>.
    if(cover>=0.01 && cover<=0.7 && foreignCreativeOverPlayer(el)){
      try{ console.warn('KZ_REMOVE_FOREIGN_OVER_PLAYER:'+location.host+':z='+z+':'+t.slice(0,50)); }catch(e){}
      return true;
    }

    // QR language + media on a layer that covers a meaningful area
    if((qrl||qrm) && cover>=0.1 && (z>=10 || pos==='fixed')) return true;

      // Grace period after deliberate click — do not strip SPA UI while movie route mounts.
    try{
      if(window.__keenNativeIntent && (Date.now()-window.__keenNativeIntent)<2500) return false;
    }catch(e){}

    // STRUCTURAL ad cards: fixed mid-size layer + close + big CTA.
    // cover 0.05–0.55 (tighter) so movie detail sheets are less likely to die.
    if(pos==='fixed' && cover>=0.05 && cover<=0.55 && closeX && cta){
      return true;
    }
    // Copy-based ad language as secondary signal on mid fixed layers.
    if(pos==='fixed' && cover>=0.05 && cover<=0.55 && ad && (cta||closeX)) return true;
    if(pos==='absolute' && cover>=0.08 && cover<=0.45 && ad && cta && closeX) return true;

    // Class/id explicit
    var idc=((el.id||'')+' '+String(el.className||'')).toLowerCase();
    if(/robot-check|not-a-robot|human-verif|qr-?modal|qr-?overlay|qrcode-modal|anti-bot-popup|captcha-modal|ad-modal|ad-overlay|interstitial|for-you-ad/i.test(idc)) return true;

    if(el.classList && (el.classList.contains('ad-trap')||el.classList.contains('overlay-ad')||el.hasAttribute('data-keen-hostile-overlay'))) return true;

    // Full-viewport fixed dimmer with QR media only (no broad "any large fixed" rule — that broke SPAs)
    if(pos==='fixed' && cover>=0.5 && z>=100 && qrm) return true;

    // Large fixed iframe ad/verify
    if(el.tagName==='IFRAME' && pos==='fixed' && cover>=0.3){
      var fs=((el.src)||'').toLowerCase();
      if(!fs || /ads|ad\.|doubleclick|captcha|verify|robot|qr|traffic|click|pop|dating|adult/.test(fs)) return true;
    }
    return false;
  }
  function unlockScroll(){
    try{
      // Only clear overflow:hidden. NEVER force position:static — that kills sticky nav
      // layouts (fmhy) and snaps the viewport to the top.
      if(document.body && getComputedStyle(document.body).overflow==='hidden'){
        document.body.style.overflow='';
      }
      if(document.documentElement && getComputedStyle(document.documentElement).overflow==='hidden'){
        document.documentElement.style.overflow='';
      }
    }catch(e){}
  }
  function sweepHostile(){
    // Never mutate the DOM while a genuine challenge runs. Resumes automatically the
    // moment the evidence disappears — nothing is permanently disabled.
    if(keenChallengeGate()) return 0;
    var sc=readScroll();
    var removed=0;
    // Ad-banner iframes by src PATH (any position, any origin) — catches first-party
    // proxied ads like dlhd.st/rs4k-adbanner.html that host-blocking cannot touch.
    try{
      var af=document.getElementsByTagName('iframe');
      for(var a=af.length-1;a>=0;a--){
        var isrc=((af[a].src)||af[a].getAttribute('src')||'').toLowerCase();
        if(CHALLENGE_ORIGIN.test(isrc)) continue;
        if(/(rs4k-?adbanner|adbanner|\/ad-|[-_]ad\.html|\/ads\/|\/adframe|\/advert|adsbanner)/.test(isrc)){
          try{ af[a].remove(); removed++; }catch(e){}
        }
      }
    }catch(e){}
    var sel='div,section,aside,dialog,article,span,iframe,figure';
    var nodes;
    try{ nodes=document.querySelectorAll(sel); }catch(e){ return 0; }
    for(var i=nodes.length-1;i>=0;i--){
      var el=nodes[i];
      try{
        if(looksHostileOverlay(el)){
          el.remove();
          removed++;
        }
      }catch(e){}
    }
    // Direct body children only (common portal mount)
    try{
      var kids=document.body?document.body.children:[];
      for(var k=kids.length-1;k>=0;k--){
        var c=kids[k];
        if(isSpaShell(c)||isSiteChrome(c)) continue;
        if(looksHostileOverlay(c)){ c.remove(); removed++; }
      }
    }catch(e){}
    if(removed){
      unlockScroll();
      try{ console.warn('KZ_REMOVE_HOSTILE_OVERLAY:'+removed+' @'+location.host); }catch(e){}
    }
    // Sticky-nav reflow + our DOM work can snap scroll to 0 — put the user back.
    restoreScroll(sc.x, sc.y);
    return removed;
  }
  /**
   * Diagnostic: describe the positioned layers this frame is carrying. The player frames
   * are cross-origin, so logcat is the only way to see what an ad element actually looks
   * like when a rule fails to match it. Runs three times per frame, then stops.
   */
  function dumpOverlays(n){
    try{
      var out=[];
      var nodes=document.querySelectorAll('div,section,aside,iframe,a,img');
      for(var i=0;i<nodes.length&&out.length<5;i++){
        var el=nodes[i];
        var s;
        try{ s=getComputedStyle(el); }catch(e){ continue; }
        if(!s||(s.position!=='fixed'&&s.position!=='absolute')) continue;
        var r=el.getBoundingClientRect();
        if(r.width<100||r.height<50) continue;
        var z=parseInt(s.zIndex,10); if(!isFinite(z)) z=0;
        out.push(el.tagName+(el.id?'#'+el.id:'')+' z='+z+' '+(r.left|0)+','+(r.top|0)+' '+
          (r.width|0)+'x'+(r.height|0)+' "'+textOf(el).slice(0,45)+'"');
      }
      if(out.length) console.warn('KZ_OVL'+n+' '+location.host+' :: '+out.join(' | '));
    }catch(e){}
  }
  function startObserver(){
    // Replace stale observer from older guard versions.
    try{ if(window.__keenHostileObs){ window.__keenHostileObs.disconnect(); window.__keenHostileObs=null; } }catch(e){}
    var obs=new MutationObserver(function(muts){
      // Ignore pure text/class jitter in the sticky header (fmhy search relocates constantly).
      var interesting=false;
      try{
        for(var i=0;i<muts.length&&i<20;i++){
          var m=muts[i];
          if(m.type!=='childList') continue;
          if((m.addedNodes&&m.addedNodes.length)||(m.removedNodes&&m.removedNodes.length)){
            var t=m.target;
            if(t&&isSiteChrome(t)) continue;
            interesting=true; break;
          }
        }
      }catch(e){ interesting=true; }
      if(!interesting) return;
      if(window.__keenSweepScheduled) return;
      window.__keenSweepScheduled=1;
      setTimeout(function(){ window.__keenSweepScheduled=0; sweepHostile(); }, 120);
    });
    try{
      obs.observe(document.documentElement,{childList:true,subtree:true});
      window.__keenHostileObs=obs;
    }catch(e){}
  }
  function installKeenStyle(){
    try{
      if(!document.getElementById('keen-hostile-css')){
        var st=document.createElement('style');
        st.id='keen-hostile-css';
        st.textContent='[data-keen-hostile-overlay],.ad-trap,.overlay-ad,.popup-ad{display:none!important;pointer-events:none!important}';
        (document.head||document.documentElement).appendChild(st);
      }
    }catch(e){}
  }
  function arm(){
    // Inert while a challenge runs, retrying until it clears. Gating only the removals
    // was not enough: arming alone started an observer, appended <style> and swept before
    // the challenge markers existed. Bisect (v0.1.123): 1337x passes with this guard off.
    if(keenChallengeGate()){
      if(!window.__keenArmRetry){
        window.__keenArmRetry=setInterval(function(){
          if(!keenChallengeActive()){
            clearInterval(window.__keenArmRetry);
            window.__keenArmRetry=null;
            arm();
          }
        }, 1000);
      }
      return;
    }
    installKeenStyle();
    startObserver();
    sweepHostile();
    if(!window.__keenOvlDumped){
      window.__keenOvlDumped=1;
      setTimeout(function(){ dumpOverlays(1); }, 2500);
      setTimeout(function(){ dumpOverlays(2); }, 7000);
      setTimeout(function(){ dumpOverlays(3); }, 14000);
      // These creatives inject AFTER the play tap, so the early dumps kept showing a
      // clean page. Keep sampling across the window where the ad actually appears.
      setTimeout(function(){ dumpOverlays(4); }, 25000);
      setTimeout(function(){ dumpOverlays(5); }, 40000);
      setTimeout(function(){ dumpOverlays(6); }, 60000);
    }
    // One in-page timer only; do not restart if already running (native sweeps must not re-arm).
    if(window.__keenHostileTimer) return;
    var n=0;
    window.__keenHostileTimer=setInterval(function(){
      sweepHostile();
      n++;
      // Never fully disarm: after warm-up, keep a light forever sweep (ads inject late).
      if(n===90){
        clearInterval(window.__keenHostileTimer);
        window.__keenHostileTimer=setInterval(function(){ sweepHostile(); }, 2500);
      }
    }, 800);
  }
  // window.open policy for single-WebView TV:
  // - NEVER return null (sites treat that as "popup blocked" and scroll-home / abort movie nav).
  // - Same-origin / content paths → same-tab navigation.
  // - Cross-origin junk → stub window (ad dies, SPA keeps working).
  try{
    // Patch only after load: replacing a native function at document-start trips
    // challenge fingerprinting. Ads call window.open on interaction, always post-load.
    // ...and never while a challenge is actually running: the challenge page fires
    // page-finished itself, so __keenPostLoad alone still let us tamper mid-challenge.
    if(!window.__keenOpenPatched && window.__keenPostLoad && !keenChallengeActive()){
      window.__keenOpenPatched=1;
      function keenStubWin(){
        var w={closed:false,opener:null,name:''};
        w.close=function(){ w.closed=true; };
        w.focus=function(){};
        w.blur=function(){};
        w.postMessage=function(){};
        w.location={href:'about:blank',assign:function(){},replace:function(){},reload:function(){}};
        w.document={write:function(){},writeln:function(){},close:function(){},open:function(){return this;}};
        return w;
      }
      function keenAbs(u){
        try{ var a=document.createElement('a'); a.href=u||''; return a; }catch(e){ return null; }
      }
      function keenIsContentPath(path){
        return /\/movie\/|\/tv\/|\/show\/|\/title\/|\/watch\/|\/play\/|\/v\/|\/embed\/|\/film\/|\/series\//i.test(path||'');
      }
      window.open=function(u,n,f){
        try{
          var a=keenAbs(u);
          var host=(a&&a.hostname||'').toLowerCase();
          var path=(a&&a.pathname)||'';
          var href=a?a.href:String(u||'');
          var same=!host||host===location.hostname;
          var deliberate=window.__keenNativeIntent && (Date.now()-window.__keenNativeIntent)<5000;
          // Content navigation: always same-tab (TV has one surface).
          if(same || keenIsContentPath(path) || keenIsContentPath(href)){
            try{
              if(href && href!=='about:blank' && href.indexOf('javascript:')!==0){
                location.assign(href);
              }
            }catch(e){}
            return keenStubWin();
          }
          // Deliberate activation to another origin: same-tab (still no second WebView).
          if(deliberate && href && href.indexOf('http')===0){
            try{ location.assign(href); }catch(e){}
            return keenStubWin();
          }
          // Everything else = ad/popunder noise.
          try{ console.warn('KZ_BLOCK_WINDOW_OPEN:'+String(href).slice(0,120)); }catch(e2){}
          return keenStubWin();
        }catch(e){
          return keenStubWin();
        }
      };
    }
  }catch(e){}
  // NOTE: the stylesheet is no longer appended here. Injecting a <style> at document-start
  // mutates the document before we can possibly know whether it is a challenge page.
  // installKeenStyle() now runs from arm(), i.e. only once the page is challenge-free.
  // Idempotent install / upgrade: v7 replaces older guards (chrome-safe + scroll preserve).
  if(window.__keenHostileV7){
    try{ window.__keenHostileV7.sweep(); }catch(e){}
    return window.__keenHostileV7;
  }
  try{ if(window.__keenHostileTimer){ clearInterval(window.__keenHostileTimer); window.__keenHostileTimer=null; } }catch(e){}
  window.__keenHostileV7={sweep:sweepHostile,arm:arm};
  window.__keenHostileV6=window.__keenHostileV7;
  window.__keenHostileV5=window.__keenHostileV7;
  window.__keenHostileV4=window.__keenHostileV7;
  window.__keenHostileV3=window.__keenHostileV7;
  window.__keenHostileV2=window.__keenHostileV7;
  // Give the document a beat to reveal a challenge before touching anything. At
  // document-start the DOM is empty, so an immediate arm() cannot see the markers and
  // would modify a challenge page before it identified itself. Ads inject later than
  // this, so the delay costs the ad defence nothing.
  setTimeout(arm, 1200);
  document.addEventListener('DOMContentLoaded',function(){ arm(); },{once:true});
  window.addEventListener('load',function(){ arm(); },{once:true});
  return window.__keenHostileV7;
})();
""".trimIndent()

    /** Cheap sweep only — never re-arm (re-arm restarts timers and reflows the page). */
    val SWEEP_JS: String = """
(function(){
  try {
    if (window.__keenHostileV7 && window.__keenHostileV7.sweep) return window.__keenHostileV7.sweep();
    if (window.__keenHostileV6 && window.__keenHostileV6.sweep) return window.__keenHostileV6.sweep();
    if (window.__keenHostileV5 && window.__keenHostileV5.sweep) return window.__keenHostileV5.sweep();
    if (window.__keenHostileV4 && window.__keenHostileV4.sweep) return window.__keenHostileV4.sweep();
    if (window.__keenHostileV3 && window.__keenHostileV3.sweep) return window.__keenHostileV3.sweep();
    if (window.__keenHostileV2 && window.__keenHostileV2.sweep) return window.__keenHostileV2.sweep();
  } catch (e) {}
  return 0;
})();
""".trimIndent()
}
