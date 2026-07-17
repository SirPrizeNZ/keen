package com.keenzero.app.input

/**
 * In-page modal scroll controller. Owns a **direct DOM element reference**, never
 * screen coordinates. Injected at document-start (or page-commit fallback).
 *
 * v4: atomic bindAndStart, stop rAF at boundary, lifecycle auto-stop.
 */
object ModalScrollJs {
    const val CONTROLLER = "__keenModalScroll"

    /** Full controller install (idempotent). */
    val INSTALL_JS: String = """
(function(){
  // v10: slightly looser room gates for long result sheets; still rejects chrome/junk.
  if(window.__keenModalScroll && window.__keenModalScroll.__v>=10) return;
  var C={
    __v:10,
    target:null,
    host:null,
    gen:0,
    lastValidate:0,
    dir:0,
    raf:0,
    lastTs:0,
    accum:0,
    pxPerSec:620,
    stepFrac:0.22,
    failTicks:0,
    firstMoved:false
  };
  function isDoc(n){
    return !n||n===document||n===document.scrollingElement||
      n===document.documentElement||n===document.body;
  }
  function oy(n){
    try{var s=getComputedStyle(n);return (s.overflowY||s.overflow||'').toLowerCase();}
    catch(e){return '';}
  }
  function visible(n){
    try{
      var st=getComputedStyle(n);
      if(st.display==='none'||st.visibility==='hidden'||+(st.opacity||1)===0) return false;
      var r=n.getBoundingClientRect();
      return r.width>=16&&r.height>=16;
    }catch(e){return false;}
  }
  function maxScroll(n){
    try{return Math.max(0,(n.scrollHeight||0)-(n.clientHeight||0));}catch(e){return 0;}
  }
  function looksTinyControl(n){
    try{
      var tag=(n.tagName||'').toUpperCase();
      if(tag==='KBD'||tag==='BUTTON'||tag==='SPAN'||tag==='A'||tag==='INPUT'||
         tag==='LABEL'||tag==='OPTION'||tag==='SELECT'||tag==='TEXTAREA'||tag==='SVG'||tag==='PATH'||
         tag==='IMG'||tag==='I'||tag==='EM'||tag==='STRONG'||tag==='CODE') return true;
      var r=n.getBoundingClientRect();
      // Real results lists are large; chrome keys/buttons are not.
      if(r.height>0 && r.height<96) return true;
      if(r.width>0 && r.width<96) return true;
      if((n.clientHeight||0)>0 && (n.clientHeight||0)<96) return true;
      if(maxScroll(n)>0 && maxScroll(n)<40) return true;
    }catch(e){}
    return false;
  }
  // Discovery-only check. Do NOT poke scrollTop (that fights continuous holds).
  function isScrollY(n){
    if(!n||isDoc(n)||!visible(n)||looksTinyControl(n)) return false;
    var room=maxScroll(n);
    if(room<40) return false;
    if((n.clientHeight||0)<88) return false;
    if((n.clientWidth||0)<100) return false;
    var o=oy(n);
    return o==='auto'||o==='scroll'||o==='overlay'||o==='hidden'||room>=40;
  }
  function looksSidebar(n){
    try{
      var r=n.getBoundingClientRect();
      var vw=window.innerWidth||0, vh=window.innerHeight||0;
      if(r.height>vh*0.75&&r.width<vw*0.34&&(r.left<vw*0.05||r.right>vw*0.95)) return true;
    }catch(e){}
    return false;
  }
  /** Top/bottom site chrome — never treat as search-results modal. */
  function looksChromeNav(n){
    try{
      var tag=(n.tagName||'').toUpperCase();
      var r=n.getBoundingClientRect();
      var vw=window.innerWidth||0, vh=window.innerHeight||0;
      var idc=((n.id||'')+' '+String(n.className||'')+' '+tag).toLowerCase();
      if(/vpnav|navbar|nav-bar|site-header|page-header|toolbar|menubar|bread|topbar|app-bar|masthead/.test(idc)) return true;
      if(tag==='HEADER'||tag==='NAV'){
        if(r.top<96 && r.height<vh*0.28 && r.width>vw*0.45) return true;
      }
      // Wide short strip stuck to top or bottom.
      if(r.top<=4 && r.height>0 && r.height<vh*0.2 && r.width>vw*0.6) return true;
      if(r.bottom>=vh-4 && r.height>0 && r.height<vh*0.2 && r.width>vw*0.6) return true;
    }catch(e){}
    return false;
  }
  function overlayish(n){
    var p=n, hops=0;
    while(p&&!isDoc(p)&&hops<22){
      try{
        var st=getComputedStyle(p);
        if(st.position==='fixed'||st.position==='absolute'||st.position==='sticky') return true;
        var role=(p.getAttribute('role')||'').toLowerCase();
        if(role==='dialog'||role==='listbox'||role==='menu'||role==='combobox'||role==='search'||role==='alertdialog') return true;
        if(p.getAttribute('aria-modal')==='true') return true;
        var idc=((p.id||'')+' '+String(p.className||'')).toLowerCase();
        if(/modal|dialog|popup|overlay|dropdown|popover|search|suggest|result|listbox|drawer|typeahead|autocomplete|sheet|combobox/.test(idc)) return true;
      }catch(e){}
      p=p.parentElement; hops++;
    }
    return false;
  }
  function findHost(n){
    var p=n, hops=0;
    while(p&&!isDoc(p)&&hops<22){
      if(overlayish(p)) return p;
      p=p.parentElement; hops++;
    }
    return n&&n.parentElement?n.parentElement:null;
  }
  function score(n){
    if(looksChromeNav(n)||looksSidebar(n)) return -1e9;
    var room=maxScroll(n);
    var r=n.getBoundingClientRect();
    var vw=window.innerWidth||0, vh=window.innerHeight||0;
    var ov=overlayish(n)?1000:0;
    var results=0;
    try{
      var items=n.querySelectorAll?n.querySelectorAll('a[href],[role="option"],li,img,article,[class*="result"],[class*="item"]'):[];
      results=Math.min(400, (items.length||0)*25);
      if(items.length) results+=200;
    }catch(e){}
    // Prefer mid-screen sheet geometry (search dropdown / dialog).
    var cx=r.left+r.width/2, cy=r.top+r.height/2;
    var center=1-Math.min(1,(Math.abs(cx-vw/2)/(vw/2+1)+Math.abs(cy-vh/2)/(vh/2+1))/2);
    var sheet=0;
    if(r.height>vh*0.22 && r.height<vh*0.92 && r.width>vw*0.2 && r.width<vw*0.92) sheet=350;
    if(r.width>vw*0.98 && r.height>vh*0.98) sheet-=500;
    return ov+room+results+sheet+center*300+Math.min(r.height,600)*0.05;
  }
  function consider(list, n){
    if(!isScrollY(n)||looksSidebar(n)||looksChromeNav(n)||looksTinyControl(n)) return;
    if(!overlayish(n)){
      try{
        var r=n.getBoundingClientRect();
        if(r.height>(window.innerHeight||0)*0.9) return;
      }catch(e){return;}
    }
    list.push(n);
  }
  function harvestHost(list, host){
    try{
      if(!host||!visible(host)) return;
      var hr=host.getBoundingClientRect();
      if(hr.width<48||hr.height<48) return;
      // Full-viewport dim layers: still walk children (sheet lives inside).
      var fullCover=hr.width>window.innerWidth*0.98&&hr.height>window.innerHeight*0.98;
      if(!fullCover) consider(list, host);
      var nodes=host.querySelectorAll('*');
      for(var j=0;j<nodes.length&&j<320;j++) consider(list, nodes[j]);
    }catch(e){}
  }
  function discover(){
    var list=[];
    if(C.target && C.target.isConnected) consider(list, C.target);
    // 1) Semantic hosts (role / class substrings).
    try{
      var hosts=document.querySelectorAll('[role="dialog"],[role="listbox"],[role="menu"],[aria-modal="true"],[class*="modal"],[class*="Modal"],[class*="dropdown"],[class*="suggest"],[class*="result"],[class*="popover"],[class*="overlay"],[class*="typeahead"],[class*="autocomplete"],[class*="Search"],[class*="search"],[class*="sheet"],[class*="Sheet"],[class*="drawer"],[class*="Drawer"],[class*="popup"],[class*="Popup"]');
      for(var i=0;i<hosts.length&&i<100;i++) harvestHost(list, hosts[i]);
    }catch(e){}
    // 2) Fixed/absolute layers — required for hashed-class SPAs (bcine.ru etc.).
    try{
      var all=document.body?document.body.getElementsByTagName('*'):[];
      var lim=Math.min(all.length, 2800);
      var fixedHosts=[];
      for(var fi=0;fi<lim;fi++){
        var el=all[fi];
        try{
          var st=getComputedStyle(el);
          var pos=st.position;
          if(pos!=='fixed'&&pos!=='absolute'&&pos!=='sticky') continue;
          if(!visible(el)) continue;
          var r=el.getBoundingClientRect();
          if(r.width<64||r.height<64) continue;
          // Ignore tiny controls; keep mid-size sheets and full-screen hosts.
          fixedHosts.push(el);
        }catch(e){}
      }
      for(var fh=0;fh<fixedHosts.length&&fh<60;fh++) harvestHost(list, fixedHosts[fh]);
    }catch(e){}
    // 3) Known fixture lists.
    try{
      var fixed=document.querySelectorAll('.list,[data-keen-modal-list]');
      for(var k=0;k<fixed.length&&k<20;k++) consider(list, fixed[k]);
    }catch(e){}
    // 4) Last resort: any non-document vertical scroller that is overlayish.
    if(!list.length){
      try{
        var all2=document.body?document.body.getElementsByTagName('*'):[];
        var lim2=Math.min(all2.length, 2000);
        for(var li=0;li<lim2;li++){
          var n=all2[li];
          if(isScrollY(n)&&overlayish(n)&&!looksSidebar(n)) list.push(n);
        }
      }catch(e){}
    }
    if(!list.length) return null;
    // Dedupe by identity.
    var uniq=[], seen=typeof WeakSet!=='undefined'?new WeakSet():null;
    for(var u=0;u<list.length;u++){
      var cand=list[u];
      if(!cand) continue;
      if(seen){ if(seen.has(cand)) continue; seen.add(cand); }
      uniq.push(cand);
    }
    uniq.sort(function(a,b){return score(b)-score(a);});
    return uniq[0];
  }
  function meta(el){
    if(!el) return {tag:'',id:'',cls:''};
    return {
      tag:(el.tagName||''),
      id:el.id||'',
      cls:String(el.className||'').slice(0,80)
    };
  }
  function rectOf(el){
    try{
      if(!el) return null;
      var r=el.getBoundingClientRect();
      return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};
    }catch(e){ return null; }
  }
  // Soft validate: keep sticky target during a hold even if overflow probes flake.
  function validateSoft(){
    C.lastValidate=Date.now();
    var el=C.target;
    if(!el||!el.isConnected) return false;
    try{
      var r=el.getBoundingClientRect();
      if(r.width<8||r.height<8) return false;
      var st=getComputedStyle(el);
      if(st.display==='none'||st.visibility==='hidden') return false;
    }catch(e){ return false; }
    if(C.host && !C.host.isConnected){ C.host=findHost(el); }
    // Host fully gone → target invalid.
    if(C.host && !C.host.isConnected) return false;
    return true;
  }
  function hardValid(el){
    return !!(el && el.isConnected && isScrollY(el) && !looksSidebar(el));
  }
  function bind(force){
    // Prefer sticky target while a continuous hold is active unless forced.
    if(!force && C.target && validateSoft() && maxScroll(C.target)>=1){
      var m0=meta(C.target);
      var r0=rectOf(C.target);
      return JSON.stringify({
        ok:true, bound:false, gen:C.gen, sticky:true,
        tag:m0.tag, id:m0.id, cls:m0.cls,
        st:C.target.scrollTop||0, sh:C.target.scrollHeight||0,
        ch:C.target.clientHeight||0, max:maxScroll(C.target), reason:'sticky',
        rect:r0
      });
    }
    var el=discover();
    if(!el){
      if(!(C.target && validateSoft() && maxScroll(C.target)>=1)){
        C.target=null; C.host=null;
      }
      if(C.target){
        var mS=meta(C.target);
        return JSON.stringify({
          ok:true, bound:false, sticky:true, gen:C.gen,
          tag:mS.tag, id:mS.id, cls:mS.cls,
          st:C.target.scrollTop||0, sh:C.target.scrollHeight||0,
          ch:C.target.clientHeight||0, max:maxScroll(C.target), reason:'sticky_keep',
          rect:rectOf(C.target)
        });
      }
      return JSON.stringify({ok:false, bound:false, reason:'no_target', gen:C.gen});
    }
    // If we already have a working target with room, keep it (avoid mid-hold rebind).
    var rebound=false;
    if(C.target && C.target.isConnected && maxScroll(C.target)>=1 && C.dir!==0){
      el=C.target;
    } else {
      rebound = (C.target !== el);
      C.target=el;
      C.host=findHost(el);
      C.gen=(C.gen|0)+1;
    }
    C.lastValidate=Date.now();
    var m=meta(el);
    return JSON.stringify({
      ok:true,
      bound:rebound,
      gen:C.gen,
      tag:m.tag, id:m.id, cls:m.cls,
      st:el.scrollTop||0,
      sh:el.scrollHeight||0,
      ch:el.clientHeight||0,
      max:maxScroll(el),
      reason:rebound?'bound':'reuse',
      rect:rectOf(el)
    });
  }
  function isActive(){
    return validateSoft() && maxScroll(C.target)>=1;
  }
  function stopAnim(reason){
    C.dir=0;
    C.lastTs=0;
    C.accum=0;
    C.failTicks=0;
    if(C.raf){ cancelAnimationFrame(C.raf); C.raf=0; }
    return reason||'stop';
  }
  function applyDelta(dirPx){
    var el=C.target;
    if(!el) return {moved:false, before:0, after:0, boundary:true, max:0, sh:0, ch:0};
    try{ if(window.__keenScrollAuth) window.__keenScrollAuth.noteUser(); }catch(e0){}
    var before=Number(el.scrollTop)||0;
    var max=maxScroll(el);
    if(max<1) return {moved:false, before:before, after:before, boundary:true, max:max, sh:el.scrollHeight||0, ch:el.clientHeight||0};
    var next=before+dirPx;
    if(next<0) next=0;
    if(next>max) next=max;
    try{
      if(typeof el.scrollTo==='function') el.scrollTo(0, next);
      else el.scrollTop=next;
    }catch(e){ try{ el.scrollTop=next; }catch(e2){} }
    var after=Number(el.scrollTop)||0;
    // Wheel fallback if scrollTop is a no-op (custom scroller).
    if(Math.abs(after-before)<0.25 && Math.abs(dirPx)>=1){
      try{
        el.dispatchEvent(new WheelEvent('wheel',{
          deltaY:dirPx, deltaMode:0, bubbles:true, cancelable:true
        }));
        after=Number(el.scrollTop)||0;
      }catch(e){}
    }
    var moved=Math.abs(after-before)>=0.25;
    var boundary=(after<=0.5&&dirPx<0)||(after>=max-0.5&&dirPx>0)||(!moved&&((before<=0.5&&dirPx<0)||(before>=max-0.5&&dirPx>0)));
    return {moved:moved, before:before, after:after, boundary:boundary, max:max, sh:el.scrollHeight||0, ch:el.clientHeight||0};
  }
  function resultBase(r, extra){
    var el=C.target;
    var m=meta(el);
    var o={
      ok:true, handled:true,
      moved:!!(r&&r.moved), boundary:!!(r&&r.boundary),
      before:r?r.before:0, after:r?r.after:0,
      st:r?r.after:(el?(el.scrollTop||0):0),
      sh:r?r.sh:(el?(el.scrollHeight||0):0),
      ch:r?r.ch:(el?(el.clientHeight||0):0),
      max:r?r.max:(el?maxScroll(el):0),
      tag:m.tag, id:m.id, cls:m.cls,
      gen:C.gen, connected:!!(el&&el.isConnected),
      rect:rectOf(el)
    };
    if(extra){ for(var k in extra){ if(Object.prototype.hasOwnProperty.call(extra,k)) o[k]=extra[k]; } }
    return JSON.stringify(o);
  }
  function step(direction){
    if(!isActive()){
      var b=bind(true);
      if(!JSON.parse(b).ok) return JSON.stringify({ok:false, handled:false, reason:'no_target', gen:C.gen});
    }
    var dir=direction|0;
    if(!dir) return JSON.stringify({ok:true, handled:false, reason:'bad_dir', gen:C.gen});
    var el=C.target;
    var stepPx=Math.max(48, Math.floor((el.clientHeight||200)*C.stepFrac));
    if(dir<0) stepPx=-stepPx;
    var r=applyDelta(stepPx);
    if(r.moved) C.firstMoved=true;
    return resultBase(r, {stepped:true, firstMoved:C.firstMoved});
  }
  function tick(ts){
    if(!C.dir){ C.raf=0; C.accum=0; return; }
    // Target gone / host removed → stop (keep no stale animation).
    if(!validateSoft()){
      var b=bind(true);
      if(!JSON.parse(b).ok){
        C.failTicks=(C.failTicks||0)+1;
        if(C.failTicks>8){
          stopAnim('target_invalid');
          return;
        }
        C.raf=requestAnimationFrame(tick);
        return;
      }
      C.failTicks=0;
    } else {
      C.failTicks=0;
    }
    if(!C.lastTs) C.lastTs=ts;
    var dt=Math.min(0.05, Math.max(0.001, (ts-C.lastTs)/1000));
    C.lastTs=ts;
    // Accumulate so sub-pixel frames still produce integer motion.
    C.accum += C.dir * C.pxPerSec * dt;
    var stepPx=C.accum|0;
    if(stepPx!==0){
      C.accum -= stepPx;
      var r=applyDelta(stepPx);
      if(r.moved) C.firstMoved=true;
      // Boundary with no further motion: stop rAF; retain target for reverse.
      if(r.boundary && !r.moved){
        stopAnim('boundary');
        return;
      }
    } else {
      // Even with sub-pixel accum, if already at hard boundary stop spinning.
      var el=C.target;
      if(el){
        var max=maxScroll(el);
        var st=Number(el.scrollTop)||0;
        if((C.dir<0 && st<=0.5) || (C.dir>0 && st>=max-0.5)){
          stopAnim('boundary');
          return;
        }
      }
    }
    C.raf=requestAnimationFrame(tick);
  }
  function start(direction){
    var dir=direction|0;
    if(!dir) return JSON.stringify({ok:false, reason:'bad_dir', gen:C.gen});
    if(!isActive()){
      var b=bind(true);
      if(!JSON.parse(b).ok) return JSON.stringify({ok:false, handled:false, reason:'no_target', gen:C.gen});
    }
    var el=C.target;
    var kick=Math.max(28, Math.floor((el.clientHeight||200)*0.14));
    if(dir<0) kick=-kick;
    var r=applyDelta(kick);
    if(r.moved) C.firstMoved=true;
    C.dir=dir<0?-1:1;
    C.lastTs=0;
    C.accum=0;
    C.failTicks=0;
    if(C.raf) cancelAnimationFrame(C.raf);
    // At boundary with no motion: do not schedule endless rAF. Keep target.
    if(r.boundary && !r.moved){
      stopAnim('boundary');
      return resultBase(r, {started:true, firstMoved:C.firstMoved, reason:'boundary'});
    }
    C.raf=requestAnimationFrame(tick);
    return resultBase(r, {started:true, firstMoved:C.firstMoved, reason:'started'});
  }
  /**
   * Atomic: validate sticky target OR bind, then start scrolling in one call.
   * Returns full diagnostic payload for native ownership decisions.
   */
  function bindAndStart(direction){
    var dir=direction|0;
    if(!dir) return JSON.stringify({ok:false, bound:false, moved:false, reason:'bad_dir', gen:C.gen});
    var boundNow=false;
    // Drop sticky chrome / dead targets before reuse.
    if(C.target && (looksChromeNav(C.target) || looksSidebar(C.target))){
      C.target=null; C.host=null;
    }
    if(C.target && validateSoft() && maxScroll(C.target)>=1 && !looksChromeNav(C.target)){
      // sticky valid
    } else {
      var bRaw=bind(true);
      var bObj;
      try{ bObj=JSON.parse(bRaw); }catch(e){ bObj={ok:false}; }
      if(!bObj.ok){
        return JSON.stringify({
          ok:false, bound:false, moved:false, boundary:false,
          reason:bObj.reason||'no_target', gen:C.gen,
          tag:'', id:'', cls:'', before:0, after:0, st:0, sh:0, ch:0
        });
      }
      boundNow=!!bObj.bound || !!bObj.ok;
    }
    if(!C.target || looksChromeNav(C.target) || (!hardValid(C.target) && !validateSoft())){
      C.target=null; C.host=null;
      return JSON.stringify({ok:false, bound:false, moved:false, reason:'no_target', gen:C.gen});
    }
    C.firstMoved=false;
    var startRaw=start(dir);
    var s;
    try{ s=JSON.parse(startRaw); }catch(e){ s={ok:false}; }
    // Reject targets that claim room but cannot actually change scrollTop.
    if(s.ok && !s.moved){
      var maxRoom=Number(s.max||0);
      var after=Number(s.after||0);
      var before=Number(s.before||0);
      var atEnd=(dir>0 && after>=maxRoom-0.5) || (dir<0 && after<=0.5);
      if(!atEnd && Math.abs(after-before)<0.25 && maxRoom>=4){
        // scroll_noop — drop and try one rebind to next-best candidate.
        var bad=C.target;
        C.target=null; C.host=null;
        var b2=bind(true);
        var b2o; try{ b2o=JSON.parse(b2); }catch(e){ b2o={ok:false}; }
        if(b2o.ok && C.target && C.target!==bad){
          startRaw=start(dir);
          try{ s=JSON.parse(startRaw); }catch(e){ s={ok:false}; }
          boundNow=true;
        } else {
          s={ok:false, bound:false, moved:false, reason:'scroll_noop', gen:C.gen};
        }
      }
    }
    s.bound=boundNow || !!s.bound;
    s.ok=!!s.ok;
    if(!s.reason) s.reason=s.ok?'bind_and_start':'start_fail';
    return JSON.stringify(s);
  }
  function stop(){
    stopAnim('native_stop');
    var el=C.target;
    var m=meta(el);
    return JSON.stringify({
      ok:true, stopped:true,
      st:el?(el.scrollTop||0):0,
      sh:el?(el.scrollHeight||0):0,
      ch:el?(el.clientHeight||0):0,
      active:isActive(),
      gen:C.gen,
      tag:m.tag, id:m.id, cls:m.cls
    });
  }
  function clear(){
    stopAnim('clear');
    C.target=null;
    C.host=null;
    C.gen=(C.gen|0)+1;
    C.firstMoved=false;
    return JSON.stringify({ok:true, cleared:true, gen:C.gen});
  }
  function snapshot(){
    var el=C.target;
    var m=meta(el);
    var rect=null;
    try{
      if(el){
        var r=el.getBoundingClientRect();
        rect={left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};
      }
    }catch(e){}
    return JSON.stringify({
      active:isActive(),
      gen:C.gen,
      dir:C.dir,
      tag:m.tag, id:m.id, cls:m.cls,
      st:el?(el.scrollTop|0):0,
      sh:el?(el.scrollHeight|0):0,
      ch:el?(el.clientHeight|0):0,
      max:el?maxScroll(el):0,
      connected:!!(el&&el.isConnected),
      hostConnected:!!(C.host&&C.host.isConnected),
      firstMoved:!!C.firstMoved,
      rect:rect
    });
  }
  /**
   * Hit-test: is (px,py) over the bound list? WebView CSS pixels.
   * Generous padding so TV aiming is not pixel-perfect.
   */
  function hitTest(px, py){
    if(!C.target||!C.target.isConnected) return JSON.stringify({ok:false, over:false, reason:'no_target'});
    var r=rectOf(C.target);
    if(!r) return JSON.stringify({ok:false, over:false, reason:'no_rect'});
    var pad=28;
    var over=px>=r.left-pad && px<=r.right+pad && py>=r.top-pad && py<=r.bottom+pad;
    return JSON.stringify({
      ok:true, over:over, gen:C.gen,
      left:r.left, top:r.top, right:r.right, bottom:r.bottom,
      width:r.width, height:r.height,
      st:C.target.scrollTop|0, sh:C.target.scrollHeight|0, ch:C.target.clientHeight|0,
      max:maxScroll(C.target)
    });
  }
  function targetRect(){
    var el=C.target;
    if(!el||!el.isConnected) return JSON.stringify({ok:false});
    try{
      var r=el.getBoundingClientRect();
      var m=meta(el);
      return JSON.stringify({
        ok:true, active:isActive(), gen:C.gen,
        left:r.left, top:r.top, right:r.right, bottom:r.bottom,
        width:r.width, height:r.height,
        st:el.scrollTop|0, sh:el.scrollHeight|0, ch:el.clientHeight|0,
        max:maxScroll(el), tag:m.tag, id:m.id, cls:m.cls
      });
    }catch(e){ return JSON.stringify({ok:false, err:String(e)}); }
  }
  // Lifecycle: never leave rAF spinning after navigation / background.
  function onLifeStop(){ stopAnim('lifecycle'); }
  try{
    window.addEventListener('blur', onLifeStop, true);
    window.addEventListener('pagehide', onLifeStop, true);
    document.addEventListener('visibilitychange', function(){
      if(document.hidden) onLifeStop();
    }, true);
    window.addEventListener('pagehide', function(){ clear(); }, true);
  }catch(e){}
  window.__keenModalScroll={
    __v:9, bind:bind, bindAndStart:bindAndStart, hitTest:hitTest,
    targetRect:targetRect,
    isActive:function(){return isActive();},
    step:step, start:start, stop:stop, clear:clear, snapshot:snapshot
  };
})();
""".trimIndent()

