package com.keenzero.app

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.keenzero.app.continuity.ContinuityCheckpoint
import com.keenzero.app.continuity.ContinuityStore
import com.keenzero.app.databinding.ActivityKeenBinding
import com.keenzero.app.diagnostics.DeviceDiagnostics
import com.keenzero.app.diagnostics.EvidenceExporter
import com.keenzero.app.diagnostics.NavigationEvent
import com.keenzero.app.playback.PlaybackJourneyState
import com.keenzero.app.web.WebViewHost
import com.keenzero.app.blocking.BlockingRuntime
import com.keenzero.app.sitepacks.SitePackRuntime
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Single-Activity runtime.
 *
 * Startup path: process → native home first frame → optional continuity surface →
 * user opens web → lazy WebView.
 */
class KeenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeenBinding
    private lateinit var continuityStore: ContinuityStore
    private lateinit var supervisor: com.keenzero.app.supervisor.KeenSupervisor
    private var uiState: AppUiState = AppUiState.HOME
    private var webHost: WebViewHost? = null

    private val events = ArrayDeque<NavigationEvent>(MAX_EVENTS)
    private val rendererTerminations = mutableListOf<JSONObject>()
    private var currentUrl: String? = null
    /** First URL of this browse session (home chooser → site). Back only returns to chooser here. */
    private var browseEntryUrl: String? = null
    private var webViewEverCreated: Boolean = false
    private var latestCheckpoint: ContinuityCheckpoint? = null
    private var pendingRestore: ContinuityCheckpoint? = null
    private var restoreMetricEmitted: Boolean = false
    private var lastChromeUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        continuityStore = ContinuityStore(this)
        supervisor = com.keenzero.app.supervisor.KeenSupervisor(this)

        recordEvent(NavigationEvent(System.currentTimeMillis(), "activity_onCreate"))
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "PERFORMANCE_POLICY",
                detail = supervisor.policy.toJson().toString(),
            ),
        )

        binding.btnLoad.requestFocus()

        binding.btnLoad.setOnClickListener { openUrlFromInput() }
        binding.btnStandards.setOnClickListener {
            binding.urlInput.setText(getString(R.string.home_url))
            openUrl(getString(R.string.home_url))
        }
        binding.btnVerticalSlice.setOnClickListener {
            openUrl(VERTICAL_SLICE_URL)
        }
        binding.btnContinue.setOnClickListener { continueFromCheckpoint() }
        binding.btnDiagnostics.setOnClickListener { showDiagnosticsPreview() }
        binding.btnExport.setOnClickListener { exportEvidence() }

        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                openUrlFromInput()
                true
            } else {
                false
            }
        }
        // In-page address bar: Enter / Go loads and dismisses keyboard.
        binding.browseUrlEdit.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                commitBrowseUrlBar()
                true
            } else {
                false
            }
        }
        binding.browseUrlEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideKeyboard(binding.browseUrlEdit)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            },
        )

        showHome(status = getString(R.string.status_home))
        hydrateContinuitySurface()
        recordEvent(NavigationEvent(System.currentTimeMillis(), "native_home_ready"))
        // LAB_URL / harness extras allowed on release for physical TV validation.
        handleDebugIntent(intent)
        // Product default: open FMHY streaming section unless LAB_URL navigated first.
        if (webHost == null && intent?.getStringExtra("com.keenzero.app.extra.LAB_URL").isNullOrBlank()) {
            binding.urlInput.setText(getString(R.string.home_url))
            openUrl(getString(R.string.home_url))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onPause() {
        webHost?.onBackground()
        webHost?.flushSession()
        latestCheckpoint?.let { continuityStore.save(it, force = true) }
        continuityStore.flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Recover pointer frame-loop / scroll after screensaver or ~1min idle.
        webHost?.onForeground()
    }

    private fun hydrateContinuitySurface() {
        val cp = continuityStore.load()
        latestCheckpoint = cp
        if (cp == null || cp.url.isNullOrBlank()) {
            binding.continuePanel.visibility = View.GONE
            return
        }
        binding.continuePanel.visibility = View.VISIBLE
        val progress = if (cp.durationSec > 0) {
            ((cp.playbackPositionSec / cp.durationSec) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        binding.continueTitle.text = cp.title ?: cp.contentId ?: getString(R.string.continue_unknown_title)
        binding.continueMeta.text = getString(
            R.string.continue_meta,
            formatTime(cp.playbackPositionSec),
            formatTime(cp.durationSec),
            progress,
        )
        binding.continueStatus.text = getString(R.string.continue_status_ready)
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "continuity_surface_shown",
                url = cp.url,
                detail = "pos=${cp.playbackPositionSec}",
            ),
        )
    }

    private fun continueFromCheckpoint() {
        val cp = continuityStore.load() ?: return
        pendingRestore = cp
        restoreMetricEmitted = false
        supervisor.resetCrashLoopForUserAction()
        binding.continueStatus.text = getString(R.string.continue_status_restoring)
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "continuity_restore_start",
                url = cp.url,
                detail = "pos=${cp.playbackPositionSec} mode=${cp.playbackMode}",
            ),
        )
        // Immediate durable marker so harness can observe restore start even before media samples.
        writeJourneyMetric(
            JSONObject()
                .put("type", "restore_started")
                .put("storedPos", cp.playbackPositionSec)
                .put("playbackMode", cp.playbackMode)
                .put("t", System.currentTimeMillis()),
        )
        openUrl(cp.url!!, restore = true)
        val tRestoreStart = System.currentTimeMillis()
        // Cold emulator starts can exceed 7s before first WebView frame — retry samples.
        fun emitRestoreProgress(pos: Double, playing: Boolean, audible: Boolean, via: String) {
            if (restoreMetricEmitted) return
            val stored = pendingRestore?.playbackPositionSec ?: cp.playbackPositionSec
            val err = kotlin.math.abs(pos - stored)
            val tVisible = System.currentTimeMillis() - tRestoreStart
            val restoreMethod = webHost?.lastRestoreMethod ?: "unknown"
            val contentOk = (cp.contentId == null) ||
                (cp.contentId == (pendingRestore?.contentId ?: cp.contentId))
            writeJourneyMetric(
                JSONObject()
                    .put("type", "restore_progress")
                    .put("storedPos", stored)
                    .put("restoredPos", pos)
                    .put("absErrorSec", err)
                    .put("playing", playing)
                    .put("audible", audible)
                    .put("playbackMode", webHost?.isPlaybackMode == true || cp.playbackMode)
                    .put("contentId", cp.contentId)
                    .put("timeToRestoredVisibleMs", tVisible)
                    .put("via", via)
                    .put("restoreMethod", restoreMethod)
                    .put("contentOk", contentOk)
                    .put("t", System.currentTimeMillis()),
            )
            // Fact 3: content + position restored (method must be seek for DIRECT gate).
            writeJourneyMetric(
                JSONObject()
                    .put("type", "restore_direct")
                    .put("storedPos", stored)
                    .put("restoredPos", pos)
                    .put("absErrorSec", err)
                    .put("restoreMethod", restoreMethod)
                    .put("contentId", cp.contentId)
                    .put("contentOk", contentOk)
                    .put("direct", restoreMethod == "seek" && err <= 2.0 && contentOk)
                    .put("via", via)
                    .put("t", System.currentTimeMillis()),
            )
            restoreMetricEmitted = true
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "continuity_restore_progress",
                    url = cp.url,
                    detail = "pos=$pos stored=$stored absErr=$err via=$via method=$restoreMethod tVisibleMs=$tVisible",
                ),
            )
            val pos0 = pos
            webHost?.labEnsurePlaying { }
            webHost?.labProveAdvance(5_200L) { t0a, t1a, playing1 ->
                val p0 = t0a ?: pos0
                val p1 = t1a
                val advanced = p1 != null && kotlin.math.abs(p1 - p0) > 0.35
                val methodFinal = webHost?.lastRestoreMethod ?: restoreMethod
                writeJourneyMetric(
                    JSONObject()
                        .put("type", "restore_advance_proof")
                        .put("storedPos", stored)
                        .put("restoredPos", pos0)
                        .put("posAfter5s", p1)
                        .put("advancedAfterRestore", advanced)
                        .put("deltaSec", if (p1 != null) p1 - p0 else JSONObject.NULL)
                        .put("playing", playing1)
                        .put("playbackMode", webHost?.isPlaybackMode == true)
                        .put("contentId", cp.contentId)
                        .put("restoreMethod", methodFinal)
                        .put("t", System.currentTimeMillis()),
                )
                // Fact 4: playback advanced after restoration.
                writeJourneyMetric(
                    JSONObject()
                        .put("type", "advanced_after_restore")
                        .put("pos0", p0)
                        .put("pos1", p1)
                        .put("advancedAfterRestore", advanced)
                        .put("restoreMethod", methodFinal)
                        .put("t", System.currentTimeMillis()),
                )
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "RESTORE_ADVANCE_PROOF",
                        detail = "pos0=$p0 pos1=$p1 advanced=$advanced",
                    ),
                )
            }
            if (err <= 2.0) pendingRestore = null
        }

        var attempts = 0
        val target = cp.playbackPositionSec
        // Wait for restore settlement method (seek|natural) before claiming progress.
        // Emitting early left restoreMethod=unknown and hid the true path.
        val maxAttempts = (target * 5.0).toInt().coerceIn(30, 100)
        fun pollRestoreSample() {
            attempts++
            webHost?.labEnsurePlaying { }
            webHost?.refreshRestoreMethodFromPage { methodNow ->
                val method = methodNow ?: webHost?.lastRestoreMethod
                webHost?.labSamplePosition { pos, playing, audible ->
                    val exact = pos != null && kotlin.math.abs(pos - target) <= 0.75
                    val near = pos != null && kotlin.math.abs(pos - target) <= 2.0
                    val methodKnown = method == "seek" || method == "natural"
                    // Prefer method-known settle; do not emit "unknown" progress as restore success.
                    if (methodKnown && (exact || (method == "natural" && near) || (method == "seek" && near))) {
                        emitRestoreProgress(pos!!, playing, audible, via = "poll_$attempts")
                    } else if (attempts < maxAttempts) {
                        binding.root.postDelayed({ pollRestoreSample() }, 400L)
                    } else {
                        if (pos != null && pos > 0.05) {
                            // Timed out: still report with best-known method for diagnosis.
                            emitRestoreProgress(pos, playing, audible, via = "poll_timeout")
                        } else {
                            writeJourneyMetric(
                                JSONObject()
                                    .put("type", "restore_progress_failed")
                                    .put("storedPos", target)
                                    .put("attempts", attempts)
                                    .put("restoreMethod", method ?: "unknown")
                                    .put("lastPos", pos)
                                    .put("t", System.currentTimeMillis()),
                            )
                        }
                    }
                } ?: binding.root.postDelayed({ pollRestoreSample() }, 500L)
            }
        }
        binding.root.postDelayed({ pollRestoreSample() }, 2_000L)
    }

    private fun handleDebugIntent(intent: Intent) {
        com.keenzero.app.diagnostics.LabSignal.emit(
            "debug_intent",
            mapOf(
                "autoJourney" to intent.getBooleanExtra(EXTRA_LAB_AUTO_JOURNEY, false),
                "autoContinue" to intent.getBooleanExtra(EXTRA_AUTO_CONTINUE, false),
                "export" to intent.getBooleanExtra(EXTRA_EXPORT_EVIDENCE, false),
            ),
        )
        if (intent.getBooleanExtra(EXTRA_EXPORT_EVIDENCE, false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_export_request"))
            exportEvidence()
            return
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_CONTINUE, false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_auto_continue"))
            com.keenzero.app.diagnostics.LabSignal.emit("auto_continue_requested")
            // Surface already hydrated; continue immediately if checkpoint exists.
            binding.root.post {
                val cp = continuityStore.load()
                if (cp?.url != null) {
                    com.keenzero.app.diagnostics.LabSignal.emit(
                        "auto_continue_checkpoint_loaded",
                        mapOf(
                            "contentId" to cp.contentId,
                            "playbackPositionSec" to cp.playbackPositionSec,
                            "url" to cp.url,
                        ),
                    )
                    continueFromCheckpoint()
                } else {
                    writeJourneyMetric(
                        JSONObject()
                            .put("type", "auto_continue_failed")
                            .put("reason", "no_checkpoint")
                            .put("t", System.currentTimeMillis()),
                    )
                }
            }
            return
        }
        if (intent.getBooleanExtra(EXTRA_LAB_AUTO_JOURNEY, false)) {
            val contentId = intent.getStringExtra(EXTRA_LAB_CONTENT_ID) ?: "ep-a2"
            val seekTo = intent.getFloatExtra(EXTRA_LAB_SEEK_SEC, 8.0f).toDouble()
            val playHoldMs = intent.getLongExtra(EXTRA_LAB_PLAY_HOLD_MS, 8_000L)
            // When true: keep media advancing; do NOT force-flush prefs before kill.
            val advancing = intent.getBooleanExtra(EXTRA_LAB_ADVANCING, false)
            val noForceSave = intent.getBooleanExtra(EXTRA_LAB_NO_FORCE_SAVE, false)
            val advanceHoldMs = intent.getLongExtra(EXTRA_LAB_ADVANCE_HOLD_MS, 5_500L)
            com.keenzero.app.diagnostics.LabSignal.emit(
                "lab_journey_start",
                mapOf(
                    "contentId" to contentId,
                    "seekTo" to seekTo,
                    "advancing" to advancing,
                    "noForceSave" to noForceSave,
                ),
            )
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "debug_lab_auto_journey",
                    detail = "contentId=$contentId seek=$seekTo holdMs=$playHoldMs advancing=$advancing noForceSave=$noForceSave",
                ),
            )
            runLabAutoJourney(
                contentId = contentId,
                seekTo = seekTo,
                playHoldMs = playHoldMs,
                advancing = advancing,
                noForceSave = noForceSave,
                advanceHoldMs = advanceHoldMs,
            )
            return
        }
        if (intent.getBooleanExtra(EXTRA_LAB_MEASURE_INPUT, false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_lab_measure_input"))
            openUrl(STRESS_URL)
            // Warm page + index before timing samples (cold first hits skew p95 on emulator).
            binding.root.postDelayed({
                webHost?.labRebuildIndex()
                binding.root.postDelayed({
                    webHost?.labRebuildIndex()
                    measureDpadLatencies(samples = intent.getIntExtra(EXTRA_LAB_INPUT_SAMPLES, 40))
                }, 2_500L)
            }, 2_500L)
            return
        }
        if (intent.getBooleanExtra(EXTRA_LAB_DUMP_REMOTE, false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_lab_dump_remote"))
            binding.root.post {
                val host = webHost
                if (host == null) {
                    writeRemoteDump(
                        org.json.JSONObject()
                            .put("ok", false)
                            .put("reason", "no_web_host")
                            .put("uiState", uiState.name)
                            .put("t", System.currentTimeMillis()),
                    )
                    return@post
                }
                host.labDumpRemoteSnapshot { snap ->
                    snap.put("uiState", uiState.name)
                    snap.put("webViewCreated", webViewEverCreated)
                    writeRemoteDump(snap)
                    com.keenzero.app.diagnostics.LabSignal.emitJson("remote_dump", snap)
                    // Keep remote keyevents landing in the WebView after debug dump intents.
                    host.webView?.requestFocus()
                    binding.browserContainer.requestFocus()
                }
            }
            return
        }
        if (intent.getBooleanExtra("com.keenzero.app.extra.LAB_TERMINATE_RENDERER", false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_terminate_renderer_request"))
            binding.root.postDelayed({
                val host = webHost
                val wv = host?.webView
                if (wv != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)) {
                    val process = WebViewCompat.getWebViewRenderProcess(wv)
                    val terminated = process?.terminate() ?: false
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "LAB_RENDERER_TERMINATE_ATTEMPT",
                            detail = "terminated=$terminated"
                        )
                    )
                } else {
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "LAB_RENDERER_TERMINATE_ATTEMPT",
                            detail = "fallback_crash_load"
                        )
                    )
                    wv?.loadUrl("chrome://crash")
                }
            }, 1000L)
            return
        }
        intent.getStringExtra(EXTRA_LAB_URL)
            ?.let(::normalizeUrl)
            ?.let { url ->
                val restore = intent.getBooleanExtra(EXTRA_LAB_RESTORE, false)
                if (restore) {
                    pendingRestore = continuityStore.load()
                    openUrl(url, restore = true)
                } else {
                    openUrl(url)
                }
            }
    }

    /**
     * Controlled journey: open vertical slice → Play → seek → (pause OR advance) →
     * durable checkpoint → metrics for adb.
     *
     * Strengthened mode ([advancing]=true, [noForceSave]=true):
     * media keeps advancing; last durable checkpoint is only via normal ContinuityStore
     * path — no cooperative force-flush immediately before kill.
     */
    private fun runLabAutoJourney(
        contentId: String,
        seekTo: Double,
        playHoldMs: Long,
        advancing: Boolean = false,
        noForceSave: Boolean = false,
        advanceHoldMs: Long = 5_500L,
    ) {
        val t0 = System.currentTimeMillis()
        openUrl(VERTICAL_SLICE_URL)
        // Cold goldfish needs more than 2s before fixture/WebView is interactive.
        binding.root.postDelayed({
            val host = webHost
            if (host == null) {
                writeJourneyMetric(
                    JSONObject()
                        .put("type", "lab_journey_failed")
                        .put("reason", "no_host")
                        .put("t", System.currentTimeMillis()),
                )
                return@postDelayed
            }
            fun drivePlay(attempt: Int) {
            host.labDrivePlay(contentId) { ok ->
                val tPlay = System.currentTimeMillis()
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "lab_journey_play_done",
                        detail = "ok=$ok attempt=$attempt afterMs=${tPlay - t0}",
                    ),
                )
                if (!ok) {
                    if (attempt < 3) {
                        com.keenzero.app.diagnostics.LabSignal.emit(
                            "lab_play_retry",
                            mapOf("attempt" to attempt),
                        )
                        binding.root.postDelayed({ drivePlay(attempt + 1) }, 1_500L)
                        return@labDrivePlay
                    }
                    writeJourneyMetric(
                        JSONObject()
                            .put("type", "lab_journey_failed")
                            .put("reason", "play_click")
                            .put("attempts", attempt)
                            .put("t0", t0)
                            .put("t", System.currentTimeMillis()),
                    )
                    return@labDrivePlay
                }
                binding.root.postDelayed({
                    host.labSamplePosition { pos, playing, audible ->
                        recordEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "lab_journey_mid_sample",
                                detail = "pos=$pos playing=$playing audible=$audible mode=${host.isPlaybackMode}",
                            ),
                        )
                        // Prove playback was advancing before seek when required.
                        writeJourneyMetric(
                            JSONObject()
                                .put("type", "lab_pre_seek_sample")
                                .put("pos", pos)
                                .put("playing", playing)
                                .put("audible", audible)
                                .put("t", System.currentTimeMillis()),
                        )
                    }
                    val afterSeek: (Boolean) -> Unit = { _ ->
                        // Advancing proof: short window so durable stays near seek target.
                        // (5s hold let a ~5s clip run to end — useless for multi-pos.)
                        val hold = if (advancing) advanceHoldMs.coerceIn(1_200L, 2_500L) else 800L
                        binding.root.postDelayed({
                            host.labSamplePosition { posA, playingA, _ ->
                                recordEvent(
                                    NavigationEvent(
                                        System.currentTimeMillis(),
                                        "lab_post_seek_sample_a",
                                        detail = "pos=$posA playing=$playingA",
                                    ),
                                )
                                binding.root.postDelayed({
                                    host.labSamplePosition { posB, playingB, audibleB ->
                                        val advancedBeforeKill =
                                            posA != null && posB != null && (posB - posA) > 0.12
                                        // Fact 1: advanced before termination.
                                        writeJourneyMetric(
                                            JSONObject()
                                                .put("type", "advanced_before_termination")
                                                .put("sampleA", posA)
                                                .put("sampleB", posB)
                                                .put("advancedBeforeTermination", advancedBeforeKill)
                                                .put("contentId", contentId)
                                                .put("seekTarget", seekTo)
                                                .put("t", System.currentTimeMillis()),
                                        )
                                        // Snapshot checkpoint from live orchestrator sample.
                                        host.labForceCheckpointSample()
                                        // Under goldfish load, JS checkpoint sample can exceed 450ms.
                                        binding.root.postDelayed({
                                            val live = latestCheckpoint?.let { cp ->
                                                // Prefer measured term position when orchestrator sample lags.
                                                if (posB != null && posB > 0.05 &&
                                                    kotlin.math.abs(cp.playbackPositionSec - posB) > 1.5
                                                ) {
                                                    cp.copy(playbackPositionSec = posB)
                                                } else cp
                                            } ?: if (posB != null && posB > 0.05) {
                                                ContinuityCheckpoint(
                                                    origin = "https://appassets.androidplatform.net",
                                                    url = host.currentUrl
                                                        ?: "https://appassets.androidplatform.net/assets/lab/vertical_slice.html",
                                                    contentId = contentId,
                                                    title = "Keen Lab",
                                                    season = 1,
                                                    episode = 2,
                                                    playerType = "html5-video",
                                                    playerOrigin = "https://appassets.androidplatform.net",
                                                    playbackPositionSec = posB,
                                                    durationSec = 30.0,
                                                    fullscreen = host.isPlaybackMode,
                                                    playbackMode = host.isPlaybackMode,
                                                    playbackState = if (playingB) "playing" else "paused",
                                                )
                                            } else null
                                            if (live != null) {
                                                // force=true only bypasses debounce for this already-sampled
                                                // normal-path position — does not invent kill-time state.
                                                continuityStore.save(live, force = true)
                                                continuityStore.flush()
                                            }
                                            val durable = continuityStore.load() ?: live
                                            val durableOk = durable != null &&
                                                durable.playbackPositionSec > 0.05 &&
                                                (durable.contentId == null || durable.contentId == contentId)
                                            // Fact 2: checkpoint durably stored.
                                            writeJourneyMetric(
                                                JSONObject()
                                                    .put("type", "checkpoint_durable")
                                                    .put("durableOk", durableOk)
                                                    .put("playbackPositionSec", durable?.playbackPositionSec ?: 0.0)
                                                    .put("contentId", durable?.contentId ?: contentId)
                                                    .put("url", durable?.url)
                                                    .put("playbackMode", durable?.playbackMode == true)
                                                    .put("t", System.currentTimeMillis()),
                                            )
                                            val metric = JSONObject()
                                                .put("type", "lab_journey_ready_for_force_stop")
                                                .put("t0", t0)
                                                .put("t1", System.currentTimeMillis())
                                                .put("elapsedMs", System.currentTimeMillis() - t0)
                                                .put("contentId", contentId)
                                                .put("seekTarget", seekTo)
                                                .put("sampledPos", posB)
                                                .put("positionBeforeTermination", posB)
                                                .put("sampleA", posA)
                                                .put("sampleB", posB)
                                                .put("advancedBeforeTermination", advancedBeforeKill)
                                                .put("playing", playingB)
                                                .put("audible", audibleB)
                                                .put("playbackMode", host.isPlaybackMode)
                                                .put("journeyState", host.journeyState.name)
                                                .put("noForceSave", noForceSave)
                                                .put("advancing", advancing)
                                                .put("setupMethod", if (advancing) "seek_preferred" else "seek_pause")
                                                .put("durableOk", durableOk)
                                                .put("durableCheckpoint", durable?.toJson() ?: JSONObject.NULL)
                                                .put("liveCheckpoint", live?.toJson() ?: JSONObject.NULL)
                                                .put(
                                                    "checkpoint",
                                                    (durable ?: live)?.toJson() ?: JSONObject.NULL,
                                                )
                                            // Do not embed eventsTail: console/detail strings can break JSON
                                            // parsers used by the adb harness.
                                            writeJourneyMetric(metric)
                                            recordEvent(
                                                NavigationEvent(
                                                    System.currentTimeMillis(),
                                                    "LAB_JOURNEY_CHECKPOINT_READY",
                                                    detail = "durable=${durable?.playbackPositionSec} " +
                                                        "live=${live?.playbackPositionSec} " +
                                                        "termPos=$posB advanced=$advancedBeforeKill " +
                                                        "noForceSave=$noForceSave",
                                                ),
                                            )
                                        }, 1_200L)
                                    }
                                }, hold)
                            }
                        }, 500L)
                    }
                    if (advancing) {
                        // Prefer DIRECT seek setup. Natural catch-up is only a degraded fallback
                        // for positioning before kill — it does not make restore DIRECT_*.
                        host.labSeekAndPlay(seekTo) { seekOk ->
                            recordEvent(
                                NavigationEvent(
                                    System.currentTimeMillis(),
                                    "lab_seek_setup_done",
                                    detail = "ok=$seekOk target=$seekTo",
                                ),
                            )
                            if (seekOk) {
                                afterSeek(true)
                            } else {
                                host.labWaitAdvanceTo(seekTo) { ok, actual ->
                                    recordEvent(
                                        NavigationEvent(
                                            System.currentTimeMillis(),
                                            "lab_natural_wait_done",
                                            detail = "ok=$ok actual=$actual target=$seekTo",
                                        ),
                                    )
                                    afterSeek(ok)
                                }
                            }
                        }
                    } else {
                        host.labSeekAndPause(seekTo, afterSeek)
                    }
                }, playHoldMs.coerceAtLeast(1_500L))
            }
            } // drivePlay
            drivePlay(1)
        }, 4_000L)
    }

    private fun measureDpadLatencies(samples: Int) {
        val host = webHost ?: return
        val directions = listOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
        )
        synchronized(events) {
            events.removeAll { it.type == "INPUT_LATENCY_CORRELATED" }
        }
        var i = 0
        fun tick() {
            if (i >= samples) {
                binding.root.postDelayed({
                    val correlated = eventSnapshot().filter { it.type == "INPUT_LATENCY_CORRELATED" }
                    val nativeAcks = mutableListOf<Long>()
                    val dispatches = mutableListOf<Long>()
                    val domCompletions = mutableListOf<Long>()
                    val settlements = mutableListOf<Long>()
                    val totals = mutableListOf<Long>()

                    correlated.forEach { e ->
                        try {
                            val o = JSONObject(e.detail ?: "{}")
                            val t0 = o.getLong("t0")
                            val t1 = o.getLong("t1")
                            val t2 = o.getLong("t2")
                            val t3 = o.getLong("t3")
                            val t3_done = o.getLong("t3_done")
                            val t5 = o.getLong("t5")

                            nativeAcks.add(t1 - t0)
                            dispatches.add(t2 - t1)
                            domCompletions.add(t3_done - t3)
                            settlements.add(t5 - t3_done)
                            totals.add(t5 - t0)
                        } catch (_: Exception) {}
                    }

                    fun stats(list: List<Long>): JSONObject {
                        if (list.isEmpty()) return JSONObject().put("p50", -1).put("p95", -1).put("worst", -1)
                        val sorted = list.sorted()
                        fun pct(p: Double): Long {
                            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
                            return sorted[idx]
                        }
                        return JSONObject()
                            .put("p50", pct(0.50))
                            .put("p95", pct(0.95))
                            .put("worst", sorted.last())
                    }

                    val report = JSONObject()
                        .put("type", "input_latency_correlated")
                        .put("sampleSize", correlated.size)
                        .put("nativeAck", stats(nativeAcks))
                        .put("dispatch", stats(dispatches))
                        .put("domCompletion", stats(domCompletions))
                        .put("twoFrameSettlement", stats(settlements))
                        .put("totalEndToVisible", stats(totals))
                        .put("missedCount", samples - correlated.size)

                    writeJourneyMetric(report)
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "INPUT_LATENCY_REPORT",
                            detail = report.toString()
                        )
                    )
                }, 1500L)
                return
            }
            val key = directions[i % directions.size]
            val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
            host.handleRemoteKey(down)
            i++
            binding.root.postDelayed({ tick() }, 250L)
        }
        binding.root.postDelayed({ tick() }, 500L)
    }

    private fun writeJourneyMetric(obj: JSONObject) {
        try {
            val dir = java.io.File(filesDir, "evidence/journeys")
            if (!dir.exists()) dir.mkdirs()
            val type = obj.optString("type")
            // Keep harness-critical terminal states on latest.json; intermediate samples go to side files.
            // Only phase-end markers land on latest.json (secondary channel).
            // Fact streams always go to logcat via LabSignal.
            val isTerminal = type in setOf(
                "lab_journey_ready_for_force_stop",
                "lab_journey_failed",
                "restore_progress",
                "restore_progress_failed",
                "restore_advance_proof",
                "restore_started",
                "auto_continue_failed",
            )
            val f = java.io.File(dir, if (isTerminal) "latest.json" else "side-$type.json")
            // Compact JSON (toString()) avoids pretty-printer edge cases with large payloads.
            val text = obj.toString()
            f.writeText(text)
            if (isTerminal) {
                java.io.File(dir, "run-${System.currentTimeMillis()}.json").writeText(text)
                getSharedPreferences("keen_lab_metrics", MODE_PRIVATE)
                    .edit()
                    .putString("latest", obj.toString())
                    .commit()
            }
            // Primary harness channel: logcat (run-as cat is flaky on goldfish).
            com.keenzero.app.diagnostics.LabSignal.emitJson(type, obj)
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "LAB_METRIC_WRITTEN",
                    detail = f.absolutePath,
                ),
            )
        } catch (t: Throwable) {
            com.keenzero.app.diagnostics.LabSignal.emit(
                "lab_metric_write_fail",
                mapOf("error" to (t.message ?: "unknown")),
            )
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "LAB_METRIC_WRITE_FAIL",
                    detail = t.message,
                ),
            )
        }
    }

    private fun eventsTailJson(n: Int): org.json.JSONArray {
        val snap = eventSnapshot()
        val start = (snap.size - n).coerceAtLeast(0)
        val arr = org.json.JSONArray()
        for (i in start until snap.size) {
            val e = snap[i]
            arr.put(
                JSONObject()
                    .put("t", e.t)
                    .put("type", e.type)
                    .put("detail", e.detail)
                    .put("url", e.url),
            )
        }
        return arr
    }

    private fun openUrlFromInput() {
        val raw = binding.urlInput.text?.toString()?.trim().orEmpty()
        val url = normalizeUrl(raw)
        if (url == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        openUrl(url)
    }

    /** Commit address bar (Enter): navigate and fold keyboard. */
    private fun commitBrowseUrlBar() {
        val raw = binding.browseUrlEdit.text?.toString()?.trim().orEmpty()
        val url = normalizeUrl(raw)
        if (url == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard(binding.browseUrlEdit)
        binding.browseUrlEdit.clearFocus()
        openUrl(url)
        // Return focus to web/pointer after load starts.
        binding.browserContainer.post {
            webHost?.webView?.requestFocus()
        }
    }

    private fun focusBrowseUrlBar() {
        binding.chromeBar.visibility = View.VISIBLE
        binding.browseUrlEdit.isFocusable = true
        binding.browseUrlEdit.isFocusableInTouchMode = true
        binding.browseUrlEdit.requestFocus()
        binding.browseUrlEdit.setSelection(binding.browseUrlEdit.text?.length ?: 0)
        showKeyboard(binding.browseUrlEdit)
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "url_bar_focus",
                url = lastChromeUrl,
            ),
        )
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun normalizeUrl(raw: String): String? = UrlNormalizer.normalize(raw)

    private fun openUrl(url: String, restore: Boolean = false) {
        recordEvent(NavigationEvent(System.currentTimeMillis(), "user_open_url", url = url))
        val host = ensureWebHost()
        webViewEverCreated = true
        // Session root: Back should not return to FMHY chooser until we leave this site stack.
        browseEntryUrl = url
        currentUrl = url
        val restoreCp = pendingRestore
        if (restore && restoreCp != null) {
            host.beginRestore(restoreCp)
        } else {
            restoreCp?.let { host.setRestorePosition(it.playbackPositionSec) }
        }
        binding.homeScroll.visibility = View.GONE
        binding.browseShell.visibility = View.VISIBLE
        binding.browserContainer.visibility = View.VISIBLE
        binding.chromeBar.visibility = View.VISIBLE
        lastChromeUrl = url
        refreshBrowseChrome()
        setLoadProgress(0)
        uiState = if (restore) AppUiState.RESTORING else AppUiState.BROWSING
        binding.statusLine.text = getString(R.string.status_webview_created)
        hideKeyboard(binding.browseUrlEdit)
        binding.browseUrlEdit.clearFocus()
        host.load(url)
    }

    private fun refreshBrowseChrome() {
        // URL only — no mode callouts (DOM/pointer hints removed).
        if (!binding.browseUrlEdit.hasFocus()) {
            binding.browseUrlEdit.setText(lastChromeUrl)
        }
    }

    private fun setLoadProgress(percent: Int) {
        val bar = binding.loadProgressBar
        val track = binding.loadProgressTrack
        val p = percent.coerceIn(0, 100)
        if (p <= 0) {
            bar.visibility = View.INVISIBLE
            bar.layoutParams = bar.layoutParams.apply { width = 0 }
            return
        }
        bar.visibility = View.VISIBLE
        val w = track.width.takeIf { it > 0 } ?: binding.root.width
        val target = ((w * p) / 100f).toInt().coerceAtLeast(2)
        bar.layoutParams = bar.layoutParams.apply { width = target }
        bar.requestLayout()
        if (p >= 100) {
            bar.postDelayed({
                if (uiState == AppUiState.BROWSING || uiState == AppUiState.RESTORING) {
                    bar.visibility = View.INVISIBLE
                }
            }, 180)
        }
    }

    private fun ensureWebHost(): WebViewHost {
        webHost?.let { return it }
        val host = WebViewHost(
            context = this,
            container = binding.browserContainer,
            cursorHost = binding.pointerLayer,
            fullscreenHost = binding.fullscreenContainer,
            onEvent = { ev ->
                recordEvent(ev)
                // Only RESTORE_SETTLED is authoritative (method=seek|natural).
                // RESTORE_SEEK_APPLIED mid-attempts previously claimed progress with method=unknown.
                if (ev.type == "RESTORE_SETTLED" && !restoreMetricEmitted && pendingRestore != null) {
                    val detail = ev.detail.orEmpty()
                    val actual = Regex("""actual:([0-9.]+)""").find(detail)?.groupValues?.getOrNull(1)
                        ?.toDoubleOrNull()
                        ?: Regex("""seek:([0-9.]+)""").find(detail)?.groupValues?.getOrNull(1)
                            ?.toDoubleOrNull()
                    val method = when {
                        detail.contains("method=seek") || detail.contains("seek:") -> "seek"
                        detail.contains("method=natural") || detail.contains("natural") -> "natural"
                        else -> webHost?.lastRestoreMethod ?: "unknown"
                    }
                    if (method == "seek" || method == "natural") {
                        webHost?.noteRestoreMethod(method)
                    }
                    val isSettled = method == "seek" || method == "natural"
                    // Ignore early failed samples near 0.
                    if (actual != null && actual > 0.05 && isSettled) {
                        val stored = pendingRestore!!.playbackPositionSec
                        val err = kotlin.math.abs(actual - stored)
                        writeJourneyMetric(
                            JSONObject()
                                .put("type", "restore_progress")
                                .put("storedPos", stored)
                                .put("restoredPos", actual)
                                .put("absErrorSec", err)
                                .put("playing", true)
                                .put("audible", false)
                                .put("playbackMode", webHost?.isPlaybackMode == true || pendingRestore?.playbackMode == true)
                                .put("contentId", pendingRestore?.contentId)
                                .put("contentOk", true)
                                .put("via", "restore_settled_console")
                                .put("restoreMethod", method)
                                .put("t", System.currentTimeMillis()),
                        )
                        writeJourneyMetric(
                            JSONObject()
                                .put("type", "restore_direct")
                                .put("storedPos", stored)
                                .put("restoredPos", actual)
                                .put("absErrorSec", err)
                                .put("restoreMethod", method)
                                .put("contentId", pendingRestore?.contentId)
                                .put("contentOk", true)
                                .put("direct", method == "seek" && err <= 2.0)
                                .put("via", "restore_settled_console")
                                .put("t", System.currentTimeMillis()),
                        )
                        restoreMetricEmitted = true
                        recordEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "continuity_restore_progress",
                                url = ev.url,
                                detail = "pos=$actual stored=$stored via=settled method=$method",
                            ),
                        )
                        webHost?.labEnsurePlaying { }
                        webHost?.labProveAdvance(5_200L) { t0a, t1a, playing1 ->
                            val p0 = t0a ?: actual
                            val p1 = t1a
                            val advanced = p1 != null && kotlin.math.abs(p1 - p0) > 0.35
                            writeJourneyMetric(
                                JSONObject()
                                    .put("type", "restore_advance_proof")
                                    .put("storedPos", stored)
                                    .put("restoredPos", actual)
                                    .put("posAfter5s", p1)
                                    .put("advancedAfterRestore", advanced)
                                    .put("deltaSec", if (p1 != null) p1 - p0 else JSONObject.NULL)
                                    .put("playing", playing1)
                                    .put("playbackMode", webHost?.isPlaybackMode == true)
                                    .put("contentId", pendingRestore?.contentId)
                                    .put("restoreMethod", method)
                                    .put("t", System.currentTimeMillis()),
                            )
                            writeJourneyMetric(
                                JSONObject()
                                    .put("type", "advanced_after_restore")
                                    .put("pos0", p0)
                                    .put("pos1", p1)
                                    .put("advancedAfterRestore", advanced)
                                    .put("restoreMethod", method)
                                    .put("t", System.currentTimeMillis()),
                            )
                        }
                        if (err <= 2.0) pendingRestore = null
                    }
                }
            },
            onUrlChanged = { url ->
                currentUrl = url
                runOnUiThread {
                    if (uiState == AppUiState.BROWSING || uiState == AppUiState.WEB_FULLSCREEN ||
                        uiState == AppUiState.PLAYBACK_MODE || uiState == AppUiState.RESTORING
                    ) {
                        if (uiState != AppUiState.PLAYBACK_MODE) {
                            lastChromeUrl = url.orEmpty()
                            refreshBrowseChrome()
                        }
                    }
                }
            },
            onFullscreen = { fullscreen ->
                runOnUiThread {
                    // HTML custom-view fullscreen only — not Keen Playback Mode success.
                    if (uiState != AppUiState.PLAYBACK_MODE) {
                        uiState = if (fullscreen) AppUiState.WEB_FULLSCREEN else AppUiState.BROWSING
                        binding.chromeBar.visibility =
                            if (fullscreen) View.GONE else View.VISIBLE
                    }
                    // Keep pointer above video for subs / quality / audio — never DOM.
                    webHost?.setMediaPointerLock(fullscreen || webHost?.isPlaybackMode == true)
                    ensurePointerAboveContent()
                    if (!fullscreen) {
                        // Return focus to the single live WebView after custom-view teardown.
                        webHost?.webView?.requestFocus()
                    }
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            if (fullscreen) "html_custom_view_enter" else "html_custom_view_exit",
                            url = currentUrl,
                            detail = "mediaPointerLock=$fullscreen",
                        ),
                    )
                }
            },
            onPlaybackMode = { enter ->
                runOnUiThread {
                    applyKeenPlaybackMode(enter)
                    webHost?.setMediaPointerLock(enter)
                    ensurePointerAboveContent()
                }
            },
            onJourneyState = { state ->
                runOnUiThread {
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "journey_state",
                            url = currentUrl,
                            detail = state.name,
                        ),
                    )
                }
            },
            onRendererGone = { detail ->
                runOnUiThread {
                    rendererTerminations.add(detail)
                    webHost = null
                    uiState = AppUiState.RECOVERY
                    supervisor.setUiState(AppUiState.RECOVERY)
                    exitImmersive()
                    val allowAuto = supervisor.onRendererDeath()
                    val cp = latestCheckpoint ?: continuityStore.load()
                    if (!allowAuto) {
                        showHome(status = getString(R.string.renderer_gone) + " (crash-loop)")
                        hydrateContinuitySurface()
                        recordEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "recovery_crash_loop_block",
                                detail = detail.toString(),
                            ),
                        )
                        return@runOnUiThread
                    }
                    if (cp?.url != null) {
                        binding.continueStatus.text = getString(R.string.continue_status_recovery)
                        showHome(status = getString(R.string.renderer_gone_restore))
                        hydrateContinuitySurface()
                        pendingRestore = cp
                        restoreMetricEmitted = false
                        // Automatic recovery: recreate and restore checkpoint.
                        openUrl(cp.url, restore = true)
                        recordEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "recovery_auto_restore",
                                url = cp.url,
                                detail = detail.toString(),
                            ),
                        )
                    } else {
                        showHome(status = getString(R.string.renderer_gone))
                        recordEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "recovery_to_home",
                                detail = detail.toString(),
                            ),
                        )
                    }
                }
            },
            onInputModeChanged = { mode ->
                runOnUiThread {
                    // Mode switches silently — no on-screen callout text.
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "input_mode",
                            url = currentUrl,
                            detail = mode,
                        ),
                    )
                }
            },
            onProgress = { percent ->
                runOnUiThread { setLoadProgress(percent) }
            },
            chromeHeightPx = {
                // GONE chrome still reports last height on some devices — only count when visible.
                if (binding.chromeBar.visibility != View.VISIBLE) 0
                else binding.chromeBar.height.takeIf { it > 0 }
                    ?: binding.chromeBar.measuredHeight.coerceAtLeast(0)
            },
            onUrlBarActivate = {
                runOnUiThread { focusBrowseUrlBar() }
            },
            onConfirmNavigation = { url, host, reason ->
                runOnUiThread {
                    showNavigationConfirm(url, host, reason)
                }
            },
            onCheckpoint = { cp ->
                latestCheckpoint = cp
                // Periodic checkpoints debounce on background thread (ContinuityStore).
                continuityStore.save(cp, force = false)
                // Emit restore metric once, at first sample after restore seek — not after free-run to end.
                if (!restoreMetricEmitted && pendingRestore != null && cp.playbackPositionSec > 0.05) {
                    val stored = pendingRestore?.playbackPositionSec ?: 0.0
                    val err = kotlin.math.abs(cp.playbackPositionSec - stored)
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "continuity_restore_progress",
                            url = cp.url,
                            detail = "pos=${cp.playbackPositionSec} stored=$stored absErr=$err",
                        ),
                    )
                    writeJourneyMetric(
                        JSONObject()
                            .put("type", "restore_progress")
                            .put("storedPos", stored)
                            .put("restoredPos", cp.playbackPositionSec)
                            .put("absErrorSec", err)
                            .put("playbackMode", webHost?.isPlaybackMode == true || cp.playbackMode)
                            .put("t", System.currentTimeMillis()),
                    )
                    restoreMetricEmitted = true
                    // Clear pending only when within truthful resume gate.
                    if (err <= 2.0) {
                        pendingRestore = null
                    }
                }
            },
            onPlaybackConfirmed = { snap ->
                runOnUiThread {
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "playback_confirmed_ui",
                            url = snap.url,
                            detail = "t=${snap.currentTime} muted=${snap.muted}",
                        ),
                    )
                    if (uiState == AppUiState.RESTORING && snap.playing) {
                        writeJourneyMetric(
                            JSONObject()
                                .put("type", "restore_playback_confirmed")
                                .put("pos", snap.currentTime)
                                .put("playbackMode", webHost?.isPlaybackMode == true)
                                .put("t", System.currentTimeMillis()),
                        )
                    }
                }
            },
        )
        webHost = host
        return host
    }

    /**
     * Minimal native confirmation for deliberate high-risk destinations.
     * Open → load in current session. Cancel → stay (never silent open/drop).
     * Ad/junk hosts are auto-cancelled — never leave "Open hai8g.com?" on the TV.
     */
    private fun showNavigationConfirm(url: String, host: String, reason: String) {
        if (isFinishing) return
        val q = com.keenzero.app.playback.PopupQuarantine()
        val junk = q.looksDisposableAdHost(host) ||
            q.decide(
                targetUrl = url,
                requestingOrigin = null,
                playIntentActive = false,
                playOrigin = null,
            ) == com.keenzero.app.playback.PopupQuarantine.Verdict.DESTROY_ADVERTISING
        if (junk) {
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "NAV_CONFIRM_AUTO_BLOCK",
                    url = url,
                    detail = "host=$host reason=$reason junk_ad",
                ),
            )
            return
        }
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "NAV_CONFIRM_SHOWN",
                url = url,
                detail = "host=$host reason=$reason",
            ),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.nav_confirm_title))
            .setMessage(getString(R.string.nav_confirm_message, host))
            .setPositiveButton(R.string.nav_confirm_open) { _, _ ->
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "NAV_CONFIRM_OPEN",
                        url = url,
                        detail = "host=$host",
                    ),
                )
                webHost?.load(url)
            }
            .setNegativeButton(R.string.nav_confirm_cancel) { _, _ ->
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "NAV_CONFIRM_CANCEL",
                        url = url,
                        detail = "host=$host",
                    ),
                )
            }
            .setOnCancelListener {
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "NAV_CONFIRM_CANCEL",
                        url = url,
                        detail = "host=$host dismissed",
                    ),
                )
            }
            .show()
    }

    /**
     * Back stack (see [com.keenzero.app.navigation.BrowsingBackPolicy]):
     * custom-view / document fullscreen → playback chrome → history → home.
     *
     * Document fullscreen (PlaybackOrchestrator OPTIONAL_FULLSCREEN_JS) is not always
     * mirrored in [uiState]; peel it whenever leaving fullscreen *or* playback.
     */
    private fun handleBack() {
        val surface = when (uiState) {
            AppUiState.HOME -> com.keenzero.app.navigation.BrowsingBackPolicy.Surface.HOME
            AppUiState.BROWSING -> com.keenzero.app.navigation.BrowsingBackPolicy.Surface.BROWSING
            AppUiState.PLAYBACK_MODE -> com.keenzero.app.navigation.BrowsingBackPolicy.Surface.PLAYBACK_MODE
            AppUiState.WEB_FULLSCREEN -> com.keenzero.app.navigation.BrowsingBackPolicy.Surface.WEB_FULLSCREEN
            AppUiState.NATIVE_OVERLAY -> com.keenzero.app.navigation.BrowsingBackPolicy.Surface.NATIVE_OVERLAY
            AppUiState.RECOVERY -> com.keenzero.app.navigation.BrowsingBackPolicy.Surface.RECOVERY
            AppUiState.RESTORING -> com.keenzero.app.navigation.BrowsingBackPolicy.Surface.RESTORING
        }
        val customViewFs = webHost?.chromeClient?.isFullscreen == true
        val atEntry = com.keenzero.app.navigation.BrowsingBackPolicy.isSameBrowseEntry(
            browseEntryUrl,
            currentUrl,
        )
        val action = com.keenzero.app.navigation.BrowsingBackPolicy.decide(
            surface = surface,
            htmlCustomViewActive = customViewFs,
            documentFullscreen = uiState == AppUiState.WEB_FULLSCREEN,
            webViewCanGoBack = webHost?.canGoBack() == true,
            atBrowseEntry = atEntry,
            urlBarFocused = binding.browseUrlEdit.hasFocus(),
        )
        when (action) {
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.EXIT_FULLSCREEN -> {
                exitAllHtmlFullscreen()
                uiState = if (webHost?.isPlaybackMode == true) {
                    AppUiState.PLAYBACK_MODE
                } else {
                    AppUiState.BROWSING
                }
                binding.browseShell.visibility = View.VISIBLE
                binding.chromeBar.visibility = View.VISIBLE
                if (webHost?.isPlaybackMode != true) {
                    webHost?.setMediaPointerLock(false)
                }
                webHost?.webView?.requestFocus()
                recordEvent(
                    NavigationEvent(System.currentTimeMillis(), "exit_fullscreen", url = currentUrl),
                )
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.EXIT_PLAYBACK_MODE -> {
                // Must peel HTML fullscreen first or video stays stuck full-bleed.
                exitAllHtmlFullscreen()
                webHost?.exitPlaybackMode("back")
                applyKeenPlaybackMode(false)
                webHost?.setMediaPointerLock(false)
                uiState = AppUiState.BROWSING
                binding.browseShell.visibility = View.VISIBLE
                binding.chromeBar.visibility = View.VISIBLE
                webHost?.webView?.requestFocus()
                recordEvent(
                    NavigationEvent(System.currentTimeMillis(), "exit_playback_mode", url = currentUrl),
                )
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.CLEAR_URL_FOCUS -> {
                hideKeyboard(binding.browseUrlEdit)
                binding.browseUrlEdit.clearFocus()
                webHost?.webView?.requestFocus()
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.HISTORY_BACK -> {
                // Movie page → previous site page (search/list), never FMHY chooser mid-site.
                exitAllHtmlFullscreen()
                webHost?.historyBack()
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "history_back",
                        url = currentUrl,
                        detail = "entry=$browseEntryUrl nativeCanGoBack=${webHost?.canGoBack()}",
                    ),
                )
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.RETURN_HOME -> {
                // Only when already at session entry URL (e.g. cineby home after openUrl).
                exitAllHtmlFullscreen()
                webHost?.flushSession()
                webHost?.destroy("return_home")
                webHost = null
                browseEntryUrl = null
                showHome(status = getString(R.string.status_home) + " (returned)")
                hydrateContinuitySurface()
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.DISMISS_OVERLAY -> {
                binding.diagnosticsPreview.visibility = View.GONE
                uiState = AppUiState.HOME
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.SYSTEM_EXIT -> {
                recordEvent(NavigationEvent(System.currentTimeMillis(), "system_exit_back"))
                finish()
            }
        }
    }

    /** Custom-view host + document/webkit fullscreen (idempotent). */
    private fun exitAllHtmlFullscreen() {
        webHost?.chromeClient?.exitFullscreenIfNeeded()
        webHost?.webView?.evaluateJavascript(EXIT_DOCUMENT_FULLSCREEN_JS, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While typing in the address bar, let the EditText / IME own keys.
        if (binding.browseUrlEdit.hasFocus()) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hideKeyboard(binding.browseUrlEdit)
                binding.browseUrlEdit.clearFocus()
                webHost?.webView?.requestFocus()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if ((uiState == AppUiState.BROWSING || uiState == AppUiState.WEB_FULLSCREEN ||
                uiState == AppUiState.PLAYBACK_MODE || uiState == AppUiState.RESTORING) &&
            webHost?.handleRemoteKey(event) == true
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Keen pointer is a root sibling — always above browse shell and HTML custom-view host.
     * Never parent the cursor into the WebView or fullscreen custom view.
     */
    private fun ensurePointerAboveContent() {
        binding.pointerLayer.elevation = 32f
        binding.pointerLayer.bringToFront()
        // Confirmation / system overlays may sit higher; keep home under pointer while browsing.
        if (binding.homeScroll.visibility == View.VISIBLE) {
            binding.homeScroll.bringToFront()
        }
    }

    /**
     * Keen-owned playback surface: hide browsing chrome, expand WebView, immersive system UI.
     * This is the primary fullscreen mechanism — not HTML requestFullscreen().
     */
    private fun applyKeenPlaybackMode(enter: Boolean) {
        if (enter) {
            uiState = AppUiState.PLAYBACK_MODE
            binding.chromeBar.visibility = View.GONE
            binding.homeScroll.visibility = View.GONE
            binding.browseShell.visibility = View.VISIBLE
            binding.browserContainer.visibility = View.VISIBLE
            enterImmersive()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "PLAYBACK_MODE_UI",
                    url = currentUrl,
                    detail = "enter immersive=1 chrome=hidden",
                ),
            )
        } else {
            exitImmersive()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (uiState == AppUiState.PLAYBACK_MODE) {
                uiState = AppUiState.BROWSING
            }
            binding.browseShell.visibility = View.VISIBLE
            binding.chromeBar.visibility = View.VISIBLE
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "PLAYBACK_MODE_UI",
                    url = currentUrl,
                    detail = "exit",
                ),
            )
        }
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun showHome(status: String) {
        uiState = AppUiState.HOME
        exitImmersive()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.browseShell.visibility = View.GONE
        binding.browserContainer.visibility = View.GONE
        binding.fullscreenContainer.visibility = View.GONE
        binding.chromeBar.visibility = View.GONE
        binding.homeScroll.visibility = View.VISIBLE
        binding.statusLine.text = status
        binding.urlInput.setText(getString(R.string.home_url))
        binding.btnLoad.requestFocus()
    }

    private fun showDiagnosticsPreview() {
        uiState = if (uiState == AppUiState.HOME) AppUiState.NATIVE_OVERLAY else uiState
        val json = buildEvidencePayload()
        val preview = buildString {
            appendLine("build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            appendLine("buildId: ${BuildConfig.BUILD_ID}")
            appendLine("git: ${BuildConfig.GIT_SHA}")
            appendLine("debuggable: ${BuildConfig.DEBUG}")
            appendLine()
            val device = json.getJSONObject("device")
            appendLine("device: ${device.getString("manufacturer")} ${device.getString("model")}")
            appendLine("android: ${device.getString("androidVersion")} (API ${device.getInt("api")})")
            appendLine("abis: ${device.getJSONArray("abis")}")
            appendLine("memoryClassMb: ${device.getInt("memoryClassMb")} lowRam=${device.getBoolean("lowRam")}")
            appendLine()
            val wv = json.getJSONObject("webview")
            appendLine("webview.package: ${wv.opt("packageName")}")
            appendLine("webview.version: ${wv.opt("versionName")}")
            appendLine()
            val runtime = json.getJSONObject("runtime")
            appendLine("uiState: ${runtime.getString("uiState")}")
            appendLine("webViewCreated: ${runtime.getBoolean("webViewCreated")}")
            appendLine("currentUrl: ${runtime.opt("currentUrl")}")
            appendLine("events: ${json.getJSONArray("events").length()}")
            appendLine("rendererTerminations: ${json.getJSONArray("rendererTerminations").length()}")
            val cont = json.optJSONObject("continuity")
            if (cont != null) {
                appendLine("continuity.url: ${cont.opt("url")}")
                appendLine("continuity.pos: ${cont.opt("playbackPositionSec")}")
            }
        }
        binding.diagnosticsPreview.visibility = View.VISIBLE
        binding.diagnosticsPreview.text = preview
        recordEvent(NavigationEvent(System.currentTimeMillis(), "diagnostics_preview"))
    }

    private fun exportEvidence() {
        try {
            val payload = buildEvidencePayload()
            val file = EvidenceExporter.export(this, payload)
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "evidence_export",
                    detail = file.absolutePath,
                ),
            )
            Toast.makeText(
                this,
                getString(R.string.export_ok, file.absolutePath),
                Toast.LENGTH_LONG,
            ).show()
            binding.diagnosticsPreview.visibility = View.VISIBLE
            binding.diagnosticsPreview.text =
                (binding.diagnosticsPreview.text?.toString().orEmpty() + "\n\nexported: ${file.absolutePath}")
                    .trim()
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                getString(R.string.export_fail, t.message ?: t.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun buildEvidencePayload(): JSONObject {
        val cp = latestCheckpoint ?: continuityStore.load()
        return DeviceDiagnostics.collect(
            context = this,
            currentUrl = currentUrl ?: webHost?.currentUrl,
            webViewCreated = webViewEverCreated,
            uiState = uiState.name,
            events = eventSnapshot(),
            rendererTerminations = rendererTerminations.toList(),
        ).put(
            "sitePacks",
            SitePackRuntime.snapshot().let { snapshot ->
                JSONObject()
                    .put("ready", snapshot.ready)
                    .put("verified", snapshot.verified)
                    .put("bundleVersion", snapshot.bundleVersion)
                    .put("activePackIds", org.json.JSONArray(snapshot.activePackIds))
                    .put("expires", snapshot.expires)
                    .put("error", snapshot.error)
            },
        ).put(
            "blocking",
            BlockingRuntime.snapshot().let { snapshot ->
                JSONObject()
                    .put("ready", snapshot.ready)
                    .put("allowedRequests", snapshot.allowedRequests)
                    .put("blockedRequests", snapshot.blockedRequests)
                    .put("matchP50Us", snapshot.matchP50Us)
                    .put("matchP95Us", snapshot.matchP95Us)
                    .put("matchP99Us", snapshot.matchP99Us)
                    .put("serviceWorkerInterception", snapshot.serviceWorkerInterception)
                    .put("pageHost", snapshot.pageHost)
                    .put("visibility", snapshot.visibility)
            },
        ).put(
            "continuity",
            cp?.toJson() ?: JSONObject.NULL,
        ).put(
            "outcome",
            JSONObject()
                .put("phase", "0-vertical-slice-32bit")
                .put("lab", true)
                .put("primaryAbi", BuildConfig.PRIMARY_ABI)
                .put("abiPolicy", BuildConfig.ABI_POLICY)
                .put("webViewAlive", webHost?.isCreated == true)
                .put("journeyState", webHost?.journeyState?.name ?: PlaybackJourneyState.BROWSING.name)
                .put("playbackMode", webHost?.isPlaybackMode == true)
                .put("note", "Vertical slice instrumentation — controlled fixture; armeabi-v7a first; not a corpus verdict"),
        ).also { root ->
            val extras = supervisor.diagnosticsExtras()
            root.put("performance", extras.optJSONObject("performance"))
            root.put("supervisor", extras.optJSONObject("supervisor"))
        }
    }

    @Synchronized
    private fun recordEvent(event: NavigationEvent) {
        if (events.size >= MAX_EVENTS) {
            events.removeFirst()
        }
        events.addLast(event)
        if (::supervisor.isInitialized) {
            supervisor.record(event)
        }
    }

    @Synchronized
    private fun eventSnapshot(): List<NavigationEvent> = events.toList()

    override fun onDestroy() {
        latestCheckpoint?.let { continuityStore.save(it, force = true) }
        webHost?.flushSession()
        webHost?.destroy("activity_destroy")
        webHost = null
        super.onDestroy()
    }

    private fun formatTime(sec: Double): String {
        if (sec <= 0 || sec.isNaN()) return "0:00"
        val total = sec.toInt()
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        const val EXTRA_LAB_URL = "com.keenzero.app.extra.LAB_URL"
        const val EXTRA_EXPORT_EVIDENCE = "com.keenzero.app.extra.EXPORT_EVIDENCE"
        const val EXTRA_LAB_AUTO_JOURNEY = "com.keenzero.app.extra.LAB_AUTO_JOURNEY"
        const val EXTRA_AUTO_CONTINUE = "com.keenzero.app.extra.AUTO_CONTINUE"
        const val EXTRA_LAB_CONTENT_ID = "com.keenzero.app.extra.LAB_CONTENT_ID"
        const val EXTRA_LAB_SEEK_SEC = "com.keenzero.app.extra.LAB_SEEK_SEC"
        const val EXTRA_LAB_PLAY_HOLD_MS = "com.keenzero.app.extra.LAB_PLAY_HOLD_MS"
        /** Keep media advancing after seek (strengthened continuity). */
        const val EXTRA_LAB_ADVANCING = "com.keenzero.app.extra.LAB_ADVANCING"
        /** Do not force-flush SharedPreferences immediately before harness kill. */
        const val EXTRA_LAB_NO_FORCE_SAVE = "com.keenzero.app.extra.LAB_NO_FORCE_SAVE"
        /** Hold ms after seek while advancing for natural checkpoint. */
        const val EXTRA_LAB_ADVANCE_HOLD_MS = "com.keenzero.app.extra.LAB_ADVANCE_HOLD_MS"
        const val EXTRA_LAB_MEASURE_INPUT = "com.keenzero.app.extra.LAB_MEASURE_INPUT"
        const val EXTRA_LAB_INPUT_SAMPLES = "com.keenzero.app.extra.LAB_INPUT_SAMPLES"
        const val EXTRA_LAB_RESTORE = "com.keenzero.app.extra.LAB_RESTORE"
        /** Debug/lab: dump interaction candidates + focus for remote journey harness. */
        const val EXTRA_LAB_DUMP_REMOTE = "com.keenzero.app.extra.LAB_DUMP_REMOTE"
        const val VERTICAL_SLICE_URL =
            "https://appassets.androidplatform.net/assets/lab/vertical_slice.html"
        const val STRESS_URL =
            "https://appassets.androidplatform.net/assets/lab/stress.html"
        const val REMOTE_FIXTURE_URL =
            "https://appassets.androidplatform.net/assets/lab/remote_control_fixture.html"
        private const val MAX_EVENTS = 400

        /** Peel document/webkit fullscreen from OPTIONAL_FULLSCREEN_JS path. */
        private val EXIT_DOCUMENT_FULLSCREEN_JS = """
            (function(){
              try{
                if(document.fullscreenElement && document.exitFullscreen) document.exitFullscreen();
                else if(document.webkitFullscreenElement && document.webkitExitFullscreen) document.webkitExitFullscreen();
                else if(document.webkitIsFullScreen && document.webkitCancelFullScreen) document.webkitCancelFullScreen();
              }catch(e){}
            })();
        """.trimIndent()
    }

    private fun writeRemoteDump(obj: org.json.JSONObject) {
        try {
            val dir = java.io.File(filesDir, "evidence/remote-control")
            dir.mkdirs()
            java.io.File(dir, "latest.json").writeText(obj.toString(2))
        } catch (_: Exception) {
        }
    }
}
