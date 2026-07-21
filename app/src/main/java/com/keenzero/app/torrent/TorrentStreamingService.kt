package com.keenzero.app.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.keenzero.app.R
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TorrentStreamingService : Service() {

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "keen-torrent-service").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "keen-torrent-ticker").apply { isDaemon = true }
    }
    private var manager: SessionManager? = null
    private var bridge: TorrentHttpBridge? = null
    private var sessionDir: File? = null
    private var progressTask: ScheduledFuture<*>? = null
    @Volatile private var requestId: String? = null
    @Volatile private var mediaHandle: org.libtorrent4j.TorrentHandle? = null
    @Volatile private var startedAtMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground for the whole stream: a background service loses its process
        // priority ~30 min in and the cached-app freezer SIGSTOPs this process —
        // download and HTTP bridge stall mid-movie with nothing but a spinner
        // (verified on the Mi Box: am_freeze at 20:38/21:12, playback died ~60 s
        // later each time when the player's buffer ran out).
        startForeground(
            NOTIFICATION_ID,
            streamingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        val magnet = intent?.getStringExtra(EXTRA_MAGNET)
        val torrentUrl = intent?.getStringExtra(EXTRA_TORRENT_URL)
        val id = intent?.getStringExtra(EXTRA_REQUEST_ID)
        if ((magnet.isNullOrBlank() && torrentUrl.isNullOrBlank()) || id.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val cookies = intent.getStringExtra(EXTRA_COOKIES)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        worker.execute {
            cleanup()
            requestId = id
            startedAtMs = System.currentTimeMillis()
            try {
                if (!torrentUrl.isNullOrBlank()) {
                    startFromTorrentUrl(torrentUrl, cookies, userAgent, id)
                } else {
                    startTorrent(magnet!!, id)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Torrent startup failed", error)
                sendFailure(id, error.message ?: error.javaClass.simpleName)
                cleanup()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** Download the .torrent file the site offered, then stream it like a magnet with known metadata. */
    private fun startFromTorrentUrl(url: String, cookies: String?, userAgent: String?, id: String) {
        sendProgress(id, STAGE_FETCHING_TORRENT, percent = -1)
        val bytes = fetchTorrentFile(url, cookies, userAgent)
        if (id != requestId) return
        val info = try {
            TorrentInfo.bdecode(bytes)
        } catch (error: Throwable) {
            throw IOException("Not a valid .torrent file")
        }
        val root = createSessionRoot(id)
        val session = createSession(id, root)
        // add_torrent is async — the ADD_TORRENT alert path configures media once
        // the session owns a handle (metadata is already inside the TorrentInfo).
        session.download(info, root)
        startProgressLoop(id)
    }

    @Synchronized
    private fun startTorrent(magnet: String, id: String) {
        val root = createSessionRoot(id)
        val session = createSession(id, root)
        sendProgress(id, STAGE_CONNECTING, percent = -1)
        session.download(magnet, root, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        startProgressLoop(id)
    }

    private fun createSessionRoot(id: String): File {
        val root = File(cacheDir, "torrent/$id")
        check(root.mkdirs() || root.isDirectory) { "Cannot create torrent cache" }
        sessionDir = root
        return root
    }

    private fun createSession(id: String, root: File): SessionManager {
        val session = SessionManager(false)
        manager = session
        session.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.METADATA_RECEIVED.swig(),
                AlertType.ADD_TORRENT.swig(),
            )

            override fun alert(alert: Alert<*>) {
                val hasMetadata = when (alert) {
                    // Magnet path: metadata just arrived from peers.
                    is MetadataReceivedAlert -> true
                    // .torrent-file path: add_torrent is async; the handle carries
                    // metadata from the moment it exists.
                    is AddTorrentAlert -> alert.handle().torrentFile() != null
                    else -> false
                }
                if (!hasMetadata) return
                // Alert memory is pooled and recycled after this callback returns;
                // only the info-hash may escape. Re-resolve a session-owned handle
                // on the worker or every later JNI call is a native use-after-free.
                val infoHash = (alert as org.libtorrent4j.alerts.TorrentAlert<*>).handle().infoHash()
                worker.execute {
                    val handle = session.find(infoHash)
                    if (handle != null && handle.isValid) {
                        configureMedia(id, root, handle)
                    } else {
                        sendFailure(id, "Torrent disappeared before metadata could be used")
                    }
                }
            }
        })
        session.start()
        session.applySettings(
            SettingsPack()
                .activeDownloads(ACTIVE_DOWNLOADS)
                .connectionsLimit(CONNECTION_LIMIT)
                // libtorrent 2 uses the OS page cache; cap its queued disk buffer.
                .maxQueuedDiskBytes(DISK_QUEUE_BYTES),
        )
        return session
    }

    private fun fetchTorrentFile(url: String, cookies: String?, userAgent: String?): ByteArray {
        var current = url
        var redirects = 0
        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = FETCH_TIMEOUT_MS
            connection.readTimeout = FETCH_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (!userAgent.isNullOrBlank()) connection.setRequestProperty("User-Agent", userAgent)
            if (!cookies.isNullOrBlank()) connection.setRequestProperty("Cookie", cookies)
            try {
                val code = connection.responseCode
                if (code in 301..308) {
                    val next = connection.getHeaderField("Location")
                        ?: throw IOException("Torrent download redirect without target")
                    check(++redirects <= MAX_REDIRECTS) { "Too many redirects fetching .torrent" }
                    current = URL(URL(current), next).toString()
                    continue
                }
                if (code != 200) throw IOException("Torrent download failed (HTTP $code)")
                connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_TORRENT_FILE_BYTES) {
                            throw IOException("File is too large to be a .torrent")
                        }
                    }
                    if (out.size() == 0) throw IOException(".torrent download was empty")
                    return out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    @Synchronized
    private fun configureMedia(id: String, root: File, handle: org.libtorrent4j.TorrentHandle) {
        if (id != requestId || bridge != null) return
        val info = handle.torrentFile() ?: error("Magnet metadata unavailable")
        val files = info.files()
        check(files.numFiles() > 0) { "Torrent contains no files" }
        var largestIndex = -1
        var largestAnyIndex = 0
        for (index in 0 until files.numFiles()) {
            if (files.fileSize(index) > files.fileSize(largestAnyIndex)) largestAnyIndex = index
            val ext = files.filePath(index).substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTENSIONS &&
                (largestIndex < 0 || files.fileSize(index) > files.fileSize(largestIndex))
            ) {
                largestIndex = index
            }
        }
        // Prefer the largest recognizable video; fall back to largest file overall.
        if (largestIndex < 0) largestIndex = largestAnyIndex
        val mediaSize = files.fileSize(largestIndex)
        if (!TorrentSpacePolicy.canDownloadWholeFile(root.usableSpace, mediaSize)) {
            // TODO(TASK-TORRENT-MVP-01): implement the feasibility doc's sparse
            // sliding window and F2FS hole-punch fallback for low-space files.
            sendFailure(id, "Not enough free space for file plus 2 GB reserve; sliding window is not yet implemented")
            stopSelf()
            return
        }

        val priorities = Array(files.numFiles()) { Priority.IGNORE }
        priorities[largestIndex] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

        val mediaPath = files.filePath(largestIndex, root.absolutePath)
        val mediaFile = File(mediaPath)
        val title = mediaFile.name.ifBlank { "Torrent video" }
        val firstPiece = (files.fileOffset(largestIndex) / info.pieceLength()).toInt()
        val lastPiece = ((files.fileOffset(largestIndex) + mediaSize - 1) / info.pieceLength()).toInt()
        val server = TorrentHttpBridge(
            mediaFile = mediaFile,
            mediaSize = mediaSize,
            mimeType = mimeType(title),
            title = title,
            torrentOffset = files.fileOffset(largestIndex),
            pieceLength = info.pieceLength(),
            pieceCount = info.numPieces(),
            handle = handle,
            // Player seeked past the downloaded window and reads are blocked:
            // surface buffering progress over the playhead window so the UI can
            // bring the loader back until playback can resume.
            onStall = { piece -> sendSeekBufferProgress(id, piece, lastPiece) },
        )
        bridge = server
        server.startBridge()
        mediaHandle = handle

        // Head pieces must exist before the player opens or the video element sits
        // on a black frame with no feedback. Tail pieces cover mp4 moov-at-end /
        // mkv cues that players fetch immediately via a range request.
        val headCount = ((HEAD_BUFFER_BYTES + info.pieceLength() - 1) / info.pieceLength())
            .toInt().coerceIn(1, lastPiece - firstPiece + 1)
        val bufferPieces = buildList {
            for (p in firstPiece until firstPiece + headCount) add(p)
            for (p in maxOf(firstPiece + headCount, lastPiece - TAIL_BUFFER_PIECES + 1)..lastPiece) add(p)
        }
        bufferPieces.forEachIndexed { i, piece -> handle.setPieceDeadline(piece, i * 250) }
        startBufferLoop(id, server, title, bufferPieces)
    }

    /** Mid-playback seek stall: buffering percent over the deadline window at [piece]. */
    private fun sendSeekBufferProgress(id: String, piece: Int, lastPiece: Int) {
        try {
            val handle = mediaHandle ?: return
            if (id != requestId || !handle.isValid) return
            val windowEnd = minOf(lastPiece + 1, piece + TorrentHttpBridge.DEADLINE_WINDOW_PIECES)
            val window = piece until windowEnd
            val have = window.count { handle.havePiece(it) }
            val size = (windowEnd - piece).coerceAtLeast(1)
            val status = handle.status()
            sendProgress(
                id,
                STAGE_BUFFERING,
                percent = (have * 100) / size,
                peers = status.numPeers(),
                seeds = status.numSeeds(),
                speedBps = status.downloadRate().toLong(),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Seek buffer progress failed", error)
        }
    }

    /** Pre-metadata (magnet): report peer discovery so the wait never looks dead. */
    private fun startBufferLoop(id: String, server: TorrentHttpBridge, title: String, bufferPieces: List<Int>) {
        stopProgressLoop()
        progressTask = ticker.scheduleWithFixedDelay({
            try {
                val handle = mediaHandle
                if (id != requestId || handle == null || !handle.isValid) return@scheduleWithFixedDelay
                val have = bufferPieces.count { handle.havePiece(it) }
                if (have >= bufferPieces.size) {
                    stopProgressLoop()
                    sendBroadcast(
                        Intent(ACTION_READY)
                            .setPackage(packageName)
                            .putExtra(EXTRA_REQUEST_ID, id)
                            .putExtra(EXTRA_PLAYER_URL, server.playerUrl)
                            .putExtra(EXTRA_STREAM_URL, server.streamUrl)
                            .putExtra(EXTRA_TITLE, title),
                    )
                } else {
                    val status = handle.status()
                    sendProgress(
                        id,
                        STAGE_BUFFERING,
                        percent = (have * 100) / bufferPieces.size,
                        peers = status.numPeers(),
                        seeds = status.numSeeds(),
                        speedBps = status.downloadRate().toLong(),
                    )
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Buffer progress tick failed", error)
            }
        }, 0, PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun startProgressLoop(id: String) {
        stopProgressLoop()
        progressTask = ticker.scheduleWithFixedDelay({
            try {
                if (id != requestId || mediaHandle != null) return@scheduleWithFixedDelay
                val session = manager ?: return@scheduleWithFixedDelay
                val elapsed = System.currentTimeMillis() - startedAtMs
                if (elapsed > METADATA_TIMEOUT_MS) {
                    stopProgressLoop()
                    sendFailure(id, "No peers responded — the torrent may be dead")
                    worker.execute { cleanup() }
                    return@scheduleWithFixedDelay
                }
                sendProgress(
                    id,
                    STAGE_METADATA,
                    percent = -1,
                    speedBps = session.downloadRate(),
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Progress tick failed", error)
            }
        }, PROGRESS_INTERVAL_MS, PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopProgressLoop() {
        progressTask?.cancel(false)
        progressTask = null
    }

    private fun sendProgress(
        id: String,
        stage: String,
        percent: Int,
        peers: Int = -1,
        seeds: Int = -1,
        speedBps: Long = -1,
    ) {
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_REQUEST_ID, id)
                .putExtra(EXTRA_STAGE, stage)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_PEERS, peers)
                .putExtra(EXTRA_SEEDS, seeds)
                .putExtra(EXTRA_SPEED_BPS, speedBps),
        )
    }

    private fun sendFailure(id: String, message: String) {
        sendBroadcast(
            Intent(ACTION_ERROR)
                .setPackage(packageName)
                .putExtra(EXTRA_REQUEST_ID, id)
                .putExtra(EXTRA_ERROR, message),
        )
    }

    @Synchronized
    private fun cleanup() {
        stopProgressLoop()
        mediaHandle = null
        bridge?.stop()
        bridge = null
        manager?.stop()
        manager = null
        // Delete the whole torrent root, not just this session — reclaims space
        // left behind by process kills mid-stream.
        val torrentRoot = File(cacheDir, "torrent")
        if (torrentRoot.exists() && !torrentRoot.deleteRecursively()) {
            Log.w(TAG, "Could not completely delete torrent cache: ${torrentRoot.absolutePath}")
        }
        sessionDir = null
        requestId = null
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        cleanup()
        ticker.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun streamingNotification(): Notification {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Streaming video")
            .apply { contentIntent?.let { setContentIntent(it) } }
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4v" -> "video/x-m4v"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    companion object {
        const val ACTION_START = "com.keenzero.app.torrent.START"
        const val ACTION_STOP = "com.keenzero.app.torrent.STOP"
        const val ACTION_READY = "com.keenzero.app.torrent.READY"
        const val ACTION_ERROR = "com.keenzero.app.torrent.ERROR"
        const val ACTION_PROGRESS = "com.keenzero.app.torrent.PROGRESS"
        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_TORRENT_URL = "torrent_url"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PLAYER_URL = "player_url"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ERROR = "error"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_PEERS = "peers"
        const val EXTRA_SEEDS = "seeds"
        const val EXTRA_SPEED_BPS = "speed_bps"

        const val STAGE_FETCHING_TORRENT = "fetching_torrent"
        const val STAGE_CONNECTING = "connecting"
        const val STAGE_METADATA = "metadata"
        const val STAGE_BUFFERING = "buffering"

        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "m4v", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "3gp",
        )

        private const val TAG = "KeenTorrent"
        private const val CHANNEL_ID = "keen_torrent_streaming"
        private const val NOTIFICATION_ID = 1002
        private const val CONNECTION_LIMIT = 60
        private const val ACTIVE_DOWNLOADS = 1
        private const val DISK_QUEUE_BYTES = 24 * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 750L
        private const val METADATA_TIMEOUT_MS = 120_000L
        private const val FETCH_TIMEOUT_MS = 20_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_TORRENT_FILE_BYTES = 20 * 1024 * 1024
        private const val HEAD_BUFFER_BYTES = 6L * 1024 * 1024
        private const val TAIL_BUFFER_PIECES = 2
    }
}
