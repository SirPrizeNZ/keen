package com.keenzero.app.web

/**
 * In-frame player agent (runs in EVERY frame via addDocumentStartJavaScript(setOf("*"))).
 *
 * Why this exists: the native side can only evaluateJavascript() in the TOP frame.
 * On embed sites (dlhd.st class) the <video> lives in nested cross-origin iframes, so
 * PlaybackOrchestrator's SAMPLE_JS / UNMUTE_AND_THEATRE_JS / OPTIONAL_FULLSCREEN_JS all
 * see no video and silently do nothing — which is why playback was always muted and
 * never auto-fullscreened. Everything that must touch the media element has to run in
 * the frame that owns it.
 *
 * Four jobs, all gated on a REAL user tap in this frame (never on autoplay):
 *  1. UNMUTE — persistently. hls.js/embed players deliberately re-mute after an
 *     "autoplay error" retry, so a one-shot unmute loses the race; we re-assert for
 *     UNMUTE_WINDOW_MS and on every volumechange/play event.
 *  2. FULLSCREEN — requestFullscreen() on the player container from inside the tap
 *     gesture (the only moment activation exists), retried while activation may still
 *     be live. If the iframe chain refuses fullscreen, fall back to a CSS fill that is
 *     propagated up the frame chain by postMessage (each frame expands the child iframe
 *     the request came from), which needs no activation at all.
 *  3. BUFFER — the embed configures hls.js with maxBufferLength:5; a 5s buffer cannot
 *     survive the 5–11s segment gaps this TV sees, so it drains and rebuffers forever.
 *     Wrap the Hls constructor (and bump live instances) to a 30s buffer.
 *  4. BRIDGE — relay media state up to the top frame (window.__keenFrameMedia) so the
 *     native orchestrator can confirm playback, take audio focus and checkpoint.
 */
object FramePlayerJs {
    /** Console needle the native side watches to enter Keen Playback Mode. */
    const val GESTURE_PLAY_NEEDLE = "KZ_FRAME_MEDIA_GESTURE_PLAY"

    /** Media state relay needle (JSON payload follows the colon). */
    const val MEDIA_STATE_NEEDLE = "KZ_FRAME_MEDIA:"

