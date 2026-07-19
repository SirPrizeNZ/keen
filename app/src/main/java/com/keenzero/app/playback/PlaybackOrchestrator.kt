package com.keenzero.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import com.keenzero.app.continuity.ContinuityCheckpoint
import com.keenzero.app.diagnostics.NavigationEvent
import org.json.JSONObject
import java.util.UUID

/**
 * Owns the playback journey after deliberate Play.
 *
 * PlayIntent: short-lived navigation authority (expires).
 * PlaybackSession: long-lived for the full video lifecycle (checkpointing continues
 * after PlayIntent expires).
 *
 * Keen Playback Mode is native immersive + chrome hide — not HTML requestFullscreen().
 * HTML fullscreen is optional and never retried on a timer.
 */
class PlaybackOrchestrator(
    context: Context,
    private val onEvent: (NavigationEvent) -> Unit,
    private val onCheckpoint: (ContinuityCheckpoint) -> Unit,
    private val onPlaybackConfirmed: (PlaybackSnapshot) -> Unit,
    private val onPlaybackMode: (enter: Boolean) -> Unit,
    private val onPlaybackActive: (active: Boolean) -> Unit = {},
    private val onJourneyState: (PlaybackJourneyState) -> Unit = {},
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var activeIntent: PlayIntent? = null
    private var session: PlaybackSession? = null
    private var pollRunnable: Runnable? = null
    private var webView: WebView? = null
    private var confirmed = false
    private var playbackModeEntered = false
    private var htmlFullscreenAttempted = false
    private var lastCheckpointAt = 0L
    private var lastPositionForSeekDetect = -1.0
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false
    private var unmuteRequested = false
    private var audibleConfirmed = false
    private var playbackPriorityActive = false

    private val machine = PlaybackJourneyMachine { from, to, reason ->
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "JOURNEY_TRANSITION",
                detail = "$from->$to reason=$reason",
            ),
        )
        if (!reason.startsWith("REJECTED")) {
            onJourneyState(to)
        }
    }

    val journeyState: PlaybackJourneyState get() = machine.state
    val activeSession: PlaybackSession? get() = session
    val isPlayIntentActive: Boolean
        get() = activeIntent?.isActive(clock()) == true
    val isPlaybackMode: Boolean get() = playbackModeEntered

    data class PlaybackSnapshot(
        val playing: Boolean,
        val muted: Boolean,
        val currentTime: Double,
        val duration: Double,
        val title: String?,
        val contentId: String?,
        val fullscreen: Boolean,
        val url: String?,
        val season: Int? = null,
        val episode: Int? = null,
        val subtitleTrack: String? = null,
        val audioTrack: String? = null,
        val qualityPreference: String? = null,
        val scrollY: Int = 0,
        val focusedFingerprint: String? = null,
        val audibleSignal: Boolean = false,
    )

    fun attach(webView: WebView) {
        this.webView = webView
    }

    fun onPlayIntent(intent: PlayIntent) {
        updatePlaybackPriority(false)
        activeIntent = intent
        confirmed = false
        playbackModeEntered = false
        htmlFullscreenAttempted = false
        unmuteRequested = false
        audibleConfirmed = false
        val tPlay = clock()
        session = PlaybackSession(
            sessionId = UUID.randomUUID().toString(),
            contentId = intent.contentId,
            origin = intent.origin,
            url = intent.url,
            startedAtElapsedMs = tPlay,
            policyPackVersion = "1",
        )
        machine.transition(PlaybackJourneyState.PLAY_INTENT, "play_press")
        onEvent(
            NavigationEvent(
                t = System.currentTimeMillis(),
                type = "PLAY_INTENT",
                url = intent.url,
                detail = "id=${intent.id} text=${intent.visibleText} fp=${intent.focusedFingerprint} role=${intent.role}",
            ),
        )
        // Immediate native acknowledgement of deliberate Play (before JS resolution).
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "PLAY_ACK",
                url = intent.url,
                detail = "native_ack session=${session?.sessionId} playIntentId=${intent.id}",
            ),
        )
        // Product requirement: enter Keen Playback Mode within one display frame of Play,
        // before media confirmation. Detection still confirms playback asynchronously.
        enterPlaybackMode(
            webView = webView,
            snapshot = PlaybackSnapshot(
                playing = false,
                muted = true,
                currentTime = 0.0,
                duration = 0.0,
                title = null,
                contentId = intent.contentId,
                fullscreen = true,
                url = intent.url,
            ),
            reason = "play_press_sync",
        )
        val modeLatencyMs = clock() - tPlay
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "PLAY_TO_MODE_LATENCY",
                url = intent.url,
                detail = "modeEnterElapsedMs=$modeLatencyMs playIntentId=${intent.id} session=${session?.sessionId}",
            ),
        )
        startPolling()
    }

    fun beginRestore(checkpoint: ContinuityCheckpoint) {
        updatePlaybackPriority(false)
        machine.force(PlaybackJourneyState.RESTORING, "continuity_restore")
        session = PlaybackSession(
            sessionId = UUID.randomUUID().toString(),
            contentId = checkpoint.contentId,
            title = checkpoint.title,
            origin = checkpoint.origin,
            url = checkpoint.url,
            playerOrigin = checkpoint.playerOrigin,
            selectedSource = checkpoint.selectedSource,
            season = checkpoint.season,
            episode = checkpoint.episode,
            playbackPositionSec = checkpoint.playbackPositionSec,
            durationSec = checkpoint.durationSec,
            playbackState = checkpoint.playbackState,
            playbackModeActive = checkpoint.playbackMode,
            subtitleTrack = checkpoint.subtitleTrack,
            audioTrack = checkpoint.audioTrack,
            qualityPreference = checkpoint.qualityPreference,
            startedAtElapsedMs = clock(),
            policyPackVersion = checkpoint.policyPackVersion,
        )
        confirmed = false
        playbackModeEntered = false
        // Always re-enter Keen Playback Mode on restore of a media checkpoint.
        // Detection still confirms playing asynchronously.
        enterPlaybackMode(
            webView = webView,
            snapshot = PlaybackSnapshot(
                playing = false,
                muted = true,
                currentTime = checkpoint.playbackPositionSec,
                duration = checkpoint.durationSec,
                title = checkpoint.title,
                contentId = checkpoint.contentId,
                fullscreen = true,
                url = checkpoint.url,
                season = checkpoint.season,
                episode = checkpoint.episode,
            ),
            reason = "restore_sync",
        )
        startPolling()
    }

    fun beginRecovering() {
        machine.force(PlaybackJourneyState.RECOVERING, "renderer_gone")
        // Session retained for restore; stop polling on dead WebView.
        stopPolling()
    }

    fun expirePlayIntentOnly(reason: String) {
        activeIntent = null
        onEvent(NavigationEvent(System.currentTimeMillis(), "PLAY_INTENT_CLEAR", detail = reason))
        // Do NOT stop session polling or checkpointing.
        if (session != null && confirmed) {
            // Intent expired mid-session — stay in PLAYING / PLAYBACK_MODE.
            return
        }
        if (!confirmed) {
            stopPolling()
            session = null
            machine.transition(PlaybackJourneyState.BROWSING, "intent_expired_no_playback")
        }
    }

    fun exitPlaybackMode(reason: String) {
        if (!playbackModeEntered) return
        playbackModeEntered = false
        session = session?.copy(playbackModeActive = false)
        onPlaybackMode(false)
        webView?.let { restoreTheatre(it) }
        checkpointNow(reason = "exit_playback_mode:$reason")
        if (session?.playbackState == "playing") {
            machine.transition(PlaybackJourneyState.PLAYING, reason)
        } else {
            machine.transition(PlaybackJourneyState.BROWSING, reason)
        }
        onEvent(NavigationEvent(System.currentTimeMillis(), "PLAYBACK_MODE_EXIT", detail = reason))
    }

    fun onBackground() {
        checkpointNow(reason = "app_background")
    }

    fun checkpointFresh(reason: String, onComplete: () -> Unit) {
        val wv = webView
        if (wv == null || session == null) {
            checkpointNow(reason = "$reason:fallback")
            onComplete()
            return
        }
        sample(wv) { snapshot ->
            if (snapshot != null) {
                checkpointNow(snapshot = snapshot, reason = reason)
            } else {
                checkpointNow(reason = "$reason:fallback")
            }
            onComplete()
        }
    }

    fun onPageSettled(webView: WebView, url: String?) {
        this.webView = webView
        injectProbe(webView)
        // Restore strategy:
        // 1) prepare fixture player (show video, load metadata)
        // 2) direct currentTime seek (required for DIRECT_* classes)
        // 3) if seek ignored, natural-play until target (DEGRADED only — never DIRECT)
        val restoreContentId = session?.contentId?.replace("'", "") ?: "ep-a2"
        // Prefer Kotlin session position: setting window.__keenRestorePosition via a separate
        // evaluateJavascript is async and often races onPageSettled (no_target skip).
        val restorePos = session?.playbackPositionSec?.takeIf { it > 0 }
            ?: 0.0
        // Single-flight: page_finished can fire multiple times; only one restore gen may settle.
        // CRITICAL: never assign currentTime / fragment until seekableEnd >= target (or timeout).
        // Seeking while readyState=0/seekable empty is the main natural-fallback path on goldfish.
        webView.evaluateJavascript(
            """(function(){
              try {
                var t=window.__keenRestorePosition;
                if(typeof t!=='number' || !(t>0)) {
                  t=$restorePos;
                  window.__keenRestorePosition=t;
                }
                if(typeof t!=='number' || !(t>0)) {
                  console.warn('KZ_RESTORE_SKIP:no_target');
                  return;
                }
                if(window.__keenRestoreSettled===true && window.__keenRestoreMethod){
                  console.warn('KZ_RESTORE_SKIP:already_settled:'+window.__keenRestoreMethod);
                  return;
                }
                window.__keenRestoreGen=(window.__keenRestoreGen||0)+1;
                var gen=window.__keenRestoreGen;
                window.__keenRestoreSettled=false;
                window.__keenRestoreMethod=null;
                var contentId='$restoreContentId';
                if(typeof window.__keenPrepareRestore==='function'){
                  try{ window.__keenPrepareRestore(contentId); }catch(e){}
                }
                var v=document.getElementById('v')||document.querySelector('video');
                if(!v) {
                  console.warn('KZ_RESTORE_SKIP:no_video');
                  return;
                }
                v.loop=true;
                v.preload='auto';
                var attempts=0;
                var maxSeekAttempts=12;
                var stillCurrent=function(){ return gen===window.__keenRestoreGen; };
                var seekableEnd=function(){
                  try{
                    if(v.seekable && v.seekable.length>0) return v.seekable.end(v.seekable.length-1)||0;
                  }catch(e){}
                  return 0;
                };
                var markSeekOk=function(a, path){
                  window.__keenRestoreSettled=true;
                  window.__keenRestoreMethod='seek';
                  try{ v.muted=false; v.volume=1; var p=v.play(); if(p&&p.catch)p.catch(function(){}); }catch(e){}
                  console.warn('KZ_RESTORE_SETTLED:seek:'+a+':path:'+path);
                };
                var naturalToTarget=function(reason){
                  if(!stillCurrent()) return;
                  console.warn('KZ_RESTORE_NATURAL_START:'+t+':gen='+gen+':reason:'+reason);
                  window.__keenRestoreMethod='natural';
                  try{ v.currentTime=0; }catch(e){}
                  try{ v.play(); }catch(e){}
                  var started=Date.now();
                  var tick=function(){
                    if(!stillCurrent()) return;
                    var a=v.currentTime||0;
                    if(a+0.1>=t || Date.now()-started>45000){
                      window.__keenRestoreSettled=true;
                      window.__keenRestoreMethod='natural';
                      console.warn('KZ_RESTORE_SETTLED:natural:'+a+':target:'+t);
                      return;
                    }
                    if(v.paused){ try{ v.play(); }catch(e){} }
                    setTimeout(tick, 200);
                  };
                  tick();
                };
                // Wait until media reports seekable range covering target (or hard timeout).
                var waitSeekable=function(done){
                  var n=0;
                  var tick=function(){
                    if(!stillCurrent()) return;
                    n++;
                    v=document.getElementById('v')||document.querySelector('video')||v;
                    var end=seekableEnd();
                    var rs=v.readyState||0;
                    console.warn('KZ_RESTORE_READY:rs='+rs+':seekableEnd='+end+':n='+n+':gen='+gen);
                    if(end+0.05>=t){ done(true, end, rs); return; }
                    if(n>50){ done(false, end, rs); return; }
                    // Nudge decoder without discarding buffered state (do not v.src=v.src here).
                    try{ var p=v.play(); if(p&&p.then){ p.then(function(){ try{v.pause();}catch(e){} }).catch(function(){}); } }catch(e){}
                    setTimeout(tick, 300);
                  };
                  tick();
                };
                var tryCurrentTimeSeek=function(done){
                  if(!stillCurrent()) return;
                  attempts++;
                  try{
                    v=document.getElementById('v')||document.querySelector('video')||v;
                    try{ v.pause(); }catch(e){}
                    var target=t;
                    var end=seekableEnd();
                    if(end>0 && target>end) target=Math.max(0, end-0.25);
                    var finished=false;
                    var finish=function(){
                      if(finished || !stillCurrent()) return;
                      finished=true;
                      setTimeout(function(){
                        if(!stillCurrent()) return;
                        var a=v.currentTime||0;
                        console.warn('KZ_RESTORE_SEEK:'+target+':actual:'+a+':attempt:'+attempts+':path:currentTime');
                        if(Math.abs(a-target)<=0.75){ markSeekOk(a, 'currentTime'); done(true); return; }
                        if(attempts<maxSeekAttempts) tryCurrentTimeSeek(done);
                        else done(false);
                      }, 150);
                    };
                    v.addEventListener('seeked', finish, {once:true});
                    try{
                      if(typeof v.fastSeek==='function') v.fastSeek(target);
                      else v.currentTime=target;
                    }catch(e){ try{ v.currentTime=target; }catch(e2){} }
                    setTimeout(function(){ if(!window.__keenRestoreSettled) finish(); }, 1000);
                  }catch(e){
                    console.warn('KZ_RESTORE_SEEK_ERR:'+e);
                    if(attempts<maxSeekAttempts) tryCurrentTimeSeek(done);
                    else done(false);
                  }
                };
                var tryFragmentSeek=function(done){
                  try{
                    v=document.getElementById('v')||document.querySelector('video')||v;
                    var base=(v.currentSrc||v.src||'').split('#')[0];
                    if(!base){ done(false); return; }
                    var frag=base+'#t='+Math.max(0,t).toFixed(3);
                    console.warn('KZ_RESTORE_FRAGMENT:'+frag);
                    var finished=false;
                    var check=function(){
                      if(finished || !stillCurrent()) return;
                      finished=true;
                      var a=v.currentTime||0;
                      console.warn('KZ_RESTORE_SEEK:'+t+':actual:'+a+':attempt:fragment');
                      if(Math.abs(a-t)<=1.0 && a>0.05){ markSeekOk(a, 'fragment'); done(true); }
                      else done(false);
                    };
                    v.addEventListener('loadeddata', check, {once:true});
                    v.addEventListener('canplay', check, {once:true});
                    v.addEventListener('seeked', check, {once:true});
                    v.src=frag;
                    try{ v.load(); }catch(e){}
                    try{ v.play(); }catch(e){}
                    setTimeout(function(){ if(!window.__keenRestoreSettled) check(); }, 2800);
                  }catch(e){ console.warn('KZ_RESTORE_FRAGMENT_ERR:'+e); done(false); }
                };
                // Order: wait seekable → currentTime seeks → fragment → natural.
                waitSeekable(function(ok, end, rs){
                  if(!stillCurrent()) return;
                  console.warn('KZ_RESTORE_SEEKABLE_GATE:ok='+ok+':end='+end+':rs='+rs+':target='+t);
                  if(!ok){
                    // Media never became seekable — still try currentTime once, then natural.
                    console.warn('KZ_RESTORE_ENGINE_HINT:unseekable_timeout');
                  }
                  tryCurrentTimeSeek(function(ctOk){
                    if(ctOk || !stillCurrent()) return;
                    tryFragmentSeek(function(frOk){
                      if(frOk || !stillCurrent()) return;
                      naturalToTarget(ok ? 'seek_failed_after_seekable' : 'never_seekable');
                    });
                  });
                });
              }catch(e){ console.warn('KZ_RESTORE_FATAL:'+e); }
            })();""",
            null,
        )
        if (session != null || activeIntent?.isActive(clock()) == true) {
            if (machine.state == PlaybackJourneyState.PLAY_INTENT) {
                machine.transition(PlaybackJourneyState.RESOLVING, "page_settled")
            }
            startPolling()
        }
    }

    fun destroy() {
        checkpointNow(reason = "orchestrator_destroy")
        updatePlaybackPriority(false)
        stopPolling()
        releaseAudioFocus()
        webView = null
        activeIntent = null
        session = null
        confirmed = false
        playbackModeEntered = false
        machine.reset()
    }

    private fun startPolling() {
        stopPolling()
        val tick = object : Runnable {
            override fun run() {
                val wv = webView
                val sess = session
                if (wv == null || sess == null) return
                // Expire PlayIntent independently of session.
                val intent = activeIntent
                if (intent != null && !intent.isActive(clock())) {
                    expirePlayIntentOnly("expired")
                }
                sample(wv)
                main.postDelayed(this, POLL_MS)
            }
        }
        pollRunnable = tick
        main.post(tick)
    }

    private fun stopPolling() {
        pollRunnable?.let { main.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun sample(
        webView: WebView,
        onSampled: ((PlaybackSnapshot?) -> Unit)? = null,
    ) {
        webView.evaluateJavascript(SAMPLE_JS) { raw ->
            val json = unwrapJs(raw)
            if (json == null) {
                onSampled?.invoke(null)
                return@evaluateJavascript
            }
            try {
                val o = JSONObject(json)
                val playing = o.optBoolean("playing", false)
                val paused = o.optBoolean("paused", false)
                val ended = o.optBoolean("ended", false)
                val muted = o.optBoolean("muted", true)
                val currentTime = o.optDouble("currentTime", 0.0)
                val duration = o.optDouble("duration", 0.0)
                val title = o.optString("title", "").takeIf { it.isNotBlank() && it != "null" }
                val contentId = o.optString("contentId", "").takeIf { it.isNotBlank() && it != "null" }
                val htmlFs = o.optBoolean("fullscreen", false)
                val audibleSignal = o.optBoolean("audible", false)
                val season = if (o.has("season") && !o.isNull("season")) o.optInt("season") else null
                val episode = if (o.has("episode") && !o.isNull("episode")) o.optInt("episode") else null
                val scrollY = o.optInt("scrollY", 0)
                val focusedFp = o.optString("focusedFp", "").takeIf { it.isNotBlank() && it != "null" }
                val snapshot = PlaybackSnapshot(
                    playing = playing,
                    muted = muted,
                    currentTime = currentTime,
                    duration = duration,
                    title = title,
                    contentId = contentId ?: session?.contentId ?: activeIntent?.contentId,
                    fullscreen = htmlFs || playbackModeEntered,
                    url = webView.url,
                    season = season ?: session?.season,
                    episode = episode ?: session?.episode,
                    scrollY = scrollY,
                    focusedFingerprint = focusedFp ?: activeIntent?.focusedFingerprint,
                    audibleSignal = audibleSignal,
                )

                // Seek detection: large jump in position.
                if (lastPositionForSeekDetect >= 0 &&
                    kotlin.math.abs(currentTime - lastPositionForSeekDetect) > 2.5 &&
                    playing
                ) {
                    checkpointNow(snapshot = snapshot, reason = "seek")
                }
                lastPositionForSeekDetect = currentTime

                if (playing && !confirmed) {
                    confirmed = true
                    val intent = activeIntent
                    val t0 = clock()
                    // Mode may already be active from synchronous Play path.
                    if (playbackModeEntered) {
                        // Stay in PLAYBACK_MODE; record playing confirmation.
                        machine.transition(PlaybackJourneyState.PLAYBACK_MODE, "playback_confirmed_while_mode")
                    } else {
                        machine.transition(PlaybackJourneyState.PLAYING, "playback_confirmed")
                    }
                    onEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "PLAYBACK_CONFIRMED",
                            url = webView.url,
                            detail = "afterPlayIntentMs=${intent?.let { t0 - it.timestampElapsedMs } ?: -1} modeAlready=$playbackModeEntered",
                        ),
                    )
                    acquireAudioFocus()
                    applyIntentionalPlayback(webView)
                    if (!playbackModeEntered) {
                        enterPlaybackMode(webView, snapshot, reason = "playback_confirmed")
                    }
                    onPlaybackConfirmed(snapshot)
                }

                if (playing) {
                    updatePlaybackPriority(true)
                    if (!playbackModeEntered && confirmed) {
                        enterPlaybackMode(webView, snapshot, reason = "playing_reassert")
                    }
                    if (audibleSignal && !audibleConfirmed) {
                        audibleConfirmed = true
                        session = session?.copy(audibleConfirmed = true)
                        onEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "AUDIBLE_PLAYBACK_CONFIRMED",
                                url = webView.url,
                                detail = "muted=$muted volume=${o.optDouble("volume", 0.0)}",
                            ),
                        )
                    }
                    if (machine.state == PlaybackJourneyState.PAUSED) {
                        machine.transition(
                            if (playbackModeEntered) PlaybackJourneyState.PLAYBACK_MODE else PlaybackJourneyState.PLAYING,
                            "resume",
                        )
                    }
                    maybeCheckpoint(snapshot)
                } else if (ended && confirmed) {
                    updatePlaybackPriority(false)
                    machine.transition(PlaybackJourneyState.ENDED, "ended")
                    checkpointNow(snapshot = snapshot, reason = "ended")
                } else if (paused && confirmed && !ended) {
                    updatePlaybackPriority(false)
                    if (machine.state != PlaybackJourneyState.PAUSED) {
                        machine.transition(PlaybackJourneyState.PAUSED, "paused")
                        checkpointNow(snapshot = snapshot, reason = "paused")
                    }
                }

                session = session?.copy(
                    contentId = snapshot.contentId,
                    title = snapshot.title,
                    url = snapshot.url,
                    playbackPositionSec = snapshot.currentTime,
                    durationSec = snapshot.duration,
                    playbackState = when {
                        ended -> "ended"
                        playing -> "playing"
                        paused -> "paused"
                        else -> session?.playbackState
                    },
                    playbackModeActive = playbackModeEntered,
                    unmuteRequested = unmuteRequested,
                    audioFocusGranted = audioFocusGranted,
                    audibleConfirmed = audibleConfirmed,
                    season = snapshot.season,
                    episode = snapshot.episode,
                )
                onSampled?.invoke(snapshot)
            } catch (_: Exception) {
                onSampled?.invoke(null)
            }
        }
    }

    private fun updatePlaybackPriority(active: Boolean) {
        if (playbackPriorityActive == active) return
        playbackPriorityActive = active
        onPlaybackActive(active)
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "PLAYBACK_PRIORITY",
                url = webView?.url,
                detail = if (active) "foreground_service_start" else "foreground_service_stop",
            ),
        )
    }

    private fun enterPlaybackMode(
        webView: WebView?,
        snapshot: PlaybackSnapshot,
        reason: String = "keen_playback_mode",
    ) {
        if (playbackModeEntered) return
        playbackModeEntered = true
        session = session?.copy(playbackModeActive = true)
        machine.transition(PlaybackJourneyState.PLAYBACK_MODE, reason)
        // Native immersive + chrome hide (callback to Activity) — synchronous UI path.
        onPlaybackMode(true)
        // Optional one-shot HTML fullscreen — never retried on a timer.
        if (webView != null && !htmlFullscreenAttempted) {
            htmlFullscreenAttempted = true
            webView.evaluateJavascript(OPTIONAL_FULLSCREEN_JS) { raw ->
                onEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "HTML_FULLSCREEN_OPTIONAL",
                        url = webView.url,
                        detail = unwrapJs(raw) ?: raw,
                    ),
                )
            }
        }
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "PLAYBACK_MODE_ENTERED",
                url = snapshot.url ?: webView?.url,
                detail = "pos=${snapshot.currentTime} native=1 reason=$reason htmlOptional=$htmlFullscreenAttempted",
            ),
        )
        // Avoid forced disk work on the synchronous Play frame unless we already have media time.
        if (snapshot.currentTime > 0.05 || reason != "play_press_sync") {
            checkpointNow(snapshot = snapshot, reason = "playback_mode_enter:$reason")
        }
    }

    private fun applyIntentionalPlayback(webView: WebView) {
        unmuteRequested = true
        webView.evaluateJavascript(UNMUTE_AND_THEATRE_JS) { raw ->
            val detail = unwrapJs(raw) ?: raw
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "PLAYBACK_AUDIO_ATTEMPT",
                    url = webView.url,
                    detail = detail,
                ),
            )
            onEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "UNMUTE_REQUESTED",
                    url = webView.url,
                    detail = detail,
                ),
            )
        }
    }

    private fun restoreTheatre(webView: WebView) {
        webView.evaluateJavascript(THEATRE_RESTORE_JS, null)
    }

    private fun maybeCheckpoint(snapshot: PlaybackSnapshot) {
        val now = clock()
        if (now - lastCheckpointAt < CHECKPOINT_INTERVAL_MS && snapshot.currentTime > 0.5) return
        checkpointNow(snapshot = snapshot, reason = "periodic")
    }

    fun checkpointNow(snapshot: PlaybackSnapshot? = null, reason: String = "manual") {
        val snap = snapshot
        val sess = session
        val intent = activeIntent
        if (sess == null && snap == null) return
        val now = clock()
        // Debounce disk pressure: skip if same second and reason is periodic.
        if (reason == "periodic" && now - lastCheckpointAt < CHECKPOINT_INTERVAL_MS) return
        lastCheckpointAt = now
        val cp = ContinuityCheckpoint(
            origin = sess?.origin ?: intent?.origin,
            url = snap?.url ?: sess?.url ?: intent?.url,
            contentId = snap?.contentId ?: sess?.contentId ?: intent?.contentId,
            title = snap?.title ?: sess?.title,
            season = snap?.season ?: sess?.season,
            episode = snap?.episode ?: sess?.episode,
            scrollY = snap?.scrollY ?: 0,
            focusedFingerprint = snap?.focusedFingerprint ?: intent?.focusedFingerprint,
            playerType = "html5-video",
            playerOrigin = sess?.playerOrigin ?: intent?.origin,
            selectedSource = sess?.selectedSource,
            playbackPositionSec = snap?.currentTime ?: sess?.playbackPositionSec ?: 0.0,
            durationSec = snap?.duration ?: sess?.durationSec ?: 0.0,
            fullscreen = playbackModeEntered || (snap?.fullscreen == true),
            playbackMode = playbackModeEntered,
            playbackState = snap?.let {
                when {
                    it.playing -> "playing"
                    else -> "paused"
                }
            } ?: sess?.playbackState,
            journeyState = machine.state.name,
            subtitleTrack = snap?.subtitleTrack ?: sess?.subtitleTrack,
            audioTrack = snap?.audioTrack ?: sess?.audioTrack,
            qualityPreference = snap?.qualityPreference ?: sess?.qualityPreference,
            adapterVersion = "1",
            policyPackVersion = sess?.policyPackVersion ?: "1",
            timestampMs = System.currentTimeMillis(),
        )
        session = session?.copy(lastCheckpointAtElapsedMs = now)
        onCheckpoint(cp)
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "CONTINUITY_CHECKPOINT",
                url = cp.url,
                detail = "pos=${cp.playbackPositionSec} dur=${cp.durationSec} mode=${cp.playbackMode} reason=$reason",
            ),
        )
    }

    private fun injectProbe(webView: WebView) {
        webView.evaluateJavascript(PROBE_INSTALL_JS, null)
    }

    private fun acquireAudioFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            result = am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            result = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        session = session?.copy(audioFocusGranted = audioFocusGranted)
        onEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                if (audioFocusGranted) "AUDIO_FOCUS_GRANTED" else "AUDIO_FOCUS_DENIED",
                detail = "result=$result",
            ),
        )
    }

    private fun releaseAudioFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        audioFocusRequest = null
        audioFocusGranted = false
    }

    private fun unwrapJs(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            try {
                JSONObject("{\"v\":$raw}").getString("v")
            } catch (_: Exception) {
                raw.trim('"')
            }
        } else {
            raw
        }
    }

    companion object {
        private const val POLL_MS = 400L
        /** Five-second sustained checkpoint interval (debounced). */
        private const val CHECKPOINT_INTERVAL_MS = 5_000L

        private val PROBE_INSTALL_JS = """
            (function(){
              if(window.__keenProbeInstalled) return;
              window.__keenProbeInstalled=true;
              window.__keenPlayback={playing:false,muted:true,currentTime:0,duration:0,audible:false};
              try{
                var v0=document.querySelector('video');
                if(v0){ v0.loop=true; }
              }catch(e){}
              document.addEventListener('play',function(e){
                if(e.target && e.target.tagName==='VIDEO'){
                  try{ e.target.loop=true; }catch(x){}
                  window.__keenPlayback.playing=true;
                  console.warn('KZ_VIDEO_PLAY');
                }
              },true);
              document.addEventListener('playing',function(e){
                if(e.target && e.target.tagName==='VIDEO'){
                  try{ e.target.loop=true; }catch(x){}
                  window.__keenPlayback.playing=true;
                  console.warn('KZ_VIDEO_PLAYING');
                }
              },true);
              document.addEventListener('timeupdate',function(e){
                if(e.target && e.target.tagName==='VIDEO'){
                  var v=e.target;
                  if(!v.muted && v.volume>0 && !v.paused && v.currentTime>0.05){
                    window.__keenPlayback.audible=true;
                    if(!window.__keenAudibleLogged){
                      window.__keenAudibleLogged=true;
                      console.warn('KZ_AUDIBLE_PLAYBACK');
                    }
                  }
                }
              },true);
            })();
        """.trimIndent()

        private val SAMPLE_JS = """
            (function(){
              var v=document.querySelector('video');
              var ae=document.activeElement;
              var fp=ae?(ae.tagName+'#'+(ae.id||'')).slice(0,80):null;
              if(!v) return JSON.stringify({playing:false,paused:true,ended:false,muted:true,currentTime:0,duration:0,title:document.title||null,contentId:window.__keenContentId||null,fullscreen:!!document.fullscreenElement,audible:false,scrollY:window.scrollY||0,focusedFp:fp,volume:0});
              var playing=!v.paused && !v.ended;
              var audible=playing && !v.muted && v.volume>0 && v.currentTime>0.05;
              return JSON.stringify({
                playing:playing,
                paused:!!v.paused,
                ended:!!v.ended,
                muted:!!v.muted || v.volume===0,
                volume:v.volume||0,
                currentTime:v.currentTime||0,
                duration:isFinite(v.duration)?v.duration:0,
                title:document.title||null,
                contentId:window.__keenContentId||null,
                season:window.__keenSeason||null,
                episode:window.__keenEpisode||null,
                fullscreen:!!(document.fullscreenElement||document.webkitFullscreenElement),
                audible:audible,
                scrollY:window.scrollY||0,
                focusedFp:fp
              });
            })();
        """.trimIndent()

        private val UNMUTE_AND_THEATRE_JS = """
            (function(){
              var v=document.querySelector('video');
              var out={ok:false,muted:true,volume:0,theatre:false};
              if(v){
                try{ v.muted=false; v.volume=1; v.play && v.play(); out.ok=true; out.muted=v.muted; out.volume=v.volume; }catch(e){ out.err=String(e); }
              }
              try{
                document.documentElement.classList.add('keen-theatre');
                document.querySelectorAll('.rail,.carousel,.ad-trap,.overlay-ad,[data-keen-hostile-overlay],.promo,.hero-preview').forEach(function(e){
                  e.style.setProperty('display','none','important');
                  e.style.setProperty('animation','none','important');
                  e.style.setProperty('pointer-events','none','important');
                });
                out.theatre=true;
              }catch(e){}
              console.warn('KZ_UNMUTE:'+JSON.stringify(out));
              return JSON.stringify(out);
            })();
        """.trimIndent()

        private val THEATRE_RESTORE_JS = """
            (function(){
              try{
                document.documentElement.classList.remove('keen-theatre');
                document.querySelectorAll('.rail,.carousel,.promo').forEach(function(e){
                  e.style.removeProperty('display');
                  e.style.removeProperty('animation');
                  e.style.removeProperty('pointer-events');
                });
              }catch(e){}
            })();
        """.trimIndent()

        /** One-shot optional HTML fullscreen — never called on a retry timer. */
        private val OPTIONAL_FULLSCREEN_JS = """
            (function(){
              var v=document.querySelector('video');
              if(!v) return 'no-video';
              try{
                if(document.fullscreenElement||document.webkitFullscreenElement) return 'already';
                if(typeof v.requestFullscreen==='function'){
                  var p=v.requestFullscreen();
                  return p?'requested-once':'no-api';
                }
                if(typeof v.webkitRequestFullscreen==='function'){
                  v.webkitRequestFullscreen();
                  return 'webkit-requested-once';
                }
                return 'no-api';
              }catch(e){ return 'err:'+e; }
            })();
        """.trimIndent()
    }
}
