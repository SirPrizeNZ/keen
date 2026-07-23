package com.keenzero.app

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
    private var lastBrowsingCheckpointUrl: String? = null
    private var torrentRequestId: String? = null
    private var torrentPlayer: ExoPlayer? = null
    /** Identity of the active magnet/.torrent for resume-point persistence. */
    private var torrentOriginKey: String? = null
    /** Raw magnet / .torrent URL of the active session — the Continue card re-activates it. */
    private var torrentOriginLabel: String? = null
    /** Display title from the torrent service (file name), for the Continue card. */
    private var torrentTitle: String? = null
    /** Pending hold-to-seek target while DPAD left/right is held in the torrent player. */
    private var torrentSeekTargetMs: Long = -1L
    private var torrentSeekLastEventMs: Long = 0L
    /** Frame grab for the Continue card: retries while playback hasn't produced a usable frame. */
    private var torrentFrameAttempts = 0
    private val torrentFrameCaptureRunnable = Runnable { captureTorrentFrame("scheduled") }
    /** og:image / poster of the current page — attached to media checkpoints. */
    private var currentPagePosterUrl: String? = null
    private var posterProbeUrl: String? = null
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
                    if (!torrentOverlayVisible && nativeTorrentPlayerActive &&
                        stage == TorrentStreamingService.STAGE_BUFFERING &&
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
                    )
                }
                TorrentStreamingService.ACTION_READY -> {
                    val streamUrl = intent.getStringExtra(TorrentStreamingService.EXTRA_STREAM_URL) ?: return
                    val title = intent.getStringExtra(TorrentStreamingService.EXTRA_TITLE)
                    torrentTitle = title?.takeIf { it.isNotBlank() }
                    recordEvent(
                        NavigationEvent(
                            System.currentTimeMillis(),
                            "torrent_ready",
                            url = streamUrl,
                            detail = "title=${title.orEmpty()}",
                        ),
                    )
                    hideTorrentOverlayWithCollapse()
                    showNativeTorrentPlayer(streamUrl, title.orEmpty())
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
                addAction(TorrentStreamingService.ACTION_ERROR)
                addAction(TorrentStreamingService.ACTION_PROGRESS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Reclaim torrent cache left behind if the :torrent process was killed
        // mid-stream (its cleanup never ran). No session can be active this early.
        stopService(Intent(this, TorrentStreamingService::class.java))
        Thread({
            val stale = java.io.File(cacheDir, "torrent")
            if (stale.exists()) stale.deleteRecursively()
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

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            },
        )

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

    override fun onResume() {
        super.onResume()
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

        // Up to 5 recently played titles as a scrollable row (falls back to the
        // single latest media checkpoint when no recents list has accrued yet).
        val recents = continuityStore.loadRecents().ifEmpty { listOfNotNull(cp) }.take(5)
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

        val hasContent = hasContinue || favs.isNotEmpty()
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
        if (!hasContent) {
            binding.homeUrlInput.requestFocus()
            binding.homeUrlInput.post {
                if (uiState == AppUiState.HOME && binding.homeShell.visibility == View.VISIBLE) {
                    showKeyboard(binding.homeUrlInput)
                }
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
    private fun buildContinueCard(cp: ContinuityCheckpoint): View {
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
        val title = android.widget.TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(190), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@KeenActivity, R.color.keen_muted))
            textSize = 15f
            alpha = 0.75f
            text = prettyMediaTitle(cp.title) ?: cp.contentId ?: getString(R.string.continue_unknown_title)
        }
        container.addView(card); container.addView(title)

        card.setOnClickListener { resumeCheckpoint(cp) }
        card.setOnFocusChangeListener { v, hasFocus ->
            (v.foreground as? com.keenzero.app.home.BorderDrawable)
                ?.animateTo(hasFocus, FOCUS_BORDER_WIDTH_DP * resources.displayMetrics.density)
            title.animate().alpha(if (hasFocus) 1f else 0.75f).setDuration(160).start()
            if (hasFocus) v.post {
                // Keep the focused card fully in view with a little breathing room,
                // scrolling the minimum needed (reliable across the whole row).
                val sv = binding.continueScroll
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

        val fraction = if (cp.durationSec > 0) {
            (cp.playbackPositionSec / cp.durationSec).toFloat().coerceIn(0f, 1f)
        } else {
            0f
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
        loadPosterInto(cp.posterUrl, poster, fallback)
        return container
    }

    /** Focus-border drawable at 50% white, used as an animated foreground cue. */
    private fun focusBorder(cornerDp: Float, oval: Boolean) =
        com.keenzero.app.home.BorderDrawable(
            android.graphics.Color.argb(128, 255, 255, 255),
            cornerDp * resources.displayMetrics.density,
            oval,
        )

    /** One roundel (icon + label) per favourite, added to `favsRow` in code. */
    private fun buildFavRoundel(fav: com.keenzero.app.favourites.FavouritesStore.Fav): View {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val letter = android.widget.TextView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
            text = fav.label.take(1).uppercase()
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@KeenActivity, R.color.keen_text))
            alpha = 0.85f
            gravity = android.view.Gravity.CENTER
        }
        val icon = android.widget.ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val roundelBorder = focusBorder(cornerDp = 0f, oval = true)
        val roundel = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(52), dp(52), android.view.Gravity.CENTER)
            setBackgroundResource(R.drawable.fav_roundel_bg)
            foreground = roundelBorder
            isDuplicateParentStateEnabled = true
            // No clipToOutline here: clipping a *circle* via the outline is low quality
            // on Android — it facets the curve. The oval background renders smoothly and
            // the focus border is an antialiased BorderDrawable; the favicon is clipped to
            // a circle in loadFavIcon via a circular drawable instead.
            addView(letter)
            addView(icon)
        }
        // Soft translucent aura behind the roundel — fades in on focus instead of
        // a hard ring, since a real drop shadow is invisible on a black surface.
        val halo = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(64), dp(64), android.view.Gravity.CENTER)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x1AFFFFFF)
            }
            alpha = 0f
        }
        val roundelWrap = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(68), dp(68))
            addView(halo)
            addView(roundel)
        }
        val label = android.widget.TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            text = fav.label
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@KeenActivity, R.color.keen_muted))
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0.7f
        }
        val item = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                dp(68),
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(16) }
            isFocusable = true
            isFocusableInTouchMode = true
            addView(roundelWrap)
            addView(label)
            setOnClickListener { openNavigation(fav.url) }
            setOnFocusChangeListener { _, hasFocus ->
                // No scaling — the focus cue is a border easing inward on the roundel,
                // plus a soft aura and the label brightening.
                roundelBorder.animateTo(hasFocus, FOCUS_BORDER_WIDTH_DP * resources.displayMetrics.density)
                halo.animate().alpha(if (hasFocus) 1f else 0f).setDuration(if (hasFocus) 260 else 160).start()
                label.animate().alpha(if (hasFocus) 1f else 0.7f).setDuration(160).start()
            }
        }
        loadFavIcon(fav.host, icon, letter)
        return item
    }

    /**
     * Favicon for a favourite roundel: single-file disk cache keyed by host,
     * then apple-touch-icon → favicon.ico. Letter fallback stays visible until
     * (and unless) a usable icon is decoded.
     */
    private fun loadFavIcon(host: String, into: android.widget.ImageView, fallback: View) {
        Thread({
            try {
                val dir = java.io.File(filesDir, "favs")
                dir.mkdirs()
                val cacheFile = java.io.File(dir, "$host.img")
                val bitmap = if (cacheFile.exists()) {
                    android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)
                } else {
                    // Prefer a large, high-res icon (apple-touch-icon is typically
                    // 180 px) over the tiny favicon.ico. Walk common high-res paths,
                    // keep the biggest that decodes, and stop early once one is large
                    // enough. Real-world favicons carry brand colour — desaturate so the
                    // roundel row stays strictly black & white.
                    var best: android.graphics.Bitmap? = null
                    for (path in FAVICON_CANDIDATE_PATHS) {
                        val candidate = fetchIconBitmap("https://$host$path") ?: continue
                        if (best == null || candidate.width > best!!.width) best = candidate
                        if ((best?.width ?: 0) >= 128) break
                    }
                    val fetched = best?.let(::toGrayscale)
                    fetched?.also { bmp ->
                        java.io.FileOutputStream(cacheFile).use { out ->
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                }
                if (bitmap != null && bitmap.width >= 16) {
                    runOnUiThread {
                        // Circular drawable clips the square favicon to the roundel's
                        // circle without clipToOutline (which would facet the edge).
                        into.setImageDrawable(
                            androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                                .create(resources, bitmap).apply { isCircular = true },
                        )
                        into.alpha = 0f
                        into.visibility = View.VISIBLE
                        into.animate().alpha(1f).setDuration(220).start()
                        fallback.animate().alpha(0f).setDuration(220)
                            .withEndAction { fallback.visibility = View.GONE }
                            .start()
                    }
                }
            } catch (_: Throwable) {
            }
        }, "keen-favicon").apply { isDaemon = true }.start()
    }

    private fun toGrayscale(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(
            src.width,
            src.height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setSaturation(0f) },
            )
        }
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun fetchIconBitmap(url: String): android.graphics.Bitmap? {
        val conn = java.net.URL(url).openConnection() as? java.net.HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) Keen")
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size !in 100..2_000_000) return null
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun continueFromCheckpoint() {
        val cp = continuityStore.load() ?: return
        resumeCheckpoint(cp)
    }

    private fun resumeCheckpoint(cp: ContinuityCheckpoint) {
        val url = cp.url ?: return
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
        if (intent.getBooleanExtra(EXTRA_LAB_UI_PREVIEW, false)) {
            recordEvent(NavigationEvent(System.currentTimeMillis(), "debug_ui_preview"))
            listOf(
                "https://github.com/",
                "https://en.wikipedia.org/",
                "https://news.ycombinator.com/",
                "https://www.nasa.gov/",
            ).forEach { url -> if (!favouritesStore.isFavourite(url)) favouritesStore.toggle(url) }
            continuityStore.save(
                ContinuityCheckpoint(
                    url = "https://example.com/watch/preview",
                    contentId = "keen-ui-preview",
                    title = "Nocturne S02E06 1080p WEB-DL x264",
                    playerType = "web",
                    playbackPositionSec = 1584.0,
                    durationSec = 2880.0,
                    posterUrl = "https://picsum.photos/seed/keenpreview/608/342",
                    playbackMode = true,
                ),
                force = true,
            )
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

    private fun openNavigation(url: String) {
        if (url.startsWith("magnet:?", ignoreCase = true)) {
            startTorrentStreaming(url)
        } else {
            openUrl(url)
        }
    }

    private fun startTorrentStreaming(magnet: String) {
        startTorrentSession(originLabel = magnet) { intent ->
            intent.putExtra(TorrentStreamingService.EXTRA_MAGNET, magnet)
        }
    }

    private fun startTorrentFromFile(url: String, cookies: String?, userAgent: String?) {
        startTorrentSession(originLabel = url) { intent ->
            intent.putExtra(TorrentStreamingService.EXTRA_TORRENT_URL, url)
                .putExtra(TorrentStreamingService.EXTRA_COOKIES, cookies)
                .putExtra(TorrentStreamingService.EXTRA_USER_AGENT, userAgent)
        }
    }

    private fun startTorrentSession(originLabel: String, configure: (Intent) -> Intent) {
        stopTorrentStreaming()
        continuityStore.markAtHome(false)
        val id = UUID.randomUUID().toString()
        torrentRequestId = id
        torrentOriginKey = com.keenzero.app.torrent.TorrentResumeStore.keyOf(originLabel)
        torrentOriginLabel = originLabel
        torrentTitle = null
        // Entry from home / URL bar has no page under the overlay — bring up the
        // browse shell on a blank page. Entry from a site keeps the page visible
        // beneath the loading overlay so cancel returns exactly where the user was.
        if (webHost?.isCreated != true || binding.browseShell.visibility != View.VISIBLE) {
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
        recordEvent(NavigationEvent(System.currentTimeMillis(), "torrent_start", url = originLabel))
        // Foreground service: the :torrent process must survive the cached-app
        // freezer for streams longer than ~30 min.
        ContextCompat.startForegroundService(
            this,
            configure(
                Intent(this, TorrentStreamingService::class.java)
                    .setAction(TorrentStreamingService.ACTION_START)
                    .putExtra(TorrentStreamingService.EXTRA_REQUEST_ID, id),
            ),
        )
    }

    private fun stopTorrentStreaming() {
        stopService(Intent(this, TorrentStreamingService::class.java))
        torrentRequestId = null
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
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        torrentPlayer = player
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
            override fun onPlayerError(error: PlaybackException) {
                recordEvent(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "torrent_player_error",
                        url = streamUrl,
                        detail = "${error.errorCodeName}: ${error.message}",
                    ),
                )
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
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    if (torrentOverlayVisible && nativeTorrentPlayerActive) {
                        hideTorrentOverlay()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
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
        val resumeMs = torrentOriginKey?.let { torrentResumeStore.positionMs(it) } ?: 0L
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
        // Scrubber (circle) walks the timeline a minute at a time when focused and
        // pressed/held left or right, instead of a duration-relative fraction.
        binding.torrentPlayerView.findViewById<androidx.media3.ui.DefaultTimeBar>(
            androidx.media3.ui.R.id.exo_progress,
        )?.setKeyTimeIncrement(TORRENT_TIMEBAR_KEY_INCREMENT_MS)
        binding.torrentPlayerContainer.visibility = View.VISIBLE
        binding.torrentPlayerView.requestFocus()
        // Card artwork: grab a real frame ~75s in (retries until the stream
        // has actually produced one), then keep it fresh with a rolling
        // 5-minute refresh plus grabs on pause/exit/TV-off.
        torrentFrameAttempts = 0
        binding.root.removeCallbacks(torrentFrameCaptureRunnable)
        binding.root.postDelayed(torrentFrameCaptureRunnable, TORRENT_FRAME_FIRST_DELAY_MS)
    }

    private fun hideNativeTorrentPlayer() {
        binding.root.removeCallbacks(torrentFrameCaptureRunnable)
        saveTorrentResumePoint()
        torrentSeekTargetMs = -1L
        binding.torrentSeekPreview.visibility = View.GONE
        binding.torrentPlayerView.player = null
        binding.torrentPlayerContainer.visibility = View.GONE
        torrentPlayer?.release()
        torrentPlayer = null
    }

    /** Persist the playhead for this magnet so re-activating it resumes there. */
    private fun saveTorrentResumePoint() {
        val player = torrentPlayer ?: return
        val key = torrentOriginKey ?: return
        torrentResumeStore.savePosition(key, player.currentPosition, player.duration)
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

    /** "frame:<info-hash>" when the poster cache currently holds this torrent's captured frame. */
    private fun capturedFrameKey(): String? {
        val frameKey = torrentOriginKey?.let { "frame:$it" } ?: return null
        val stored = getSharedPreferences(POSTER_PREFS, MODE_PRIVATE).getString(POSTER_SRC_KEY, null)
        return frameKey.takeIf { it == stored }
    }

    /**
     * Card artwork for torrents: copy a real frame off the video surface
     * (PixelCopy — adb screencap can't see this plane, but an in-process copy
     * usually can). Validated against all-black output and retried while the
     * stream warms up; falls back to the branded card if the plane is opaque.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun captureTorrentFrame(reason: String) {
        val player = torrentPlayer ?: return
        val key = torrentOriginKey ?: return
        if (player.playbackState != Player.STATE_READY ||
            player.currentPosition < TORRENT_FRAME_MIN_POS_MS
        ) {
            scheduleTorrentFrameRetry()
            return
        }
        val surfaceView = binding.torrentPlayerView.videoSurfaceView as? android.view.SurfaceView
        if (surfaceView == null || !surfaceView.holder.surface.isValid) {
            scheduleTorrentFrameRetry()
            return
        }
        val bitmap = android.graphics.Bitmap.createBitmap(
            TORRENT_FRAME_WIDTH_PX,
            TORRENT_FRAME_HEIGHT_PX,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            android.view.PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS &&
                        !looksBlack(bitmap) && !looksGarbled(bitmap)
                    ) {
                        recordEvent(
                            NavigationEvent(
                                System.currentTimeMillis(),
                                "torrent_frame_captured",
                                detail = "reason=$reason pos=${torrentPlayer?.currentPosition}",
                            ),
                        )
                        persistTorrentFrame(bitmap, key)
                    } else {
                        bitmap.recycle()
                        scheduleTorrentFrameRetry()
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        } catch (_: Throwable) {
            bitmap.recycle()
            scheduleTorrentFrameRetry()
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
                val tmp = java.io.File(dir, "poster.tmp")
                val dst = java.io.File(dir, "poster.img")
                java.io.FileOutputStream(tmp).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                }
                tmp.renameTo(dst)
                val frameKey = "frame:$originKey"
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
                        continuityStore.loadMedia()?.let { cp ->
                            val sameTorrent = cp.playerType == "torrent" &&
                                com.keenzero.app.torrent.TorrentResumeStore.keyOf(cp.url.orEmpty()) == originKey
                            if (sameTorrent && cp.posterUrl != frameKey) {
                                continuityStore.save(cp.copy(posterUrl = frameKey), force = true)
                            }
                        }
                        // Card may already be on screen with the fallback — swap in the frame.
                        if (uiState == AppUiState.HOME) hydrateContinuitySurface()
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
        if (webHost?.isCreated == true && currentUrl != null && currentUrl != "about:blank") {
            uiState = AppUiState.BROWSING
            webHost?.webView?.requestFocus()
        } else {
            // Backing out of a home-launched playback is a deliberate return to
            // the Continue surface — a cold start should land here too.
            if (reason == "back") continuityStore.markAtHome(true)
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

    private fun showTorrentOverlay() {
        lastGiantPercent = -1
        currentFocus?.let { hideKeyboard(it) }
        binding.torrentLoadingTitle.text = getString(R.string.torrent_loading_title)
        binding.torrentLoadingDetail.text = getString(R.string.torrent_stage_starting)
        // No real percent yet on a fresh session — the jumbo watermark only makes sense
        // once there's a real number behind it, so it stays hidden until buffering starts.
        // INVISIBLE, not GONE: keeps it participating in layout so its width/height are
        // already known (not 0) the instant the first buffering percent needs to size against it.
        binding.torrentLoadingPercentGiant.animate().cancel()
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
        ensurePointerAboveContent()
    }

    private fun hideTorrentOverlay() {
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
        binding.torrentLoadingSpinner.collapse()
        binding.root.postDelayed({ hideTorrentOverlay() }, TORRENT_COLLAPSE_HOLD_MS)
    }

    private fun updateTorrentOverlay(stage: String, percent: Int, peers: Int, seeds: Int, speedBps: Long) {
        if (!torrentOverlayVisible) return
        val stageText = when (stage) {
            TorrentStreamingService.STAGE_FETCHING_TORRENT -> getString(R.string.torrent_stage_fetching)
            TorrentStreamingService.STAGE_CONNECTING,
            TorrentStreamingService.STAGE_METADATA,
            -> getString(R.string.torrent_stage_metadata)
            TorrentStreamingService.STAGE_BUFFERING -> getString(R.string.torrent_stage_buffering)
            else -> getString(R.string.torrent_loading_title)
        }
        // Real percent means real progress. The number itself now lives in the jumbo
        // watermark behind everything instead of the small title, so the bar chase doesn't
        // need to encode it geometrically — it just keeps running throughout.
        if (stage == TorrentStreamingService.STAGE_BUFFERING && percent >= 0) {
            // Monotonic: hold the highest value seen this session so the readout
            // only ever climbs.
            val clamped = percent.coerceIn(0, 100).coerceAtLeast(lastGiantPercent)
            lastGiantPercent = clamped
            binding.torrentLoadingSpinner.setProgress(clamped / 100f)
            binding.torrentLoadingPercentGiant.setPercentText(getString(R.string.torrent_percent_giant, clamped))
            if (binding.torrentLoadingPercentGiant.visibility != View.VISIBLE) {
                binding.torrentLoadingPercentGiant.visibility = View.VISIBLE
                binding.torrentLoadingPercentGiant.animate().alpha(1f).setDuration(320).start()
            }
        } else {
            binding.torrentLoadingSpinner.startIndeterminate()
        }
        val extras = buildList {
            if (seeds >= 0 && peers >= 0) {
                add(getString(R.string.torrent_seeds_leechers, seeds, (peers - seeds).coerceAtLeast(0)))
            } else if (peers >= 0) {
                add(getString(R.string.torrent_peers, peers))
            }
            if (speedBps > 0) add(formatSpeed(speedBps))
        }
        binding.torrentLoadingTitle.text = stageText
        val detail = extras.joinToString("   ·   ")
        binding.torrentLoadingDetail.text = detail
        binding.torrentLoadingDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB/s", bps / 1_048_576.0)
        else -> String.format(java.util.Locale.US, "%d KB/s", bps / 1024)
    }

    private fun openUrl(url: String, restore: Boolean = false, stopTorrent: Boolean = true) {
        if (stopTorrent) stopTorrentStreaming()
        dismissPageError()
        continuityStore.markAtHome(false)
        recordEvent(NavigationEvent(System.currentTimeMillis(), "user_open_url", url = url))
        val host = ensureWebHost()
        webViewEverCreated = true
        // Session root: Back should not return to FMHY chooser until we leave this site stack.
        // The torrent player (stopTorrent=false) is an overlay page, not a new session root.
        if (stopTorrent) browseEntryUrl = url
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
        lastChromeUrl = url
        refreshBrowseChrome()
        setLoadProgress(0)
        uiState = if (restore) AppUiState.RESTORING else AppUiState.BROWSING
        if (!restore) persistBrowsingCheckpoint(url, force = true)
        hideKeyboard(binding.browseUrlEdit)
        binding.browseUrlEdit.clearFocus()
        host.load(url)
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
        // 0.9 alpha (chromeLogo) makes a favourited star render as literally the same
        // colour as the logo, not just visually close.
        binding.chromeFavButton.alpha = if (fav) 0.9f else 0.35f
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
                    setLoadProgress(percent)
                    // Real progress past the fold means the page is not stalled — a
                    // "not responding" takeover would only cover usable content.
                    if (percent >= 80) cancelStallTimeout()
                    if (percent >= 100) capturePagePoster()
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
            onTorrentFileIntent = { url, cookies, userAgent ->
                runOnUiThread { startTorrentFromFile(url, cookies, userAgent) }
            },
            onCheckpoint = { rawCp ->
                // Attach the playing page's artwork for the Continue card.
                val cp = if (rawCp.posterUrl.isNullOrBlank() && !currentPagePosterUrl.isNullOrBlank()) {
                    rawCp.copy(posterUrl = currentPagePosterUrl)
                } else {
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
            if (webHost?.isCreated != true || currentUrl == null || currentUrl == "about:blank") {
                continuityStore.markAtHome(true)
                showHome(status = getString(R.string.status_home))
            }
            return
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
        val durationMs = player.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return false
        val focused = binding.torrentPlayerView.findFocus()
        // Focus on the scrubber circle itself: hand left/right to Media3 so it
        // scrubs natively — the thumb walks the timeline live as you press/hold,
        // a minute per step (see the key increment set in showNativeTorrentPlayer).
        if (focused is androidx.media3.ui.DefaultTimeBar) return false
        // Controller up and focus on a button row: left/right must keep navigating
        // controls (subtitles etc.). Keen's hold-seek owns the keys otherwise.
        if (binding.torrentPlayerView.isControllerFullyVisible && focused != null) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Seeking with the controller hidden should still show the real timeline
                // (scrubber + elapsed/total time), not just our own target-time preview —
                // this also resets Media3's auto-hide timer so it stays up while scrubbing.
                binding.torrentPlayerView.showController()
                val now = event.eventTime
                if (torrentSeekTargetMs < 0) torrentSeekTargetMs = player.currentPosition
                val stepMs = if (event.repeatCount == 0) {
                    TORRENT_SEEK_TAP_MS
                } else {
                    // Steady base rate for the first few seconds (fine control), then
                    // rate (media-seconds per held second) keeps climbing with hold
                    // time; each repeat advances by rate × time since the last repeat.
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
            KeyEvent.ACTION_UP -> {
                if (torrentSeekTargetMs < 0) return false
                commitTorrentSeek()
                return true
            }
        }
        return false
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
        binding.torrentSeekPreview.visibility = View.GONE
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
        hydrateContinuitySurface()
        recordEvent(
            NavigationEvent(System.currentTimeMillis(), "home_shown", detail = status),
        )
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
                    val frame = java.io.File(filesDir, "continue/poster.img")
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
        }
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
        /** Debug/lab: seed fake Favs + a Continue watching checkpoint to preview home UI. */
        const val EXTRA_LAB_UI_PREVIEW = "com.keenzero.app.extra.LAB_UI_PREVIEW"
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
        /** Target width of the focus border micro-animation (grows inward). */
        private const val FOCUS_BORDER_WIDTH_DP = 3f
        /** High-res-first icon paths tried when caching a favourite's roundel icon. */
        private val FAVICON_CANDIDATE_PATHS = listOf(
            "/apple-touch-icon.png",
            "/apple-touch-icon-precomposed.png",
            "/apple-touch-icon-180x180.png",
            "/favicon-196x196.png",
            "/favicon-192x192.png",
            "/favicon.ico",
        )
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
        private const val TORRENT_FRAME_MIN_POS_MS = 45_000L
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