    fun callBind(): String =
        "(function(){try{if(!window.__keenModalScroll||window.__keenModalScroll.__v<9){$INSTALL_JS}return window.__keenModalScroll.bind();}catch(e){return JSON.stringify({ok:false,err:String(e)});}})();"

    fun callBindAndStart(direction: Int): String =
        "(function(){try{if(!window.__keenModalScroll||window.__keenModalScroll.__v<9){$INSTALL_JS}return window.__keenModalScroll.bindAndStart($direction);}catch(e){return JSON.stringify({ok:false,err:String(e)});}})();"

    fun callHitTest(px: Int, py: Int): String =
        "(function(){try{if(!window.__keenModalScroll||window.__keenModalScroll.__v<9){$INSTALL_JS}return window.__keenModalScroll.hitTest($px,$py);}catch(e){return JSON.stringify({ok:false,err:String(e)});}})();"

    fun callStart(direction: Int): String =
        "(function(){try{if(!window.__keenModalScroll||window.__keenModalScroll.__v<9){$INSTALL_JS}return window.__keenModalScroll.start($direction);}catch(e){return JSON.stringify({ok:false,err:String(e)});}})();"

    fun callStop(): String =
        "(function(){try{if(!window.__keenModalScroll)return JSON.stringify({ok:false});return window.__keenModalScroll.stop();}catch(e){return JSON.stringify({ok:false});}})();"

