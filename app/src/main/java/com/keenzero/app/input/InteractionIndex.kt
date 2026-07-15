package com.keenzero.app.input

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Bounded interaction candidate for 32-bit TV navigation.
 * Built off the key-press path; queried in O(n) over a hard-capped list.
 */
data class InteractionCandidate(
    val id: String,
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val tag: String = "",
    val role: String = "",
    val text: String = "",
    val href: String = "",
    val isPlayerControl: Boolean = false,
    val isScrollContainer: Boolean = false,
    val offscreen: Boolean = false,
) {
    val cx: Double get() = left + width / 2.0
    val cy: Double get() = top + height / 2.0
}

/**
 * Compact interaction index. Hard candidate cap for low-memory armeabi-v7a devices.
 * Rebuild only after meaningful page events — never on every key press.
 */
class InteractionIndex(
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    private var candidates: List<InteractionCandidate> = emptyList()
    private var focusedId: String? = null
    private var version: Long = 0L
    private var lastRebuildMs: Long = 0L
    private var lastRebuildDurationMs: Long = 0L
    private var estimatedBytes: Int = 0

    val size: Int get() = candidates.size
    val indexVersion: Long get() = version
    val rebuildDurationMs: Long get() = lastRebuildDurationMs
    val memoryEstimateBytes: Int get() = estimatedBytes
    val focused: String? get() = focusedId

    fun clear() {
        candidates = emptyList()
        focusedId = null
        version++
        estimatedBytes = 0
    }

    /**
     * Replace index from a JSON array produced by the document-side collector.
     * Shape: [{id,left,top,width,height,tag,role,text,player,scroll,offscreen}, ...]
     * plus optional focusedId.
     */
    fun rebuildFromJson(raw: String?, rebuildStartedElapsedMs: Long, rebuildEndedElapsedMs: Long): Int {
        if (raw.isNullOrBlank() || raw == "null") {
            clear()
            lastRebuildMs = rebuildEndedElapsedMs
            lastRebuildDurationMs = (rebuildEndedElapsedMs - rebuildStartedElapsedMs).coerceAtLeast(0)
            return 0
        }
        return try {
            val root = if (raw.trimStart().startsWith("[")) {
                JSONObject().put("items", JSONArray(raw)).put("focusedId", JSONObject.NULL)
            } else {
                JSONObject(raw)
            }
            val arr = root.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<InteractionCandidate>(minOf(arr.length(), maxCandidates))
            val n = minOf(arr.length(), maxCandidates)
            for (i in 0 until n) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    InteractionCandidate(
                        id = o.optString("id", "i$i"),
                        left = o.optDouble("left", 0.0),
                        top = o.optDouble("top", 0.0),
                        width = o.optDouble("width", 0.0),
                        height = o.optDouble("height", 0.0),
                        tag = o.optString("tag", ""),
                        role = o.optString("role", ""),
                        text = o.optString("text", "").take(40),
                        href = o.optString("href", ""),
                        isPlayerControl = o.optBoolean("player", false),
                        isScrollContainer = o.optBoolean("scroll", false),
                        offscreen = o.optBoolean("offscreen", false),
                    ),
                )
            }
            candidates = list
            focusedId = root.optString("focusedId", "").takeIf { it.isNotBlank() && it != "null" }
            version++
            lastRebuildMs = rebuildEndedElapsedMs
            lastRebuildDurationMs = (rebuildEndedElapsedMs - rebuildStartedElapsedMs).coerceAtLeast(0)
            // Rough estimate: ~120 bytes per candidate + overhead.
            estimatedBytes = 64 + list.size * 120
            list.size
        } catch (_: Exception) {
            clear()
            0
        }
    }

    fun setFocused(id: String?) {
        focusedId = id
    }

    fun current(): InteractionCandidate? =
        focusedId?.let { id -> candidates.firstOrNull { it.id == id } }
            ?: candidates.firstOrNull { !it.offscreen }

    /**
     * Select best neighbour in [direction] without DOM access.
     * direction: up|down|left|right
     *
     * Off-screen candidates remain eligible (with score penalty). FOCUS_JS
     * scrollIntoView then brings them into the viewport — required for long
     * catalogue pages (e.g. FMHY external links below the fold).
     */
    fun select(direction: String): InteractionCandidate? {
        val current = current() ?: return candidates.firstOrNull { !it.offscreen }
            ?: candidates.firstOrNull()
        // Majority host among http(s) candidates ≈ site chrome; others ≈ external catalogue.
        val hosts = candidates.mapNotNull { hostOfHref(it.href) }
        val mainHost = hosts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val currentHost = hostOfHref(current.href)

        fun bestIn(
            pool: List<InteractionCandidate>,
            offPenaltyBase: Double = 250.0,
        ): InteractionCandidate? {
            val scored = pool.mapNotNull { n ->
                val dx = n.cx - current.cx
                val dy = n.cy - current.cy
                val valid = when (direction) {
                    "left" -> dx < -2
                    "right" -> dx > 2
                    "up" -> dy < -2
                    "down" -> dy > 2
                    else -> false
                }
                if (!valid) return@mapNotNull null
                val primary = if (direction == "left" || direction == "right") abs(dx) else abs(dy)
                val secondary = if (direction == "left" || direction == "right") abs(dy) else abs(dx)
                // Prefer column/row alignment: heavy perpendicular penalty.
                val score = primary + secondary * 2.5 + (if (n.offscreen) offPenaltyBase else 0.0)
                n to score
            }.sortedBy { it.second }
            return scored.firstOrNull()?.first
        }

        // Scroll-and-focus continuation: only from same-origin chrome *links*
        // (currentHost == mainHost). Do NOT trigger when current has no href
        // (grid buttons, SPA cards) — that steals DOWN/UP into distant externals
        // and breaks in-page rails (fixture 3×3, catalogue carousels).
        // Still half-plane only: not a teleport.
        if ((direction == "down" || direction == "up") && mainHost != null &&
            currentHost != null && currentHost == mainHost
        ) {
            val externals = candidates.filter { n ->
                n.id != current.id && hostOfHref(n.href).let { h -> h != null && h != mainHost }
            }
            val bestExt = bestIn(externals, offPenaltyBase = 80.0)
            if (bestExt != null) {
                focusedId = bestExt.id
                return bestExt
            }
        }

        val onScreen = candidates.filter { it.id != current.id && !it.offscreen }
        val offScreen = candidates.filter { it.id != current.id && it.offscreen }
        val next = bestIn(onScreen) ?: bestIn(offScreen) ?: return null
        focusedId = next.id
        return next
    }

    private fun hostOfHref(href: String): String? {
        if (!href.startsWith("http://") && !href.startsWith("https://")) return null
        return try {
            val noScheme = href.substringAfter("://")
            val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            host.lowercase().removePrefix("www.").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun statsJson(): JSONObject = JSONObject()
        .put("candidates", candidates.size)
        .put("version", version)
        .put("rebuildMs", lastRebuildDurationMs)
        .put("estBytes", estimatedBytes)
        .put("focusedId", focusedId)
        .put("maxCandidates", maxCandidates)

    companion object {
        /** Hard cap suitable for constrained 32-bit TV heaps. */
        const val DEFAULT_MAX_CANDIDATES = 256

        /**
         * Document-side collector: visible actionable + nearby overscan + player + scroll.
         * Must stay free of // line comments (injected as single-line in some paths).
         */
        /**
         * Shared selector fragment for product index + remote dump.
         * Covers SPA shells that use role=button / onclick / pointer cards
         * without native <a>/<button> (generic — no site allowlists).
         */
        const val CANDIDATE_SELECTOR =
            "a[href],button,input,select,textarea," +
                "[tabindex]:not([tabindex=\"-1\"])," +
                "video,audio,[data-keen-focus],[data-keen-play]," +
                "[role=\"button\"],[role=\"link\"],[role=\"menuitem\"],[role=\"tab\"]," +
                "[role=\"option\"],[role=\"checkbox\"],[role=\"radio\"],[role=\"switch\"]," +
                "[onclick],[data-nav],[data-link],[data-href]"

        val COLLECT_JS = """
            (function(){
              var CAP=256;
              var sel='$CANDIDATE_SELECTOR';
              var nodes=document.querySelectorAll(sel);
              var seen=Object.create(null);
              var items=[];
              var vw=window.innerWidth||0, vh=window.innerHeight||0;
              var overscan=80;
              function pushEl(e,i){
                if(!e||e.disabled||e.getAttribute('aria-disabled')==='true') return;
                if(e.dataset&&e.dataset.keenIdx&&seen[e.dataset.keenIdx]) return;
                var r=e.getBoundingClientRect();
                var s=getComputedStyle(e);
                if(r.width<=2||r.height<=2||s.visibility==='hidden'||s.display==='none'||s.opacity==='0') return;
                var off=r.bottom<-overscan||r.top>vh+overscan||r.right<-overscan||r.left>vw+overscan;
                var id=e.id||(e.tagName+'-'+i+'-'+Math.round(r.left)+'-'+Math.round(r.top));
                if(!e.dataset.keenIdx) e.dataset.keenIdx=id;
                id=e.dataset.keenIdx;
                seen[id]=1;
                if(!e.hasAttribute('tabindex')) e.setAttribute('tabindex','-1');
                var role=e.getAttribute('role')||e.tagName;
                var text=(e.innerText||e.getAttribute('aria-label')||e.getAttribute('title')||e.value||'').trim().slice(0,40);
                var href=e.href||e.getAttribute('href')||e.getAttribute('data-href')||'';
                var player=e.tagName==='VIDEO'||e.tagName==='AUDIO'||e.dataset.keenPlay==='1'||e.id==='real-play'||!!e.closest('#player-wrap,.player-controls');
                var scroll=e.scrollWidth>e.clientWidth+8||e.scrollHeight>e.clientHeight+8||!!e.closest('[data-keen-rail],.rail,.row');
                items.push({id:id,left:r.left,top:r.top,width:r.width,height:r.height,tag:e.tagName,role:role,text:text,href:href,player:player,scroll:scroll,offscreen:!!off});
              }
              for(var i=0;i<nodes.length && items.length<CAP;i++){ pushEl(nodes[i],i); }
              if(items.length<CAP){
                var extras=document.querySelectorAll('div,li,span,article,section,img');
                for(var j=0;j<extras.length && items.length<CAP;j++){
                  var el=extras[j];
                  var st=getComputedStyle(el);
                  if(st.cursor!=='pointer') continue;
                  if(el.closest('a,button,input,select,textarea,[role=button],[role=link]')) continue;
                  var rr=el.getBoundingClientRect();
                  if(rr.width<24||rr.height<24||rr.width*rr.height>400000) continue;
                  pushEl(el,10000+j);
                }
              }
              var ae=document.activeElement;
              var fid=null;
              if(ae && ae.dataset && ae.dataset.keenIdx) fid=ae.dataset.keenIdx;
              return JSON.stringify({items:items,focusedId:fid,totalNodes:document.getElementsByTagName('*').length});
            })();
        """.trimIndent()

        /**
         * Deterministic scroll → re-resolve → focus → verify pipeline.
         *
         * **Must be synchronous** — Android System WebView 83 (lab) does not
         * await Promises returned from evaluateJavascript. Returning a Promise
         * yields `{}` to Kotlin and leaves focus/scroll racing with index rebuild.
         *
         * Uses instant/auto scroll (not smooth) so layout updates before focus.
         * Nested scroll parents are adjusted when window scroll is insufficient.
         */
        val FOCUS_JS = """
            function(id){
              var t0=Date.now();
              var sid=String(id||'').replace(/"/g,'');
              function resolve(){
                return document.querySelector('[data-keen-idx="'+sid+'"]');
              }
              function rectOf(el){
                var r=el.getBoundingClientRect();
                return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};
              }
              function inViewport(r){
                var vw=window.innerWidth||0, vh=window.innerHeight||0;
                return r.bottom>0&&r.top<vh&&r.right>0&&r.left<vw&&r.width>0&&r.height>0;
              }
              function scrollParents(el){
                var out=[];
                var p=el.parentElement;
                while(p){
                  var s=getComputedStyle(p);
                  var oy=s.overflowY||s.overflow;
                  if((oy==='auto'||oy==='scroll'||oy==='overlay')&&p.scrollHeight>p.clientHeight+8) out.push(p);
                  p=p.parentElement;
                }
                return out;
              }
              function forceIntoView(el){
                var parents=scrollParents(el);
                var i,p,r,mid,delta;
                for(i=0;i<parents.length;i++){
                  p=parents[i];
                  r=el.getBoundingClientRect();
                  var pr=p.getBoundingClientRect();
                  mid=pr.top+pr.height/2;
                  delta=(r.top+r.height/2)-mid;
                  if(Math.abs(delta)>8) p.scrollTop=p.scrollTop+delta;
                }
                // nearest keeps scroll small and avoids full-page reflow jumps
                try{ el.scrollIntoView({block:'nearest',inline:'nearest',behavior:'auto'}); }
                catch(e1){ try{ el.scrollIntoView(false); }catch(e2){} }
                r=el.getBoundingClientRect();
                if(!inViewport(r)){
                  var vh=window.innerHeight||600;
                  var delta=(r.top+r.height/2)-(vh/2);
                  // Cap jump size for smoother remote feel
                  if(delta>vh*0.6) delta=vh*0.6;
                  if(delta<-vh*0.6) delta=-vh*0.6;
                  window.scrollBy(0, delta);
                }
              }
              var diag={selectedId:sid,ok:false,t0:t0,stages:[]};
              var el=resolve();
              if(!el){ diag.reason='element_missing_pre_scroll'; try{console.warn('KZ_FOCUS_DIAG:'+JSON.stringify(diag));}catch(e0){} return JSON.stringify(diag); }
              diag.originalRect=rectOf(el);
              var scList=scrollParents(el);
              diag.scrollContainer=scList.length?(scList[0].tagName+'#'+(scList[0].id||'')):'window';
              diag.scrollTopPre=scList.length?scList[0].scrollTop:window.scrollY;
              if(!el.hasAttribute('tabindex')) el.setAttribute('tabindex','-1');
              forceIntoView(el);
              diag.stages.push('scroll_done');
              el=resolve();
              if(!el){ diag.reason='element_invalidated_after_scroll'; try{console.warn('KZ_FOCUS_DIAG:'+JSON.stringify(diag));}catch(e1){} return JSON.stringify(diag); }
              diag.postScrollRect=rectOf(el);
              diag.viewportIntersection=inViewport(diag.postScrollRect);
              diag.scrollTopPost=scList.length?scList[0].scrollTop:window.scrollY;
              if(!el.hasAttribute('tabindex')) el.setAttribute('tabindex','-1');
              try{ el.focus({preventScroll:true}); }catch(e3){ try{ el.focus(); }catch(e4){} }
              diag.stages.push('focus_called');
              var ae=document.activeElement;
              diag.activeElementId=(ae&&ae.dataset&&ae.dataset.keenIdx)||(ae&&ae.id)||null;
              diag.activeElementTag=ae?ae.tagName:null;
              diag.activeElementHref=ae?(ae.href||ae.getAttribute('href')||''):'';
              diag.focusSucceeded=(ae===el);
              if(!diag.focusSucceeded){
                try{ el.setAttribute('tabindex','0'); el.focus(); }catch(e5){}
                ae=document.activeElement;
                diag.focusSucceeded=(ae===el);
                diag.activeElementId=(ae&&ae.dataset&&ae.dataset.keenIdx)||(ae&&ae.id)||null;
                diag.stages.push('focus_retry_tabindex0');
              }
              if(!diag.focusSucceeded){
                diag.reason=ae?'focus_theft_or_mismatch':'focus_failed';
                diag.ok=false;
              } else {
                diag.ok=true;
                diag.reason=diag.viewportIntersection?'focused':'focused_offscreen_partial';
              }
              diag.tFocusVerified=Date.now();
              try{ console.warn('KZ_FOCUS_DIAG:'+JSON.stringify(diag)); }catch(e6){}
              return JSON.stringify(diag);
            }
        """.trimIndent().replace("\n", " ")
    }
}
