package com.keenzero.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.IntentFilter
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
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
import androidx.core.content.ContextCompat
import com.keenzero.app.continuity.ContinuityCheckpoint
import com.keenzero.app.continuity.ContinuityStore
import com.keenzero.app.databinding.ActivityKeenBinding
import com.keenzero.app.diagnostics.DeviceDiagnostics
import com.keenzero.app.diagnostics.EvidenceExporter
import com.keenzero.app.diagnostics.NavigationEvent
import com.keenzero.app.diagnostics.MemoryPressureDiagnostics
import com.keenzero.app.playback.PlaybackJourneyState
import com.keenzero.app.playback.PlaybackPriorityService
import com.keenzero.app.web.WebViewHost
import com.keenzero.app.blocking.BlockingRuntime
import com.keenzero.app.sitepacks.SitePackRuntime
import com.keenzero.app.torrent.TorrentStreamingService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

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

    /**
     * Isolated stock WebView for approved compatibility origins (see
     * [com.keenzero.app.compat.CompatibilityOrigins]). Never coexists with [webHost]:
     * entering compatibility mode destroys the normal host, leaving it rebuilds one with
     * every protection back in force, so no protection state can leak between the two.
     */
    private var compatSession: com.keenzero.app.compat.CompatibilitySession? = null

    /** Origins the user has agreed to open in compatibility mode. */
    private val compatOriginStore by lazy {
        com.keenzero.app.compat.CompatibilityOriginStore(this).also {
            com.keenzero.app.compat.CompatibilityOrigins.store = it
        }
    }

    /** Guards against switching the same host twice in one session. */
    private val compatSwitchedHosts = mutableSetOf<String>()

    private val events = ArrayDeque<NavigationEvent>(MAX_EVENTS)
    private val rendererTerminations = mutableListOf<JSONObject>()
    private var currentUrl: String? = null
    /** First URL of this browse session (home chooser → site). Back only returns to chooser here. */
    private var browseEntryUrl: String? = null

    /**
     * The WebView history index this browsing session started at, captured on the entry
     * page's first page-finished. Back walks history only while the index is above it;
     * at or below, the remaining entries belong to a previous session and Back leaves.
     */
    private var browseEntryHistoryIndex: Int? = null
    private var webViewEverCreated: Boolean = false
    private var latestCheckpoint: ContinuityCheckpoint? = null
    private var pendingRestore: ContinuityCheckpoint? = null
    private var restoreMetricEmitted: Boolean = false
    private var lastChromeUrl: String = ""
    private var lastBrowsingCheckpointUrl: String? = null
    private var torrentRequestId: String? = null
    private var torrentPlayer: ExoPlayer? = null
    /** Identity of the active magnet/.torrent for resume-point persistence. */
    private var torrentOriginKey: String? = null

    /** File index being streamed from a multi-file torrent; null when it has only one. */
    private var torrentFileIndex: Int? = null

    /**
     * Every feature in the live torrent, in episode order, as the service resolved it.
     *
     * Empty for a single film — which is also the answer to "is there a next episode".
     */
    private var torrentPackIndices: IntArray = IntArray(0)
    private var torrentPackNames: Array<String> = emptyArray()

    /** The browse shell was on screen when playback took over, so it is owed a restore. */
    private var browseShellHiddenForPlayer = false

    /** Stream URL the live player was already built for, so READY does not rebuild it. */
    private var torrentOpenedStreamUrl: String? = null

    /** True once the next-episode button is up and its countdown is running. */
    private var nextEpisodeArmed = false

    /**
     * The user pressed Back on the offer for this episode, so it stays gone.
     *
     * Without it the one-second watcher simply puts the offer back up on the next tick,
     * which turns "no thanks" into a button that cannot be dismissed. Cleared on a new
     * file, and on seeking back into the film — deciding not to skip the credits once
     * should not mean never being offered the next episode again.
     */
    private var nextEpisodeDeclined = false

    /** Resume identity for what is playing now: the torrent, narrowed to the chosen file. */
    private val torrentResumeKey: String?
        get() = torrentOriginKey?.let {
            com.keenzero.app.torrent.TorrentResumeStore.fileKeyOf(it, torrentFileIndex)
        }
    /** Raw magnet / .torrent URL of the active session — the Continue card re-activates it. */
    private var torrentOriginLabel: String? = null
    /** Display title from the torrent service (file name), for the Continue card. */
    private var torrentTitle: String? = null

    /** On-disk media file the bridge serves — the source for Continue-card frame grabs. */
    private var torrentMediaPath: String? = null
    /** Pending hold-to-seek target while DPAD left/right is held in the torrent player. */
    private var torrentSeekTargetMs: Long = -1L
    private var torrentSeekLastEventMs: Long = 0L
    /** True while a Keen hold-seek gesture owns left/right (between key-down and key-up).
     * Once a gesture starts it keeps ownership through release — showController() moves
     * focus to the scrubber mid-gesture, and re-checking focus would otherwise abandon
     * the seek (and leave the target-time preview stuck on screen). */
    private var torrentSeekActive = false

    /** Media3's scrubber, driven to the pending target while a hold-seek is in flight. */
    private var torrentTimeBar: androidx.media3.ui.DefaultTimeBar? = null

    /**
     * Keeps the scrubber pinned to the pending seek target while a hold is in progress.
     *
     * The seek itself only commits on key-release (one piece-deadline reset instead of
     * one per repeat), so the time bar would otherwise sit at the player's real position
     * and look frozen while the target raced ahead in the text preview. Media3's own
     * progress loop rewrites the bar every couple of hundred ms, so re-asserting the
     * target every frame is what keeps the thumb travelling smoothly.
     */
    private val torrentScrubTick = object : Runnable {
        override fun run() {
            if (!torrentSeekActive) return
            val duration = torrentPlayer?.duration ?: 0L
            if (duration > 0 && torrentSeekTargetMs >= 0) {
                // One writer, every frame: the fill is the target and nothing else ever
                // sets it, so the skim is smooth however long the hold runs.
                binding.torrentScrubFill.scaleX =
                    (torrentSeekTargetMs.toFloat() / duration).coerceIn(0f, 1f)
            }
            binding.root.postDelayed(this, TORRENT_SCRUB_FRAME_MS)
        }
    }
    /**
     * True once this torrent has actually produced playback. Until then the bridge's
     * stall reporting is start-up noise, not a seek: the player's first range read
     * always blocks briefly, which restarted the loader at 0% straight after the
     * initial buffer had already shown 100%.
     */
    private var torrentPlaybackStarted = false

    /**
     * True once the current player has put a frame on screen and the circular reveal
     * has run. Separate from [torrentPlaybackStarted], which is already true before the
     * player exists for a local library file. Nothing may take the loading surface down
     * while this is false — the reveal is what removes it.
     */
    private var torrentFirstFrameShown = false

    /** A frame has reached the screen. Necessary for the reveal, but nowhere near enough. */
    private var torrentRenderedFirstFrame = false

    /** A playhead-movement sample is in flight; stops every event queueing another. */
    private var revealMotionCheckPending = false

    /** True from launch until a restored session has taken over the screen. */
    private var autoContinuePending = false

    /** Consecutive playback errors recovered from; reset once playback resumes. */
    private var torrentPlayerRetries = 0

    /** Frame grab for the Continue card: retries while playback hasn't produced a usable frame. */
    private var torrentFrameAttempts = 0
    private val torrentFrameCaptureRunnable = Runnable { captureTorrentFrame("scheduled") }

    /**
     * Writes the playback position while a torrent plays.
     *
     * Checkpoints were only saved on pause, on exit, and after the first frame capture at
     * 75 s. Anything that kills the process before one of those — a low-memory kill, a
     * crash, an app update, a power cut — lost the title from Continue watching entirely,
     * which is exactly what those checkpoints exist to survive. Cheap: one small write.
     */
    private val torrentCheckpointRunnable = object : Runnable {
        override fun run() {
            if (!nativeTorrentPlayerActive) return
            saveTorrentResumePoint()
            binding.root.postDelayed(this, TORRENT_CHECKPOINT_INTERVAL_MS)
        }
    }
    /** og:image / poster of the current page — attached to media checkpoints. */
    private var currentPagePosterUrl: String? = null
    private var posterProbeUrl: String? = null

    /** Page the poster was scraped from; without it a title inherited the previous one's art. */
    private var currentPagePosterForUrl: String? = null
    // Load bar is driven as a continuous 0..1 fraction and rendered via scaleX so
    // real progress jumps ease in instead of snapping to new rectangle widths.
    private var loadProgressAnimator: android.animation.ValueAnimator? = null
    private var loadProgressFraction: Float = 0f
    private var loadProgressTrickling: Boolean = false
    // Failed / stalled main-frame load state. A single navigation owns one of these
    // outcomes: it finishes cleanly (hide), errors (show reason), or never progresses
    // (stall timeout → show). onPageFinished fires even on error pages, so a recorded
    // error must survive the finish that follows it.
    private var failedLoadUrl: String? = null
    private var mainFrameLoadErrored: Boolean = false
    private val stallTimeout = Runnable {
        if (!mainFrameLoadErrored) showPageError(getString(R.string.error_reason_stalled))
    }
    private val torrentResumeStore by lazy { com.keenzero.app.torrent.TorrentResumeStore(this) }
    private val favouritesStore by lazy { com.keenzero.app.favourites.FavouritesStore(this) }
    private val urlHistoryStore by lazy { com.keenzero.app.history.UrlHistoryStore(this) }
    private val libraryStore by lazy { com.keenzero.app.library.StarredLibraryStore(this) }

    /** True while the K marks are showing their spinner state. */
    /** True while the address bar's loading line is running. */
    private var navLoadingShown = false

    /** Last resort: end the line if nothing else ever said the load was over. */
    private val navLoadingWatchdog = Runnable { setNavLoading(false) }

    /** Ends a line started optimistically on a press that never became a load. */
    private val navLoadingProvisionalClose = Runnable { setNavLoading(false) }

    /** Caps how long a real load may run the line, whatever the page claims. */
    private val navLoadingSettle = Runnable { setNavLoading(false) }

    /** Star injected into the player controls, left of the subtitle button. */
    private var playerStarButton: android.widget.ImageButton? = null

    /**
     * Per-title views of the Downloaded row, so a progress tick can update the figure in
     * place. Rebuilding the row every two seconds would restart its entry animations and
     * throw away D-pad focus mid-scroll.
     */
    private val downloadedCardViews =
        mutableMapOf<String, Pair<android.widget.TextView, View>>()

    /** Keys and states the Downloaded row was last built from; a change means rebuild. */
    private var downloadedRowSignature: String? = null

    /**
     * What the row is made of, as opposed to what the figures on it say. Progress and speed
     * are deliberately absent: they change every tick and are painted in place.
     */
    private fun rowSignature(
        entries: List<com.keenzero.app.library.StarredLibraryStore.Entry>,
    ): String = entries.joinToString("|") { "${it.key}:${it.state}" }

    /** Interrupted downloads are restarted once per activity start, not per repaint. */
    private var resumedDownloadsThisSession = false

    /**
     * Polls the library index while a download is running and the home screen is up.
     *
     * The download service broadcasts on every tick, but the card was still showing a
     * stale figure, so the row no longer depends on that broadcast arriving: the index is
     * a small JSON file and reading it once a second is far cheaper than being wrong.
     * Stops itself as soon as nothing is downloading.
     */
    private val downloadProgressTicker = object : Runnable {
        override fun run() {
            if (uiState != AppUiState.HOME) return
            applyDownloadProgress()
            if (libraryStore.list().any {
                    it.state == com.keenzero.app.library.StarredLibraryStore.State.DOWNLOADING
                }
            ) {
                binding.root.postDelayed(this, DOWNLOAD_TICK_MS)
            }
        }
    }

    /** True while the address bar is writing its own inline completion (re-entrancy guard). */
    private var applyingUrlCompletion = false
    private val torrentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(TorrentStreamingService.EXTRA_REQUEST_ID)
            if (id.isNullOrBlank() || id != torrentRequestId) return
            when (intent.action) {
                TorrentStreamingService.ACTION_PROGRESS -> {
                    val stage = intent.getStringExtra(TorrentStreamingService.EXTRA_STAGE).orEmpty()
                    // Timeline seek past the downloaded window: the bridge reports
                    // buffering while the player is stalled — bring the loader back
                    // over the player until enough pieces arrive to resume.
                    // The buffer counter runs 0 -> 100 exactly once, then playback
                    // starts. The bridge's stall progress is for MID-PLAYBACK seeks
                    // only; before the first frame it is just the opening read
                    // blocking, and showing it rewound a completed 100% back to 0.
                    // Only the SEEK stall is start-up noise before the first frame.
                    // Normal buffering must always show — that is the readout with the
                    // percentage, peers, seeds and speed on it.
                    if (stage == TorrentStreamingService.STAGE_SEEK_BUFFERING && !torrentPlaybackStarted) {
                        return
                    }
                    if (!torrentOverlayVisible && nativeTorrentPlayerActive &&
                        stage == TorrentStreamingService.STAGE_SEEK_BUFFERING &&
                        torrentPlayer?.playbackState == Player.STATE_BUFFERING
                    ) {
                        showTorrentOverlay()
                    }
                    updateTorrentOverlay(
                        stage = stage,
                        percent = intent.getIntExtra(TorrentStreamingService.EXTRA_PERCENT, -1),
                        peers = intent.getIntExtra(TorrentStreamingService.EXTRA_PEERS, -1),
                        seeds = intent.getIntExtra(TorrentStreamingService.EXTRA_SEEDS, -1),
                        speedBps = intent.getLongExtra(TorrentStreamingService.EXTRA_SPEED_BPS, -1),
                        swarmSeeds = intent.getIntExtra(TorrentStreamingService.EXTRA_SWARM_SEEDS, -1),
                        swarmPeers = intent.getIntExtra(TorrentStreamingService.EXTRA_SWARM_PEERS, -1),
                    )
                }
                // The bridge can serve bytes. Build the player NOW so it opens the
                // container against the same pieces the buffer loop is fetching, instead
                // of queueing that work behind a buffer that already reported 100%.
                TorrentStreamingService.ACTION_STREAM_OPEN -> {
                    val streamUrl = intent.getStringExtra(TorrentStreamingService.EXTRA_STREAM_URL) ?: return
                    applyTorrentStreamIdentity(intent)
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "torrent_stream_open",
                            url = streamUrl,
                            detail = "title=${torrentTitle.orEmpty()}",
                        ),
                    )
                    // The loading surface stays exactly as it is: buffering is still
                    // running and its numbers are still the honest thing to show.
                    // Set after building: showNativeTorrentPlayer tears the previous
                    // player down first, and that teardown clears this.
                    showNativeTorrentPlayer(streamUrl, torrentTitle.orEmpty())
                    torrentOpenedStreamUrl = streamUrl
                }
                TorrentStreamingService.ACTION_READY -> {
                    val streamUrl = intent.getStringExtra(TorrentStreamingService.EXTRA_STREAM_URL) ?: return
                    val title = intent.getStringExtra(TorrentStreamingService.EXTRA_TITLE)
                    applyTorrentStreamIdentity(intent)
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "torrent_ready",
                            url = streamUrl,
                            detail = "title=${title.orEmpty()}",
                        ),
                    )
                    // Overlay stays up until onRenderedFirstFrame — opening the stream and
                    // reading the container index is still ahead of us, and black screen
                    // with no indicator reads as a failure.
                    showTorrentStartingStage()
                    // Normally the player has been running since STREAM_OPEN and is well
                    // into the container by now; rebuilding it here would throw that away
                    // and reintroduce the very wait this is meant to remove.
                    if (torrentOpenedStreamUrl != streamUrl || torrentPlayer == null) {
                        showNativeTorrentPlayer(streamUrl, title.orEmpty())
                        torrentOpenedStreamUrl = streamUrl
                    }
                    // Armed from here, not from STREAM_OPEN: until the buffer is full a
                    // frameless screen is simply the wait working as designed, and the
                    // loader must not be pulled out from under it.
                    binding.root.removeCallbacks(firstFrameWatchdog)
                    binding.root.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS)
                }
                com.keenzero.app.library.LibraryDownloadService.ACTION_LIBRARY_CHANGED -> {
                    // The download service owns the records; we only reflect them.
                    if (uiState == AppUiState.HOME) applyDownloadProgress()
                }
                TorrentStreamingService.ACTION_CHOOSE_FILE -> {
                    val id = intent.getStringExtra(TorrentStreamingService.EXTRA_REQUEST_ID)
                    if (id != null && id == torrentRequestId) promptTorrentFileChoice(intent, id)
                }
                TorrentStreamingService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(TorrentStreamingService.EXTRA_ERROR)
                        ?: "Torrent streaming failed"
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "torrent_error",
                            detail = message,
                        ),
                    )
                    hideTorrentOverlay()
                    Toast.makeText(this@KeenActivity, message, Toast.LENGTH_LONG).show()
                    torrentRequestId = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        continuityStore = ContinuityStore(this)
        supervisor = com.keenzero.app.supervisor.KeenSupervisor(this)

        ContextCompat.registerReceiver(
            this,
            torrentReceiver,
            IntentFilter().apply {
                addAction(TorrentStreamingService.ACTION_READY)
                addAction(TorrentStreamingService.ACTION_STREAM_OPEN)
                addAction(TorrentStreamingService.ACTION_ERROR)
                addAction(TorrentStreamingService.ACTION_PROGRESS)
                addAction(TorrentStreamingService.ACTION_CHOOSE_FILE)
                addAction(com.keenzero.app.library.LibraryDownloadService.ACTION_LIBRARY_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Once per app start: bring the index in line with the disk and pick up any
        // download interrupted by a process death. Deliberately not in the row repaint.
        libraryStore.reconcile()
        resumeInterruptedDownloads()

        // Reclaim torrent cache left behind if the :torrent process was killed
        // mid-stream (its cleanup never ran). No session can be active this early.
        stopService(Intent(this, TorrentStreamingService::class.java))
        Thread({
            val stale = java.io.File(cacheDir, "torrent")
            if (stale.exists()) stale.deleteRecursively()
            // Spooled .torrent files are consumed and deleted within seconds of being
            // written; one still here at startup was stranded by a kill mid-handover.
            val spools = java.io.File(cacheDir, "torrent-files")
            if (spools.exists()) spools.deleteRecursively()
        }, "keen-torrent-sweep").apply { isDaemon = true }.start()

        recordEvent(NavigationEvent(System.currentTimeMillis(), "activity_onCreate"))
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "PERFORMANCE_POLICY",
                detail = supervisor.policy.toJson().toString(),
            ),
        )

        binding.chromeFavButton.setOnClickListener { toggleFavourite() }
        // Tapping the K in the address bar is an explicit, clean return to the home canvas.
        binding.chromeLogo.setOnClickListener { returnHomeFromChrome() }
        binding.errorRetry.setOnClickListener { retryFailedLoad() }

        binding.homeUrlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                commitHomeUrl()
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
        // EditText normally eats DPAD_RIGHT to move the text cursor and only hands off to
        // nextFocusRight once the cursor is already at the end — on a remote that reads as
        // "the star button doesn't work." Jump straight to it instead of letting text-cursor
        // navigation swallow the press.
        binding.browseUrlEdit.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                binding.chromeFavButton.requestFocus()
                true
            } else {
                false
            }
        }
        // Typing a URL one D-pad key at a time is the slowest thing in the product.
        // Both address fields finish it from what has been opened before.
        attachUrlCompletion(binding.browseUrlEdit)
        attachUrlCompletion(binding.homeUrlInput)

        // Re-run on every layout of the address row: the mark tracks the text's metrics,
        // so it re-derives whenever anything about the field changes. Posted rather than
        // applied inline because it may adjust child sizes, which cannot happen during
        // the layout pass that reported them.
        binding.homeTopBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.homeTopBar.post(::alignHomeMarkToAddressText)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            },
        )

        // Decided BEFORE the home surface renders. On a cold start the favourites and
        // Continue rows have not hydrated yet, so showHome() briefly sees an empty home
        // and focuses the address field — and on Android TV focusing an EditText raises
        // the keyboard on its own, with no showSoftInput call to suppress. The movie then
        // loads underneath an IME nobody asked for.
        autoContinuePending = intent?.getStringExtra("com.keenzero.app.extra.LAB_URL").isNullOrBlank() &&
            intent?.getBooleanExtra(EXTRA_AUTO_CONTINUE, false) != true &&
            continuityStore.load()?.url != null &&
            !continuityStore.wasAtHome()
        showHome(status = getString(R.string.status_home))
        recordEvent(NavigationEvent(System.currentTimeMillis(), "native_home_ready"))
        // LAB_URL / harness extras allowed on release for physical TV validation.
        handleDebugIntent(intent)
        // Cold start lands exactly where the user left off (page or playback).
        // Only a deliberate back-out to home (at_home flag) keeps the launch on
        // the Continue watching surface.
        if (webHost == null && torrentRequestId == null &&
            intent?.getStringExtra("com.keenzero.app.extra.LAB_URL").isNullOrBlank() &&
            intent?.getBooleanExtra(EXTRA_AUTO_CONTINUE, false) != true &&
            intent?.getBooleanExtra(EXTRA_LAB_AUTO_JOURNEY, false) != true
        ) {
            val checkpoint = continuityStore.load()
            if (checkpoint?.url != null && !continuityStore.wasAtHome()) {
                continueFromCheckpoint()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onPause() {
        compatSession?.onPause()
        webHost?.onBackground {
            latestCheckpoint?.let { continuityStore.save(it, force = true) }
            continuityStore.flush()
        }
        webHost?.flushSession()
        latestCheckpoint?.let { continuityStore.save(it, force = true) }
        continuityStore.flush()
        super.onPause()
    }

    override fun onStop() {
        latestCheckpoint?.let { continuityStore.save(it, force = true) }
        continuityStore.flush()
        // Screen gone (HOME / screensaver): never keep decoding & playing audio.
        torrentPlayer?.pause()
        // Surface still holds the last frame — refresh the Continue card art.
        if (nativeTorrentPlayerActive) captureTorrentFrame("tv_off")
        // The process may die in the background; keep the resume point current.
        saveTorrentResumePoint()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The window regaining focus is where Android restores a previously-shown IME.
        // Unless the user is actually in the address field right now, it goes away.
        if (hasFocus &&
            !binding.homeUrlInput.hasFocus() &&
            !binding.browseUrlEdit.hasFocus()
        ) {
            dismissHomeKeyboard()
        }
    }

    override fun onResume() {
        super.onResume()
        compatSession?.onResume()
        // Recover pointer frame-loop / scroll after screensaver or ~1min idle.
        webHost?.onForeground()
    }

    /**
     * Home surface has two states: first-run / nothing watched (faded K mark +
     * address line with the IME up) and Continue watching (Netflix-style card
     * for the last played title). adjustResize keeps the centered group clear
     * of the keyboard.
     */
    private fun hydrateContinuitySurface() {
        latestCheckpoint = continuityStore.load()
        // Recents are capped at 5; whatever fell off the end takes its artwork with it.
        pruneOrphanPosters()
        val cp = continuityStore.loadMedia()?.takeIf { !it.url.isNullOrBlank() }

        val favs = favouritesStore.list()
        binding.favsGroup.visibility = if (favs.isNotEmpty()) View.VISIBLE else View.GONE
        binding.favsRow.removeAllViews()
        favs.forEachIndexed { index, fav ->
            val roundel = buildFavRoundel(fav)
            roundel.alpha = 0f
            roundel.translationY = 10f * resources.displayMetrics.density
            binding.favsRow.addView(roundel)
            roundel.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(50L * index)
                .setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        // Recently played titles as a scrollable row (falls back to the single latest
        // media checkpoint when no recents list has accrued yet). The length is the
        // store's to decide — a second `.take(5)` here silently overrode it, so raising
        // the cap in ContinuityStore alone would have changed nothing on screen.
        val recents = continuityStore.loadRecents().ifEmpty { listOfNotNull(cp) }
        val hasContinue = recents.isNotEmpty()

        binding.continueRow.removeAllViews()
        binding.continueScroll.scrollTo(0, 0)
        recents.forEachIndexed { index, item ->
            val card = buildContinueCard(item)
            card.alpha = 0f
            card.translationY = 12f * resources.displayMetrics.density
            binding.continueRow.addView(card)
            card.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L * favs.size + 45L * index)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        val library = hydrateDownloadedRow()
        val hasContent = hasContinue || favs.isNotEmpty() || library
        binding.continueGroup.visibility = if (hasContinue) View.VISIBLE else View.GONE
        binding.homeCenterGroup.visibility = if (hasContent) View.GONE else View.VISIBLE
        if (hasContinue) {
            binding.continueGroup.alpha = 0f
            binding.continueGroup.translationY = 14f * resources.displayMetrics.density
            binding.continueGroup.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L * favs.size)
                .setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        // requestFocus() alone opens the TV keyboard, so the guard has to sit here, not
        // just on the showKeyboard call below.
        if (!hasContent && !autoContinuePending) {
            binding.homeUrlInput.requestFocus()
            binding.homeUrlInput.post {
                // This runs a frame later, so the world may have moved on: a cold start
                // that auto-continues begins connecting to peers while the home shell is
                // still up, and dismissHomeKeyboard() has already run by the time this
                // fires — which is how the IME reappeared over a starting stream.
                // Losing focus is the authoritative signal that something else took over.
                val stillIdleOnHome = uiState == AppUiState.HOME &&
                    binding.homeShell.visibility == View.VISIBLE &&
                    binding.homeUrlInput.hasFocus() &&
                    torrentRequestId == null &&
                    !torrentOverlayVisible &&
                    !nativeTorrentPlayerActive
                if (stillIdleOnHome) showKeyboard(binding.homeUrlInput)
            }
            return
        }
        if (!hasContinue) {
            // Favourites exist but nothing to continue — land focus on the first roundel.
            binding.favsRow.getChildAt(0)?.requestFocus()
            return
        }
        (binding.continueRow.getChildAt(0) as? android.view.ViewGroup)?.getChildAt(0)?.requestFocus()
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "continuity_surface_shown",
                url = recents.first().url,
                detail = "count=${recents.size}",
            ),
        )
    }

    /** One card (poster + progress + title) per recent title, added to `continueRow`. */
    /**
     * Starred titles kept on the box. Completed ones play straight off local storage —
     * no swarm, no network — while in-progress ones show their download percentage and
     * are not playable yet.
     *
     * @return true when the row has anything in it.
     */
    private fun hydrateDownloadedRow(): Boolean {
        // reconcile() and resumeInterruptedDownloads() used to run here. Both are
        // once-per-session jobs — one rewrites the index, the other starts a service —
        // and this method is also the repaint path for a one-second progress ticker, so
        // they were running every second for as long as any record claimed to be
        // downloading. They now run from onCreate, once.
        val entries = libraryStore.list()
        downloadedRowSignature = rowSignature(entries)
        downloadedCardViews.clear()
        binding.downloadedRow.removeAllViews()
        binding.downloadedScroll.scrollTo(0, 0)
        binding.downloadedGroup.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isEmpty()) return false
        entries.forEach { entry ->
            val complete = entry.state == com.keenzero.app.library.StarredLibraryStore.State.COMPLETE
            val name = prettyMediaTitle(entry.title) ?: entry.title
            val label = if (complete) name else "$name · Downloading"
            // Finished titles carry no chip at all: the card and its name are the whole
            // story, exactly as asked. Anything unfinished says so, in figures.
            val chip = chipTextFor(entry)
            val card = buildMediaCard(
                titleText = label,
                posterUrl = "frame:${entry.key}",
                // Finished titles show nothing extra — no bar, no tick, no badge; the
                // card and its name are the whole story. The bar means "still working".
                fraction = if (complete) 0f else entry.progress,
                scroll = binding.downloadedScroll,
                onClick = {
                    // An unfinished title cannot be played, so OK offers the only action
                    // that makes sense on it rather than a toast that leads nowhere.
                    if (complete) playLibraryEntry(entry) else confirmRemoveLibraryEntry(entry)
                },
                badge = chip,
                onLongClick = { confirmRemoveLibraryEntry(entry) },
            )
            binding.downloadedRow.addView(card)
            @Suppress("UNCHECKED_CAST")
            val chipView = card.getTag(R.id.downloaded_chip_tag) as? android.widget.TextView
            val fillView = card.getTag(R.id.downloaded_fill_tag) as? View
            if (chipView != null && fillView != null) {
                downloadedCardViews[entry.key] = chipView to fillView
            }
            // Backfill art for titles that finished in an earlier session, or whose grab
            // failed at completion time. The capture is keyed and cached on disk, so this
            // decodes once per title and is a no-op on every later render.
            if (complete && entry.mediaPath != null &&
                !java.io.File(filesDir, "continue/" + frameFileName("frame:${entry.key}")).exists()
            ) {
                captureLibraryPoster(entry.mediaPath, entry.key)
            }
        }
        // Live figures without waiting on a cross-process broadcast to arrive.
        binding.root.removeCallbacks(downloadProgressTicker)
        if (entries.any {
                it.state == com.keenzero.app.library.StarredLibraryStore.State.DOWNLOADING
            }
        ) {
            binding.root.postDelayed(downloadProgressTicker, DOWNLOAD_TICK_MS)
        }
        return true
    }

    /**
     * Repaint just the Downloaded row. The full surface rebuild re-runs every entry
     * animation, which on a 2-second progress tick would make the home screen twitch.
     */
    private fun refreshDownloadedRowProgress() {
        if (binding.downloadedGroup.visibility != View.VISIBLE) {
            hydrateDownloadedRow()
            return
        }
        hydrateDownloadedRow()
    }

    /**
     * Chip text for an unfinished download: "12% · 1.4 MB/s".
     *
     * Speed is dropped when the swarm is idle, so a stalled download reads as a plain
     * percentage rather than claiming "0 KB/s". A finished title gets no chip at all.
     */
    /**
     * The chip a card should be showing, or null for a finished title, which carries none.
     *
     * Shared by the build and the once-a-second in-place update. Those two had their own
     * copies of the decision, and only the build path knew about states: the tick then
     * relabelled every card with a percentage, so a queued download's "Queued" chip turned
     * into "0%" one second after it appeared, and a failed one lost its warning entirely.
     */
    private fun chipTextFor(
        entry: com.keenzero.app.library.StarredLibraryStore.Entry,
    ): String? = when (entry.state) {
        com.keenzero.app.library.StarredLibraryStore.State.COMPLETE -> null
        com.keenzero.app.library.StarredLibraryStore.State.FAILED ->
            getString(R.string.library_failed_chip)
        com.keenzero.app.library.StarredLibraryStore.State.QUEUED ->
            getString(R.string.library_queued_chip)
        else -> downloadChipText(entry)
    }

    private fun downloadChipText(
        entry: com.keenzero.app.library.StarredLibraryStore.Entry,
    ): String {
        val pct = (entry.progress * 100).toInt()
        val bps = entry.speedBps
        if (bps <= 0L) return "$pct%"
        val speed = if (bps >= 1024L * 1024L) {
            String.format(java.util.Locale.US, "%.1f MB/s", bps / 1048576.0)
        } else {
            String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024.0)
        }
        return "$pct% · $speed"
    }

    /**
     * Update the Downloaded row's percentages without rebuilding it.
     *
     * The service ticks every two seconds while a download runs, so the figure has to
     * move on its own with no navigation or refresh. Rebuilding the row for each tick
     * would restart every card's entry animation and drop D-pad focus, so only the chip
     * text and the bar width are touched; a full rebuild happens only when a title
     * finishes, fails, or appears, which changes what the cards are.
     */
    private fun applyDownloadProgress() {
        val entries = libraryStore.list()
        // Rebuild when the *shape* of the row changes — a title added or removed, or one
        // that has changed state — not merely because a finished title is present.
        //
        // The old test rebuilt whenever any entry was COMPLETE or FAILED, which is a
        // standing condition rather than an event: with one finished download in the
        // library every tick tore the row down and rebuilt it, re-running the entrance
        // animations and re-decoding every poster once a second.
        // The signature is set by hydrateDownloadedRow itself, so it always describes what
        // is actually on screen and is the only test needed here.
        //
        // This used to also rebuild whenever an entry was missing from
        // downloadedCardViews. That map only holds cards that *have* a chip, and a finished
        // title deliberately has none — so a library containing one completed download
        // failed the test on every tick and tore the row down once a second for ever. Each
        // rebuild re-decoded the posters and restarted their fade-in, which is the pulsing
        // between black and full colour, and it threw away D-pad focus with it.
        if (rowSignature(entries) != downloadedRowSignature) {
            hydrateDownloadedRow()
            return
        }
        entries.forEach { entry ->
            val (chip, fill) = downloadedCardViews[entry.key] ?: return@forEach
            chipTextFor(entry)?.let { chip.text = it }
            (fill.layoutParams as? android.widget.LinearLayout.LayoutParams)?.also {
                it.weight = entry.progress
                fill.layoutParams = it
            }
        }
    }

    /**
     * Restart any download that was interrupted rather than finished.
     *
     * The download service runs in its own process, and that process dies with an app
     * update, a low-memory kill or a reboot. Nothing restarted it, so a record sat in
     * DOWNLOADING for ever showing the percentage it had reached when the process went
     * away, with no speed because nothing was writing to it any more.
     *
     * Restarting is cheap and safe: the service ignores a request for the download it is
     * already running, and libtorrent rechecks the pieces already on disk, so a resumed
     * download picks up where it stopped instead of starting over. Runs once per activity
     * start, not on every repaint.
     */
    private fun resumeInterruptedDownloads() {
        if (resumedDownloadsThisSession) return
        resumedDownloadsThisSession = true
        val pending = libraryStore.list()
            .filter {
                it.state == com.keenzero.app.library.StarredLibraryStore.State.DOWNLOADING ||
                    it.state == com.keenzero.app.library.StarredLibraryStore.State.QUEUED
            }
            .filter { it.origin.isNotBlank() }
        // The service downloads one title at a time and switches to whichever key it was
        // last handed, so starting them all made each one cancel the one before it. Run
        // the most recent and mark the rest queued, so their cards say "Queued" instead
        // of showing a frozen percentage that looks like a stalled download.
        pending.drop(1).forEach {
            libraryStore.update(
                it.key,
                state = com.keenzero.app.library.StarredLibraryStore.State.QUEUED,
                speedBps = 0L,
            )
        }
        pending.take(1)
            .forEach { entry ->
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "library_download_resume",
                        url = entry.origin,
                        detail = "key=${entry.key} at=${(entry.progress * 100).toInt()}%",
                    ),
                )
                startService(
                    Intent(this, com.keenzero.app.library.LibraryDownloadService::class.java)
                        .setAction(com.keenzero.app.library.LibraryDownloadService.ACTION_START)
                        .putExtra(
                            com.keenzero.app.library.LibraryDownloadService.EXTRA_ORIGIN,
                            entry.origin,
                        )
                        .putExtra(com.keenzero.app.library.LibraryDownloadService.EXTRA_KEY, entry.key)
                        .putExtra(
                            com.keenzero.app.library.LibraryDownloadService.EXTRA_DIR,
                            libraryStore.dirFor(entry.key).apply { mkdirs() }.absolutePath,
                        ),
                )
            }
    }

    /**
     * Play a finished download from local storage.
     *
     * Deliberately does NOT go through the torrent service: the file is complete on
     * disk, so re-adding it to a swarm would put this box back online for a title it has
     * already finished — the exact thing starring is supposed to stop.
     */
    private fun playLibraryEntry(entry: com.keenzero.app.library.StarredLibraryStore.Entry) {
        val path = entry.mediaPath ?: return
        val file = java.io.File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.library_missing_file, Toast.LENGTH_LONG).show()
            libraryStore.remove(entry.key)
            hydrateContinuitySurface()
            return
        }
        dismissHomeKeyboard()
        torrentPlaybackStarted = true
        torrentOriginKey = entry.key
        torrentOriginLabel = entry.origin
        torrentTitle = entry.title
        torrentMediaPath = path
        binding.homeShell.visibility = View.GONE
        uiState = AppUiState.NATIVE_OVERLAY
        recordEvent(
            NavigationEvent(System.currentTimeMillis(), "library_play", url = entry.origin),
        )
        showNativeTorrentPlayer(android.net.Uri.fromFile(file).toString(), entry.title)
    }

    /** Continue-watching card. Thin wrapper so both home rows share one construction. */
    private fun buildContinueCard(cp: ContinuityCheckpoint): View {
        val fraction = if (cp.durationSec > 0) {
            (cp.playbackPositionSec / cp.durationSec).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        return buildMediaCard(
            titleText = prettyMediaTitle(cp.title) ?: cp.contentId
                ?: getString(R.string.continue_unknown_title),
            posterUrl = cp.posterUrl,
            fraction = fraction,
            scroll = binding.continueScroll,
            onClick = { resumeCheckpoint(cp) },
            onLongClick = { confirmRemoveRecent(cp) },
        )
    }

    /**
     * One card: art, progress bar, title, focus border and row auto-scroll.
     *
     * Shared by Continue watching and Downloaded so the two rows are the same object
     * with different data, rather than a copy that drifts the first time one is styled.
     */
    private fun buildMediaCard(
        titleText: String,
        posterUrl: String?,
        fraction: Float,
        scroll: android.widget.HorizontalScrollView,
        onClick: () -> Unit,
        /** Optional status chip drawn on the artwork, e.g. a download percentage. */
        badge: String? = null,
        /** Long-press OK: the Android TV convention for per-card actions. */
        onLongClick: (() -> Unit)? = null,
    ): View {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(16) }
            clipChildren = false
            clipToPadding = false
        }
        val card = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(190), dp(107))
            background = ContextCompat.getDrawable(this@KeenActivity, R.drawable.continue_card_bg)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            isFocusable = true
            isFocusableInTouchMode = true
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            foreground = focusBorder(cornerDp = 10f, oval = false)
        }
        val poster = android.widget.ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val fallback = android.widget.ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(88), dp(72)).apply {
                gravity = android.view.Gravity.CENTER
            }
            alpha = 0.22f
            setImageResource(R.drawable.keen_mark)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val track = android.widget.LinearLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(4),
            ).apply { gravity = android.view.Gravity.BOTTOM }
            setBackgroundColor(0x33FFFFFF)
            orientation = android.widget.LinearLayout.HORIZONTAL
            weightSum = 1f
        }
        val fill = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { weight = 0f }
            setBackgroundColor(0xE6FFFFFF.toInt())
        }
        track.addView(fill)
        card.addView(poster); card.addView(fallback); card.addView(track)
        var badgeView: android.widget.TextView? = null
        if (badge != null) {
            // Readable at a glance from the sofa: a solid chip on the artwork, not a
            // suffix buried at the end of the title.
            badgeView = android.widget.TextView(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        topMargin = dp(8); marginStart = dp(8)
                    }
                    text = badge
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    typeface = googleSansBold
                setPadding(dp(8), dp(3), dp(8), dp(3))
                setBackgroundColor(0xCC000000.toInt())
            }
            card.addView(badgeView)
        }
        container.setTag(R.id.downloaded_chip_tag, badgeView)
        container.setTag(R.id.downloaded_fill_tag, fill)
        val title = android.widget.TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(190), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@KeenActivity, R.color.keen_muted))
            textSize = 15f
            alpha = 0.75f
            typeface = googleSansMedium
            text = titleText
        }
        container.addView(card); container.addView(title)

        card.setOnClickListener { onClick() }
        if (onLongClick != null) {
            card.setOnLongClickListener {
                onLongClick()
                true
            }
        }
        card.setOnFocusChangeListener { v, hasFocus ->
            (v.foreground as? com.keenzero.app.home.BorderDrawable)
                ?.animateTo(hasFocus, FOCUS_BORDER_WIDTH_DP * resources.displayMetrics.density)
            // Focus means white, not "the same grey but brighter".
            title.setTextColor(
                if (hasFocus) android.graphics.Color.WHITE
                else ContextCompat.getColor(this@KeenActivity, R.color.keen_muted),
            )
            title.animate().alpha(if (hasFocus) 1f else 0.75f).setDuration(160).start()
            if (hasFocus) v.post {
                // Keep the focused card fully in view with a little breathing room,
                // scrolling the minimum needed (reliable across the whole row).
                val sv = scroll
                val pad = dp(24)
                val left = container.left
                val right = left + container.width
                if (right + pad > sv.scrollX + sv.width) {
                    sv.smoothScrollTo(right + pad - sv.width, 0)
                } else if (left - pad < sv.scrollX) {
                    sv.smoothScrollTo((left - pad).coerceAtLeast(0), 0)
                }
            }
        }

        android.animation.ValueAnimator.ofFloat(0f, fraction).apply {
            duration = 700
            startDelay = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                (fill.layoutParams as android.widget.LinearLayout.LayoutParams).also {
                    it.weight = anim.animatedValue as Float
                    fill.layoutParams = it
                }
            }
        }.start()
        loadPosterInto(posterUrl, poster, fallback)
        return container
    }

    /**
     * Google Sans, subset to Latin.
     *
     * The full family is ~2 MB per weight because it carries Cyrillic, Greek and the
     * extended Latin ranges; Keen's chrome is ASCII plus a handful of punctuation marks,
     * so the shipped files are subset down to ~44 KB each. Views built in XML pick this
     * up from the theme's widget styles; views built in code have to be told.
     */
    private val googleSansBold by lazy {
        androidx.core.content.res.ResourcesCompat.getFont(this, R.font.google_sans_bold)
    }
    private val googleSansMedium by lazy {
        androidx.core.content.res.ResourcesCompat.getFont(this, R.font.google_sans_medium)
    }

    /**
     * Sit the home screen's K exactly on the address text: its ink spans the same top
     * and bottom as an 'h' in the field beside it.
     *
     * Done in code, from the field's own font metrics, rather than as a hand-measured
     * margin in the layout. The margin approach was correct three times and wrong three
     * times, because the row is centred and every change to the field — the typeface,
     * the text size, `includeFontPadding`, the caret — moves the text within it and
     * takes the mark out of alignment again. Reading the metrics means the mark follows
     * the text wherever it goes, and there is nothing left to re-measure.
     *
     * Position is applied as a translation, not a margin, so it cannot feed back into
     * the layout pass that produced it.
     */
    private fun alignHomeMarkToAddressText() {
        val field = binding.homeUrlInput
        val mark = binding.homeBarLogo
        val frame = mark.parent as? View ?: return
        val art = mark.drawable ?: return
        if (field.height == 0 || art.intrinsicHeight <= 0) return
        val baseline = field.baseline
        if (baseline < 0) return

        // The 'E' specifically: cap height, so the mark reads as one of the capitals in
        // the line beside it rather than as a smaller glyph tucked in front of them.
        val ink = android.graphics.Rect()
        field.paint.getTextBounds("E", 0, 1, ink)
        // 4% over the measured cap height: the mark's own artwork carries a hair of
        // transparent margin, so matching the raw number left it reading a touch short
        // of the E beside it.
        val targetHeight = Math.round(ink.height() * 1.04f)
        if (targetHeight <= 0) return
        val aspect = art.intrinsicWidth.toFloat() / art.intrinsicHeight.toFloat()
        val targetWidth = Math.round(targetHeight * aspect)

        var resized = false
        mark.layoutParams?.let { lp ->
            if (lp.width != targetWidth || lp.height != targetHeight) {
                lp.width = targetWidth
                lp.height = targetHeight
                mark.layoutParams = lp
                resized = true
            }
        }
        // A resize invalidates the offsets below; the layout it triggers calls back here.
        if (resized) return

        val inkTopInField = baseline + ink.top   // ink.top is negative: above the baseline
        // Split the 4% overshoot above and below the cap so the mark stays centred on
        // the E rather than hanging below the baseline.
        val overshoot = (targetHeight - ink.height()) / 2f
        frame.translationY = (field.top + inkTopInField - frame.top) - overshoot
    }

    /** Focus-border drawable at 50% white, used as an animated foreground cue. */
    private fun focusBorder(cornerDp: Float, oval: Boolean) =
        com.keenzero.app.home.BorderDrawable(
            // Fully opaque. At 128 alpha this composited against the black home screen
            // to mid-grey, so the focused tile never looked focused.
            android.graphics.Color.argb(255, 255, 255, 255),
            cornerDp * resources.displayMetrics.density,
            oval,
        )

    /**
     * One saved site per tile, added to `favsRow` in code.
     *
     * A word on a dark slab, and nothing else. The roundels this replaced carried a
     * favicon, a letter fallback, a focus ring, a halo and a caption — five things to
     * say "wikipedia", most of them fetched over the network and none of them legible
     * from a sofa. The site's own name, set in the app's typeface, is the whole design.
     *
     * The name is the registrable label alone: `www.example.com` reads as `example`.
     * That is the part a person actually calls the site.
     */
    private fun buildFavRoundel(fav: com.keenzero.app.favourites.FavouritesStore.Fav): View {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val name = siteName(fav.host.ifBlank { fav.label })

        val label = android.widget.TextView(this).apply {
            text = name
            textSize = 17f
            typeface = googleSansMedium
            setTextColor(ContextCompat.getColor(this@KeenActivity, R.color.keen_text))
            maxLines = 1
            // No ellipsis: a name longer than the tile is cut by the fade below, which
            // is the point — "wikipedi…" announces the truncation, a fade just lets the
            // word run out of room.
            ellipsize = null
            includeFontPadding = false
            setSingleLine(true)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // Centring the text box is not the same as centring the text: the font leaves
        // more room above the caps than below the baseline, so the word sat low in the
        // tile and the chip looked bottom-heavy. Centre the ink instead.
        run {
            val capInk = android.graphics.Rect()
            label.paint.getTextBounds("E", 0, 1, capInk)
            val fm = label.paint.fontMetrics
            val above = -fm.ascent - capInk.height()
            val below = fm.descent
            label.translationY = -(above - below) / 2f
        }
        // The tile is exactly as wide as the first FAV_NAME_CHARS characters of this
        // name in this font, so every tile is sized by its own text rather than to a
        // guessed constant that would clip some names and pad others.
        val visibleWidth = label.paint.measureText(name.take(FAV_NAME_CHARS)).toInt()
        val overflows = name.length > FAV_NAME_CHARS

        val tileBorder = focusBorder(cornerDp = FAV_TILE_CORNER_DP, oval = false)
        val tile = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                visibleWidth + dp(FAV_TILE_PAD_DP) * 2,
                dp(FAV_TILE_HEIGHT_DP),
            ).apply { marginEnd = dp(12) }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = FAV_TILE_CORNER_DP * resources.displayMetrics.density
                setColor(FAV_TILE_BG)
            }
            foreground = tileBorder
            clipToPadding = false
            setPadding(dp(FAV_TILE_PAD_DP), 0, dp(FAV_TILE_PAD_DP), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                label,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        if (overflows) {
            // The cut is hidden by fading the tile's own colour back in over the last of
            // the text. Android's built-in fading edge fades to transparent, which on
            // this tile would reveal the black page behind it and read as a hole rather
            // than as a word running off the edge — hence an explicit gradient in the
            // tile's colour, with the right-hand corners matched so it never paints
            // square over the rounded edge.
            val radius = FAV_TILE_CORNER_DP * resources.displayMetrics.density
            val fade = View(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    dp(FAV_FADE_WIDTH_DP),
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.END,
                )
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(FAV_TILE_BG and 0x00FFFFFF, FAV_TILE_BG),
                ).apply {
                    cornerRadii = floatArrayOf(
                        0f, 0f, radius, radius, radius, radius, 0f, 0f,
                    )
                }
            }
            // Added to the frame, not the padded content, so it covers the tile's right
            // padding too — otherwise the text reappears in the gap past the gradient.
            tile.addView(fade)
            tile.clipChildren = true
        }
        tile.setOnClickListener { openNavigation(fav.url) }
        tile.setOnLongClickListener {
            confirmRemoveFavourite(fav)
            true
        }
        tile.setOnFocusChangeListener { _, hasFocus ->
            tileBorder.animateTo(hasFocus, FOCUS_BORDER_WIDTH_DP * resources.displayMetrics.density)
            label.animate().alpha(if (hasFocus) 1f else 0.82f).setDuration(160).start()
        }
        label.alpha = 0.82f
        return tile
    }

    /**
     * The name a person would use for a host: `www.example.com` → `example`,
     * `en.wikipedia.org` → `wikipedia`, `bbc.co.uk` → `bbc`.
     *
     * That last case is why this is not simply "the second-to-last label": under a
     * two-part public suffix that rule returns `co`. The common ones are listed rather
     * than pulling in a full public-suffix list for a home-screen caption.
     */
    private fun siteName(host: String): String {
        val clean = host.trim().removePrefix("www.").trimEnd('.')
        val parts = clean.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return clean.ifBlank { host }
        val lastTwo = parts.takeLast(2).joinToString(".")
        val suffixLabels = if (lastTwo in TWO_PART_SUFFIXES) 2 else 1
        return parts.getOrNull(parts.size - suffixLabels - 1) ?: parts.first()
    }

    private fun continueFromCheckpoint() {
        val cp = continuityStore.load() ?: return
        resumeCheckpoint(cp)
    }

    private fun resumeCheckpoint(cp: ContinuityCheckpoint) {
        val url = cp.url ?: return
        // Leaving home: drop the address field's focus first. It is the only
        // focusable-in-touch-mode view on this surface, so as the card animates away
        // focus falls back into it and Android helpfully opens the IME over playback.
        dismissHomeKeyboard()
        // Torrent resume: openNavigation routes magnets into the torrent
        // pipeline; the playhead comes from TorrentResumeStore (info-hash keyed).
        if (url.startsWith("magnet:?", ignoreCase = true)) {
            supervisor.resetCrashLoopForUserAction()
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "continuity_torrent_restore",
                    url = url,
                    detail = "pos=${cp.playbackPositionSec}",
                ),
            )
            startTorrentStreaming(url)
            return
        }
        if (!cp.requiresMediaRestore()) {
            pendingRestore = null
            restoreMetricEmitted = false
            supervisor.resetCrashLoopForUserAction()
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "continuity_browsing_restore",
                    url = cp.url,
                    detail = "journey=${cp.journeyState ?: "BROWSING"}",
                ),
            )
            openUrl(cp.url!!, restore = false)
            return
        }
        pendingRestore = cp
        restoreMetricEmitted = false
        supervisor.resetCrashLoopForUserAction()
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
        // Undo of the seed below. Demo content that cannot be removed is not mock
        // content, it is a mess left in someone's real library — so the seed and its
        // reversal are defined together, from one list.
        // Favourites are the user's own data, and a demo capture needs a different set
        // on screen than theirs. Dump-then-restore rather than edit-in-place: the dump is
        // what makes the change reversible, so it exists before anything is removed.
        if (intent.getBooleanExtra(EXTRA_LAB_FAVS_DUMP, false)) {
            android.util.Log.i(
                "KeenFavs",
                "favs=" + favouritesStore.list().joinToString(",") { it.host + "|" + it.url },
            )
            return
        }
        intent.getStringExtra(EXTRA_LAB_FAVS_REMOVE)?.takeIf { it.isNotBlank() }?.let { hosts ->
            val removed = favouritesStore.removeHosts(hosts.split(",").map { it.trim() }.toSet())
            android.util.Log.i("KeenFavs", "removed=$removed")
            binding.root.post { hydrateContinuitySurface() }
            return
        }
        intent.getStringExtra(EXTRA_LAB_FAVS_ADD)?.takeIf { it.isNotBlank() }?.let { urls ->
            urls.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { url ->
                if (!favouritesStore.isFavourite(url)) favouritesStore.toggle(url)
            }
            binding.root.post { hydrateContinuitySurface() }
            return
        }
        if (intent.getBooleanExtra(EXTRA_LAB_UI_PREVIEW_CLEAR, false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_ui_preview_clear"))
            UI_PREVIEW_SITES.forEach { url ->
                if (favouritesStore.isFavourite(url)) favouritesStore.toggle(url)
            }
            // Restore FIRST, then purge. The stash is a snapshot of whatever was in the row
            // when the demo began, which on a re-seed is itself demo content — restoring it
            // after the purge put those cards straight back. Purging afterwards cleans the
            // restored list too, so the row ends up with the user's titles and nothing else.
            continuityStore.restoreRealState()
            continuityStore.removeByContentId(UI_PREVIEW_CONTENT_IDS)
            // remove() drops the record and deletes the title's directory, so the seeded
            // downloads leave nothing behind — not a stub dir, not a cached poster.
            // Lift the filter before removing, so remove() is working against the real set.
            libraryStore.setDemoFilter(null)
            uiPreviewLibrary().forEach { libraryStore.remove(it.key) }
            // Anything else a demo left in Continue watching, matched on title. The
            // recents list is capped, so a demo that plays real media costs the user
            // real history slots; this is how those are handed back.
            intent.getStringExtra(EXTRA_LAB_CLEAR_TITLE)?.takeIf { it.isNotBlank() }?.let { needle ->
                fun matches(t: String?) = t?.contains(needle, ignoreCase = true) == true
                continuityStore.saveRecents(
                    continuityStore.loadRecents().filterNot { matches(it.title) },
                )
                // The recents list is only what the home screen *shows*. Cold start
                // resumes from the media/browsing checkpoints, which are separate — so
                // clearing the row alone left the box re-entering the demo stream on
                // every launch and buffering it over the user's connection.
                val ids = setOfNotNull(
                    continuityStore.loadMedia()?.takeIf { matches(it.title) }?.contentId,
                    continuityStore.load()?.takeIf { matches(it.title) }?.contentId,
                )
                if (ids.isNotEmpty()) continuityStore.removeByContentId(ids)
                continuityStore.markAtHome(true)
            }
            binding.root.post { hydrateContinuitySurface() }
            return
        }
        if (intent.getBooleanExtra(EXTRA_LAB_UI_PREVIEW, false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_ui_preview"))
            UI_PREVIEW_SITES
                .forEach { url -> if (!favouritesStore.isFavourite(url)) favouritesStore.toggle(url) }
            // The seed takes the Continue row over completely, so the real one is parked
            // first and handed back by the clear path.
            continuityStore.stashRealState()
            val previewRecents = uiPreviewRecents()
            // save() upserts the checkpoint into recents itself, so it runs first and
            // saveRecents() then states the whole row — otherwise the newest title would
            // appear twice, once from each writer.
            continuityStore.save(previewRecents.first(), force = true)
            continuityStore.saveRecents(previewRecents)
            // Hides the user's own downloads for the duration of the demo without moving a
            // byte of them; the clear path lifts it.
            libraryStore.setDemoFilter(UI_PREVIEW_LIBRARY_PREFIX)
            uiPreviewLibrary().forEach { entry ->
                // reconcile() drops a COMPLETE record whose directory has gone, on the
                // grounds that finished media lives on disk. The seed has no media, so the
                // directory has to exist or the finished card vanishes on the next launch.
                if (entry.state == com.keenzero.app.library.StarredLibraryStore.State.COMPLETE) {
                    libraryStore.dirFor(entry.key).mkdirs()
                }
                libraryStore.put(entry)
            }
            installPreviewArtwork()
            // This is a home-surface preview, not a real session — do not let the
            // cold-start auto-resume check (below, in onCreate) navigate into it.
            continuityStore.markAtHome(true)
            binding.root.post {
                hydrateContinuitySurface()
                if (intent.getBooleanExtra(EXTRA_LAB_UI_PREVIEW_SPINNER, false)) {
                    binding.root.postDelayed({
                        showTorrentOverlay()
                        // Simulate a real buffering sweep — bloom, spin through several
                        // ticks, then finish and collapse — so the "keep spinning until
                        // told to stop" contract can be eyeballed without a live session.
                        binding.root.postDelayed({
                            var pct = 0
                            val step = object : Runnable {
                                override fun run() {
                                    updateTorrentOverlay(
                                        stage = TorrentStreamingService.STAGE_BUFFERING,
                                        percent = pct,
                                        peers = 47,
                                        seeds = 31,
                                        speedBps = 1_400_000L,
                                    )
                                    pct += 8
                                    if (pct <= 100) {
                                        binding.root.postDelayed(this, 420L)
                                    } else {
                                        hideTorrentOverlayWithCollapse()
                                    }
                                }
                            }
                            step.run()
                        }, 1200L)
                    }, 500L)
                }
            }
            return
        }
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
        if (intent.getBooleanExtra(EXTRA_LAB_MOCK_LOADING, false)) {
            startMockLoadingOverlay()
            return
        }
        intent.getStringExtra(EXTRA_LAB_URL)
            ?.let(::normalizeUrl)
            ?.let { url ->
                val restore = intent.getBooleanExtra(EXTRA_LAB_RESTORE, false)
                if (url.startsWith("magnet:?", ignoreCase = true)) {
                    // Same route as the URL bar — magnets start the torrent
                    // pipeline, they are never a WebView document load.
                    startTorrentStreaming(url)
                } else if (restore) {
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

    /** Commit home address line (Enter/Go): navigate and fold the keyboard. */
    private fun commitHomeUrl() {
        val raw = binding.homeUrlInput.text?.toString()?.trim().orEmpty()
        val url = normalizeUrl(raw)
        if (url == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard(binding.homeUrlInput)
        binding.homeUrlInput.clearFocus()
        openNavigation(url)
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
        openNavigation(url)
        // Return focus to web/pointer after load starts.
        binding.browserContainer.post {
            webHost?.webView?.requestFocus()
        }
    }

    /**
     * Inline autocomplete for an address field, driven by [urlHistoryStore].
     *
     * The predicted tail is appended in grey with the **caret left in front of it**, so
     * typing "13" on a field that has seen 1337x.to shows `13|37x.to` — the user's own
     * text is white and live, the guess is grey and inert. From there OK on the keyboard
     * commits the whole line (the prediction), while carrying on typing simply extends
     * the real text and re-predicts, so a wrong guess never has to be deleted.
     *
     * That last part is why the ghost is deleted and re-added on every keystroke rather
     * than left in the buffer: the caret sits *before* it, so an insertion would
     * otherwise land in the middle and leave the stale tail stranded behind the new
     * character ("133|7x.to7x.to"). Each pass strips the previous prediction first,
     * leaving exactly what the user typed, and only then predicts again.
     */
    private fun attachUrlCompletion(field: android.widget.EditText) {
        field.addTextChangedListener(object : android.text.TextWatcher {
            /** Marks the predicted tail — also how it is found and removed next pass. */
            private val ghost = android.text.style.ForegroundColorSpan(
                androidx.core.content.ContextCompat.getColor(
                    this@KeenActivity,
                    R.color.keen_url_prediction,
                ),
            )

            /** Whether this change was an insertion; only insertions may predict. */
            private var inserting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Our own strip/append edits re-enter here; they must not be mistaken
                // for the user's intent.
                if (applyingUrlCompletion) return
                inserting = count > before
            }

            override fun afterTextChanged(s: android.text.Editable?) {
                if (applyingUrlCompletion) return
                val editable = s ?: return
                val wasInserting = inserting

                // Strip any prediction still in the buffer, leaving only typed text.
                val start = editable.getSpanStart(ghost)
                val end = editable.getSpanEnd(ghost)
                if (start in 0..end && end <= editable.length) {
                    applyingUrlCompletion = true
                    try {
                        editable.removeSpan(ghost)
                        if (end > start) editable.delete(start, end)
                    } finally {
                        applyingUrlCompletion = false
                    }
                }

                // Deleting means the user is rejecting or correcting; predicting again
                // here would make backspace look like it did nothing.
                if (!wasInserting) return
                val typed = editable.toString()
                val caret = field.selectionStart
                // Only when extending the end of the line. A caret in the middle means
                // the user is editing, not typing an address forward.
                if (caret != typed.length || field.selectionEnd != typed.length) return
                if (typed.isBlank() || typed.endsWith(" ")) return
                val suggestion = urlHistoryStore.suggest(typed) ?: return
                val typedPrefix = com.keenzero.app.history.UrlHistoryStore.typedPrefixOf(typed)
                if (!suggestion.startsWith(typedPrefix, ignoreCase = true)) return
                // Append only the missing tail, so the user's own capitalisation and any
                // "https://" they typed survive verbatim.
                val tail = suggestion.substring(typedPrefix.length)
                if (tail.isEmpty()) return
                applyingUrlCompletion = true
                try {
                    editable.append(tail)
                    // EXCLUSIVE_EXCLUSIVE: a character typed at the caret (the span's
                    // start boundary) stays outside the ghost and keeps the live colour.
                    editable.setSpan(
                        ghost,
                        typed.length,
                        typed.length + tail.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    // Caret stays where the user is typing — in front of the prediction.
                    android.text.Selection.setSelection(editable, typed.length)
                } finally {
                    applyingUrlCompletion = false
                }
            }
        })
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

    /** Fold the IME away and release the home address field's focus. */
    private fun dismissHomeKeyboard() {
        if (binding.homeUrlInput.hasFocus()) binding.homeUrlInput.clearFocus()
        hideKeyboard(binding.homeUrlInput)
        currentFocus?.let { hideKeyboard(it) }
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

    private fun openNavigation(url: String) {
        if (url.startsWith("magnet:?", ignoreCase = true)) {
            startTorrentStreaming(url)
        } else {
            openUrl(url)
        }
    }

    private fun startTorrentStreaming(magnet: String) {
        // Same reason as resumeCheckpoint: nothing should hand focus to the address
        // field while a torrent overlay or player is coming up.
        dismissHomeKeyboard()
        // Every magnet funnels through here, so this is where the per-stream
        // "has playback actually begun" latch resets.
        torrentPlaybackStarted = false
        startTorrentSession(originLabel = magnet) { intent ->
            intent.putExtra(TorrentStreamingService.EXTRA_MAGNET, magnet)
        }
    }

    private fun startTorrentFromFile(
        url: String,
        cookies: String?,
        userAgent: String?,
        base64: String?,
    ) {
        // The page already read the file for us (the only way past a Cloudflare
        // challenge — see WebViewHost.fetchTorrentInPage). Spool it to a scratch file
        // rather than an intent extra: a season pack's .torrent runs to hundreds of KB
        // and Binder's transaction buffer is 1 MB for the whole process. The service
        // deletes it the moment it has been decoded.
        val spooled = base64?.let { spoolTorrentFile(it) }
        // Same URL either way: it is the resume identity and the Continue card's label,
        // and it must not change depending on how the bytes happened to arrive.
        startTorrentSession(originLabel = url) { intent ->
            intent.putExtra(TorrentStreamingService.EXTRA_TORRENT_URL, url)
                .putExtra(TorrentStreamingService.EXTRA_TORRENT_FILE, spooled?.absolutePath)
                .putExtra(TorrentStreamingService.EXTRA_COOKIES, cookies)
                .putExtra(TorrentStreamingService.EXTRA_USER_AGENT, userAgent)
        }
    }

    /**
     * Park page-fetched .torrent bytes in a scratch file for the :torrent process.
     *
     * Written outside the torrent cache root, which the service wipes wholesale on
     * teardown — this file has to survive the `cleanup()` that starting a new session
     * runs before it reads anything.
     */
    private fun spoolTorrentFile(base64: String): java.io.File? = try {
        val dir = java.io.File(cacheDir, "torrent-files").apply { mkdirs() }
        // Nothing else clears this directory, and a fetch that never reaches the service
        // (start refused, process killed) would otherwise leave its file for good.
        dir.listFiles()?.forEach { it.delete() }
        java.io.File(dir, "${UUID.randomUUID()}.torrent").apply {
            writeBytes(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        }
    } catch (error: Throwable) {
        android.util.Log.w("KeenTorrent", "Could not spool .torrent; falling back to a native fetch", error)
        null
    }

    private fun startTorrentSession(originLabel: String, configure: (Intent) -> Intent) {
        setNavLoading(true)
        stopTorrentStreaming()
        continuityStore.markAtHome(false)
        val id = UUID.randomUUID().toString()
        torrentRequestId = id
        torrentOriginKey = com.keenzero.app.torrent.TorrentResumeStore.keyOf(originLabel)
        torrentOriginLabel = originLabel
        torrentTitle = null
        // Stale from the previous stream; only a picker choice sets it.
        torrentFileIndex = null
        // Entry from home / URL bar has no page under the overlay — bring up the
        // browse shell on a blank page. Entry from a site keeps the page visible
        // beneath the loading overlay so cancel returns exactly where the user was.
        // A compatibility-mode page counts as a page underneath. `webHost` is null while
        // compat mode is active (the normal host is destroyed on entry), so testing it
        // alone read "launched from home", built a blank normal WebView over the top and
        // loaded about:blank — that blank page was the black screen the user landed on
        // after backing out of a magnet opened from a compatibility-mode page.
        val compatPageUp = compatSession?.isActive == true &&
            binding.browseShell.visibility == View.VISIBLE
        if (!compatPageUp &&
            (webHost?.isCreated != true || binding.browseShell.visibility != View.VISIBLE)
        ) {
            currentUrl = originLabel
            lastChromeUrl = originLabel
            binding.homeShell.visibility = View.GONE
            binding.browseShell.visibility = View.VISIBLE
            binding.browserContainer.visibility = View.VISIBLE
            binding.chromeBar.visibility = View.VISIBLE
            refreshBrowseChrome()
            ensureWebHost().load("about:blank")
        }
        showTorrentOverlay()
        // Where playback will actually begin, as a fraction of the file. Without this the
        // service buffers the HEAD of the file and reports 99%, then the player seeks to
        // the resume point and has to wait all over again for pieces nobody fetched —
        // which is the long black screen after a part-watched title.
        val resumeKey = torrentOriginKey
        val resumeFraction = resumeKey?.let { key ->
            val pos = torrentResumeStore.positionMs(key)
            val dur = torrentResumeStore.durationMs(key)
            if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 0.98f) else 0f
        } ?: 0f
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "torrent_start",
                url = originLabel,
                detail = "resumeFraction=$resumeFraction",
            ),
        )
        // Foreground service: the :torrent process must survive the cached-app
        // freezer for streams longer than ~30 min.
        ContextCompat.startForegroundService(
            this,
            configure(
                Intent(this, TorrentStreamingService::class.java)
                    .setAction(TorrentStreamingService.ACTION_START)
                    .putExtra(TorrentStreamingService.EXTRA_REQUEST_ID, id)
                    .putExtra(TorrentStreamingService.EXTRA_RESUME_FRACTION, resumeFraction),
            ),
        )
    }

    private fun stopTorrentStreaming() {
        stopService(Intent(this, TorrentStreamingService::class.java))
        torrentRequestId = null
        // The service deletes the cache on stop; a stale path would let a later grab
        // decode another title's file (or a half-deleted one) into this card.
        torrentMediaPath = null
        hideTorrentOverlay()
        hideNativeTorrentPlayer()
    }

    /** True while the native ExoPlayer surface owns the screen. */
    private val nativeTorrentPlayerActive: Boolean
        get() = binding.torrentPlayerContainer.visibility == View.VISIBLE

    /**
     * Torrent playback is native, not a WebView page: the WebView video stack has
     * no E-AC-3/DTS decoders (silent playback), while ExoPlayer reaches the
     * platform MediaCodec audio decoders. The source page stays loaded beneath.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showNativeTorrentPlayer(streamUrl: String, title: String) {
        hideNativeTorrentPlayer()
        // The bridge blocks range reads until pieces arrive; a slow swarm can
        // stall reads far past the 8 s default before data flows again.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(TORRENT_HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(TORRENT_HTTP_TIMEOUT_MS)
        // Default buffering (50 s, ~26 MB) is only a few seconds of a high-bitrate remux,
        // so a big film played, ran dry, rebuffered after 5 s, ran dry again. Bank far
        // more time, and — crucially — refuse to resume after a stall until there is a
        // real cushion, which is what breaks the cut-out/retry cycle. Byte cap stays
        // modest and authoritative (prioritizeTimeOverSizeThresholds = false): the device
        // has a 256 MB heap ceiling, so the deep buffer lives on disk in the torrent's
        // read-ahead window (TorrentHttpBridge.READAHEAD_BYTES), not in the player.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                TORRENT_MIN_BUFFER_MS,
                TORRENT_MAX_BUFFER_MS,
                TORRENT_BUFFER_FOR_PLAYBACK_MS,
                TORRENT_BUFFER_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(TORRENT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // Wraps the HTTP factory so the same player serves both sources: the bridge's
        // http://127.0.0.1 stream while a torrent is live, and a file:// path once a
        // starred title is finished and playing off local storage.
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
        torrentPlayer = player
        torrentFirstFrameShown = false
        torrentRenderedFirstFrame = false
        revealMotionCheckPending = false
        // Turn on English subtitles by default whenever the media carries them —
        // preferring an "en"-tagged text track, and falling back to an untagged
        // one (common in torrent MKVs where the English subs have no language tag).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(true)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        player.addListener(object : Player.Listener {
            /**
             * Buffering hitting 100% is not the same as the film being on screen.
             *
             * The overlay used to collapse the moment ACTION_READY arrived, but the
             * player still has to open the bridge and read the container's index — on an
             * mkv the cues sit at the end of the file, so it issues a long range read
             * before it can decode anything. That left ~20 s of pure black after the
             * progress bar said done, which reads as a hang. Hold the indicator until a
             * frame is actually on screen.
             */
            override fun onRenderedFirstFrame() {
                torrentRenderedFirstFrame = true
                considerRevealingPicture()
            }

            override fun onPlayerError(error: PlaybackException) {
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "torrent_player_error",
                        url = streamUrl,
                        detail = "${error.errorCodeName}: ${error.message} retry=$torrentPlayerRetries",
                    ),
                )
                // A stream fed by a swarm hiccups: a peer drops, a read blocks, the
                // bridge stumbles. Ending the film on the first error is far too harsh —
                // rebuild the pipeline and carry on from the same position instead, and
                // only give up once it is clearly not recoverable.
                val player = torrentPlayer
                if (player != null && torrentPlayerRetries < TORRENT_PLAYER_MAX_RETRIES) {
                    torrentPlayerRetries++
                    val resumeAt = player.currentPosition.coerceAtLeast(0L)
                    android.util.Log.w(
                        "KeenBack",
                        "player_error retry $torrentPlayerRetries at $resumeAt: ${error.errorCodeName}",
                    )
                    player.seekTo(resumeAt)
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                Toast.makeText(
                    this@KeenActivity,
                    getString(R.string.torrent_playback_error),
                    Toast.LENGTH_LONG,
                ).show()
                exitNativeTorrentPlayer("player_error")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> playbackState.toString()
                }
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "torrent_player_state",
                        url = streamUrl,
                        detail = name,
                    ),
                )
                // Playback resumed (or finished) — drop the seek-buffering loader.
                //
                // Only ever a MID-playback loader. READY arrives before the first frame
                // is decoded, so without the latch this fade beat onRenderedFirstFrame to
                // the surface: the reveal then found nothing to wipe and the film simply
                // appeared behind a 160 ms fade. Before the first frame the loading
                // surface belongs to the circle, and to nothing else.
                // The file ran out. If the offer is up, its fill was timed to the reported
                // duration — which containers routinely overstate by a second or two — so
                // honour the end of the media over the end of the animation.
                if (playbackState == Player.STATE_ENDED && nextEpisodeArmed) {
                    playNextEpisode()
                    return
                }
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    if (torrentFirstFrameShown && torrentOverlayVisible && nativeTorrentPlayerActive) {
                        hideTorrentOverlay()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // First frame on screen: from here a stall really is a seek.
                if (isPlaying) {
                    torrentPlaybackStarted = true
                    // Playing again: the retry budget is for consecutive failures.
                    torrentPlayerRetries = 0
                    considerRevealingPicture()
                }
                // Pause = a deliberate moment; snapshot it for the Continue card.
                if (!isPlaying && torrentPlayer?.playbackState == Player.STATE_READY) {
                    captureTorrentFrame("pause")
                }
            }
        })
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        // Same magnet/.torrent watched before: resume where the user left off
        // (the media file itself was deleted on exit; only the position survives).
        val resumeMs = torrentResumeKey?.let { torrentResumeStore.positionMs(it) } ?: 0L
        if (resumeMs > 0) {
            player.setMediaItem(mediaItem, resumeMs)
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "torrent_resume",
                    url = streamUrl,
                    detail = "positionMs=$resumeMs",
                ),
            )
        } else {
            player.setMediaItem(mediaItem)
        }
        player.playWhenReady = true
        player.prepare()
        // A keyboard left open by an in-page search must not sit over playback,
        // and the URL bar must not keep focus or OK would reopen the IME.
        currentFocus?.let { hideKeyboard(it) }
        binding.browseUrlEdit.clearFocus()
        binding.torrentPlayerView.player = player
        styleSubtitles()
        // Scrubber (circle) walks the timeline a minute at a time when focused and
        // pressed/held left or right, instead of a duration-relative fraction.
        torrentTimeBar = binding.torrentPlayerView.findViewById<androidx.media3.ui.DefaultTimeBar>(
            androidx.media3.ui.R.id.exo_progress,
        )?.apply { setKeyTimeIncrement(TORRENT_TIMEBAR_KEY_INCREMENT_MS) }
        installPlayerStarButton()
        refreshPlayerStarIcon()
        // Take the page out of view for the duration.
        //
        // A film started from a search result leaves the torrent site — usually a white
        // page — sitting in the hierarchy behind the loading surface. During the reveal
        // the surface is no longer the backdrop everywhere (the player is lifted over it
        // and its own background is clipped to the circle), and that white page showed
        // through around the growing edge. Resuming from the dark home screen never
        // showed it, which is exactly why it looked like it only happened on new titles.
        // INVISIBLE, not GONE: the WebView keeps its size and state for the return trip.
        browseShellHiddenForPlayer = binding.browseShell.visibility == View.VISIBLE
        if (browseShellHiddenForPlayer) binding.browseShell.visibility = View.INVISIBLE
        binding.torrentPlayerContainer.visibility = View.VISIBLE
        binding.torrentPlayerView.requestFocus()
        // Card artwork: grab a real frame ~75s in (retries until the stream
        // has actually produced one), then keep it fresh with a rolling
        // 5-minute refresh plus grabs on pause/exit/TV-off.
        torrentFrameAttempts = 0
        // A new file is playing: any offer from the previous episode is stale, and a
        // "no thanks" belonged to that episode, not this one.
        nextEpisodeDeclined = false
        dismissNextEpisode()
        binding.root.removeCallbacks(nextEpisodeWatchRunnable)
        binding.root.postDelayed(nextEpisodeWatchRunnable, NEXT_EPISODE_POLL_MS)
        binding.root.removeCallbacks(torrentCheckpointRunnable)
        binding.root.postDelayed(torrentCheckpointRunnable, TORRENT_CHECKPOINT_INTERVAL_MS)
        binding.root.removeCallbacks(torrentFrameCaptureRunnable)
        binding.root.postDelayed(torrentFrameCaptureRunnable, TORRENT_FRAME_FIRST_DELAY_MS)
    }

    /**
     * Subtitles at a size and weight we choose, not whatever the file or the television
     * asks for.
     *
     * Two sources were making the English track heavy and oversized: subtitle files
     * carry their own styling (SRT markup, ASS style blocks — bold is common), and
     * media3 otherwise takes the system caption settings, which on a TV default to a
     * large size. Both are switched off here, and the track is drawn in regular-weight
     * sans at 0.045 of the view height rather than media3's 0.0533.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun styleSubtitles() {
        val view = binding.torrentPlayerView.subtitleView ?: return
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                android.graphics.Typeface.SANS_SERIF,
            ),
        )
        view.setFractionalTextSize(SUBTITLE_TEXT_SIZE_FRACTION)
    }

    /**
     * Put the star into the player's control row, immediately left of the subtitle (CC)
     * button.
     *
     * Injected into the stock controller at runtime rather than shipped as a custom
     * `controller_layout_id`. Overriding the layout means copying media3's entire control
     * view and owning it forever — every id (`exo_progress`, `exo_subtitle`, the play and
     * seek controls) and every default behaviour, including the minute-scrubbing setup
     * above. Inserting one button into the existing parent keeps all of that intact.
     */
    private fun installPlayerStarButton() {
        if (playerStarButton != null) return
        val subtitleButton = binding.torrentPlayerView.findViewById<View>(
            androidx.media3.ui.R.id.exo_subtitle,
        ) ?: return
        val row = subtitleButton.parent as? android.view.ViewGroup ?: return
        val star = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_star)
            background = androidx.core.content.ContextCompat.getDrawable(
                this@KeenActivity,
                R.drawable.focusable_icon,
            )
            contentDescription = getString(R.string.library_star_toggle)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            isClickable = true
            layoutParams = android.view.ViewGroup.LayoutParams(
                subtitleButton.width.takeIf { it > 0 } ?: STAR_BUTTON_FALLBACK_PX,
                subtitleButton.height.takeIf { it > 0 } ?: STAR_BUTTON_FALLBACK_PX,
            )
            setPadding(STAR_BUTTON_PADDING_PX, STAR_BUTTON_PADDING_PX, STAR_BUTTON_PADDING_PX, STAR_BUTTON_PADDING_PX)
            setOnClickListener { toggleStarForCurrentTorrent() }
        }
        row.addView(star, row.indexOfChild(subtitleButton))
        playerStarButton = star
        refreshPlayerStarIcon()
    }

    /** Filled/bright when this title is in the library, dim when it is not. */
    private fun refreshPlayerStarIcon() {
        val starred = libraryStore.isStarred(torrentOriginKey)
        playerStarButton?.alpha = if (starred) 1.0f else 0.35f
    }

    /**
     * Star: keep this title on the box. Unstar: delete it, completely.
     *
     * Starring hands the running download to the service's retain path, so the file is
     * finished and moved into the library instead of dying with the player's cache.
     */
    private fun toggleStarForCurrentTorrent() {
        val key = torrentOriginKey ?: return
        val origin = torrentOriginLabel ?: return
        val existing = libraryStore.find(key)
        if (existing != null) {
            confirmUnstar(existing)
            return
        }
        val dir = libraryStore.dirFor(key).apply { mkdirs() }
        libraryStore.put(
            com.keenzero.app.library.StarredLibraryStore.Entry(
                key = key,
                origin = origin,
                title = torrentTitle ?: getString(R.string.app_name),
                state = com.keenzero.app.library.StarredLibraryStore.State.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = 0L,
                mediaPath = null,
                starredAtMs = System.currentTimeMillis(),
            ),
        )
        // Its own service, its own torrent session: the download must be untouched by
        // anything the player does afterwards, including starting another stream.
        startService(
            Intent(this, com.keenzero.app.library.LibraryDownloadService::class.java)
                .setAction(com.keenzero.app.library.LibraryDownloadService.ACTION_START)
                .putExtra(com.keenzero.app.library.LibraryDownloadService.EXTRA_ORIGIN, origin)
                .putExtra(com.keenzero.app.library.LibraryDownloadService.EXTRA_KEY, key)
                .putExtra(com.keenzero.app.library.LibraryDownloadService.EXTRA_DIR, dir.absolutePath),
        )
        refreshPlayerStarIcon()
        Toast.makeText(this, R.string.library_starred, Toast.LENGTH_SHORT).show()
        recordEvent(NavigationEvent(System.currentTimeMillis(), "library_star", url = origin))
    }

    /**
     * Unstarring destroys gigabytes, and OK on a remote is one careless press away at all
     * times — so the delete direction asks first. The deletion itself is total: the
     * record and the whole per-title directory.
     */
    private fun confirmUnstar(entry: com.keenzero.app.library.StarredLibraryStore.Entry) =
        confirmRemoveLibraryEntry(entry)

    /**
     * Confirm removing a starred title, from the Downloaded row or the player's star.
     *
     * Always confirms: OK on a remote is one careless press away at any moment, and this
     * destroys gigabytes. The message states what will actually be reclaimed, and an
     * unfinished download is cancelled as well as deleted, so a background transfer does
     * not carry on for a title the user just removed.
     */
    /**
     * A page's `<select>`, presented the way a television can actually use.
     *
     * WebView anchors its own list under the control, which on a TV opens off the bottom
     * of the screen and scrolls where the remote cannot follow. Same dialog as the stream
     * file picker: centred, D-pad native, one press to choose, Back to dismiss without
     * changing anything.
     */
    private fun showSelectPopup(payload: String) {
        val json = try {
            org.json.JSONObject(payload)
        } catch (_: Exception) {
            return
        }
        val token = json.optString("token").takeIf { it.isNotBlank() } ?: return
        val options = json.optJSONArray("options") ?: return
        if (options.length() == 0) return

        val indices = ArrayList<Int>(options.length())
        val labels = ArrayList<String>(options.length())
        for (i in 0 until options.length()) {
            val o = options.optJSONObject(i) ?: continue
            if (o.optBoolean("disabled")) continue
            val group = o.optString("group")
            val label = o.optString("label")
            indices.add(o.optInt("i", i))
            labels.add(if (group.isNotBlank()) "$group · $label" else label)
        }
        if (labels.isEmpty()) return

        val current = json.optInt("selected", -1)
        // Mark where the page currently sits, so a long genre list says where you are.
        val shown = labels.mapIndexed { i, label ->
            if (indices[i] == current) "✓ $label" else label
        }.toTypedArray()

        val title = json.optString("name").takeIf { it.isNotBlank() }
            ?: getString(R.string.select_popup_title)

        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(shown) { _, which ->
                webHost?.applySelectChoice(token, indices[which])
            }
            .show()
    }

    /**
     * Which file of a multi-video torrent to stream.
     *
     * A 4-movie collection used to resolve silently to whichever file was biggest, so
     * asking for one film could start another. Nothing is downloading while this is up —
     * the service pauses before it prioritises anything — and the picked file is the only
     * one that ever gets a non-IGNORE priority, so the other three never transfer a byte.
     */
    private fun promptTorrentFileChoice(intent: Intent, requestId: String) {
        val indices = intent.getIntArrayExtra(TorrentStreamingService.EXTRA_FILE_INDICES) ?: return
        val names = intent.getStringArrayExtra(TorrentStreamingService.EXTRA_FILE_NAMES) ?: return
        val sizes = intent.getLongArrayExtra(TorrentStreamingService.EXTRA_FILE_SIZES) ?: return
        if (indices.isEmpty() || names.size != indices.size || sizes.size != indices.size) return

        // Which of these have already been watched through, so a pack you are working
        // your way along tells you where you got to instead of looking identical every time.
        val watched = torrentOriginKey?.let { torrentResumeStore.watchedIndices(it) } ?: emptySet()
        val labels = names.mapIndexed { i, name ->
            val pretty = prettyMediaTitle(name) ?: name
            val tick = if (indices[i] in watched) "✓ " else ""
            "$tick$pretty\n${android.text.format.Formatter.formatShortFileSize(this, sizes[i])}"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.torrent_pick_file_title, indices.size))
            // Picking IS the decision — one press, no confirm step. A radio list plus an
            // OK button made choosing a film a two-press job on a remote for no gain;
            // there is nothing to review between the two presses.
            .setItems(labels) { _, which ->
                torrentFileIndex = indices[which]
                // The fraction sent at start-up belonged to the torrent, before anyone
                // knew which film this would be. Now that we do, send this file's own
                // resume point so the buffer window lands on its playhead.
                val fraction = torrentResumeKey?.let { key ->
                    val pos = torrentResumeStore.positionMs(key)
                    val dur = torrentResumeStore.durationMs(key)
                    if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 0.98f) else 0f
                } ?: 0f
                startService(
                    Intent(this, TorrentStreamingService::class.java)
                        .setAction(TorrentStreamingService.ACTION_SELECT_FILE)
                        .putExtra(TorrentStreamingService.EXTRA_REQUEST_ID, requestId)
                        .putExtra(TorrentStreamingService.EXTRA_FILE_INDEX, indices[which])
                        .putExtra(TorrentStreamingService.EXTRA_RESUME_FRACTION, fraction),
                )
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "torrent_file_chosen",
                        detail = "index=${indices[which]} of=${indices.size}",
                    ),
                )
            }
            // Back must not leave the service parked on a picker nobody can see again.
            .setOnCancelListener { cancelTorrentRequest() }
            .show()
    }

    /** Abandon a torrent request the user backed out of at the file picker. */
    private fun cancelTorrentRequest() {
        startService(
            Intent(this, TorrentStreamingService::class.java)
                .setAction(TorrentStreamingService.ACTION_STOP),
        )
        hideTorrentOverlay()
        torrentRequestId = null
    }

    /**
     * Long-press a favourite roundel to drop it, matching the Downloaded card.
     *
     * Removing a favourite is not the same act as deleting a download — nothing leaves
     * the box — so the copy says so rather than reusing the Downloaded wording.
     */
    private fun confirmRemoveFavourite(fav: com.keenzero.app.favourites.FavouritesStore.Fav) {
        val name = siteName(fav.host.ifBlank { fav.label })
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.fav_remove_title)
            .setMessage(getString(R.string.fav_remove_message, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.fav_remove_confirm) { _, _ ->
                val removed = favouritesStore.removeHosts(setOf(fav.host))
                refreshPlayerStarIcon()
                hydrateContinuitySurface()
                Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show()
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "favourite_remove",
                        url = fav.url,
                        detail = "host=${fav.host} removed=$removed",
                    ),
                )
            }
            .show()
    }

    /** Long-press a Continue watching card to drop it, matching the Downloaded card. */
    private fun confirmRemoveRecent(cp: ContinuityCheckpoint) {
        val name = prettyMediaTitle(cp.title) ?: cp.contentId ?: getString(R.string.continue_unknown_title)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.continue_remove_title)
            .setMessage(getString(R.string.continue_remove_message, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_remove_confirm) { _, _ ->
                val removed = continuityStore.removeRecent(cp)
                hydrateContinuitySurface()
                Toast.makeText(this, R.string.continue_removed, Toast.LENGTH_SHORT).show()
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "continue_remove",
                        url = cp.url,
                        detail = "removed=$removed",
                    ),
                )
            }
            .show()
    }

    private fun confirmRemoveLibraryEntry(
        entry: com.keenzero.app.library.StarredLibraryStore.Entry,
    ) {
        val complete = entry.state == com.keenzero.app.library.StarredLibraryStore.State.COMPLETE
        val onDisk = libraryStore.bytesOnDisk(entry.key)
        val size = if (onDisk > 0) android.text.format.Formatter.formatShortFileSize(this, onDisk) else null
        val name = prettyMediaTitle(entry.title) ?: entry.title
        val message = when {
            !complete && size != null -> getString(R.string.library_remove_partial, name, size)
            !complete -> getString(R.string.library_remove_partial_nosize, name)
            size != null -> getString(R.string.library_remove_complete, name, size)
            else -> getString(R.string.library_unstar_message, name)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(
                if (complete) getString(R.string.library_unstar_title)
                else getString(R.string.library_remove_downloading_title),
            )
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_unstar_confirm) { _, _ ->
                startService(
                    Intent(this, com.keenzero.app.library.LibraryDownloadService::class.java)
                        .setAction(com.keenzero.app.library.LibraryDownloadService.ACTION_CANCEL)
                        .putExtra(com.keenzero.app.library.LibraryDownloadService.EXTRA_KEY, entry.key),
                )
                val freed = libraryStore.remove(entry.key)
                refreshPlayerStarIcon()
                hydrateContinuitySurface()
                Toast.makeText(this, R.string.library_unstarred, Toast.LENGTH_SHORT).show()
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "library_unstar",
                        url = entry.origin,
                        detail = "freedBytes=$freed",
                    ),
                )
            }
            .show()
    }

    private fun hideNativeTorrentPlayer() {
        binding.root.removeCallbacks(torrentCheckpointRunnable)
        binding.root.removeCallbacks(torrentFrameCaptureRunnable)
        binding.root.removeCallbacks(torrentScrubTick)
        binding.root.removeCallbacks(nextEpisodeWatchRunnable)
        binding.root.removeCallbacks(firstFrameWatchdog)
        nextEpisodeArmed = false
        binding.torrentNextEpisode.cancelCountdown()
        binding.torrentNextEpisode.visibility = View.GONE
        torrentTimeBar = null
        saveTorrentResumePoint()
        torrentSeekActive = false
        torrentSeekTargetMs = -1L
        binding.torrentSeekPreview.visibility = View.GONE
        binding.torrentScrubTrack.visibility = View.GONE
        binding.torrentPlayerView.player = null
        torrentOpenedStreamUrl = null
        binding.torrentPlayerContainer.visibility = View.GONE
        // Give the page back exactly as it was, and only if we were the ones who took it.
        if (browseShellHiddenForPlayer) {
            browseShellHiddenForPlayer = false
            if (binding.browseShell.visibility == View.INVISIBLE) {
                binding.browseShell.visibility = View.VISIBLE
            }
        }
        torrentPlayer?.release()
        torrentPlayer = null
    }

    /**
     * Last resort for a first frame that never comes.
     *
     * The reveal is triggered by `onRenderedFirstFrame`, and the loading surface is torn
     * down as part of it. That made the animation the *only* thing that could dismiss the
     * surface — so a stream that reached READY and then starved (no data to the decoder,
     * no frame, ever) parked the user on "Starting playback…" indefinitely, with the film
     * neither playing nor recoverable. Whatever the stream does, the surface comes down.
     *
     * Deliberately generous: opening a big mkv reads its cues from the end of the file and
     * can legitimately take ~20 s before the first frame, and cutting the loader off early
     * is what the indicator exists to prevent.
     */
    private val firstFrameWatchdog = Runnable {
        if (torrentFirstFrameShown || !torrentOverlayVisible) return@Runnable
        android.util.Log.w("KeenBack", "first_frame_watchdog: no frame, dropping loader")
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "torrent_first_frame_timeout",
                detail = "after ${FIRST_FRAME_TIMEOUT_MS}ms",
            ),
        )
        // No reveal: there is no picture to reveal. Collapse is the honest exit — it
        // leaves the player on screen so its controls and Back still work.
        hideTorrentOverlayWithCollapse()
    }

    /**
     * Watches for the end of an episode so the next one can be offered before it arrives.
     *
     * Polled once a second rather than driven off a listener: there is no "approaching the
     * end" callback, and a second's granularity is invisible against a 60 s lead-in.
     */
    private val nextEpisodeWatchRunnable = object : Runnable {
        override fun run() {
            if (!nativeTorrentPlayerActive) return
            val player = torrentPlayer
            if (player != null) {
                val duration = player.duration
                val remaining = duration - player.currentPosition
                if (duration > 0 && remaining >= 0) {
                    if (!nextEpisodeArmed && !nextEpisodeDeclined &&
                        remaining <= NEXT_EPISODE_LEAD_MS && nextEpisodeIndex() != null
                    ) {
                        armNextEpisode(remaining)
                    } else if (remaining > NEXT_EPISODE_LEAD_MS + 5_000L) {
                        // Seeked back into the film. Any live countdown was timed against
                        // a promise that is no longer true, so withdraw it rather than
                        // leave it running over the middle of an episode — and forget a
                        // previous "no thanks", since the end is being approached afresh.
                        nextEpisodeDeclined = false
                        if (nextEpisodeArmed) dismissNextEpisode()
                    }
                }
            }
            binding.root.postDelayed(this, NEXT_EPISODE_POLL_MS)
        }
    }

    /**
     * The file after the one playing, or null when this is the last (or a single film).
     *
     * Strictly the next in order, watched or not. Skipping seen episodes sounds helpful
     * until it silently jumps you over the one you were rewatching; the picker already
     * shows ticks for anyone who wants to choose.
     */
    private fun nextEpisodeIndex(): Int? {
        val current = torrentFileIndex ?: return null
        val position = torrentPackIndices.indexOf(current)
        if (position < 0 || position + 1 >= torrentPackIndices.size) return null
        return torrentPackIndices[position + 1]
    }

    /** Put the offer on screen with its fill timed to land exactly as the file ends. */
    private fun armNextEpisode(remainingMs: Long) {
        val next = nextEpisodeIndex() ?: return
        val position = torrentPackIndices.indexOf(next)
        val name = torrentPackNames.getOrNull(position)
        val pretty = name?.let { prettyMediaTitle(it) ?: it }
        nextEpisodeArmed = true
        binding.torrentNextEpisode.apply {
            label = getString(R.string.torrent_next_episode)
            visibility = View.VISIBLE
            onCountdownComplete = { playNextEpisode() }
            startCountdown(remainingMs.coerceAtLeast(1_000L))
        }
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "next_episode_offered",
                detail = "index=$next name=${pretty.orEmpty()}",
            ),
        )
    }

    /** Take the offer away; the film keeps the keys it always had. */
    private fun dismissNextEpisode() {
        nextEpisodeArmed = false
        binding.torrentNextEpisode.cancelCountdown()
        binding.torrentNextEpisode.visibility = View.GONE
    }

    /**
     * Move the live torrent onto the next file.
     *
     * Not a new session: the service keeps the handle and re-points the bridge, so this is
     * a short buffer rather than a fresh magnet resolve. The loading surface goes back up
     * because the next episode deserves the same opening as the first — the new player
     * will wipe it away with the circular reveal on its first frame.
     */
    private fun playNextEpisode() {
        val next = nextEpisodeIndex() ?: return
        val requestId = torrentRequestId ?: return
        // Credit the episode just finished before the playhead is replaced.
        saveTorrentResumePoint()
        torrentOriginKey?.let { torrentResumeStore.markWatched(it, torrentFileIndex ?: return@let) }
        dismissNextEpisode()
        torrentFileIndex = next
        showTorrentOverlay()
        showTorrentStartingStage()
        startService(
            Intent(this, TorrentStreamingService::class.java)
                .setAction(TorrentStreamingService.ACTION_PLAY_FILE)
                .putExtra(TorrentStreamingService.EXTRA_REQUEST_ID, requestId)
                .putExtra(TorrentStreamingService.EXTRA_FILE_INDEX, next),
        )
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "next_episode_started",
                detail = "index=$next",
            ),
        )
    }

    /**
     * Adopt the identity of the stream a broadcast describes.
     *
     * STREAM_OPEN and READY carry the same payload — the former as early as the bridge can
     * answer, the latter when the buffer window is full — so both land here rather than
     * duplicating the parsing and drifting apart.
     */
    private fun applyTorrentStreamIdentity(intent: Intent) {
        intent.getStringExtra(TorrentStreamingService.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?.let { torrentTitle = it }
        intent.getStringExtra(TorrentStreamingService.EXTRA_MEDIA_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let { torrentMediaPath = it }
        // What else this torrent holds, for the next-episode offer. Sent even in the
        // auto-resolved single-film case, where it arrives empty, so the player never has
        // to remember picker state.
        torrentPackIndices =
            intent.getIntArrayExtra(TorrentStreamingService.EXTRA_PACK_INDICES) ?: IntArray(0)
        torrentPackNames =
            intent.getStringArrayExtra(TorrentStreamingService.EXTRA_PACK_NAMES) ?: emptyArray()
        intent.getIntExtra(TorrentStreamingService.EXTRA_FILE_INDEX, -1)
            .takeIf { it >= 0 }
            ?.let { torrentFileIndex = it }
    }

    /** Persist the playhead for this magnet so re-activating it resumes there. */
    private fun saveTorrentResumePoint() {
        val player = torrentPlayer ?: return
        val key = torrentResumeKey ?: return
        torrentResumeStore.savePosition(key, player.currentPosition, player.duration)
        // Watching a pack's film to the end is what earns its tick in the picker. Keyed on
        // the torrent, not the per-file resume key: the picker asks "which of these have
        // I seen" and needs them all under one entry.
        val index = torrentFileIndex
        val originKey = torrentOriginKey
        if (index != null && originKey != null &&
            torrentResumeStore.isFinished(player.currentPosition, player.duration)
        ) {
            torrentResumeStore.markWatched(originKey, index)
        }
        persistTorrentCheckpoint(player)
    }

    /**
     * Media checkpoint for torrent playback: feeds the Continue watching card
     * and lets a cold start resume the magnet (position via TorrentResumeStore).
     */
    private fun persistTorrentCheckpoint(player: ExoPlayer) {
        val origin = torrentOriginLabel ?: return
        val durationMs = player.duration
        val checkpoint = ContinuityCheckpoint(
            url = origin,
            title = torrentTitle,
            playerType = "torrent",
            playbackPositionSec = player.currentPosition.coerceAtLeast(0L) / 1000.0,
            durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0,
            posterUrl = capturedFrameKey(),
            playbackState = if (player.isPlaying) "playing" else "paused",
            journeyState = PlaybackJourneyState.PLAYING.name,
        )
        latestCheckpoint = checkpoint
        continuityStore.save(checkpoint, force = true)
    }

    /** "frame:<info-hash>" when this title's own captured frame exists on disk. */
    private fun capturedFrameKey(): String? {
        val frameKey = torrentOriginKey?.let { "frame:$it" } ?: return null
        // Was gated on a single global "last captured" pref, which is only meaningful
        // when there is one shared slot. Ask for THIS title's file instead.
        return frameKey.takeIf { java.io.File(filesDir, "continue/" + frameFileName(it)).exists() }
    }

    /**
     * Fill the preview rows' artwork from images side-loaded onto the box.
     *
     * The stills are freely licensed open-movie frames, but they are demo dressing and do
     * not belong in everyone's APK, so they are not assets. They are read from this app's
     * own external files directory — `adb push`-able without root and readable without a
     * storage permission — and copied into the same on-disk frame cache a real capture
     * writes to, so the seeded cards paint offline and on the very first frame.
     *
     * Absent files are simply skipped: a card with no art shows the branded placeholder.
     */
    private fun installPreviewArtwork() {
        val source = java.io.File(getExternalFilesDir(null), "preview")
        if (!source.isDirectory) return
        val library = uiPreviewLibrary()
        val mapping = mapOf(
            "sintel.jpg" to "keen-preview-sintel",
            "cosmos.jpg" to "keen-preview-cosmos",
            "tears.jpg" to "keen-preview-tears",
            "bunny.jpg" to library[0].key,
            "elephants.jpg" to library[1].key,
            "caminandes.jpg" to library[2].key,
        )
        val dest = java.io.File(filesDir, "continue").apply { mkdirs() }
        mapping.forEach { (name, frameKey) ->
            runCatching {
                val src = java.io.File(source, name)
                if (src.exists()) src.copyTo(java.io.File(dest, frameFileName(frameKey)), overwrite = true)
            }
        }
    }

    /** Cache file for a "frame:<key>" poster. Hashed so any key yields a safe filename. */
    private fun frameFileName(posterKey: String): String =
        "frame_" + posterKey.removePrefix("frame:").hashCode().toUInt().toString(16) + ".img"

    /**
     * Drop cached artwork no longer referenced by the (max 5) Continue entries.
     *
     * Eviction previously left images behind forever; now that each title owns a file,
     * that would grow without bound. Reconciling against the live set also cleans up the
     * legacy shared slot and any orphans, rather than trying to hook every eviction path.
     */
    private fun pruneOrphanPosters() {
        // While a demo owns the row, the user's real titles are parked in the stash and so
        // look like orphans — pruning then deleted the artwork of history that was about to
        // be restored, and those cards came back as blank placeholders. Nothing accumulates
        // in the meantime that the next prune will not catch.
        if (continuityStore.hasStash()) return
        Thread({
            try {
                // The Downloaded row's artwork lives in the same directory under
                // "frame:<library key>", and it is not referenced by any checkpoint. Left
                // out of the live set, every starred title's poster was deleted the moment
                // the Continue row changed, and had to be decoded out of the media again.
                val live = (
                    continuityStore.loadRecents() +
                        listOfNotNull(continuityStore.load(), continuityStore.loadMedia())
                    ).mapNotNull { it.posterUrl }.toSet() +
                    libraryStore.list().map { "frame:${it.key}" }
                val keepFrames = live.filter { it.startsWith("frame:") }
                    .map { frameFileName(it) }.toHashSet()
                java.io.File(filesDir, "continue").listFiles()?.forEach { f ->
                    // Legacy single slot: delete it too, so stale duplicates cannot resurface.
                    if (f.name == "poster.img" || (f.name.startsWith("frame_") && f.name !in keepFrames)) {
                        f.delete()
                    }
                }
                val keepPosters = live
                    .filterNot { it.startsWith("frame:") || it.startsWith("res:") }
                    .map { "${it.hashCode()}.img" }.toHashSet()
                java.io.File(filesDir, "posters").listFiles()?.forEach { f ->
                    if (f.name !in keepPosters) f.delete()
                }
            } catch (_: Throwable) {
            }
        }, "poster-prune").start()
    }

    /**
     * Card artwork for torrents: decode a frame near the playhead straight out of the
     * downloaded media file.
     *
     * This used to PixelCopy the PlayerView's SurfaceView. On this hardware the video
     * rides a decoder overlay plane whose buffer is not CPU-readable, so the copy
     * silently returned whatever else had been composited into that region — which is
     * why the Continue cards showed torn, glitched pictures of the *page the magnet came
     * from* rather than the film. A frame decoded from the container is the real frame,
     * on any plane, at any resolution.
     *
     * The file is read directly rather than through [TorrentHttpBridge]: a second reader
     * on the bridge would call `clearPieceDeadlines` and re-arm the swarm at the poster's
     * timestamp, stalling actual playback. Reading the file needs no piece deadlines at
     * all — it just needs bytes that are already there, which is why the grab targets a
     * point [TORRENT_FRAME_LOOKBACK_MS] *behind* the playhead. Undownloaded regions read
     * as zeros, so the black/garbled checks still gate what gets persisted.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun captureTorrentFrame(reason: String) {
        val player = torrentPlayer ?: return
        val key = torrentOriginKey ?: return
        val path = torrentMediaPath
        if (path.isNullOrBlank() || !java.io.File(path).exists()) {
            scheduleTorrentFrameRetry()
            return
        }
        if (player.playbackState != Player.STATE_READY ||
            player.currentPosition < TORRENT_FRAME_MIN_POS_MS
        ) {
            scheduleTorrentFrameRetry()
            return
        }
        val positionMs = player.currentPosition
        val frameAtMs = (positionMs - TORRENT_FRAME_LOOKBACK_MS)
            .coerceAtLeast(TORRENT_FRAME_MIN_POS_MS / 2)
        Thread({
            val bitmap = decodeMediaFrame(path, frameAtMs * 1000L)
            runOnUiThread {
                if (bitmap == null || looksBlack(bitmap) || looksGarbled(bitmap)) {
                    bitmap?.recycle()
                    scheduleTorrentFrameRetry()
                    return@runOnUiThread
                }
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "torrent_frame_captured",
                        detail = "reason=$reason pos=$positionMs frameAt=$frameAtMs",
                    ),
                )
                persistTorrentFrame(bitmap, key)
            }
        }, "keen-frame-decode").apply { isDaemon = true }.start()
    }

    /**
     * Artwork for a starred title that may never have been played.
     *
     * Poster frames normally come from the playback capture path, so a download the user
     * starred and left alone had no art at all and fell back to the branded placeholder.
     * The whole file is on disk here, so a frame can simply be decoded — taken ~10% in to
     * skip studio idents and black leader, which is where a title card usually isn't.
     */
    /**
     * Titles whose poster has already been attempted this session.
     *
     * Without this the attempt repeats on every repaint of the Downloaded row, because
     * the only thing suppressing it is the cached file existing — and a capture that
     * legitimately produces nothing (an unreadable container, a black or garbled frame)
     * never writes one. On a finished 1.3 GB title that meant a full MediaMetadataRetriever
     * pass over the file every second, for ever.
     */
    private val libraryPosterAttempted = mutableSetOf<String>()

    private fun captureLibraryPoster(path: String, key: String) {
        if (!libraryPosterAttempted.add(key)) return
        Thread({
            val file = java.io.File(path)
            if (!file.exists()) return@Thread
            val durationMs = mediaDurationMs(path)
            // 10% in, but never past a sane cap on a very long file, and never before
            // the point where leader/idents usually end.
            val atMs = if (durationMs > 0) {
                (durationMs / 10).coerceIn(LIBRARY_POSTER_MIN_MS, LIBRARY_POSTER_MAX_MS)
            } else {
                LIBRARY_POSTER_MIN_MS
            }
            val bitmap = decodeMediaFrame(path, atMs * 1000L) ?: return@Thread
            if (looksBlack(bitmap) || looksGarbled(bitmap)) {
                bitmap.recycle()
                return@Thread
            }
            // Same store the Continue cards read from, keyed by info-hash, so the
            // Downloaded card picks it up as "frame:<key>" with no extra plumbing.
            persistTorrentFrame(bitmap, key)
        }, "keen-library-poster").apply { isDaemon = true }.start()
    }

    /** Container duration in ms, or 0 when it cannot be read. */
    private fun mediaDurationMs(path: String): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * One card-sized frame from [path] at [timeUs], or null when the container cannot be
     * read (index not downloaded yet, partial file, unsupported codec). Callers retry.
     */
    private fun decodeMediaFrame(path: String, timeUs: Long): android.graphics.Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            // CLOSEST_SYNC decodes from a keyframe at or before the target, so it never
            // needs bytes ahead of the playhead — exactly the region that may be missing.
            val frame = retriever.getFrameAtTime(
                timeUs,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return null
            // Scale to card size here so the persisted JPEG and the row's ImageView agree
            // regardless of the source resolution (SD rips through 4K).
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                frame,
                TORRENT_FRAME_WIDTH_PX,
                TORRENT_FRAME_HEIGHT_PX,
                true,
            )
            if (scaled !== frame) frame.recycle()
            scaled
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleTorrentFrameRetry() {
        if (torrentPlayer == null) return
        if (torrentFrameAttempts >= TORRENT_FRAME_MAX_ATTEMPTS) return
        torrentFrameAttempts++
        binding.root.removeCallbacks(torrentFrameCaptureRunnable)
        binding.root.postDelayed(torrentFrameCaptureRunnable, TORRENT_FRAME_RETRY_MS)
    }

    /** Amlogic video planes sometimes read back opaque — treat those grabs as failures. */
    private fun looksBlack(bitmap: android.graphics.Bitmap): Boolean {
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 9).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val luma = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                if (luma > TORRENT_FRAME_BLACK_LUMA) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    /**
     * The Amlogic video plane can also read back as high-frequency colour noise
     * (a garbled dither) rather than black. Real frames have spatial coherence;
     * noise does not — nearly every neighbouring sample differs wildly. Reject
     * those so the card falls back to the branded placeholder instead of static.
     */
    private fun looksGarbled(bitmap: android.graphics.Bitmap): Boolean {
        val stepX = (bitmap.width / 40).coerceAtLeast(2)
        val stepY = (bitmap.height / 24).coerceAtLeast(2)
        var noisy = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x + 1 < bitmap.width) {
                val a = bitmap.getPixel(x, y)
                val b = bitmap.getPixel(x + 1, y)
                val delta = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                    kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                    kotlin.math.abs((a and 0xFF) - (b and 0xFF))
                if (delta > TORRENT_FRAME_NOISE_DELTA) noisy++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && noisy.toFloat() / total > TORRENT_FRAME_NOISE_RATIO
    }

    private fun persistTorrentFrame(bitmap: android.graphics.Bitmap, originKey: String) {
        Thread({
            try {
                val dir = java.io.File(filesDir, "continue")
                dir.mkdirs()
                val frameKey = "frame:$originKey"
                // One file PER TITLE. This used to be a single shared "poster.img" slot,
                // so every torrent card in the row rendered whichever frame was captured
                // last — which is why most Continue cards showed the same screenshot.
                val tmp = java.io.File(dir, "poster.tmp")
                val dst = java.io.File(dir, frameFileName(frameKey))
                java.io.FileOutputStream(tmp).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                }
                tmp.renameTo(dst)
                getSharedPreferences(POSTER_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(POSTER_SRC_KEY, frameKey)
                    .commit()
                runOnUiThread {
                    val player = torrentPlayer
                    if (player != null) {
                        persistTorrentCheckpoint(player)
                        torrentFrameAttempts = 0
                        binding.root.removeCallbacks(torrentFrameCaptureRunnable)
                        binding.root.postDelayed(torrentFrameCaptureRunnable, TORRENT_FRAME_REFRESH_MS)
                    } else {
                        // Exit-refresh landed after teardown: stamp the frame onto
                        // the stored media checkpoint if it is still this torrent.
                        var changedCheckpoint = false
                        continuityStore.loadMedia()?.let { cp ->
                            val sameTorrent = cp.playerType == "torrent" &&
                                com.keenzero.app.torrent.TorrentResumeStore.keyOf(cp.url.orEmpty()) == originKey
                            if (sameTorrent && cp.posterUrl != frameKey) {
                                continuityStore.save(cp.copy(posterUrl = frameKey), force = true)
                                changedCheckpoint = true
                            }
                        }
                        // Card may already be on screen with the fallback — swap in the
                        // frame. Only when the checkpoint actually changed: rebuilding
                        // the surface unconditionally re-ran the Downloaded row, which
                        // re-requested this very capture, which rebuilt the surface. A
                        // one-second loop that re-decoded a gigabyte-scale file on every
                        // pass and re-ran every entry animation with it.
                        if (uiState == AppUiState.HOME && changedCheckpoint) {
                            hydrateContinuitySurface()
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                bitmap.recycle()
            }
        }, "keen-frame").apply { isDaemon = true }.start()
    }

    /** Leaving playback stops the session (deletes cache) and returns to the source page. */
    private fun exitNativeTorrentPlayer(reason: String) {
        // Best-effort art refresh with the exact frame the user left on; the
        // scheduled mid-playback grab already covered the common case.
        captureTorrentFrame("exit")
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "torrent_player_exit",
                url = currentUrl,
                detail = reason,
            ),
        )
        stopTorrentStreaming()
        // Force both surfaces down. `nativeTorrentPlayerActive` / `torrentOverlayVisible`
        // are derived from these views' visibility, and handleBack() returns early on
        // either — so if one survived teardown, EVERY later Back press re-entered this
        // branch and did nothing visible. Back appeared permanently dead after playing a
        // torrent. Hiding them here makes that state unreachable.
        binding.torrentPlayerContainer.visibility = View.GONE
        binding.torrentLoadingOverlay.visibility = View.GONE
        android.util.Log.i(
            "KeenBack",
            "torrent_player_exit reason=$reason playerVis=${binding.torrentPlayerContainer.visibility} " +
                "overlayVis=${binding.torrentLoadingOverlay.visibility}",
        )
        // A magnet can be launched from either WebView. In compatibility mode `webHost`
        // is deliberately null (the normal host is destroyed on entry), so testing it
        // alone sent every compat-launched torrent home on exit instead of back to the
        // page it came from.
        returnToSourcePageOrHome(reason)
    }

    /**
     * Leave torrent playback and land where the user came from.
     *
     * Shared by every exit route (player Back, player error, and cancelling the loading
     * overlay) because they kept drifting apart: the cancel path tested `webHost` alone,
     * and `webHost` is deliberately null while compatibility mode is active, so backing
     * out of a magnet launched from a compatibility-mode site always dropped to the home
     * screen instead of the page it came from. Returning to the source page is the rule;
     * home is only correct when there genuinely is no page underneath.
     */
    private fun returnToSourcePageOrHome(reason: String) {
        val compatActive = compatSession?.isActive == true
        val pageAvailable = compatActive || webHost?.isCreated == true
        if (pageAvailable && currentUrl != null && currentUrl != "about:blank") {
            uiState = AppUiState.BROWSING
            binding.homeShell.visibility = View.GONE
            binding.browseShell.visibility = View.VISIBLE
            binding.browserContainer.visibility = View.VISIBLE
            binding.chromeBar.visibility = View.VISIBLE
            continuityStore.markAtHome(false)
            // Both kinds of page are pointer surfaces; only the focus target differs.
            binding.pointerLayer.visibility = View.VISIBLE
            if (!compatActive) {
                webHost?.webView?.requestFocus()
            }
        } else {
            // Only when nothing is underneath: a home-launched playback backing out is a
            // deliberate return to the Continue surface, and a cold start lands here too.
            if (reason == "back" || reason == "cancel") continuityStore.markAtHome(true)
            showHome(status = getString(R.string.status_home))
        }
    }

    /** True while the native loading overlay (magnet/.torrent startup) is up. */
    private val torrentOverlayVisible: Boolean
        get() = binding.torrentLoadingOverlay.visibility == View.VISIBLE

    // Highest buffer percent shown in the current loader session. The number
    // never ticks backwards: a piece that finishes downloading briefly drops out
    // of both the partial-block count and havePiece() for a tick, which otherwise
    // shows a jarring slide like 99 → 65. Reset each time the loader reappears.
    private var lastGiantPercent = -1

    // Last swarm figures seen this loader session (tracker scrape, or peers known from
    // DHT/PEX). Held so the readout does not flicker back to our own connection count
    // between announces. Reset with the percent each time the loader reappears.
    private var lastSwarmSeeds = -1
    private var lastSwarmPeers = -1

    // Subtle brightness breathe on the stage title while loading (opacity only).
    private var titlePulse: ObjectAnimator? = null

    private fun startTitlePulse() {
        titlePulse?.cancel()
        titlePulse = ObjectAnimator.ofFloat(binding.torrentLoadingTitle, View.ALPHA, 1f, 0.68f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopTitlePulse() {
        titlePulse?.cancel()
        titlePulse = null
        binding.torrentLoadingTitle.alpha = 1f
    }

    private fun showTorrentOverlay() {
        // The overlay is now the progress feedback; the mark goes back to being a mark.
        setNavLoading(false)
        // Whatever raised it, the keyboard has no business over a loading stream.
        dismissHomeKeyboard()
        autoContinuePending = false
        binding.torrentLoadingOverlay.cutoutRadius = 0f
        lastGiantPercent = -1
        lastSwarmSeeds = -1
        lastSwarmPeers = -1
        currentFocus?.let { hideKeyboard(it) }
        binding.torrentLoadingTitle.text = getString(R.string.torrent_loading_title)
        // INVISIBLE, never GONE: this row is the only thing in the loading column
        // whose presence changes, and the column is centred, so removing it from the
        // layout re-centred everything above — the stage title visibly jumped as the
        // stream moved from "connecting to peers" to "buffering". Holding its space
        // keeps the title and spinner nailed in place while it fades in and out.
        binding.torrentLoadingStats.animate().cancel()
        binding.torrentLoadingStats.alpha = 0f
        binding.torrentLoadingStats.visibility = View.INVISIBLE
        // No real percent yet on a fresh session — the jumbo watermark only makes sense
        // once there's a real number behind it, so it stays hidden until buffering starts.
        // INVISIBLE, not GONE: keeps it participating in layout so its width/height are
        // already known (not 0) the instant the first buffering percent needs to size against it.
        binding.torrentLoadingPercentGiant.animate().cancel()
        binding.torrentLoadingPercentGiant.reset()
        binding.torrentLoadingPercentGiant.visibility = View.INVISIBLE
        binding.torrentLoadingPercentGiant.alpha = 0f
        binding.torrentLoadingOverlay.animate().cancel()
        binding.torrentLoadingOverlay.alpha = 0f
        binding.torrentLoadingOverlay.visibility = View.VISIBLE
        binding.torrentLoadingOverlay.bringToFront()
        binding.torrentLoadingOverlay.animate().alpha(1f).setDuration(200).start()
        // Content pops in a beat after the scrim so the eye lands on the ring, not a flash.
        binding.torrentLoadingContent.animate().cancel()
        binding.torrentLoadingContent.alpha = 0f
        binding.torrentLoadingContent.scaleX = 0.9f
        binding.torrentLoadingContent.scaleY = 0.9f
        binding.torrentLoadingContent.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(60)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        // Fresh session: no real percent yet (still fetching/connecting/resolving
        // metadata), so the ring loops indeterminately until buffering starts.
        binding.torrentLoadingSpinner.startIndeterminate()
        startTitlePulse()
        ensurePointerAboveContent()
    }

    /**
     * Between "buffered" and "on screen": the player is opening the stream and reading
     * the container index. Real work, no percentage to report, so the ring goes
     * indeterminate and the caption says what is happening rather than showing a
     * finished-looking 100% over a black screen.
     */
    private fun showTorrentStartingStage() {
        if (!torrentOverlayVisible) return
        binding.torrentLoadingSpinner.startIndeterminate()
        binding.torrentLoadingTitle.text = getString(R.string.torrent_starting_playback)
    }

    /**
     * Decide whether the film is really running, and only then open the circle.
     *
     * `onRenderedFirstFrame` means one frame reached the screen. It does not mean playback
     * — a torrent stream routinely decodes its first frame and then starves, and revealing
     * on that signal left the circle finishing over a still image for five or ten seconds
     * while the buffer caught up. Three things must all hold before the surface goes:
     * a frame exists, the player says it is playing, and the playhead has measurably moved
     * between two samples. The last one is the only one that cannot be faked by a stalled
     * decoder, which is why it is worth the extra quarter second.
     *
     * Called from every event that could make it true; it is cheap and idempotent.
     */
    private fun considerRevealingPicture() {
        if (torrentFirstFrameShown || revealMotionCheckPending) return
        if (!torrentRenderedFirstFrame || !torrentOverlayVisible) return
        val player = torrentPlayer ?: return
        if (!player.isPlaying) return
        val startedAt = player.currentPosition
        revealMotionCheckPending = true
        binding.root.postDelayed({
            revealMotionCheckPending = false
            val live = torrentPlayer
            if (torrentFirstFrameShown || live == null || !torrentOverlayVisible) {
                return@postDelayed
            }
            // Advanced by a real margin, not by rounding: anything less and the picture is
            // still frozen whatever the player claims.
            if (live.isPlaying && live.currentPosition - startedAt >= REVEAL_MOTION_MIN_MS) {
                torrentFirstFrameShown = true
                binding.root.removeCallbacks(firstFrameWatchdog)
                revealPlayerWithCircle()
            } else {
                // Not moving yet. onIsPlayingChanged will call back when it recovers, but
                // a stall that never flips that flag would otherwise wait forever, so
                // re-arm from here too.
                binding.root.postDelayed({ considerRevealingPicture() }, REVEAL_MOTION_RETRY_MS)
            }
        }, REVEAL_MOTION_SAMPLE_MS)
    }

    /**
     * Open the film through a circle growing from the centre of the screen.
     *
     * The loading surface is not faded out — a hole opens in it, growing from the centre,
     * so the numbers and spinner leave with the same gesture that brings the video in.
     *
     * The hole is cut in the surface rather than revealed on the player; see
     * [com.keenzero.app.home.CircleCutoutFrameLayout] for why a circular reveal on the
     * player cannot work behind a SurfaceView, and what it looked like when it was tried.
     */
    private fun revealPlayerWithCircle() {
        val overlay = binding.torrentLoadingOverlay
        if (!torrentOverlayVisible || overlay.width == 0 || overlay.height == 0) {
            hideTorrentOverlayWithCollapse()
            return
        }
        // The readout reaches 100 here and nowhere else: the buffer being full was never
        // the finish line, the picture moving is. It is on screen at 100 only for the
        // opening of the circle that eats it, which is exactly the intent.
        binding.torrentLoadingPercentGiant.snapToComplete()
        binding.torrentLoadingSpinner.setProgress(1f)
        val radius = kotlin.math.hypot(overlay.width / 2f, overlay.height / 2f)
        val animator = android.animation.ValueAnimator.ofFloat(0f, radius)
        animator.duration = TORRENT_REVEAL_MS
        // Decelerate, over the whole duration.
        //
        // An earlier curve — PathInterpolator(0.05, 0.8, 0.06, 1) — put its first control
        // point at 80% output for 5% of the time, so the circle hit four fifths of its
        // radius in about 60 ms and then crawled through the rest. Filmed off the box,
        // that read as a flash followed by a full second of edges retreating around an
        // already-visible picture. The radius runs to the corners, so the tail of the
        // curve IS the corners, and starving it is what showed.
        animator.interpolator = android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)
        animator.addUpdateListener { overlay.cutoutRadius = it.animatedValue as Float }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                dismissTorrentOverlayNow()
            }
        })
        animator.start()
    }

    /**
     * Tear the loading surface down in one frame, no fade.
     *
     * The circular reveal has already covered it, so there is nothing left to animate —
     * and a fade here is exactly what let it show again on the way out.
     */
    private fun dismissTorrentOverlayNow() {
        stopTitlePulse()
        binding.torrentLoadingOverlay.animate().cancel()
        // Whole again, so the next stream's surface is not born with a hole in it.
        binding.torrentLoadingOverlay.cutoutRadius = 0f
        binding.torrentLoadingOverlay.visibility = View.GONE
        binding.torrentLoadingOverlay.alpha = 1f
        binding.torrentLoadingSpinner.stop()
        binding.torrentLoadingPercentGiant.visibility = View.INVISIBLE
        binding.torrentLoadingPercentGiant.alpha = 0f
    }

    private fun hideTorrentOverlay() {
        stopTitlePulse()
        binding.torrentLoadingOverlay.animate().cancel()
        binding.torrentLoadingOverlay.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                binding.torrentLoadingOverlay.visibility = View.GONE
                binding.torrentLoadingOverlay.alpha = 1f
                binding.torrentLoadingSpinner.stop()
                binding.torrentLoadingPercentGiant.visibility = View.INVISIBLE
                binding.torrentLoadingPercentGiant.alpha = 0f
            }
            .start()
    }

    /**
     * Successful finish only: the bar chase keeps running through the whole load, then this
     * triggers its collapse (fade out) and holds the (already-fading-in-behind-it) player
     * under the loading scrim for just long enough to watch it happen, instead of the
     * spinner being cut off mid-cycle the instant the stream is ready.
     */
    private fun hideTorrentOverlayWithCollapse() {
        // The stream is ready, so the readout may finally say so. This is the only
        // place 100 is honest: the reserved top is released here rather than being
        // reached by a progress figure that then sat still waiting for the last pieces.
        binding.torrentLoadingPercentGiant.finish()
        binding.torrentLoadingSpinner.collapse()
        binding.root.postDelayed({ hideTorrentOverlay() }, TORRENT_COLLAPSE_HOLD_MS)
    }

    /**
     * Drive the loading overlay from invented numbers, with no torrent behind it.
     *
     * Every part of this overlay — the stage wording, the stat lock-up's spacing, the
     * counter's pacing and the roll distance — is a layout and motion problem, and
     * checking any of it against a real stream means fetching real content, waiting on
     * real peers, and getting a different sequence of numbers every run. This walks the
     * same code path the service drives with a fixed, repeatable script instead, so the
     * design can be judged on its own and nothing is downloaded to look at a margin.
     */
    private fun startMockLoadingOverlay() {
        showTorrentOverlay()
        val handler = binding.root
        var elapsedMs = 0L
        fun tick() {
            val t = elapsedMs / 1000f
            when {
                // Connecting: no percentage yet, peers arriving.
                t < MOCK_CONNECT_SEC -> updateTorrentOverlay(
                    stage = TorrentStreamingService.STAGE_CONNECTING,
                    percent = -1,
                    peers = (t * 4).toInt().coerceAtMost(23),
                    seeds = (t * 2).toInt().coerceAtMost(11),
                    speedBps = 0L,
                )
                // Buffering: a percentage that climbs in the coarse jumps the real
                // service reports, so the counter's own pacing is what is on trial.
                else -> {
                    val p = (((t - MOCK_CONNECT_SEC) / MOCK_BUFFER_SEC) * 100f).toInt()
                    updateTorrentOverlay(
                        stage = TorrentStreamingService.STAGE_BUFFERING,
                        percent = (p / 7) * 7,
                        peers = 23,
                        seeds = 11,
                        speedBps = (2_400_000L..3_900_000L).random(),
                    )
                }
            }
            elapsedMs += MOCK_TICK_MS
            if (elapsedMs < (MOCK_CONNECT_SEC + MOCK_BUFFER_SEC + 2) * 1000) {
                handler.postDelayed({ tick() }, MOCK_TICK_MS)
            } else {
                hideTorrentOverlayWithCollapse()
            }
        }
        tick()
    }

    private fun updateTorrentOverlay(
        stage: String,
        percent: Int,
        peers: Int,
        seeds: Int,
        speedBps: Long,
        swarmSeeds: Int = -1,
        swarmPeers: Int = -1,
    ) {
        if (!torrentOverlayVisible) return
        val stageText = when (stage) {
            TorrentStreamingService.STAGE_FETCHING_TORRENT -> getString(R.string.torrent_stage_fetching)
            TorrentStreamingService.STAGE_CONNECTING,
            TorrentStreamingService.STAGE_METADATA,
            -> getString(R.string.torrent_stage_metadata)
            // Thirty seconds in with nothing to show. Which of the two things went wrong
            // is worth distinguishing: "nobody is there" and "they are there but have
            // nothing" send the user to different next moves.
            TorrentStreamingService.STAGE_NO_PEERS -> if (peers > 0) {
                getString(R.string.torrent_stage_no_data)
            } else {
                getString(R.string.torrent_stage_no_seeders)
            }
            TorrentStreamingService.STAGE_BUFFERING,
            TorrentStreamingService.STAGE_SEEK_BUFFERING,
            // Buffering with nobody connected yet is still peer discovery, and saying
            // "Buffering" over three blank stats is what made a working stream look
            // stalled. Name the stage the wait is actually in.
            -> if (peers <= 0) {
                getString(R.string.torrent_stage_metadata)
            } else {
                getString(R.string.torrent_stage_buffering)
            }
            else -> getString(R.string.torrent_loading_title)
        }
        // The jumbo readout is up for the whole wait, not only for buffering.
        //
        // It used to appear the moment the first buffering percent arrived, which meant
        // connecting to peers — often the longest part — had no readout at all, and then
        // one materialised partway through. Getting to playback is one continuous wait,
        // so it is one continuous counter: it comes up at 00 while peers are found and
        // simply carries on when real percentages start arriving. The looping ring is
        // what says "still working" during connecting; the counter only reports.
        if (binding.torrentLoadingPercentGiant.visibility != View.VISIBLE) {
            binding.torrentLoadingPercentGiant.setPercent(0)
            binding.torrentLoadingPercentGiant.visibility = View.VISIBLE
            binding.torrentLoadingPercentGiant.animate().alpha(1f).setDuration(320).start()
        }
        if ((stage == TorrentStreamingService.STAGE_BUFFERING ||
                stage == TorrentStreamingService.STAGE_SEEK_BUFFERING) && percent >= 0
        ) {
            // 100 means "the picture is up", not "the buffer is full".
            //
            // Filling the buffer is not the last thing that has to happen: the player
            // still has to finish opening the container, and on an mkv with its cues at
            // the end of the file that can take a few more seconds. Reporting 100 at the
            // end of buffering parked the counter on a finished number while the screen
            // stayed black — measured at ~5 s on one title. Hold the buffer's own
            // progress just short of the end and let the first rendered frame, which is
            // what the user actually calls "started", spend the last point.
            val ceiling = if (torrentFirstFrameShown) 100 else 99
            val clamped = percent.coerceIn(0, ceiling).coerceAtLeast(lastGiantPercent)
            lastGiantPercent = clamped
            binding.torrentLoadingSpinner.setProgress(clamped / 100f)
            binding.torrentLoadingPercentGiant.setPercent(clamped)
        } else {
            // No real figure yet: the ring loops rather than claiming a position.
            binding.torrentLoadingSpinner.startIndeterminate()
        }
        binding.torrentLoadingTitle.text = stageText
        val noPeers = stage == TorrentStreamingService.STAGE_NO_PEERS
        // Says the session is still up and Back is a way out. Only while the drought
        // lasts — a late peer clears the stage and takes this with it.
        binding.torrentLoadingHint.visibility = if (noPeers) View.VISIBLE else View.GONE

        // Peer stat lock-up: only shown once we have a real seeder/leecher breakdown,
        // then it fades in and stays. Speed is always in MB/s to match the fixed label.
        if (seeds >= 0 && peers >= 0) {
            // Swarm size, not our socket count — and sticky, because the scrape figure
            // arrives a few ticks in and a swarm does not really shrink to nothing
            // between two 750 ms samples. Without the latch the numbers flickered
            // between the tracker's answer and our own connections.
            if (swarmSeeds >= 0) lastSwarmSeeds = maxOf(lastSwarmSeeds, swarmSeeds)
            if (swarmPeers >= 0) lastSwarmPeers = maxOf(lastSwarmPeers, swarmPeers)
            val showSeeds = if (lastSwarmSeeds >= 0) lastSwarmSeeds else seeds
            val showPeers = if (lastSwarmPeers >= 0) lastSwarmPeers else (peers - seeds).coerceAtLeast(0)
            // A count of zero this early is a state, not a measurement: the swarm is
            // still being found. Printing "0" told the user the torrent was dead while
            // it was in fact about to start, which is the one thing this readout must
            // never do — people turn it off and never learn it was working.
            if (showSeeds <= 0 && showPeers <= 0 && !noPeers) {
                binding.statSeeders.text = STAT_PENDING
                binding.statLeechers.text = STAT_PENDING
            } else if (noPeers) {
                // The dash means "not known yet", and once the headline says nobody is
                // sharing, it is known. Print the zeros: a readout that hides the number
                // exactly when it turns bad is the reason this was mistaken for a bug in
                // our own counting for two days.
                binding.statSeeders.text = showSeeds.coerceAtLeast(0).toString()
                binding.statLeechers.text = showPeers.coerceAtLeast(0).toString()
            } else {
                binding.statSeeders.text = showSeeds.coerceAtLeast(0).toString()
                binding.statLeechers.text = showPeers.coerceAtLeast(0).toString()
            }
            // Same reasoning for the rate. Head pieces arrive at tens of KB/s, which in
            // fixed MB/s rounds to "0.0" — a stream downloading perfectly well read as a
            // stream doing nothing. The unit follows the number instead.
            if (speedBps > 0 && speedBps < BYTES_PER_MB) {
                binding.statSpeed.text = (speedBps / 1024L).coerceAtLeast(1L).toString()
                binding.statSpeedLabel.setText(R.string.torrent_stat_speed_kb)
            } else {
                binding.statSpeed.text = if (speedBps > 0) formatMbps(speedBps) else STAT_PENDING
                binding.statSpeedLabel.setText(R.string.torrent_stat_speed)
            }
            showStatLockUp(true)
        } else {
            showStatLockUp(false)
        }
    }

    /**
     * Fade the peer stat lock-up in or out, without ever giving up its space.
     *
     * INVISIBLE, never GONE: this row is the only thing in the loading column whose
     * presence changes, and the column is centred, so removing it from the layout
     * re-centres everything above it — the stage title visibly jumped as a stream moved
     * from "connecting to peers" to "buffering".
     *
     * Visibility and alpha are always set together. Driving the fade from alpha while
     * resetting only visibility between sessions left the row invisible-but-opaque, so
     * the "should I fade in?" test read as already-shown and the stats never came back
     * after the first stream of an app run.
     */
    private fun showStatLockUp(show: Boolean) {
        val row = binding.torrentLoadingStats
        val alreadyShown = row.visibility == View.VISIBLE && row.alpha >= 1f
        if (show == alreadyShown) return
        row.animate().cancel()
        if (show) {
            row.visibility = View.VISIBLE
            row.animate().alpha(1f).setDuration(320).start()
        } else {
            row.animate().alpha(0f).setDuration(160)
                .withEndAction { row.visibility = View.INVISIBLE }
                .start()
        }
    }

    /** Speed as a bare MB/s number for the stat lock-up (label supplies the unit). */
    private fun formatMbps(bps: Long): String {
        val mbps = bps / 1_048_576.0
        return if (mbps >= 10) String.format(java.util.Locale.US, "%.0f", mbps)
        else String.format(java.util.Locale.US, "%.1f", mbps)
    }

    private fun openUrl(url: String, restore: Boolean = false, stopTorrent: Boolean = true) {
        if (stopTorrent) stopTorrentStreaming()
        dismissPageError()
        continuityStore.markAtHome(false)
        recordEvent(NavigationEvent(System.currentTimeMillis(), "user_open_url", url = url))
        setNavLoading(true)
        // Feeds the address bar's inline completion. Recorded on open rather than on
        // page-finish so a site that fails to load once still completes next time —
        // the user's intent is what is worth remembering, not the server's mood.
        urlHistoryStore.record(url)
        // Approved compatibility origins get their own stock WebView; everything else —
        // the live-stream site included — takes the normal fully protected path below.
        if (com.keenzero.app.compat.CompatibilityOrigins.isApproved(url)) {
            openUrlInCompatibility(url)
            return
        }
        exitCompatibilityMode()
        val host = ensureWebHost()
        webViewEverCreated = true
        // Session root: Back should not return to a link-directory site chooser until we leave this site stack.
        // The torrent player (stopTorrent=false) is an overlay page, not a new session root.
        if (stopTorrent) {
            browseEntryUrl = url
            browseEntryHistoryIndex = null
        }
        currentUrl = url
        val restoreCp = pendingRestore
        if (restore && restoreCp != null) {
            host.beginRestore(restoreCp)
        } else {
            restoreCp?.let { host.setRestorePosition(it.playbackPositionSec) }
        }
        binding.homeShell.visibility = View.GONE
        binding.browseShell.visibility = View.VISIBLE
        binding.browserContainer.visibility = View.VISIBLE
        binding.chromeBar.visibility = View.VISIBLE
        // Home hides the pointer layer (it is a focus surface, not a pointer one), and
        // only the compatibility path used to bring it back — so every ordinary page
        // opened after a visit to home had no cursor at all, on any site. The layer
        // hosts the cursor view itself, so hidden means the D-pad moves an invisible
        // point around the page.
        binding.pointerLayer.visibility = View.VISIBLE
        lastChromeUrl = url
        refreshBrowseChrome()
        setLoadProgress(0)
        uiState = if (restore) AppUiState.RESTORING else AppUiState.BROWSING
        if (!restore) persistBrowsingCheckpoint(url, force = true)
        hideKeyboard(binding.browseUrlEdit)
        binding.browseUrlEdit.clearFocus()
        host.load(url)
    }

    /**
     * Enter (or continue) compatibility mode for an approved origin.
     *
     * The normal WebView is destroyed rather than reconfigured: reusing it would mean
     * toggling protections off and hoping to restore them later, which is exactly the
     * failure mode this architecture exists to avoid.
     */
    /**
     * A Cloudflare verification loop was detected in normal mode: switch this origin to
     * the isolated compatibility WebView and reload, without asking.
     *
     * Being stuck on a spinner is not a state a user can act on usefully, so the browser
     * resolves it itself. The safeguards are structural rather than a prompt:
     *  - only genuine Cloudflare responses count (cf-ray / server headers, never wording),
     *    so a hostile page cannot fake its way out of Keen's blocking;
     *  - [CompatibilityOriginStore.PINNED_NORMAL] origins can never be switched;
     *  - the decision expires, so a site that stops needing it gets protections back;
     *  - a non-blocking notice says what happened, and it is revocable.
     */
    private fun switchToCompatibilityMode(host: String, url: String, reason: String) {
        if (isFinishing) return
        if (com.keenzero.app.diagnostics.ExperimentFlags.isOn(
                com.keenzero.app.diagnostics.ExperimentFlags.NO_COMPAT,
            )
        ) {
            // Kill switch is on (A/B testing): never auto-switch, or the detector would
            // fire repeatedly against a route that refuses to change.
            android.util.Log.i("KZ_CHALLENGE", "auto-switch suppressed by keen_no_compat")
            return
        }
        if (!compatSwitchedHosts.add(host)) return
        compatOriginStore.allow(host)
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "COMPAT_AUTO_SWITCH",
                url = url,
                detail = "host=$host reason=$reason",
            ),
        )
        android.util.Log.i("KZ_CHALLENGE", "auto-switching $host to compatibility mode ($reason)")
        openUrl(url)
    }

    private fun openUrlInCompatibility(url: String) {
        webViewEverCreated = true
        browseEntryUrl = url
        browseEntryHistoryIndex = null
        currentUrl = url
        webHost?.let { host ->
            host.destroy("compat_enter")
            webHost = null
        }
        binding.homeShell.visibility = View.GONE
        binding.browseShell.visibility = View.VISIBLE
        binding.browserContainer.visibility = View.VISIBLE
        binding.chromeBar.visibility = View.VISIBLE
        binding.pointerLayer.visibility = View.VISIBLE
        lastChromeUrl = url
        refreshBrowseChrome()
        setLoadProgress(0)
        uiState = AppUiState.BROWSING
        persistBrowsingCheckpoint(url, force = true)
        hideKeyboard(binding.browseUrlEdit)
        binding.browseUrlEdit.clearFocus()

        val existing = compatSession
        if (existing != null && existing.isActive) {
            existing.load(url)
            return
        }
        val session = com.keenzero.app.compat.CompatibilitySession(
            context = this,
            container = binding.browserContainer,
            cursorHost = binding.pointerLayer,
            onLeaveOrigin = { target -> openUrl(target) },
            onBack = {
                handleBack()
                true
            },
            onMagnet = { magnet -> runOnUiThread { startTorrentStreaming(magnet) } },
            onTorrentFile = { url, cookies, userAgent, base64 ->
                runOnUiThread { startTorrentFromFile(url, cookies, userAgent, base64) }
            },
            homeButtonRect = { keenLogoRectPx() },
            onHomeActivate = { runOnUiThread { returnHomeFromChrome() } },
            starButtonRect = { favouriteStarRectPx() },
            onFavouriteActivate = { runOnUiThread { toggleFavourite() } },
            chromeHeightPx = {
                // GONE chrome still reports its last height on some devices — only count when visible.
                if (binding.chromeBar.visibility != View.VISIBLE) 0
                else binding.chromeBar.height.takeIf { it > 0 }
                    ?: binding.chromeBar.measuredHeight.coerceAtLeast(0)
            },
            onUrlBarActivate = { runOnUiThread { focusBrowseUrlBar() } },
        )
        compatSession = session
        session.start(url)
    }

    /** Tear down compatibility mode, if any. Safe to call when it was never entered. */
    private fun exitCompatibilityMode() {
        compatSession?.destroy()
        compatSession = null
    }

    /**
     * Site-scoped verification reset for the current compatibility origin. Clears only
     * that origin's Cloudflare cookies and storage — never the live-stream site, never all Keen data.
     */
    private fun resetVerificationForCurrentSite() {
        compatSession?.resetVerification(currentUrl)
    }

    private fun refreshBrowseChrome() {
        // URL only — no mode callouts (DOM/pointer hints removed).
        if (!binding.browseUrlEdit.hasFocus()) {
            binding.browseUrlEdit.setText(lastChromeUrl)
        }
        updateFavIcon()
    }

    private fun updateFavIcon() {
        val fav = favouritesStore.isFavourite(currentUrl ?: lastChromeUrl)
        // Star and K logo are the same white vector already — matching the logo's own
        // alpha (chromeLogo, now fully opaque) makes a favourited star render as
        // literally the same colour as the logo, not just visually close.
        binding.chromeFavButton.alpha = if (fav) 1.0f else 0.35f
    }

    private fun toggleFavourite() {
        val url = currentUrl ?: lastChromeUrl
        val host = com.keenzero.app.favourites.FavouritesStore.hostOf(url) ?: return
        val nowFav = favouritesStore.toggle(url)
        updateFavIcon()
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "favourite_toggle",
                url = url,
                detail = "host=$host fav=$nowFav",
            ),
        )
    }

    /**
     * Star's current bounds in the same shell coordinate space as the pointer cursor
     * (both are ultimately window-relative, computed the same way regardless of view
     * hierarchy depth), so [RemoteInputRouter] can tell "pointer OK on the star" apart
     * from "pointer OK anywhere else in the chrome bar."
     */
    private fun favouriteStarRectPx(): android.graphics.RectF? {
        val star = binding.chromeFavButton
        if (star.visibility != View.VISIBLE) return null
        val starLoc = IntArray(2)
        star.getLocationInWindow(starLoc)
        val rootLoc = IntArray(2)
        binding.root.getLocationInWindow(rootLoc)
        val left = (starLoc[0] - rootLoc[0]).toFloat()
        val top = (starLoc[1] - rootLoc[1]).toFloat()
        return android.graphics.RectF(left, top, left + star.width, top + star.height)
    }

    /**
     * K logo's current bounds in the same shell coordinate space as the pointer cursor,
     * so [RemoteInputRouter] can tell "pointer OK on the logo" (→ home) apart from
     * "pointer OK anywhere else in the chrome bar" (→ URL keyboard).
     */
    private fun keenLogoRectPx(): android.graphics.RectF? {
        val logo = binding.chromeLogo
        if (logo.visibility != View.VISIBLE) return null
        val logoLoc = IntArray(2)
        logo.getLocationInWindow(logoLoc)
        val rootLoc = IntArray(2)
        binding.root.getLocationInWindow(rootLoc)
        val left = (logoLoc[0] - rootLoc[0]).toFloat()
        val top = (logoLoc[1] - rootLoc[1]).toFloat()
        return android.graphics.RectF(left, top, left + logo.width, top + logo.height)
    }

    /**
     * Show or hide the loading line at the foot of the address bar.
     *
     * This replaced a spinner drawn on the K itself. The spinner was legible but it was
     * the wrong instrument: it sat in the middle of the chrome, moved constantly, and
     * drew the eye to the one thing on screen the user was not waiting for. A line at
     * the bottom edge of the bar reports the same fact at the edge of attention, which
     * is where a progress report belongs.
     *
     * The line's own motion — the eased advance, the trickle between callbacks, the
     * sweep to full and fade — lives in [setLoadProgress] and [animateLoadProgress].
     * Starting it here at a small non-zero fraction matters: feedback has to be
     * immediate on the press, and a bar of zero width is indistinguishable from none.
     */
    private fun setNavLoading(active: Boolean) {
        if (navLoadingShown == active) return
        navLoadingShown = active
        binding.root.removeCallbacks(navLoadingProvisionalClose)
        binding.root.removeCallbacks(navLoadingSettle)
        // A progress line that outlives whatever started it is worse than none: it says
        // the box is still working when nothing is. Every start arms its own ceiling.
        binding.root.removeCallbacks(navLoadingWatchdog)
        if (active) {
            binding.root.postDelayed(navLoadingWatchdog, NAV_LOADING_MAX_MS)
            setLoadProgress(NAV_LOADING_START_PERCENT)
        } else {
            // Sweep to full and fade, rather than cutting: the line has to finish its
            // journey or the page reads as having given up rather than arrived.
            setLoadProgress(100)
        }
    }

    private fun setLoadProgress(percent: Int) {
        val bar = binding.loadProgressBar
        val p = percent.coerceIn(0, 100)
        if (p <= 0) {
            resetLoadProgress()
            return
        }
        bar.visibility = View.VISIBLE
        if (p >= 100) {
            // Sweep to full, hold briefly, then fade the whole bar out.
            animateLoadProgress(1f, durationMs = 220L) {
                bar.animate().alpha(0f).setStartDelay(120L).setDuration(180L)
                    .withEndAction {
                        if (uiState == AppUiState.BROWSING || uiState == AppUiState.RESTORING) {
                            bar.visibility = View.INVISIBLE
                        }
                        resetLoadProgress()
                    }.start()
            }
            return
        }
        // Never let a real update pull the bar backwards; ease it forward, then let
        // it keep trickling so motion never freezes between sparse WebView callbacks.
        val target = (p / 100f).coerceAtLeast(loadProgressFraction)
        animateLoadProgress(target, durationMs = 420L) { startLoadProgressTrickle() }
    }

    /** Full width of the progress track in px (falls back to the root width pre-layout). */
    private fun loadTrackWidth(): Int =
        binding.loadProgressTrack.width.takeIf { it > 0 } ?: binding.root.width

    /**
     * Drive the bar to [target] (0..1) by animating scaleX with pivot at the left
     * edge — a GPU transform, so there is no per-frame layout pass on the 2 GB box.
     * The bar is sized to the full track once and only its scale changes.
     */
    private fun animateLoadProgress(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        val bar = binding.loadProgressBar
        loadProgressAnimator?.cancel()
        loadProgressTrickling = false
        val trackW = loadTrackWidth()
        if (trackW <= 0) {
            loadProgressFraction = target
            onEnd?.invoke()
            return
        }
        if (bar.layoutParams.width != trackW) {
            bar.layoutParams = bar.layoutParams.apply { width = trackW }
        }
        bar.pivotX = 0f
        bar.alpha = 1f
        // Pin the visible scale to the current fraction so a freshly-shown bar never
        // flashes at its default full width before the first animation frame lands.
        bar.scaleX = loadProgressFraction
        loadProgressAnimator = android.animation.ValueAnimator.ofFloat(loadProgressFraction, target).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                loadProgressFraction = it.animatedValue as Float
                bar.scaleX = loadProgressFraction
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    /**
     * Between real progress callbacks, creep slowly toward a 0.9 cap so the bar
     * always looks alive without ever falsely reaching the end. Each leg re-arms
     * the next, and any real update or reset cancels the chain.
     */
    private fun startLoadProgressTrickle() {
        val cap = 0.9f
        if (loadProgressFraction >= cap) return
        val bar = binding.loadProgressBar
        loadProgressTrickling = true
        val toward = (loadProgressFraction + 0.06f).coerceAtMost(cap)
        loadProgressAnimator?.cancel()
        loadProgressAnimator = android.animation.ValueAnimator.ofFloat(loadProgressFraction, toward).apply {
            duration = 1600L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                loadProgressFraction = it.animatedValue as Float
                bar.scaleX = loadProgressFraction
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!cancelled && loadProgressTrickling) startLoadProgressTrickle()
                }
            })
            start()
        }
    }

    private fun resetLoadProgress() {
        loadProgressAnimator?.cancel()
        loadProgressAnimator = null
        loadProgressTrickling = false
        loadProgressFraction = 0f
        val bar = binding.loadProgressBar
        bar.scaleX = 0f
        bar.alpha = 1f
        bar.visibility = View.INVISIBLE
    }

    // ---- Failed / stalled page state -------------------------------------------------

    private val pageErrorVisible: Boolean
        get() = binding.errorShell.visibility == View.VISIBLE

    private val stallTimeoutMs = 20_000L

    /**
     * Translate the raw main-frame lifecycle events into the three load outcomes.
     * WebViewClient delivers these on the UI thread, but other event types on this
     * stream may not, so the view work is marshalled defensively.
     */
    private fun driveFailedLoadState(ev: NavigationEvent) {
        when (ev.type) {
            "onPageStarted" -> {
                val url = ev.url
                if (url.isNullOrBlank() || url == "about:blank") return
                runOnUiThread {
                    failedLoadUrl = url
                    mainFrameLoadErrored = false
                    dismissPageError()
                    armStallTimeout()
                    // Every main-frame load spins the mark, not just the ones Keen
                    // started itself. Following a link or submitting a search used to
                    // give no feedback at all — the page simply sat there until the new
                    // one painted, which on a slow site reads as the remote having
                    // missed the press.
                    binding.root.removeCallbacks(navLoadingProvisionalClose)
                    setNavLoading(true)
                    // Independent of anything the page reports. Some sites never cross
                    // the progress threshold and never commit a paint the WebView tells
                    // us about, and their load "finishes" only once a tail of backend
                    // requests does. Past this point the spinner is no longer describing
                    // anything the user is waiting for.
                    binding.root.removeCallbacks(navLoadingSettle)
                    binding.root.postDelayed(navLoadingSettle, NAV_LOADING_SETTLE_MS)
                }
            }
            // First paint of the main frame: the page is on screen and can be read and
            // scrolled. Whatever is still in flight is the site's own housekeeping.
            "onPageCommitVisible" -> {
                if (ev.url == "about:blank") return
                runOnUiThread { setNavLoading(false) }
            }
            // The user pressed OK on something that navigates. This lands well before
            // the load starts — often by a second or more on a slow server — and it is
            // that gap the press felt lost in, so the mark starts turning on the press
            // itself. Only for activations classified as a link or a form: a Play
            // button or an unrecognised control would spin for nothing.
            "ACTIVATION_GRANT" -> {
                val detail = ev.detail.orEmpty()
                if (!detail.contains("type=LINK") && !detail.contains("type=FORM")) return
                runOnUiThread {
                    setNavLoading(true)
                    // If no navigation follows, this was a link that did nothing (an
                    // anchor, a JS handler that bailed). Close on our own rather than
                    // leaving the mark turning until the long watchdog.
                    binding.root.removeCallbacks(navLoadingProvisionalClose)
                    binding.root.postDelayed(navLoadingProvisionalClose, NAV_PROVISIONAL_MS)
                }
            }
            "onReceivedError" -> {
                if (ev.isMainFrame != true) return
                if (ev.url == "about:blank") return
                val code = Regex("""code=(-?\d+)""").find(ev.detail.orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                runOnUiThread {
                    cancelStallTimeout()
                    mainFrameLoadErrored = true
                    ev.url?.let { failedLoadUrl = it }
                    showPageError(reasonForError(code))
                }
            }
            "onPageFinished" -> {
                if (ev.url == "about:blank") return
                runOnUiThread {
                    cancelStallTimeout()
                    // First page to finish in this session is its entry, and its position
                    // in the back-forward list is the floor Back may not walk below.
                    if (browseEntryHistoryIndex == null) {
                        browseEntryHistoryIndex = webHost?.historyIndex()
                    }
                    // The page is done, so the mark must go back to being a mark. The
                    // progress threshold alone was not enough: plenty of pages never
                    // report a value above it (cached loads jump straight to finished,
                    // and some sites stall the reported percentage in the eighties),
                    // which left the arc turning over content that had fully arrived.
                    setNavLoading(false)
                    // onPageFinished also fires for the browser's own error page, so a
                    // load that already errored must keep its overlay.
                    if (!mainFrameLoadErrored) dismissPageError()
                }
            }
        }
    }

    private fun armStallTimeout() {
        cancelStallTimeout()
        binding.root.postDelayed(stallTimeout, stallTimeoutMs)
    }

    private fun cancelStallTimeout() {
        binding.root.removeCallbacks(stallTimeout)
    }

    private fun showPageError(reason: String) {
        setNavLoading(false)
        // Never take over home or a native/torrent surface.
        if (uiState == AppUiState.HOME) return
        if (nativeTorrentPlayerActive || torrentOverlayVisible) return
        resetLoadProgress()
        val host = failedLoadUrl?.let { com.keenzero.app.favourites.FavouritesStore.hostOf(it) }
        binding.errorHost.text = host.orEmpty()
        binding.errorHost.visibility = if (host.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.errorReason.text = reason
        binding.errorShell.animate().cancel()
        binding.errorShell.alpha = 0f
        binding.errorShell.visibility = View.VISIBLE
        binding.errorShell.bringToFront()
        // Keep Keen's own pointer clickable above the takeover.
        binding.pointerLayer.bringToFront()
        binding.errorShell.animate().alpha(1f).setDuration(200).start()
        binding.errorRetry.requestFocus()
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "page_error_shown",
                url = failedLoadUrl,
                detail = reason,
            ),
        )
    }

    /** Fade the overlay out; safe to call when already hidden. */
    private fun dismissPageError() {
        cancelStallTimeout()
        if (binding.errorShell.visibility != View.VISIBLE) return
        binding.errorShell.animate().cancel()
        binding.errorShell.animate().alpha(0f).setDuration(150)
            .withEndAction {
                binding.errorShell.visibility = View.GONE
                binding.errorShell.alpha = 1f
            }
            .start()
    }

    private fun retryFailedLoad() {
        val url = failedLoadUrl ?: currentUrl
        recordEvent(NavigationEvent(System.currentTimeMillis(), "page_error_retry", url = url))
        if (url.isNullOrBlank() || url == "about:blank") {
            returnHomeFromError()
            return
        }
        // Reload through the normal open path so chrome, progress and the checkpoint
        // all reset exactly as a fresh navigation would.
        openUrl(url)
    }

    private fun returnHomeFromError() {
        dismissPageError()
        exitAllHtmlFullscreen()
        webHost?.flushSession()
        webHost?.destroy("page_error_home")
        webHost = null
        browseEntryUrl = null
        browseEntryHistoryIndex = null
        continuityStore.markAtHome(true)
        showHome(status = getString(R.string.status_home))
    }

    private fun reasonForError(code: Int?): String {
        if (!hasActiveNetwork()) return getString(R.string.error_reason_offline)
        return when (code) {
            android.webkit.WebViewClient.ERROR_HOST_LOOKUP ->
                getString(R.string.error_reason_dns)
            android.webkit.WebViewClient.ERROR_CONNECT,
            android.webkit.WebViewClient.ERROR_IO,
            android.webkit.WebViewClient.ERROR_REDIRECT_LOOP,
            android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
                getString(R.string.error_reason_connect)
            android.webkit.WebViewClient.ERROR_TIMEOUT ->
                getString(R.string.error_reason_timeout)
            else -> getString(R.string.error_reason_generic)
        }
    }

    private fun hasActiveNetwork(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) {
        // A permissions/OEM failure must never masquerade as "offline".
        true
    }

    private fun ensureWebHost(): WebViewHost {
        webHost?.let { return it }
        // Init the store before any navigation so a previously accepted origin routes
        // to compatibility mode on the very first load, not the second.
        compatOriginStore
        val host = WebViewHost(
            context = this,
            container = binding.browserContainer,
            cursorHost = binding.pointerLayer,
            fullscreenHost = binding.fullscreenContainer,
            onEvent = { ev ->
                recordEvent(ev)
                driveFailedLoadState(ev)
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
                if (!url.isNullOrBlank() &&
                    uiState == AppUiState.BROWSING
                ) {
                    persistBrowsingCheckpoint(url, force = url != lastBrowsingCheckpointUrl)
                }
                runOnUiThread {
                    if (uiState == AppUiState.BROWSING || uiState == AppUiState.WEB_FULLSCREEN ||
                        uiState == AppUiState.PLAYBACK_MODE || uiState == AppUiState.RESTORING
                    ) {
                        if (uiState != AppUiState.PLAYBACK_MODE) {
                            lastChromeUrl = url.orEmpty()
                            refreshBrowseChrome()
                        }
                    }
                    // SPA route changes never hit onProgress(100); give the new
                    // route a beat to render its meta tags, then re-probe.
                    binding.root.postDelayed({ capturePagePoster() }, 1_500L)
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
            onPlaybackActive = { active ->
                PlaybackPriorityService.setPlaybackActive(this, active)
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
                        showHome(status = getString(R.string.renderer_gone_restore))
                        pendingRestore = cp.takeIf { it.requiresMediaRestore() }
                        restoreMetricEmitted = false
                        // Automatic recovery: recreate and restore checkpoint.
                        openUrl(cp.url!!, restore = cp.requiresMediaRestore())
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
                runOnUiThread {
                    // Only while a load is being reported. Once the line has finished
                    // and faded — on first paint, well before the last request lands —
                    // a trailing progress callback must not bring it back.
                    if (navLoadingShown) setLoadProgress(percent)
                    // Real progress past the fold means the page is not stalled — a
                    // "not responding" takeover would only cover usable content.
                    if (percent >= 80) cancelStallTimeout()
                    if (percent >= 100) {
                        setNavLoading(false)
                        capturePagePoster()
                    }
                }
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
            starButtonRectPx = { favouriteStarRectPx() },
            onFavouriteActivate = {
                runOnUiThread { toggleFavourite() }
            },
            homeButtonRectPx = { keenLogoRectPx() },
            onHomeActivate = {
                runOnUiThread { returnHomeFromChrome() }
            },
            onConfirmNavigation = { url, host, reason ->
                runOnUiThread {
                    showNavigationConfirm(url, host, reason)
                }
            },
            onMagnetIntent = { magnet ->
                runOnUiThread { startTorrentStreaming(magnet) }
            },
            onTorrentFileIntent = { url, cookies, userAgent, base64 ->
                runOnUiThread { startTorrentFromFile(url, cookies, userAgent, base64) }
            },
            onCheckpoint = { rawCp ->
                // Attach the playing page's artwork for the Continue card.
                val cp = if (rawCp.posterUrl.isNullOrBlank() &&
                    !currentPagePosterUrl.isNullOrBlank() &&
                    samePageKey(currentPagePosterForUrl, rawCp.url ?: currentUrl)
                ) {
                    rawCp.copy(posterUrl = currentPagePosterUrl)
                } else {
                    // No artwork of its own: leave it blank so the card falls back to the
                    // placeholder. A wrong image is worse than no image.
                    rawCp
                }
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
        host.onChallengeLoop = { h, u, reason ->
            runOnUiThread { switchToCompatibilityMode(h, u, reason) }
        }
        host.onSelectPopup = { payload ->
            runOnUiThread { showSelectPopup(payload) }
        }
        android.util.Log.i("KZ_CHALLENGE", "loop callback attached to new WebViewHost")
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
    /**
     * Idempotent "put the browsing UI back". Each exit path used to restore a different
     * subset, so whether the URL bar returned depended on which layer was active.
     */
    private fun restoreBrowsingChrome(reason: String) {
        exitImmersive()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.fullscreenContainer.visibility = View.GONE
        binding.browseShell.visibility = View.VISIBLE
        binding.browserContainer.visibility = View.VISIBLE
        binding.chromeBar.visibility = View.VISIBLE
        uiState = AppUiState.BROWSING
        webHost?.setMediaPointerLock(false)
        webHost?.webView?.requestFocus()
        // NEVER bringToFront() the chrome bar: it is a child of the vertical LinearLayout
        // `browseColumn`, and bringToFront re-adds the view at the END of its parent's
        // child list — in a LinearLayout that means it is laid out LAST, i.e. moved to the
        // BOTTOM of the screen. It also desynced pointer hit-testing, because
        // chromeHeightPx() still reported a top chrome inset, so taps on the site's own
        // top buttons opened the URL keyboard instead. Only pointerLayer/homeShell may use
        // bringToFront — those are children of the root FrameLayout, where it is z-order only.
        ensurePointerAboveContent()
        android.util.Log.i(
            "KeenBack",
            "restore_chrome reason=$reason chromeVis=${binding.chromeBar.visibility} " +
                "fsHost=${binding.fullscreenContainer.visibility} " +
                "playbackMode=${webHost?.isPlaybackMode} customView=${webHost?.chromeClient?.isFullscreen}",
        )
    }

    private fun handleBack() {
        // Failed / stalled page takeover: Back is the advertised way out — return home.
        if (pageErrorVisible) {
            recordEvent(
                NavigationEvent(System.currentTimeMillis(), "page_error_back", url = failedLoadUrl),
            )
            returnHomeFromError()
            return
        }
        // Native torrent player (including seek re-buffering with the loader up):
        // leaving must stop the session so the cache (video + .torrent) is deleted.
        if (nativeTorrentPlayerActive) {
            exitNativeTorrentPlayer("back")
            return
        }
        // Torrent startup overlay: Back cancels the download and stays put.
        if (torrentOverlayVisible) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "torrent_cancel", url = currentUrl))
            stopTorrentStreaming()
            // Same self-heal as the player exit: if the overlay survived teardown this
            // branch would swallow every future Back press.
            binding.torrentLoadingOverlay.visibility = View.GONE
            android.util.Log.i("KeenBack", "torrent_cancel overlayVis=${binding.torrentLoadingOverlay.visibility}")
            returnToSourcePageOrHome("cancel")
            return
        }
        // Compatibility mode owns Back only once the overlay surfaces above have had
        // their turn. Placing this first meant a magnet opened from a compatibility page
        // could never be exited: Back walked the hidden WebView's history (or dropped to
        // home) instead of closing the player and returning to the page it was launched
        // from.
        compatSession?.let { session ->
            if (session.isActive) {
                if (session.canGoBack()) {
                    session.goBack()
                } else {
                    exitCompatibilityMode()
                    continuityStore.markAtHome(true)
                    showHome(status = getString(R.string.status_home))
                }
                return
            }
        }
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
        // "Can go back" has to mean "within this session". A WebView reused between
        // sessions keeps the previous one's entries, so the raw canGoBack() was true on
        // the very first page of a new session and Back walked sideways into pages the
        // user had never opened from here instead of returning home.
        val entryIndex = browseEntryHistoryIndex
        val inSessionBack = webHost?.canGoBack() == true &&
            (entryIndex == null || (webHost?.historyIndex() ?: -1) > entryIndex)
        val action = com.keenzero.app.navigation.BrowsingBackPolicy.decide(
            surface = surface,
            htmlCustomViewActive = customViewFs,
            documentFullscreen = uiState == AppUiState.WEB_FULLSCREEN,
            webViewCanGoBack = inSessionBack,
            atBrowseEntry = atEntry,
            urlBarFocused = binding.browseUrlEdit.hasFocus(),
        )
        android.util.Log.i(
            "KeenBack",
            "decide action=$action surface=$surface customView=$customViewFs " +
                "playbackMode=${webHost?.isPlaybackMode} canGoBack=${webHost?.canGoBack()} " +
                "inSessionBack=$inSessionBack idx=${webHost?.historyIndex()} entryIdx=$entryIndex " +
                "atEntry=$atEntry chromeVis=${binding.chromeBar.visibility}",
        )
        when (action) {
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.EXIT_FULLSCREEN,
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.EXIT_PLAYBACK_MODE,
            -> {
                // One press = "get me out of the video", always. Peel every layer that
                // could be up (custom view, document fullscreen, the CSS fill chain, then
                // playback mode) and restore the browsing surface once, at the end.
                // Splitting these produced the "back does nothing / URL bar gone" state.
                exitAllHtmlFullscreen()
                // Unconditional: it no-ops if mode is already down, but always arms the
                // suppression that stops the playback poller re-entering behind us.
                webHost?.exitPlaybackMode("back")
                applyKeenPlaybackMode(false)
                restoreBrowsingChrome("back:$action")
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "exit_playback_surface",
                        url = currentUrl,
                        detail = action.name,
                    ),
                )
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.CLEAR_URL_FOCUS -> {
                hideKeyboard(binding.browseUrlEdit)
                binding.browseUrlEdit.clearFocus()
                webHost?.webView?.requestFocus()
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.HISTORY_BACK -> {
                // Movie page → previous site page (search/list), never a link-directory site chooser mid-site.
                exitAllHtmlFullscreen()
                // Walk the native back-forward list ourselves. Falling through to JS
                // history.back() let an ad chain answer each press by pushing another
                // entry, so Back climbed the stack into a new throwaway domain every time.
                val walked = webHost?.historyBackSafe(browseEntryUrl) ?: false
                if (!walked) {
                    // No clean earlier entry: the only way out of a poisoned stack is off it.
                    webHost?.historyBack()
                }
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "history_back",
                        url = currentUrl,
                        detail = "entry=$browseEntryUrl walked=$walked " +
                            "nativeCanGoBack=${webHost?.canGoBack()}",
                    ),
                )
            }
            com.keenzero.app.navigation.BrowsingBackPolicy.Action.RETURN_HOME -> {
                // Only when already at session entry URL (e.g. an SPA home after openUrl).
                exitAllHtmlFullscreen()
                webHost?.flushSession()
                webHost?.destroy("return_home")
                webHost = null
                browseEntryUrl = null
                browseEntryHistoryIndex = null
                // Deliberate back-out to home: cold starts stay here (Continue card).
                continuityStore.markAtHome(true)
                showHome(status = getString(R.string.status_home) + " (returned)")
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
        // Embed players that could not get real fullscreen were expanded with CSS
        // across the iframe chain — collapse that too or the page stays covered.
        webHost?.webView?.evaluateJavascript(
            com.keenzero.app.web.FramePlayerJs.EXIT_FILL_JS,
            null,
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Native torrent playback: PlayerView owns DPAD/media keys — checked BEFORE
        // the URL bar so a stale EditText/IME focus can never eat OK into a keyboard.
        if (nativeTorrentPlayerActive) {
            if (binding.browseUrlEdit.hasFocus()) {
                hideKeyboard(binding.browseUrlEdit)
                binding.browseUrlEdit.clearFocus()
            }
            if (binding.torrentPlayerView.findFocus() == null) {
                binding.torrentPlayerView.requestFocus()
            }
            // The next-episode offer owns OK while it is up. It is the only thing on
            // screen asking to be pressed at that moment, and claiming the key here is
            // what makes it reachable at all — every key below is routed to the
            // PlayerView regardless of focus, so a focusable button could never win it.
            if (nextEpisodeArmed) {
                if (event.action == KeyEvent.ACTION_UP &&
                    (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_BUTTON_A)
                ) {
                    playNextEpisode()
                    return true
                }
                // Back is "no thanks": withdraw the offer and let the credits run. It must
                // not also leave the player, so it is consumed here.
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        nextEpisodeDeclined = true
                        dismissNextEpisode()
                    }
                    return true
                }
                // Any DPAD press is the user reaching for the film, not the offer — but
                // swallow only the OK/Back above, so seeking still works underneath.
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                return super.dispatchKeyEvent(event)
            }
            // Timeline seeking is Keen-owned: short taps step gently, holding
            // accelerates with hold time, and the single seek commits on release
            // (one piece-deadline reset instead of one per repeat).
            if (handleTorrentSeekKey(event)) {
                return true
            }
            // Deliver to the PlayerView regardless of focus: OK/DPAD shows the
            // controller, media keys act, remaining keys fall through normally.
            return binding.torrentPlayerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        }
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
        // Compatibility mode: native D-pad controller owns the remote. It never
        // consumes Back, so Keen/Android back handling stays intact.
        compatSession?.let { session ->
            if (uiState != AppUiState.HOME && session.isActive &&
                event.keyCode != KeyEvent.KEYCODE_BACK && session.handleKey(event)
            ) {
                return true
            }
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
     * Hold-to-seek for the native torrent player.
     *
     * A tap moves ±10 s. Holding accumulates a pending target whose rate grows
     * with hold time (up to ~4 min of media per held second), with live feedback
     * in [showTorrentSeekPreview]; the player only seeks once, on key release —
     * far kinder to the torrent bridge than a seek per key repeat.
     */
    private fun handleTorrentSeekKey(event: KeyEvent): Boolean {
        val player = torrentPlayer ?: return false
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> false
            else -> return false
        }
        // Key-up ALWAYS finishes an in-progress gesture: commit the target and clear
        // the on-screen preview. This must run before any focus check, because
        // showController() (below) moves focus to the scrubber during the hold — a
        // focus-gated bail here was what left "7:15 (−0:19)" stuck on screen.
        if (event.action == KeyEvent.ACTION_UP) {
            if (!torrentSeekActive) return false
            torrentSeekActive = false
            commitTorrentSeek()
            return true
        }
        val durationMs = player.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return false
        if (!torrentSeekActive) {
            // Start gate — only decided on the first press. Defer to Media3 solely when
            // the controls are up and a control *button* (not the scrubber) is focused,
            // so left/right still moves between subtitle/settings buttons. In every other
            // case (controls hidden, or the scrubber focused) Keen owns the accelerating
            // hold-seek so hold behaves consistently instead of falling back to a flat
            // one-minute native step.
            val focused = binding.torrentPlayerView.findFocus()
            val onControlButton = focused != null && focused !is androidx.media3.ui.DefaultTimeBar
            if (binding.torrentPlayerView.isControllerFullyVisible && onControlButton) return false
            torrentSeekActive = true
            if (torrentSeekTargetMs < 0) torrentSeekTargetMs = player.currentPosition
            // Media3's controller has to go: while it is up it rewrites its own scrubber
            // from the live playback position, which is the wrong story during a hold
            // (the playhead has not moved yet) and produced the jitter this bar replaces.
            binding.torrentPlayerView.hideController()
            binding.torrentScrubTrack.visibility = View.VISIBLE
            binding.root.removeCallbacks(torrentScrubTick)
            binding.root.post(torrentScrubTick)
        }
        val now = event.eventTime
        val stepMs = if (event.repeatCount == 0) {
            TORRENT_SEEK_TAP_MS
        } else {
            // Steady base rate for the first few seconds (fine control), then rate
            // (media-seconds per held second) keeps climbing with hold time; each repeat
            // advances by rate × time since the last repeat.
            val heldSec = (now - event.downTime) / 1000.0
            val accelSec = (heldSec - TORRENT_SEEK_ACCEL_DELAY_SEC).coerceAtLeast(0.0)
            val rate = (TORRENT_SEEK_RATE_BASE + TORRENT_SEEK_RATE_GROWTH * accelSec)
                .coerceAtMost(TORRENT_SEEK_RATE_MAX)
            val dtMs = (now - torrentSeekLastEventMs).coerceIn(16L, 250L)
            (rate * dtMs).toLong()
        }
        torrentSeekLastEventMs = now
        torrentSeekTargetMs = (torrentSeekTargetMs + if (forward) stepMs else -stepMs)
            .coerceIn(0L, durationMs)
        showTorrentSeekPreview(forward)
        return true
    }

    private fun showTorrentSeekPreview(forward: Boolean) {
        val player = torrentPlayer ?: return
        val deltaMs = torrentSeekTargetMs - player.currentPosition
        val sign = if (deltaMs >= 0) "+" else "−"
        binding.torrentSeekPreview.text = String.format(
            java.util.Locale.US,
            "%s  %s   (%s%s)",
            if (forward) "»" else "«",
            formatClock(torrentSeekTargetMs / 1000),
            sign,
            formatClock(kotlin.math.abs(deltaMs) / 1000),
        )
        binding.torrentSeekPreview.visibility = View.VISIBLE
    }

    private fun commitTorrentSeek() {
        torrentSeekActive = false
        // Stop driving the scrubber; from here Media3's own progress loop owns it again.
        binding.root.removeCallbacks(torrentScrubTick)
        binding.torrentSeekPreview.visibility = View.GONE
        binding.torrentScrubTrack.visibility = View.GONE
        val target = torrentSeekTargetMs
        torrentSeekTargetMs = -1L
        val player = torrentPlayer ?: return
        if (target >= 0 && kotlin.math.abs(target - player.currentPosition) > 250L) {
            recordEvent(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "torrent_seek_commit",
                    detail = "from=${player.currentPosition} to=$target",
                ),
            )
            player.seekTo(target)
        }
    }

    /**
     * Keen pointer is a root sibling — always above browse shell and HTML custom-view host.
     * Never parent the cursor into the WebView or fullscreen custom view.
     */
    private fun ensurePointerAboveContent() {
        binding.pointerLayer.elevation = 32f
        binding.pointerLayer.bringToFront()
        // Confirmation / system overlays may sit higher; keep home under pointer while browsing.
        if (binding.homeShell.visibility == View.VISIBLE) {
            binding.homeShell.bringToFront()
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
            binding.homeShell.visibility = View.GONE
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

    /**
     * The K mark in the address bar is a deliberate "home" affordance: tear the
     * live session down and return to the initial black home canvas, mirroring the
     * RETURN_HOME back action so a cold start also lands on the Continue surface.
     */
    private fun returnHomeFromChrome() {
        if (uiState == AppUiState.HOME) return
        exitAllHtmlFullscreen()
        webHost?.flushSession()
        webHost?.destroy("chrome_logo_home")
        webHost = null
        browseEntryUrl = null
        browseEntryHistoryIndex = null
        continuityStore.markAtHome(true)
        showHome(status = getString(R.string.status_home) + " (logo)")
    }

    private fun showHome(status: String) {
        stopTorrentStreaming()
        dismissPageError()
        uiState = AppUiState.HOME
        exitImmersive()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.browseShell.visibility = View.GONE
        binding.browserContainer.visibility = View.GONE
        binding.fullscreenContainer.visibility = View.GONE
        binding.chromeBar.visibility = View.GONE
        binding.homeShell.visibility = View.VISIBLE
        binding.homeUrlInput.setText("")
        // Home is a focus surface, not a pointer one. A compatibility session left alive
        // here keeps its native cursor on screen and swallows D-pad keys before the home
        // rows ever see them, so it must go the moment we land here — every route home
        // funnels through this method.
        exitCompatibilityMode()
        binding.pointerLayer.visibility = View.GONE
        hydrateContinuitySurface()
        focusHomeDefault()
        recordEvent(
            NavigationEvent(System.currentTimeMillis(), "home_shown", detail = status),
        )
    }

    /**
     * Where the remote starts on the home surface: first favourite, else the first
     * Continue-watching card, else the URL field. Posted because the rows are populated
     * by [hydrateContinuitySurface] in the same pass and are not laid out yet.
     */
    private fun focusHomeDefault() {
        binding.homeShell.post {
            val target = binding.favsRow.takeIf { it.childCount > 0 && it.isShown }?.getChildAt(0)
                ?: binding.continueRow.takeIf { it.childCount > 0 && it.isShown }?.getChildAt(0)
                ?: binding.homeUrlInput
            target.isFocusable = true
            target.isFocusableInTouchMode = true
            target.requestFocus()
        }
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = MemoryPressureDiagnostics.record(this, level, "activity")
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "MEMORY_PRESSURE",
                url = currentUrl,
                detail = pressure.detail,
            ),
        )
        webHost?.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val pressure = MemoryPressureDiagnostics.recordLowMemory(this, "activity")
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "MEMORY_PRESSURE",
                url = currentUrl,
                detail = pressure.detail,
            ),
        )
        webHost?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onDestroy() {
        latestCheckpoint?.let { continuityStore.save(it, force = true) }
        exitCompatibilityMode()
        webHost?.flushSession()
        webHost?.destroy("activity_destroy")
        webHost = null
        stopTorrentStreaming()
        unregisterReceiver(torrentReceiver)
        super.onDestroy()
    }

    private fun persistBrowsingCheckpoint(url: String, force: Boolean) {
        if (url.isBlank()) return
        val uri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            null
        }
        val origin = uri?.let { parsed ->
            if (parsed.scheme.isNullOrBlank() || parsed.host.isNullOrBlank()) null
            else "${parsed.scheme}://${parsed.host}${if (parsed.port >= 0) ":${parsed.port}" else ""}"
        }
        val checkpoint = ContinuityCheckpoint(
            origin = origin,
            url = url,
            title = binding.browseUrlEdit.text?.toString()?.takeIf { it.isNotBlank() },
            journeyState = PlaybackJourneyState.BROWSING.name,
        )
        latestCheckpoint = checkpoint
        lastBrowsingCheckpointUrl = url
        continuityStore.save(checkpoint, force = force)
        recordEvent(
            NavigationEvent(
                System.currentTimeMillis(),
                "CONTINUITY_CHECKPOINT",
                url = url,
                detail = "journey=BROWSING reason=url_change durable=$force",
            ),
        )
    }

    private fun formatClock(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, sec)
        }
    }

    /**
     * Human title for the Continue card: strips release-name noise
     * ("Show.S03E04.1080p.x265-GRP.mkv" → "Show S03E04").
     */
    private fun prettyMediaTitle(raw: String?): String? {
        var t = raw?.trim().orEmpty()
        if (t.isBlank()) return null
        t = t.replace(Regex("""\.(mkv|mp4|avi|m4v|ts|webm|mov)$""", RegexOption.IGNORE_CASE), "")
        if (!t.contains(' ')) t = t.replace('.', ' ').replace('_', ' ')
        val cut = Regex(
            """\b(2160p|1080p|720p|480p|WEB[- ]?DL|WEBRip|BluRay|BDRip|BRRip|HDR(10)?|HDTV|x264|x265|[Hh][ .]?26[45]|HEVC|AVC|AAC|DDP?[0-9.]*|Atmos|10bit|REPACK|PROPER|iNTERNAL|AMZN|NF|DSNP)\b""",
        ).find(t)?.range?.first
        if (cut != null && cut > 3) t = t.substring(0, cut)
        return t.trim(' ', '-', '.', '[', '(').ifBlank { raw?.trim() }
    }

    /**
     * Poster for the Continue card. Single-slot disk cache so the artwork
     * survives cold starts and offline launches; falls back to the branded
     * placeholder already in the layout when there is nothing to show.
     */
    /**
     * Load a card's artwork into [poster] (with [fallback] shown until it lands).
     * "frame:" URLs come from the single captured-frame slot; http(s) URLs are
     * fetched and cached per-URL so each card in the row keeps its own poster.
     */
    private fun loadPosterInto(
        posterUrl: String?,
        poster: android.widget.ImageView,
        fallback: android.widget.ImageView,
    ) {
        poster.animate().cancel()
        fallback.animate().cancel()
        poster.alpha = 1f
        poster.visibility = View.GONE
        fallback.alpha = 0.22f
        fallback.visibility = View.VISIBLE
        if (posterUrl.isNullOrBlank()) return
        Thread({
            try {
                val bitmap = if (posterUrl.startsWith("frame:")) {
                    // Per-title file. No fallback to the old shared slot on purpose: a
                    // missing frame shows the branded placeholder, and a placeholder is
                    // better than another card's screenshot.
                    val frame = java.io.File(filesDir, "continue/" + frameFileName(posterUrl))
                    if (frame.exists()) {
                        android.graphics.BitmapFactory.decodeFile(frame.absolutePath)
                            ?.takeUnless { looksBlack(it) || looksGarbled(it) }
                    } else {
                        null
                    }
                } else if (posterUrl.startsWith("res:")) {
                    val id = resources.getIdentifier(posterUrl.removePrefix("res:"), "drawable", packageName)
                    if (id != 0) android.graphics.BitmapFactory.decodeResource(resources, id) else null
                } else {
                    val dir = java.io.File(filesDir, "posters").apply { mkdirs() }
                    val cacheFile = java.io.File(dir, "${posterUrl.hashCode()}.img")
                    if (cacheFile.exists()) {
                        android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)
                    } else {
                        fetchPosterBitmap(posterUrl)?.also { fetched ->
                            java.io.FileOutputStream(cacheFile).use { out ->
                                fetched.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                            }
                        }
                    }
                }
                if (bitmap != null) {
                    runOnUiThread {
                        poster.setImageBitmap(bitmap)
                        poster.alpha = 0f
                        poster.visibility = View.VISIBLE
                        poster.animate().alpha(1f).setDuration(260).start()
                        fallback.animate().alpha(0f).setDuration(260)
                            .withEndAction { fallback.visibility = View.GONE }
                            .start()
                    }
                }
            } catch (_: Throwable) {
            }
        }, "keen-poster").apply { isDaemon = true }.start()
    }

    private fun fetchPosterBitmap(url: String): android.graphics.Bitmap? {
        val conn = java.net.URL(url).openConnection() as? java.net.HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) Keen")
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > POSTER_MAX_BYTES) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= POSTER_MAX_WIDTH_PX) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Grab the page's og:image / video poster once per URL — attached to media
     * checkpoints so the Continue card has artwork.
     */
    /**
     * Same content page, ignoring query/fragment — SPA routes and tracking params must not
     * count as a different page, but a different title must never match.
     */
    private fun samePageKey(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        fun key(u: String): String = u.substringBefore('#').substringBefore('?').trimEnd('/')
        return key(a) == key(b)
    }

    private fun capturePagePoster() {
        val wv = webHost?.webView ?: return
        val pageUrl = currentUrl ?: return
        if (pageUrl == posterProbeUrl) return
        posterProbeUrl = pageUrl
        wv.evaluateJavascript(PAGE_POSTER_JS) { raw ->
            val value = raw?.trim()?.trim('"')?.takeIf {
                it.isNotBlank() && it != "null" &&
                    (it.startsWith("https://") || it.startsWith("http://"))
            }
            // The playing page's own artwork (or none) — never a stale carry-over.
            currentPagePosterUrl = value
            currentPagePosterForUrl = pageUrl
        }
    }

    companion object {
        /** Runs the loading overlay on invented numbers — see startMockLoadingOverlay. */
        const val EXTRA_LAB_MOCK_LOADING = "com.keenzero.app.extra.LAB_MOCK_LOADING"
        private const val MOCK_TICK_MS = 500L
        private const val MOCK_CONNECT_SEC = 6f
        private const val MOCK_BUFFER_SEC = 14f

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
        /** Debug/lab: seed fake Favs + a Continue watching checkpoint to preview home UI. */
        const val EXTRA_LAB_UI_PREVIEW = "com.keenzero.app.extra.LAB_UI_PREVIEW"
        /** Removes everything EXTRA_LAB_UI_PREVIEW seeded, and nothing else. */
        const val EXTRA_LAB_UI_PREVIEW_CLEAR = "com.keenzero.app.extra.LAB_UI_PREVIEW_CLEAR"
        /** Logs the current favourites so a demo edit can be undone exactly. */
        const val EXTRA_LAB_FAVS_DUMP = "com.keenzero.app.extra.LAB_FAVS_DUMP"
        /** Comma-separated hosts to drop from favourites. */
        const val EXTRA_LAB_FAVS_REMOVE = "com.keenzero.app.extra.LAB_FAVS_REMOVE"
        /** Comma-separated URLs to add back to favourites. */
        const val EXTRA_LAB_FAVS_ADD = "com.keenzero.app.extra.LAB_FAVS_ADD"
        /** Optional title substring: drops matching Continue-watching entries too. */
        const val EXTRA_LAB_CLEAR_TITLE = "com.keenzero.app.extra.LAB_CLEAR_TITLE"
        private const val UI_PREVIEW_CONTENT_ID = "keen-ui-preview"
        private val UI_PREVIEW_SITES = listOf(
            "https://archive.org/",
            "https://en.wikipedia.org/",
            "https://www.blender.org/",
            "https://webtorrent.io/",
            "https://www.nasa.gov/",
        )

        /**
         * Mock Continue-watching row. Freely licensed open movies throughout, so a capture
         * of this screen can be published: nothing here names or shows anyone's copyright.
         *
         * Artwork comes from "frame:" keys rather than http URLs — the frame cache is on
         * disk, so the row paints identically offline and on the first frame, instead of
         * a capture catching three empty placeholders waiting on a network fetch.
         */
        private fun uiPreviewRecents(now: Long = System.currentTimeMillis()) = listOf(
            ContinuityCheckpoint(
                url = "https://archive.org/details/Sintel",
                contentId = "keen-ui-preview-sintel",
                title = "Sintel 2010 1080p BluRay x264",
                playerType = "web",
                playbackPositionSec = 604.0,
                durationSec = 888.0,
                posterUrl = "frame:keen-preview-sintel",
                playbackMode = true,
                timestampMs = now - 4 * 60_000L,
            ),
            ContinuityCheckpoint(
                url = "https://archive.org/details/CosmosLaundromat",
                contentId = "keen-ui-preview-cosmos",
                title = "Cosmos Laundromat 2015 2160p WEB-DL x265",
                playerType = "web",
                playbackPositionSec = 226.0,
                durationSec = 728.0,
                posterUrl = "frame:keen-preview-cosmos",
                playbackMode = true,
                timestampMs = now - 3 * 60 * 60_000L,
            ),
            ContinuityCheckpoint(
                url = "https://archive.org/details/TearsOfSteel",
                contentId = "keen-ui-preview-tears",
                title = "Tears of Steel 2012 1080p WEBRip x264",
                playerType = "web",
                playbackPositionSec = 88.0,
                durationSec = 734.0,
                posterUrl = "frame:keen-preview-tears",
                playbackMode = true,
                timestampMs = now - 2 * 24 * 60 * 60_000L,
            ),
        )

        /** Every contentId the preview seed writes, so the clear path can undo all of it. */
        private val UI_PREVIEW_CONTENT_IDS =
            (uiPreviewRecents().mapNotNull { it.contentId } + UI_PREVIEW_CONTENT_ID).toSet()

        /**
         * Mock Downloaded row: one finished title, one in flight, one queued.
         *
         * `origin` is deliberately blank. resumeInterruptedDownloads() skips records with
         * no origin, so seeding an unfinished download cannot start a real service or put
         * a byte on the wire — the row is a picture of a download, not a download.
         */
        /** Shared by every seeded key, so the demo row can be selected by prefix alone. */
        private const val UI_PREVIEW_LIBRARY_PREFIX = "keenpreview"

        private fun uiPreviewLibrary(now: Long = System.currentTimeMillis()) = listOf(
            com.keenzero.app.library.StarredLibraryStore.Entry(
                key = "keenpreview8b3d2f6a1c9e4d7b5a0f2e8c1d4b7a9f",
                origin = "",
                title = "Big Buck Bunny 2008 1080p BluRay x264",
                state = com.keenzero.app.library.StarredLibraryStore.State.COMPLETE,
                downloadedBytes = 691_000_000L,
                totalBytes = 691_000_000L,
                mediaPath = null,
                starredAtMs = now - 6 * 60_000L,
            ),
            com.keenzero.app.library.StarredLibraryStore.Entry(
                key = "keenpreview3e6c2d5b8a1f4d7e9c0b3a6f2e5d8c1b",
                origin = "",
                title = "Elephants Dream 2006 1080p WEB-DL x264",
                state = com.keenzero.app.library.StarredLibraryStore.State.DOWNLOADING,
                downloadedBytes = 313_000_000L,
                totalBytes = 824_000_000L,
                mediaPath = null,
                starredAtMs = now - 9 * 60_000L,
                speedBps = 1_400_000L,
            ),
            com.keenzero.app.library.StarredLibraryStore.Entry(
                key = "keenpreview5a0f2e8c1d4b7a9f3e6c2d5b8b3d2f6a",
                origin = "",
                title = "Caminandes Llamigos 2016 1080p WEBRip x264",
                state = com.keenzero.app.library.StarredLibraryStore.State.QUEUED,
                downloadedBytes = 0L,
                totalBytes = 486_000_000L,
                mediaPath = null,
                starredAtMs = now - 11 * 60_000L,
            ),
        )
        /** Debug/lab: also pop the torrent-loading spinner overlay for a few seconds. */
        const val EXTRA_LAB_UI_PREVIEW_SPINNER = "com.keenzero.app.extra.LAB_UI_PREVIEW_SPINNER"
        /** How long the loading scrim holds after collapse() starts, so the spinner's
         * wind-down is actually visible instead of the overlay vanishing mid-motion. */
        const val TORRENT_COLLAPSE_HOLD_MS = 650L
        const val VERTICAL_SLICE_URL =
            "https://appassets.androidplatform.net/assets/lab/vertical_slice.html"
        const val STRESS_URL =
            "https://appassets.androidplatform.net/assets/lab/stress.html"
        const val REMOTE_FIXTURE_URL =
            "https://appassets.androidplatform.net/assets/lab/remote_control_fixture.html"
        private const val MAX_EVENTS = 400
        /**
         * Bridge reads block on missing pieces; allow slow swarms before failing.
         * Far timeline seeks restart buffering at the new position with the
         * loader up — the wait must outlive a slow swarm refilling the window.
         */
        private const val TORRENT_HTTP_TIMEOUT_MS = 120_000

        /** Per-key step for the focused scrubber circle's native left/right scrub:
         * one minute of media, so pressing/holding walks it by the minute. */
        private const val TORRENT_TIMEBAR_KEY_INCREMENT_MS = 60_000L

        /** Shown where a stat has no measurement yet — never a bare, dead-looking 0. */
        private const val STAT_PENDING = "—"
        private const val BYTES_PER_MB = 1_048_576L

        /** Subtitle height as a fraction of the video view; media3's default is 0.0533. */
        private const val SUBTITLE_TEXT_SIZE_FRACTION = 0.045f
        /** Target width of the focus border micro-animation (grows inward). */
        private const val FOCUS_BORDER_WIDTH_DP = 3f
        /** Single DPAD tap in the torrent player: gentle 10 s step. */
        private const val TORRENT_SEEK_TAP_MS = 10_000L
        /** Hold-to-seek rate for the first [TORRENT_SEEK_ACCEL_DELAY_SEC] of a hold
         * (media-seconds per held second) — steady scrubbing, no acceleration yet. */
        private const val TORRENT_SEEK_RATE_BASE = 30.0
        /** How long a hold stays at the base rate before it starts accelerating. */
        private const val TORRENT_SEEK_ACCEL_DELAY_SEC = 5.0
        /** Rate growth per second once past the delay — keeps getting faster the longer
         * the hold continues, not an instant ramp-to-ceiling. */
        private const val TORRENT_SEEK_RATE_GROWTH = 45.0
        /** Rate ceiling (~8 min of media per held second), reached after ~10s of
         * acceleration (~15s total hold). */
        private const val TORRENT_SEEK_RATE_MAX = 480.0

        private const val POSTER_PREFS = "keen_continue_card"
        private const val POSTER_SRC_KEY = "poster_src"
        private const val POSTER_MAX_BYTES = 8_000_000
        private const val POSTER_MAX_WIDTH_PX = 1280

        /**
         * First grab lands ~75s into playback, then a rolling refresh every 5
         * minutes, plus grabs on pause/exit/TV-off — so the Continue card
         * always shows a recent scene, not a stale first-minute frame.
         */
        private const val TORRENT_FRAME_FIRST_DELAY_MS = 75_000L
        private const val TORRENT_FRAME_REFRESH_MS = 300_000L
        private const val TORRENT_FRAME_RETRY_MS = 90_000L
        private const val TORRENT_FRAME_MAX_ATTEMPTS = 6
        /** Don't snapshot title cards / warm-up: need at least this much watched. */
        // Torrent playback buffering. Deliberately time-generous and byte-frugal: the
        // seconds come from the swarm's read-ahead on disk, the bytes are capped to
        // protect a 256 MB heap shared with the WebView.
        /** Poster grab window for a finished download: 30 s in at the earliest, 5 min at the latest. */
        private const val LIBRARY_POSTER_MIN_MS = 30_000L
        private const val LIBRARY_POSTER_MAX_MS = 300_000L

        /** ~60fps re-assert of the scrubber position during a hold-seek. */
        private const val TORRENT_SCRUB_FRAME_MS = 16L

        /** How often the Downloaded row re-reads the library index while downloading. */
        private const val DOWNLOAD_TICK_MS = 1_000L

        /**
         * Where the line starts the moment a navigation begins.
         *
         * Non-zero on purpose: the whole point of starting on the press is that the
         * user sees something immediately, and a zero-width bar is no different from
         * an absent one. From here [startLoadProgressTrickle] keeps it creeping until
         * real progress arrives.
         */
        private const val NAV_LOADING_START_PERCENT = 6

        /** Hard ceiling on a load's line, so it can never be left running for ever. */
        private const val NAV_LOADING_MAX_MS = 25_000L

        /**
         * How long a press is given to turn into a navigation before its optimistic
         * spin is closed. Long enough to cover a slow server's first byte, short enough
         * that a link which does nothing does not leave the mark turning.
         */
        private const val NAV_PROVISIONAL_MS = 2_500L

        /**
         * Hard cap on a real load's spin, measured from the page starting.
         *
         * The 25s watchdog exists to catch a stuck spinner; this is a different claim:
         * that after a few seconds the spinner has stopped being informative whether the
         * page is done or not. Either the content arrived — in which case the remaining
         * requests are the site's own business — or it did not, and a turning arc is not
         * what tells the user that.
         */
        private const val NAV_LOADING_SETTLE_MS = 4_000L

        /** Saved-site tiles: a name on a dark slab. See buildFavRoundel. */
        private const val FAV_NAME_CHARS = 10
        private const val FAV_TILE_HEIGHT_DP = 44
        private const val FAV_TILE_PAD_DP = 16
        private const val FAV_TILE_CORNER_DP = 8f
        private const val FAV_FADE_WIDTH_DP = 26
        private const val FAV_TILE_BG = 0xFF1E1E20.toInt()

        /** Public suffixes with two labels, so `bbc.co.uk` does not read as "co". */
        private val TWO_PART_SUFFIXES = setOf(
            "co.uk", "co.nz", "co.za", "co.jp", "co.kr", "co.in", "co.il",
            "com.au", "com.br", "com.cn", "com.mx", "com.tr", "com.sg", "com.hk",
            "net.au", "net.nz", "org.uk", "org.nz", "org.au", "ac.uk", "gov.uk",
        )

        private const val STAR_BUTTON_FALLBACK_PX = 96
        private const val STAR_BUTTON_PADDING_PX = 18

        private const val TORRENT_MIN_BUFFER_MS = 60_000
        private const val TORRENT_MAX_BUFFER_MS = 180_000
        /** Initial start-up cushion. */
        private const val TORRENT_BUFFER_FOR_PLAYBACK_MS = 5_000
        /** After a stall: resume only with a real cushion, or it stalls straight back. */
        private const val TORRENT_BUFFER_AFTER_REBUFFER_MS = 20_000
        private const val TORRENT_TARGET_BUFFER_BYTES = 48 * 1024 * 1024

        /** How often the playhead is checkpointed while a torrent plays. */
        private const val TORRENT_CHECKPOINT_INTERVAL_MS = 15_000L

        /** Consecutive playback errors to recover from before giving up on a stream. */
        private const val TORRENT_PLAYER_MAX_RETRIES = 5
        /** Circular reveal from loading surface to picture. Long enough to read, short
         *  enough that it never sits between the user and the film. */
        private const val TORRENT_REVEAL_MS = 1250L

        /**
         * How long before the end of an episode the next one is offered.
         *
         * Long enough to cover a title sequence and to be noticed without pausing, short
         * enough that it is not sitting over the last scene. The fill spans exactly this
         * window, so it is also the countdown.
         */
        /**
         * How long the loading surface may wait for a first frame before giving up on the
         * reveal and coming down anyway. Longer than a legitimate slow mkv open, short
         * enough that a starved stream does not read as a dead app.
         */
        private const val FIRST_FRAME_TIMEOUT_MS = 30_000L

        /**
         * Proof that the picture is moving before the loading surface is given up.
         *
         * Sample the playhead, wait, and require it to have advanced. 250 ms is long
         * enough that a running stream clears it comfortably and short enough to be
         * invisible next to the wait it follows; 100 ms of progress is well above any
         * rounding in the reported position.
         */
        private const val REVEAL_MOTION_SAMPLE_MS = 250L
        private const val REVEAL_MOTION_MIN_MS = 100L
        private const val REVEAL_MOTION_RETRY_MS = 400L

        private const val NEXT_EPISODE_LEAD_MS = 60_000L
        private const val NEXT_EPISODE_POLL_MS = 1_000L

        private const val TORRENT_FRAME_MIN_POS_MS = 45_000L

        /**
         * Decode the poster this far behind the playhead. Sequential download means the
         * bytes behind the playhead are on disk; the bytes just ahead of it may not be.
         */
        private const val TORRENT_FRAME_LOOKBACK_MS = 15_000L
        /** 2× the card footprint keeps the JPEG small but crisp. */
        private const val TORRENT_FRAME_WIDTH_PX = 608
        private const val TORRENT_FRAME_HEIGHT_PX = 342
        /** Max channel value at or below this across the sample grid = failed grab. */
        private const val TORRENT_FRAME_BLACK_LUMA = 24
        // Per-sample neighbour delta (sum of R+G+B abs diffs) above which a sample
        // pair counts as "noise", and the fraction of noisy pairs that marks the
        // whole grab as garbled readback rather than a real frame.
        private const val TORRENT_FRAME_NOISE_DELTA = 70
        private const val TORRENT_FRAME_NOISE_RATIO = 0.22f

        /** og:image / twitter:image / <video poster> of the current document. */
        private val PAGE_POSTER_JS = """
            (function(){
              try{
                var m=document.querySelector('meta[property="og:image"],meta[name="og:image"],meta[name="twitter:image"],meta[property="twitter:image"]');
                if(m&&m.content)return m.content;
                var v=document.querySelector('video[poster]');
                if(v)return v.getAttribute('poster');
                return null;
              }catch(e){return null;}
            })();
        """.trimIndent()

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
