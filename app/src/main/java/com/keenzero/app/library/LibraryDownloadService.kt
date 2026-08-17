package com.keenzero.app.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.keenzero.app.R
import com.keenzero.app.torrent.TorrentNetworkTuning
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Background downloads for starred titles — a **completely separate** torrent session
 * from streaming.
 *
 * Streaming and downloading were briefly the same session, which meant starting any new
 * stream ran `cleanup()` and destroyed an in-progress starred download along with its
 * partial data. They are different jobs with different lifetimes: a stream is transient
 * and dies with the player, a download must outlive everything until it finishes. So this
 * owns its own [SessionManager], its own save path (straight into the library, never the
 * cache), its own notification and its own process lifetime. Nothing here touches
 * `TorrentStreamingService`, and nothing it does can touch this.
 *
 * Two deliberate constraints:
 *  - **Never seeds.** On completion the torrent is removed from the session outright, so
 *    the box leaves the swarm rather than staying in it at a throttled rate.
 *  - **Modest connection budget.** The box's Wi-Fi firmware watchdog-reset under a
 *    60-peer streaming load, so this stays small enough that a download running
 *    alongside a stream keeps the total well under what was observed to fall over.
 */
class LibraryDownloadService : Service() {

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "keen-library-service").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "keen-library-ticker").apply { isDaemon = true }
    }

    private var session: SessionManager? = null

    /**
     * Whether DHT is currently running for this download session.
     *
     * Starts on and is retired once the download holds enough peers to carry itself; see
     * [TorrentNetworkTuning.followSwarm]. Held here rather than on the shared helper
     * because the streaming session runs its own, and a busy stream must not decide DHT
     * for a download that is starving.
     */
    private var dhtEnabled = true

    /** When the peer count last fell to zero, or 0 while peers are present. */
    private var peersLostAtMs = 0L
    private var progressTask: ScheduledFuture<*>? = null
    @Volatile private var handle: org.libtorrent4j.TorrentHandle? = null
    @Volatile private var activeKey: String? = null
    @Volatile private var targetDir: File? = null

    /**
     * True while a torrent is streaming in the other process.
     *
     * A download and a stream share one radio, one disk and one Wi-Fi chip, so the
     * download's budget depends entirely on whether anything is competing with it.
     */
    @Volatile private var streaming = false

    /**
     * Told by the activity when playback starts and stops.
     *
     * A broadcast rather than a service intent on purpose: a start intent would launch
     * this service just to inform it about a stream, and a download service with nothing
     * to download should not exist. Only a running download hears this, which is exactly
     * when it matters.
     */
    private val streamModeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = intent?.getBooleanExtra(EXTRA_STREAMING, false) ?: return
            if (active == streaming) return
            streaming = active
            applyBandwidthMode()
        }
    }

    /**
     * Give the download everything that is going spare, and get out of the way when it is not.
     *
     * The session was pinned at the streaming-safe budget for its whole life, which is the
     * right number while a film is playing and far too cautious the rest of the time: a
     * download running on its own was held to a third of the peers the box can manage, for
     * the sake of a stream that was not happening. The limits now follow what is actually
     * running.
     *
     * The ceiling is not raised beyond what this hardware survived. The box's Wi-Fi
     * firmware reset under a 60-peer load, so the idle budget stops below that rather than
     * at whatever libtorrent would accept.
     */
    private fun applyBandwidthMode() {
        val session = session ?: return
        val connections = if (streaming) CONNECTION_LIMIT_STREAMING else CONNECTION_LIMIT_IDLE
        try {
            // Re-asserted rather than assumed: this pack is partial, and carrying the
            // router throttles in it means a mode switch can never hand the session back
            // to libtorrent's defaults.
            session.applySettings(
                TorrentNetworkTuning.apply(SettingsPack().connectionsLimit(connections)),
            )
            Log.i(
                TAG,
                "bandwidth mode: streaming=$streaming connections=$connections " +
                    TorrentNetworkTuning.summary,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Could not apply bandwidth mode", error)
        }
    }

    private val store by lazy { StarredLibraryStore(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            streamModeReceiver,
            android.content.IntentFilter(ACTION_STREAM_MODE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                if (key == null || key == activeKey) {
                    teardown()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        val key = intent.getStringExtra(EXTRA_KEY)
        val dirPath = intent.getStringExtra(EXTRA_DIR)
        if (origin.isNullOrBlank() || key.isNullOrBlank() || dirPath.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // dataSync, not mediaPlayback: nothing is playing. This is a file transfer that
        // must survive the user leaving the app.
        startForeground(
            NOTIFICATION_ID,
            notification(getString(R.string.library_notification_title), 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (activeKey == key) return START_NOT_STICKY

        worker.execute {
            try {
                teardown()
                activeKey = key
                targetDir = File(dirPath).apply { mkdirs() }
                start(origin, key, targetDir!!)
            } catch (error: Throwable) {
                Log.e(TAG, "Library download failed to start", error)
                store.update(key, state = StarredLibraryStore.State.FAILED)
                notifyChanged(key)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun start(origin: String, key: String, dir: File) {
        val manager = SessionManager(false)
        session = manager
        // New session, new settings: DHT runs until this download no longer needs it.
        dhtEnabled = true
        peersLostAtMs = 0L
        manager.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.METADATA_RECEIVED.swig(),
                AlertType.ADD_TORRENT.swig(),
                AlertType.TORRENT_FINISHED.swig(),
            )

            override fun alert(alert: Alert<*>) {
                when {
                    alert.type() == AlertType.TORRENT_FINISHED -> worker.execute { finish(key) }
                    alert is MetadataReceivedAlert || alert is AddTorrentAlert -> {
                        val infoHash =
                            (alert as org.libtorrent4j.alerts.TorrentAlert<*>).handle().infoHash()
                        worker.execute {
                            val h = manager.find(infoHash) ?: return@execute
                            if (h.isValid) configure(h, key)
                        }
                    }
                }
            }
        })
        manager.start()
        manager.applySettings(
            // No listenInterfaces override: pinning one measurably made things worse
            // (peers 1 -> 0), most likely because the IPv6 bind fails on this box and
            // leaves the session without a usable listen socket. Default binding is
            // what the streaming session uses, and that one finds peers.
            TorrentNetworkTuning.apply(
                SettingsPack()
                    .activeDownloads(1)
                    // Whatever is true right now: the activity may have told us a stream was
                    // already playing before this download existed.
                    .connectionsLimit(
                        if (streaming) CONNECTION_LIMIT_STREAMING else CONNECTION_LIMIT_IDLE,
                    )
                    .maxQueuedDiskBytes(DISK_QUEUE_BYTES),
            ),
        )
        store.update(key, state = StarredLibraryStore.State.DOWNLOADING)
        notifyChanged(key)
        val isMagnet = origin.startsWith("magnet:", ignoreCase = true)
        Log.i(TAG, "starting download key=$key magnet=$isMagnet dir=${dir.absolutePath}")
        if (isMagnet) {
            manager.download(origin, dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        } else {
            // A .torrent URL, not a magnet. download(String, ...) only understands magnet
            // URIs, so handing it an http link left the session in DOWNLOADING_METADATA
            // for ever with no peers: it had nothing to look for. Fetch the file and hand
            // libtorrent real metadata, the same way the streaming path does.
            val info = TorrentInfo.bdecode(fetchTorrentFile(origin))
            manager.download(info, dir)
        }
        startProgressLoop(key)
    }

    /** Keep only the largest (feature) file, exactly as the streaming path does. */
    private fun configure(h: org.libtorrent4j.TorrentHandle, key: String) {
        handle = h
        val info = h.torrentFile() ?: return
        val files = info.files()
        var largest = 0
        for (i in 0 until files.numFiles()) {
            if (files.fileSize(i) > files.fileSize(largest)) largest = i
        }
        val priorities = Array(files.numFiles()) { Priority.IGNORE }
        priorities[largest] = Priority.DEFAULT
        h.prioritizeFiles(priorities)
        val path = files.filePath(largest, targetDir?.absolutePath.orEmpty())
        store.update(key, mediaPath = path, totalBytes = files.fileSize(largest))
        notifyChanged(key)
    }

    private fun startProgressLoop(key: String) {
        progressTask?.cancel(false)
        val startedAt = System.currentTimeMillis()
        progressTask = ticker.scheduleWithFixedDelay({
            try {
                val h = handle ?: return@scheduleWithFixedDelay
                if (!h.isValid) return@scheduleWithFixedDelay
                val status = h.status()
                val done = status.totalDone()
                val wanted = status.totalWanted()
                // No metadata after several minutes means the magnet found nobody who
                // has it. Say so rather than showing a frozen percentage indefinitely.
                if (wanted <= 0L &&
                    System.currentTimeMillis() - startedAt > METADATA_TIMEOUT_MS
                ) {
                    Log.w(TAG, "No metadata after ${METADATA_TIMEOUT_MS / 1000}s; marking failed: $key")
                    store.update(key, state = StarredLibraryStore.State.FAILED, speedBps = 0L)
                    notifyChanged(key)
                    teardown()
                    stopSelf()
                    return@scheduleWithFixedDelay
                }
                store.update(
                    key,
                    downloadedBytes = done,
                    totalBytes = wanted,
                    speedBps = status.downloadRate().toLong(),
                )
                val pct = if (wanted > 0) ((done * 100) / wanted).toInt().coerceIn(0, 100) else 0
                Log.i(
                    TAG,
                    "tick state=${status.state()} pct=$pct done=$done wanted=$wanted " +
                        "peers=${status.numPeers()} seeds=${status.numSeeds()} " +
                        "conn=${status.numConnections()} rate=${status.downloadRate()}B/s " +
                        "paused=${status.flags().and_(TorrentFlags.PAUSED).non_zero()}",
                )
                // Retire DHT once the swarm carries this download, restore it if the
                // peers go away.
                val s = session
                if (s != null) {
                    if (status.numPeers() == 0) {
                        if (peersLostAtMs == 0L) peersLostAtMs = System.currentTimeMillis()
                    } else {
                        peersLostAtMs = 0L
                    }
                    val before = dhtEnabled
                    dhtEnabled = TorrentNetworkTuning.followSwarm(
                        s::applySettings,
                        peers = status.numPeers(),
                        wanted = dhtEnabled,
                        starvedForMs = if (peersLostAtMs == 0L) 0L
                        else System.currentTimeMillis() - peersLostAtMs,
                    )
                    if (before != dhtEnabled) {
                        Log.i(
                            TAG,
                            "DHT ${if (dhtEnabled) "restored" else "retired"} " +
                                "at peers=${status.numPeers()}",
                        )
                    }
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(store.find(key)?.title.orEmpty(), pct))
                notifyChanged(key)
            } catch (error: Throwable) {
                Log.w(TAG, "Library progress tick failed", error)
            }
        }, 0L, PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Complete: leave the swarm immediately and permanently. Removing the torrent is
     * stronger than a zero upload limit — a throttled seed still announces, still holds
     * peers and still serves pieces.
     */
    private fun finish(key: String) {
        val h = handle
        val manager = session
        if (h != null && manager != null && h.isValid) {
            manager.remove(h)
            Log.i(TAG, "Library download finished; left the swarm (no seeding): $key")
        }
        val entry = store.find(key)
        val path = entry?.mediaPath
        val size = path?.let { File(it).length() } ?: 0L
        store.update(
            key,
            state = StarredLibraryStore.State.COMPLETE,
            downloadedBytes = size,
            totalBytes = size,
        )
        notifyChanged(key)
        teardown()
        stopSelf()
    }

    /** Download a .torrent file so libtorrent can be given real metadata. */
    private fun fetchTorrentFile(url: String): ByteArray {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = FETCH_TIMEOUT_MS
            conn.readTimeout = FETCH_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("Torrent file fetch failed: ${conn.responseCode}")
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun notifyChanged(key: String?) {
        sendBroadcast(
            Intent(ACTION_LIBRARY_CHANGED).setPackage(packageName).putExtra(EXTRA_KEY, key),
        )
    }

    private fun notification(title: String, percent: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.library_notification_title))
            .setContentText(if (title.isBlank()) "" else "$title · $percent%")
            .setSmallIcon(R.drawable.keen_mark)
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .build()

    private fun teardown() {
        progressTask?.cancel(false)
        progressTask = null
        handle = null
        session?.stop()
        session = null
        activeKey = null
        targetDir = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(streamModeReceiver) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        teardown()
        ticker.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeenLibrary"
        private const val CHANNEL_ID = "keen_library_downloads"
        private const val NOTIFICATION_ID = 1003

        /**
         * Kept small on purpose. The streaming session already runs at 40, and the box's
         * Wi-Fi firmware reset under a 60-peer load — so a download alongside a stream
         * still totals comfortably less than what was seen to fall over.
         */
        private const val CONNECTION_LIMIT_STREAMING = 30

        /**
         * What the download may use when nothing is streaming.
         *
         * Nothing else is holding connections then, so the whole budget is the download's.
         * Still short of the 60 that reset this box's Wi-Fi firmware — the point is to
         * stop throttling for a stream that is not running, not to find the ceiling.
         */
        private const val CONNECTION_LIMIT_IDLE = 50

        /**
         * Distinct from the streaming session's default port. Two libtorrent sessions in
         * two processes cannot share one listen port.
         */
        private const val LISTEN_PORT = 6891
        private const val DISK_QUEUE_BYTES = 12 * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 2_000L
        private const val METADATA_TIMEOUT_MS = 5 * 60 * 1000L
        private const val FETCH_TIMEOUT_MS = 20_000

        const val ACTION_START = "com.keenzero.app.library.START"
        /** Broadcast: a stream started or stopped, so the download budget should change. */
        const val ACTION_STREAM_MODE = "com.keenzero.app.library.STREAM_MODE"
        const val EXTRA_STREAMING = "streaming"

        /**
         * Tell any running download whether something is streaming.
         *
         * Safe to call at any time: it is a broadcast, so it cannot start the service, and
         * with no download running there is nothing to slow down and the call does nothing.
         */
        fun setStreaming(context: Context, streaming: Boolean) {
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_STREAM_MODE)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_STREAMING, streaming),
                )
            }
        }

        const val ACTION_CANCEL = "com.keenzero.app.library.CANCEL"
        const val ACTION_LIBRARY_CHANGED = "com.keenzero.app.library.CHANGED"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_KEY = "key"
        const val EXTRA_DIR = "dir"
    }
}