    fun callStep(direction: Int): String =
        "(function(){try{if(!window.__keenModalScroll||window.__keenModalScroll.__v<9){$INSTALL_JS}return window.__keenModalScroll.step($direction);}catch(e){return JSON.stringify({ok:false,err:String(e)});}})();"

    fun callTargetRect(): String =
        "(function(){try{if(!window.__keenModalScroll||window.__keenModalScroll.__v<9){$INSTALL_JS}return window.__keenModalScroll.targetRect();}catch(e){return JSON.stringify({ok:false});}})();"

    fun callClear(): String =
        "(function(){try{if(!window.__keenModalScroll)return JSON.stringify({ok:true});return window.__keenModalScroll.clear();}catch(e){return JSON.stringify({ok:false});}})();"

    fun callIsActive(): String =
        "(function(){try{if(!window.__keenModalScroll)return 'false';return window.__keenModalScroll.isActive()?'true':'false';}catch(e){return 'false';}})();"

    fun callSnapshot(): String =
        "(function(){try{if(!window.__keenModalScroll)return '{}';return window.__keenModalScroll.snapshot();}catch(e){return '{}';}})();"

    /**
     * After IME submit: always blur the text field. Does not submit.
     */
    val IME_SUBMIT_JS: String = """
(function(){
  try{
    function isTextual(el){
      if(!el||el.nodeType!==1) return false;
      var tag=(el.tagName||'').toUpperCase();
      if(tag==='TEXTAREA'||el.isContentEditable) return true;
      if(tag==='INPUT'){
        var type=(el.getAttribute('type')||'text').toLowerCase();
        return type===''||type==='text'||type==='search'||type==='url'||type==='email'||type==='tel';
      }
      var role=(el.getAttribute('role')||'').toLowerCase();
      return role==='searchbox'||role==='combobox'||role==='textbox';
    }
    var input=document.activeElement;
    if(!isTextual(input)){
      var inputs=document.querySelectorAll('input,textarea,[contenteditable="true"],[role="searchbox"]');
      for(var i=0;i<inputs.length&&i<30;i++){
        if(isTextual(inputs[i])){
          try{
            var r=inputs[i].getBoundingClientRect();
            var st=getComputedStyle(inputs[i]);
            if(r.width>16&&r.height>8&&st.display!=='none'){ input=inputs[i]; break; }
          }catch(e){}
        }
      }
    }
    var val=input&&(input.value!=null?String(input.value):(input.textContent||'')).trim()||'';
    var url0=location.href;
    try{ if(input) input.blur(); }catch(e){}
    try{ if(document.activeElement&&document.activeElement.blur) document.activeElement.blur(); }catch(e2){}
    return JSON.stringify({
      ok:true, val:val.slice(0,80), url:url0, hadInput:!!input,
      blurred:true, empty:!val
    });
  }catch(e){ return JSON.stringify({ok:false, err:String(e)}); }
})();
""".trimIndent()

