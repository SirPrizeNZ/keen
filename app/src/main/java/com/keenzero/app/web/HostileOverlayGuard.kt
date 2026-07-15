package com.keenzero.app.web

/**
 * Hostile interstitial guard v5.
 * Targets "confirm you're not a robot" / QR popups without deleting SPA shells (bcine #root).
 * v5: do not re-arm on every native sweep (v4 re-armed every 750ms → scroll thrash ~5–6s).
 */
object HostileOverlayGuard {
    val DOCUMENT_START_JS: String = """
(function(){
  /**
   * Hostile interstitial guard v5
   * Goal: kill "confirm you're not a robot" / huge QR ad popups.
   * Must NOT delete SPA shell children of #root (causes "Something went wrong" on bcine/coreflix).
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
  function looksHostileOverlay(el){
    if(!el||isSpaShell(el)) return false;
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
    // Popups are almost always fixed (sometimes absolute)
    if(pos!=='fixed' && pos!=='absolute') return false;

    var r=el.getBoundingClientRect();
    var vw=window.innerWidth||0, vh=window.innerHeight||0;
    if(vw<80||vh<80) return false;
    if(r.width<80||r.height<80) return false;
    var cover=(r.width*r.height)/(vw*vh);
    var z=parseInt(s.zIndex,10); if(!isFinite(z)) z=0;
    var t=textOf(el);
    var bot=botLanguage(t);
    var qrl=qrLanguage(t);
    var qrm=hasQrMedia(el);

    // PRIMARY: bot/robot confirmation language on a positioned layer
    if(bot) return true;

    // QR language + media on a layer that covers a meaningful area
    if((qrl||qrm) && cover>=0.1 && (z>=10 || pos==='fixed')) return true;

    // Class/id explicit
    var idc=((el.id||'')+' '+String(el.className||'')).toLowerCase();
    if(/robot-check|not-a-robot|human-verif|qr-?modal|qr-?overlay|qrcode-modal|anti-bot-popup|captcha-modal/i.test(idc)) return true;

    if(el.classList && (el.classList.contains('ad-trap')||el.classList.contains('overlay-ad')||el.hasAttribute('data-keen-hostile-overlay'))) return true;

    // Full-viewport fixed dimmer with QR media only (no broad "any large fixed" rule — that broke SPAs)
    if(pos==='fixed' && cover>=0.5 && z>=100 && qrm) return true;

    // Large fixed iframe ad/verify
    if(el.tagName==='IFRAME' && pos==='fixed' && cover>=0.3){
      var fs=((el.src)||'').toLowerCase();
      if(!fs || /ads|ad\.|doubleclick|captcha|verify|robot|qr|traffic|click|pop/.test(fs)) return true;
    }
    return false;
  }
  function unlockScroll(){
    try{
      // Only clear overflow:hidden if we actually removed something; don't force position:static (breaks layouts)
      if(document.body && getComputedStyle(document.body).overflow==='hidden'){
        document.body.style.overflow='';
      }
      if(document.documentElement && getComputedStyle(document.documentElement).overflow==='hidden'){
        document.documentElement.style.overflow='';
      }
    }catch(e){}
  }
  function sweepHostile(){
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
        if(isSpaShell(c)) continue;
        if(looksHostileOverlay(c)){ c.remove(); removed++; }
      }
    }catch(e){}
    if(removed){
      unlockScroll();
      try{ console.warn('KZ_REMOVE_HOSTILE_OVERLAY:'+removed); }catch(e){}
    }
    return removed;
  }
  function startObserver(){
    if(window.__keenHostileObs) return;
    var obs=new MutationObserver(function(){
      if(window.__keenSweepScheduled) return;
      window.__keenSweepScheduled=1;
      setTimeout(function(){ window.__keenSweepScheduled=0; sweepHostile(); }, 50);
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
      if(n>40){ // 40 * 1000ms ≈ 40s coverage for late QR
        clearInterval(window.__keenHostileTimer);
        window.__keenHostileTimer=null;
      }
    }, 1000);
  }
  try{
    if(!document.getElementById('keen-hostile-css')){
      var st=document.createElement('style');
      st.id='keen-hostile-css';
      st.textContent='[data-keen-hostile-overlay],.ad-trap,.overlay-ad,.popup-ad{display:none!important;pointer-events:none!important}';
      (document.head||document.documentElement).appendChild(st);
    }
  }catch(e){}
  // Idempotent install: do not stack observers/timers if re-evaluated.
  if(window.__keenHostileV5){
    try{ window.__keenHostileV5.sweep(); }catch(e){}
    return window.__keenHostileV5;
  }
  window.__keenHostileV5={sweep:sweepHostile,arm:arm};
  window.__keenHostileV4=window.__keenHostileV5;
  window.__keenHostileV3=window.__keenHostileV5;
  window.__keenHostileV2=window.__keenHostileV5;
  arm();
  document.addEventListener('DOMContentLoaded',function(){ arm(); },{once:true});
  window.addEventListener('load',function(){ arm(); },{once:true});
  return window.__keenHostileV5;
})();
""".trimIndent()

    /** Cheap sweep only — never re-arm (re-arm restarts timers and reflows the page). */
    val SWEEP_JS: String = """
(function(){
  try {
    if (window.__keenHostileV5 && window.__keenHostileV5.sweep) return window.__keenHostileV5.sweep();
    if (window.__keenHostileV4 && window.__keenHostileV4.sweep) return window.__keenHostileV4.sweep();
    if (window.__keenHostileV3 && window.__keenHostileV3.sweep) return window.__keenHostileV3.sweep();
    if (window.__keenHostileV2 && window.__keenHostileV2.sweep) return window.__keenHostileV2.sweep();
  } catch (e) {}
  return 0;
})();
""".trimIndent()
}
