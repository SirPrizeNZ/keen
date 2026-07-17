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
  /**
   * Hostile interstitial guard v7
   * Goal: kill robot/QR gates and dating/for-you/CONTINUE ad cards.
   * Must NOT delete SPA shell children of #root (causes "Something went wrong" on bcine/coreflix).
   * Must NOT strip sticky site nav (fmhy) or reset position/scroll to top.
   * Re-arming full script must be rare — native side only sweeps, does not re-inject this bundle.
   */
  function isCloudflareChallenge(el){
    try{
      var t=(el.innerText||'').toLowerCase();
      if(/just a moment|checking your browser|ray id|cf-challenge|enable javascript and cookies/i.test(t)){
        if(el.querySelector && el.querySelector('iframe[src*="challenges.cloudflare"],iframe[src*="turnstile"]')) return true;
      }
      if(el.querySelector && el.querySelector('iframe[src*="challenges.cloudflare"],iframe[src*="turnstile"]')) return true;
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
    var t=textOf(el);
    var bot=botLanguage(t);
    var qrl=qrLanguage(t);
    var qrm=hasQrMedia(el);
    var ad=adLanguage(t);
    var cta=hasBigCta(el);
    var closeX=hasCloseControl(el);

    // PRIMARY: bot/robot confirmation language on a positioned layer
    if(bot) return true;

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
    var sc=readScroll();
    var removed=0;
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
      try{ console.warn('KZ_REMOVE_HOSTILE_OVERLAY:'+removed); }catch(e){}
    }
    // Sticky-nav reflow + our DOM work can snap scroll to 0 — put the user back.
    restoreScroll(sc.x, sc.y);
    return removed;
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
  function arm(){
    startObserver();
    sweepHostile();
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
    if(!window.__keenOpenPatched){
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
  try{
    if(!document.getElementById('keen-hostile-css')){
      var st=document.createElement('style');
      st.id='keen-hostile-css';
      st.textContent='[data-keen-hostile-overlay],.ad-trap,.overlay-ad,.popup-ad{display:none!important;pointer-events:none!important}';
      (document.head||document.documentElement).appendChild(st);
    }
  }catch(e){}
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
  arm();
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