    /**
     * Observe whether the page already handled IME submit (nav / modal / results).
     */
    val IME_OBSERVE_JS: String = """
(function(){
  try{
    var url=location.href;
    var active=false;
    try{ active=!!(window.__keenModalScroll&&window.__keenModalScroll.isActive&&window.__keenModalScroll.isActive()); }catch(e){}
    var results=0;
    try{
      var hosts=document.querySelectorAll('[role="dialog"],[role="listbox"],[aria-modal="true"],[class*="result"],[class*="suggest"],[class*="dropdown"],[class*="typeahead"],[class*="autocomplete"],[class*="modal"],[class*="overlay"]');
      for(var i=0;i<hosts.length&&i<40;i++){
        var h=hosts[i];
        try{
          var st=getComputedStyle(h); var r=h.getBoundingClientRect();
          if(st.display==='none'||st.visibility==='hidden') continue;
          if(r.width>=48&&r.height>=48) results++;
        }catch(e){}
      }
    }catch(e){}
    var input=document.activeElement;
    var tag=input?(input.tagName||''):'';
    var focusedTextual=false;
    try{
      if(input&&input!==document.body){
        var t=(input.tagName||'').toUpperCase();
        focusedTextual=t==='INPUT'||t==='TEXTAREA'||!!input.isContentEditable;
      }
    }catch(e){}
    var val='';
    try{
      var el=document.querySelector('input[type="search"],input[type="text"],input:not([type]),[role="searchbox"]');
      if(el&&el.value!=null) val=String(el.value).trim();
    }catch(e){}
    return JSON.stringify({
      ok:true, url:url, modalActive:active, resultsVisible:results,
      focusedTextual:focusedTextual, activeTag:tag, val:val.slice(0,80), empty:!val
    });
  }catch(e){ return JSON.stringify({ok:false, err:String(e)}); }
})();
""".trimIndent()