    val INSTALL_JS: String = """
(function(){
  // Never run inside a challenge provider's own frame (see WebViewHost bundle prelude).
  if(window.__keenProviderFrame) return;
  if(window.__keenFramePlayerV1) return;
  window.__keenFramePlayerV1=1;

  var GESTURE_WINDOW_MS=15000;   // tap -> playback association window
  var ACTIVATION_MS=4500;        // transient activation lifetime (Chromium ~5s)
  var UNMUTE_WINDOW_MS=45000;    // keep re-asserting unmute this long after the tap
  var FILL_DELAY_MS=2200;        // grace before the CSS-fill fallback
  var isTop=false;
  try{ isTop=(window.top===window); }catch(e){ isTop=false; }

  var lastGesture=0;
  var suppressUntil=0;      // set when the user leaves playback; blocks auto re-fullscreen
  var gesturePoint=null;
  var unmuteUntil=0;
  var fsTried=0;
  var filled=false;
  var reported='';
  var announced=false;

  function now(){ return Date.now(); }
  function gestureFresh(ms){ return lastGesture>0 && (now()-lastGesture)<ms; }
  /** Short frame identifier — logcat is the only view we have into these frames. */
  var _tag=null;
  function tag(){
    if(_tag===null){
      try{ _tag=(isTop?'TOP ':'sub ')+(location.host||'?')+(location.pathname||'').slice(-22); }
      catch(e){ _tag=(isTop?'TOP':'sub')+' x-origin'; }
    }
    return _tag;
  }

  // ---------------------------------------------------------------- media --
  function videos(){
    try{ return document.getElementsByTagName('video'); }catch(e){ return []; }
  }
  /** Largest visible video in this frame — the player, not a decorative loop. */
  function mainVideo(){
    var vs=videos(), best=null, bestArea=0;
    for(var i=0;i<vs.length;i++){
      try{
        var r=vs[i].getBoundingClientRect();
        var a=Math.max(0,r.width)*Math.max(0,r.height);
        if(a>bestArea){ best=vs[i]; bestArea=a; }
      }catch(e){}
    }
    return best;
  }
  function isPlayerSized(v){
    if(!v) return false;
    try{
      var r=v.getBoundingClientRect();
      var vw=window.innerWidth||1, vh=window.innerHeight||1;
      if(r.width<160||r.height<90) return false;
      return (r.width*r.height)/(vw*vh)>=0.12;
    }catch(e){ return false; }
  }
  function playerHost(v){
    if(!v) return null;
    try{
      var c=v.closest('[id*="player" i],[class*="player" i],[class*="jw-" i],.video-js,.plyr,[class*="video-container" i],[class*="videoWrapper" i]');
      if(c){
        var cr=c.getBoundingClientRect(), vr=v.getBoundingClientRect();
        // Reject wrappers that are barely bigger than the page itself.
        if(cr.width>=vr.width-4 && cr.height>=vr.height-4) return c;
      }
    }catch(e){}
    return v.parentElement||v;
  }

  function unmuteNow(reason){
    var vs=videos(), touched=0;
    for(var i=0;i<vs.length;i++){
      var v=vs[i];
      try{
        if(v.muted){ v.muted=false; touched++; }
        if(v.volume<1){ v.volume=1; touched++; }
        v.removeAttribute&&v.removeAttribute('muted');
        if(v.paused && gestureFresh(GESTURE_WINDOW_MS)){
          var p=v.play(); if(p&&p['catch']) p['catch'](function(){});
        }
      }catch(e){}
    }
    if(touched){
      try{ console.warn('KZ_FRAME_UNMUTE:'+reason+' '+tag()); }catch(e){}
    }
    return touched;
  }
  function armUnmute(){
    unmuteUntil=now()+UNMUTE_WINDOW_MS;
    if(window.__keenUnmuteTimer) return;
    window.__keenUnmuteTimer=setInterval(function(){
      if(now()>unmuteUntil){
        clearInterval(window.__keenUnmuteTimer);
        window.__keenUnmuteTimer=null;
        return;
      }
      unmuteNow('tick');
    },400);
    unmuteNow('arm');
  }

  // ----------------------------------------------------------- fullscreen --
  function inFullscreen(){
    try{ return !!(document.fullscreenElement||document.webkitFullscreenElement); }catch(e){ return false; }
  }
  function requestFs(el){
    if(!el) return false;
    try{
      if(typeof el.requestFullscreen==='function'){
        var p=el.requestFullscreen();
        if(p&&p['catch']) p['catch'](function(){ scheduleFill('fs_rejected'); });
        return true;
      }
      if(typeof el.webkitRequestFullscreen==='function'){ el.webkitRequestFullscreen(); return true; }
    }catch(e){}
    return false;
  }
  /** Must be called synchronously inside a trusted gesture handler to succeed. */
  function tryFullscreen(reason){
    var out;
    if(now()<suppressUntil){
      out='suppressed';
    }else if(inFullscreen()){
      out='already';
    }else{
      var v=mainVideo();
      if(!v){
        out='no-video';
      }else if(!isPlayerSized(v)){
        out='too-small';
      }else{
        fsTried++;
        if(requestFs(playerHost(v))||requestFs(v)) out='requested';
        else { scheduleFill('no_fs_api'); out='no-api'; }
      }
    }
    // Always report: "which step did auto-fullscreen die at" is the only thing the
    // native side can learn about a cross-origin player frame.
    try{ console.warn('KZ_FRAME_FS:'+reason+'='+out+' '+tag()+' vids='+videos().length); }catch(e){}
    return out;
  }

  // ------------------------------------------------------- CSS fill chain --
  // Real fullscreen can be refused (missing allowfullscreen on an ancestor iframe, or
  // activation already consumed). Filling the frame chain with CSS gets the same
  // picture with no activation requirement, and the app is already in native
  // immersive mode, so the result is a genuinely fullscreen video.
  function stash(el){
    try{ if(el.getAttribute('data-keen-fill-prev')===null) el.setAttribute('data-keen-fill-prev', el.style.cssText||''); }catch(e){}
  }
  function unstash(el){
    try{
      var prev=el.getAttribute('data-keen-fill-prev');
      if(prev!==null){ el.style.cssText=prev; el.removeAttribute('data-keen-fill-prev'); }
    }catch(e){}
  }
  function applyFill(el){
    if(!el) return;
    stash(el);
    try{
      var s=el.style;
      s.setProperty('position','fixed','important');
      s.setProperty('left','0','important');
      s.setProperty('top','0','important');
      s.setProperty('right','0','important');
      s.setProperty('bottom','0','important');
      s.setProperty('width','100%','important');
      s.setProperty('height','100%','important');
      s.setProperty('max-width','none','important');
      s.setProperty('max-height','none','important');
      s.setProperty('margin','0','important');
      s.setProperty('padding','0','important');
      s.setProperty('border','0','important');
      s.setProperty('z-index','2147483000','important');
      s.setProperty('background','#000','important');
    }catch(e){}
  }
  function fillSelf(){
    if(filled) return;
    var v=mainVideo();
    if(!v||!isPlayerSized(v)) return;
    filled=true;
    var host=playerHost(v);
    applyFill(host);
    stash(v);
    try{
      v.style.setProperty('width','100%','important');
      v.style.setProperty('height','100%','important');
      v.style.setProperty('object-fit','contain','important');
      v.style.setProperty('background','#000','important');
    }catch(e){}
    try{
      if(document.body){
        stash(document.body);
        document.body.style.setProperty('overflow','hidden','important');
        document.body.style.setProperty('background','#000','important');
      }
    }catch(e){}
    try{ console.warn('KZ_FRAME_FILL:self '+tag()); }catch(e){}
    post(window.parent,{__keen:1,kind:'fill'});
  }
  function unfillSelf(){
    filled=false;
    try{
      var marked=document.querySelectorAll('[data-keen-fill-prev]');
      for(var i=0;i<marked.length;i++) unstash(marked[i]);
    }catch(e){}
  }
  function scheduleFill(reason){
    if(filled||now()<suppressUntil) return;
    setTimeout(function(){
      if(inFullscreen()||filled||now()<suppressUntil) return;
      var v=mainVideo();
      if(!v||v.paused||!isPlayerSized(v)) return;
      if(!gestureFresh(GESTURE_WINDOW_MS+FILL_DELAY_MS)) return;
      try{ console.warn('KZ_FRAME_FILL_FALLBACK:'+reason+' '+tag()); }catch(e){}
      fillSelf();
    }, FILL_DELAY_MS);
  }

  // ------------------------------------------------------------ messaging --
  function post(win,msg){
    try{ if(win&&win!==window) win.postMessage(msg,'*'); }catch(e){}
  }
  function childFrameOf(src){
    try{
      var f=document.getElementsByTagName('iframe');
      for(var i=0;i<f.length;i++){
        try{ if(f[i].contentWindow===src) return f[i]; }catch(e){}
      }
    }catch(e){}
    return null;
  }
  function broadcastDown(msg){
    try{
      var f=document.getElementsByTagName('iframe');
      for(var i=0;i<f.length;i++){ try{ post(f[i].contentWindow,msg); }catch(e){} }
    }catch(e){}
  }
  window.addEventListener('message',function(ev){
    var d=ev&&ev.data;
    if(!d||typeof d!=='object'||d.__keen!==1) return;
    if(d.kind==='fill'){
      // A descendant frame went CSS-fullscreen: expand the iframe it lives in, then
      // ask our own parent to do the same, all the way to the top document.
      var fr=childFrameOf(ev.source);
      if(fr){ applyFill(fr); filled=true; }
      try{
        if(document.body){
          stash(document.body);
          document.body.style.setProperty('overflow','hidden','important');
        }
      }catch(e){}
      post(window.parent,{__keen:1,kind:'fill'});
      return;
    }
    if(d.kind==='unfill'){
      // The user left playback. Forget the tap that authorised fullscreen and hold off
      // briefly, otherwise a already-scheduled fill re-expands the player right after
      // Back and it looks like Back did nothing. A new tap re-arms everything.
      lastGesture=0;
      suppressUntil=now()+8000;
      unfillSelf();
      broadcastDown(d);
      return;
    }
    if(d.kind==='tap'){
      // Relay taps to the top document, which fans them back down the whole tree.
      if(isTop) broadcastDown({__keen:1,kind:'gesture'});
      else post(window.top||window.parent,d);
      return;
    }
    if(d.kind==='intent'||d.kind==='gesture'){
      // A tap happened SOMEWHERE in this page (native Play, or another frame). On these
      // embeds the play control and the <video> sit in different frames, so the media
      // frame must learn about a tap it never received.
      lastGesture=now();
      armUnmute();
      broadcastDown(d);
      // No user activation in this frame (the tap landed elsewhere), so real
      // fullscreen will refuse — the CSS fill needs none and is the path that works.
      tryFullscreen('remote:'+d.kind);
      scheduleFill('remote:'+d.kind);
      return;
    }
    if(d.kind==='media'){
      if(isTop){
        window.__keenFrameMedia=d.state;
      }else{
        post(window.parent,d);
      }
      return;
    }
  },false);

  // Top document only: let the native play path push a gesture hint down the chain.
  if(isTop){
    window.__keenBroadcastIntent=function(){ broadcastDown({__keen:1,kind:'intent'}); };
    window.__keenExitFill=function(){ unfillSelf(); broadcastDown({__keen:1,kind:'unfill'}); return 1; };
  }

  // --------------------------------------------------------- state report --
  function report(force){
    var v=mainVideo();
    if(!v) return;
    var playing=false, muted=true, audible=false, ct=0, dur=0;
    try{
      playing=!v.paused&&!v.ended&&v.readyState>=2;
      muted=!!v.muted||v.volume===0;
      audible=playing&&!muted&&v.currentTime>0.05;
      ct=v.currentTime||0;
      dur=isFinite(v.duration)?v.duration:0;
    }catch(e){}
    var state={playing:playing,muted:muted,audible:audible,currentTime:ct,duration:dur,t:now()};
    if(isTop){ window.__keenFrameMedia=state; } else { post(window.parent,{__keen:1,kind:'media',state:state}); }
    var key=(playing?'1':'0')+(muted?'1':'0')+(audible?'1':'0');
    if(force||key!==reported){
      reported=key;
      try{ console.warn('KZ_FRAME_MEDIA:'+JSON.stringify({playing:playing,muted:muted,audible:audible,gesture:gestureFresh(GESTURE_WINDOW_MS)})+' '+tag()); }catch(e){}
    }
    // One-shot native signal: media is genuinely playing because the user tapped.
    if(playing&&!announced&&gestureFresh(GESTURE_WINDOW_MS)&&isPlayerSized(v)){
      announced=true;
      try{ console.warn('KZ_FRAME_MEDIA_GESTURE_PLAY'); }catch(e){}
    }
  }

  // ------------------------------------------------------------- hls tune --
  // The embed ships maxBufferLength:5. With 5–11s segment gaps on this device the
  // buffer drains before the next fragment lands and playback stalls for good.
  function tuneConfig(cfg,fresh){
    if(!cfg) return cfg;
    try{
      if(!(cfg.maxBufferLength>=20)) cfg.maxBufferLength=30;
      if(!(cfg.maxMaxBufferLength>=60)) cfg.maxMaxBufferLength=120;
      if(!(cfg.backBufferLength>=15)) cfg.backBufferLength=30;
      if(fresh){
        // Start further behind the live edge so the cushion actually exists.
        // Only safe before the instance is running — changing it live forces a seek.
        if(!(cfg.liveSyncDurationCount>=5)) cfg.liveSyncDurationCount=5;
        if(cfg.liveMaxLatencyDurationCount&&cfg.liveMaxLatencyDurationCount<=cfg.liveSyncDurationCount){
          cfg.liveMaxLatencyDurationCount=cfg.liveSyncDurationCount*2;
        }
      }
    }catch(e){}
    return cfg;
  }
  function wrapHls(H){
    if(!H||H.__keenWrapped||typeof H!=='function') return H;
    function KeenHls(cfg){
      var c=tuneConfig(cfg||{},true);
      var inst=new H(c);
      try{ window.__keenHls=inst; inst.__keenBumped=1; }catch(e){}
      try{ console.warn('KZ_HLS_TUNED:'+(c.maxBufferLength)+'/'+(c.liveSyncDurationCount)); }catch(e){}
      return inst;
    }
    try{
      KeenHls.prototype=H.prototype;
      Object.setPrototypeOf(KeenHls,H);   // carries isSupported/Events/ErrorTypes statics
      KeenHls.__keenWrapped=true;
    }catch(e){ return H; }
    return KeenHls;
  }
  /**
   * Only frames that actually host a player may be touched.
   *
   * Redefining a window property is exactly what bot-detection services fingerprint for.
   * Installing this on EVERY page put 1337x.to (DataDome) into a permanent
   * "Performing security verification" reload loop — nothing was blocked, the page just
   * never passed. Keep our footprint off pages that have no player at all.
   */
  function looksLikePlayerContext(){
    try{
      if(document.getElementsByTagName('video').length) return true;
      if(document.querySelector('#playerFrame,iframe[src*="/premiumtv/"],iframe[src*="/stream-"],iframe[src*="/stream/"],iframe[src*="daddy"]')) return true;
      var s=document.getElementsByTagName('script');
      for(var i=0;i<s.length&&i<40;i++){
        if(/hls|clappr|jwplayer|video-?js|dash\.|p2p-engine|playerjs/i.test(s[i].src||'')) return true;
      }
    }catch(e){}
    return false;
  }
  var hlsHooked=false;
  function installHlsHook(){
    if(hlsHooked) return;
    hlsHooked=true;
    try{
      var _hls=window.Hls;
      Object.defineProperty(window,'Hls',{
        configurable:true,
        get:function(){ return _hls; },
        set:function(v){ _hls=wrapHls(v); }
      });
      if(_hls) _hls=wrapHls(_hls);
    }catch(e){}
  }
  // Bundled players (Clappr et al) never touch window.Hls — bump the live instance.
  // hls.js re-reads config each buffering tick, so this takes effect immediately.
  function bumpLive(){
    var cands=[];
    try{ cands.push(window.hls,window.__keenHls); }catch(e){}
    try{
      var p=window.player||window.playerInstance;
      if(p&&p.core&&typeof p.core.getCurrentPlayback==='function'){
        var pb=p.core.getCurrentPlayback();
        if(pb) cands.push(pb._hls,pb.hls);
      }
    }catch(e){}
    // The player is usually held in a local var we cannot name (on-device logs showed
    // zero hits via the paths above), so sweep window for anything shaped like a Clappr
    // player or an hls.js instance. Bounded and one-shot-ish: it stops once bumped.
    try{
      var keys=Object.keys(window);
      for(var k=0;k<keys.length&&k<400;k++){
        var o;
        try{ o=window[keys[k]]; }catch(e){ continue; }
        if(!o||typeof o!=='object') continue;
        try{
          if(o.config&&o.levels!==undefined&&typeof o.attachMedia==='function'){ cands.push(o); continue; }
          if(o.core&&typeof o.core.getCurrentPlayback==='function'){
            var pb2=o.core.getCurrentPlayback();
            if(pb2) cands.push(pb2._hls,pb2.hls);
          }
        }catch(e){}
      }
    }catch(e){}
    for(var i=0;i<cands.length;i++){
      var h=cands[i];
      if(!h||h.__keenBumped||!h.config) continue;
      h.__keenBumped=1;
      tuneConfig(h.config,false);
      try{ console.warn('KZ_HLS_BUMPED:'+h.config.maxBufferLength); }catch(e){}
    }
  }

  // ----------------------------------------------------------- gesture in --
  function onGesture(ev){
    try{ if(ev&&ev.isTrusted===false) return; }catch(e){}
    var first=(now()-lastGesture)>1500;
    lastGesture=now();
    try{ gesturePoint={x:ev.clientX,y:ev.clientY}; }catch(e){ gesturePoint=null; }
    armUnmute();
    if(first){
      try{ console.warn('KZ_FRAME_TAP:'+(ev&&ev.type)+' '+tag()+' vids='+videos().length); }catch(e){}
      // Only fan out when this frame cannot service the tap itself — that is exactly
      // the embed case (play control here, <video> in a sibling/child frame) and keeps
      // ordinary pages, where the media is local, free of cross-frame chatter.
      if(!mainVideo()){
        if(isTop) broadcastDown({__keen:1,kind:'gesture'});
        else post(window.top||window.parent,{__keen:1,kind:'tap'});
      }
    }
  }
  function onActivate(ev){
    onGesture(ev);
    // Synchronous — this is the only moment fullscreen can be granted.
    tryFullscreen('gesture');
    scheduleFill('gesture');
  }
  document.addEventListener('pointerdown',onGesture,true);
  document.addEventListener('touchstart',onGesture,true);
  document.addEventListener('pointerup',onActivate,true);
  document.addEventListener('touchend',onActivate,true);
  document.addEventListener('click',onActivate,true);

  // Media events: unmute again (players re-mute on autoplay-error retry) and take the
  // second fullscreen shot while the tap's activation may still be alive.
  function onMedia(e){
    if(!e||!e.target||e.target.tagName!=='VIDEO') return;
    if(gestureFresh(GESTURE_WINDOW_MS)){
      unmuteNow('media:'+e.type);
      if(!inFullscreen()&&gestureFresh(ACTIVATION_MS)) tryFullscreen('media:'+e.type);
      scheduleFill('media:'+e.type);
    }
    report(false);
  }
  document.addEventListener('play',onMedia,true);
  document.addEventListener('playing',onMedia,true);
  document.addEventListener('loadedmetadata',onMedia,true);
  document.addEventListener('volumechange',function(e){
    if(!e||!e.target||e.target.tagName!=='VIDEO') return;
    if(now()<unmuteUntil&&(e.target.muted||e.target.volume===0)) unmuteNow('re-mute');
  },true);
  document.addEventListener('pause',function(e){ if(e&&e.target&&e.target.tagName==='VIDEO') report(false); },true);
  document.addEventListener('ended',function(e){ if(e&&e.target&&e.target.tagName==='VIDEO') report(false); },true);

  // Keep child iframes fullscreen-capable (the container policy is read at navigation,
  // so stamp it as early as the node appears).
  function allowFs(node){
    try{
      if(!node||node.tagName!=='IFRAME') return;
      // Never rewrite attributes on a challenge widget's frame, and do nothing at all
      // while a challenge is running — mutating an iframe's permission attributes
      // changes its container policy and is exactly the kind of tampering that gets a
      // client failed. Shares the guard's single authoritative detector.
      if(window.__keenChallengeActive && window.__keenChallengeActive()) return;
      if(/challenges\.cloudflare\.com|\/turnstile\/|recaptcha|hcaptcha|arkoselabs|captcha-delivery|\/cdn-cgi\/challenge-platform\//i
        .test(node.src||node.getAttribute('src')||'')) return;
      if(!node.hasAttribute('allowfullscreen')) node.setAttribute('allowfullscreen','');
      var a=node.getAttribute('allow')||'';
      if(a.indexOf('fullscreen')<0) node.setAttribute('allow',(a?a+'; ':'')+'fullscreen; autoplay');
    }catch(e){}
  }
  try{
    new MutationObserver(function(muts){
      for(var i=0;i<muts.length;i++){
        var added=muts[i].addedNodes||[];
        for(var j=0;j<added.length;j++){
          var n=added[j];
          if(!n||n.nodeType!==1) continue;
          if(n.tagName==='IFRAME') allowFs(n);
          else if(n.getElementsByTagName){
            var inner=n.getElementsByTagName('iframe');
            for(var k=0;k<inner.length&&k<8;k++) allowFs(inner[k]);
          }
        }
      }
    }).observe(document.documentElement,{childList:true,subtree:true});
  }catch(e){}

  // Install beacon: proves the agent reached this frame at all. Without it there is no
  // way to distinguish "agent never installed in the player frame" from "installed but
  // fullscreen was refused" — they look identical from the native side.
  try{
    console.warn('KZ_FRAME_BOOT:'+tag());
  }catch(e){}

  var ticks=0;
  setInterval(function(){
    ticks++;
    // Player-only work, gated so ordinary pages see no global tampering or window scans.
    if(looksLikePlayerContext()){
      installHlsHook();
      bumpLive();
    }
    report(false);
    if(ticks%25===0){
      try{
        var f=document.getElementsByTagName('iframe');
        for(var i=0;i<f.length&&i<8;i++) allowFs(f[i]);
      }catch(e){}
    }
  },400);
})();
    """.trimIndent()

    /** Top-frame call: undo the CSS-fill chain when leaving playback mode. */
    val EXIT_FILL_JS: String = """
(function(){
  try{ if(typeof window.__keenExitFill==='function') return window.__keenExitFill(); }catch(e){}
  return 0;
})();
    """.trimIndent()

    /** Top-frame call: push the native Play gesture hint into every descendant frame. */
    val BROADCAST_INTENT_JS: String = """
(function(){
  try{ if(typeof window.__keenBroadcastIntent==='function'){ window.__keenBroadcastIntent(); return 1; } }catch(e){}
  return 0;
})();
    """.trimIndent()
}