    /**
     * Exactly one fallback: form.requestSubmit() OR one nearby search/submit click.
     * Never both. Empty query → no submit.
     */
    val IME_FALLBACK_SUBMIT_JS: String = """
(function(){
  try{
    function isTextual(el){
      if(!el||el.nodeType!==1) return false;
      var tag=(el.tagName||'').toUpperCase();
      if(tag==='TEXTAREA'||el.isContentEditable) return true;
      if(tag==='INPUT'){
        var type=(el.getAttribute('type')||'text').toLowerCase();
        return type===''||type==='text'||type==='search'||type==='url'||type==='email';
      }
      return false;
    }
    var input=document.activeElement;
    if(!isTextual(input)){
      var inputs=document.querySelectorAll('input[type="search"],input[type="text"],input:not([type]),[role="searchbox"]');
      for(var i=0;i<inputs.length&&i<20;i++){
        try{
          var r=inputs[i].getBoundingClientRect();
          if(r.width>16&&r.height>8){ input=inputs[i]; break; }
        }catch(e){}
      }
    }
    if(!input) return JSON.stringify({ok:false, reason:'no_input', submitted:false});
    var val=(input.value!=null?String(input.value):'').trim();
    if(!val){
      try{ input.blur(); }catch(e){}
      return JSON.stringify({ok:true, reason:'empty_no_submit', submitted:false, val:''});
    }
    var form=input.form||(input.closest&&input.closest('form'));
    if(form){
      try{
        if(typeof form.requestSubmit==='function'){ form.requestSubmit(); }
        else { form.submit(); }
        try{ input.blur(); }catch(e){}
        return JSON.stringify({ok:true, method:'requestSubmit', submitted:true, val:val.slice(0,80)});
      }catch(e){}
    }
    // Nearest search/submit control near the input (exactly one click).
    var root=input.closest? (input.closest('[role="dialog"],[class*="search"],[class*="Search"],form,div')||document):document;
    var btns=root.querySelectorAll?root.querySelectorAll('button,input[type="submit"],[role="button"],a'):[];
    for(var j=0;j<btns.length&&j<40;j++){
      var b=btns[j];
      try{
        var t=(b.innerText||b.value||b.getAttribute('aria-label')||'').trim().toLowerCase();
        var ty=(b.getAttribute('type')||'').toLowerCase();
        if(ty==='submit'||/search|go|find|submit|send/.test(t)){
          b.click();
          try{ input.blur(); }catch(e){}
          return JSON.stringify({ok:true, method:'button', submitted:true, val:val.slice(0,80)});
        }
      }catch(e){}
    }
    try{ input.blur(); }catch(e){}
    return JSON.stringify({ok:false, reason:'no_fallback', submitted:false, val:val.slice(0,80)});
  }catch(e){ return JSON.stringify({ok:false, err:String(e), submitted:false}); }
})();
""".trimIndent()
}
