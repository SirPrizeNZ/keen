package com.keenzero.app.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
    private var progressTask: ScheduledFuture<*>? = null
    @Volatile private var handle: org.libtorrent4j.TorrentHandle? = null
    @Volatile private var activeKey: String? = null
    @Volatile private var targetDir: File? = null

    private val store by lazy { StarredLibraryStore(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
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
            SettingsPack()
                .activeDownloads(1)
                .connectionsLimit(CONNECTION_LIMIT)
                .maxQueuedDiskBytes(DISK_QUEUE_BYTES),
        )
        store.update(key, state = StarredLibraryStore.State.DOWNLOADING)
        notifyChanged(key)
        // Same call shape the streaming path has proven on this device. Sequential is
        // not strictly needed when nothing is playing, but it keeps the partial file
        // contiguous and the disk access pattern gentle on the box.
        manager.download(origin, dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
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
        progressTask = ticker.scheduleWithFixedDelay({
            try {
                val h = handle ?: return@scheduleWithFixedDelay
                if (!h.isValid) return@scheduleWithFixedDelay
                val status = h.status()
                val done = status.totalDone()
                val wanted = status.totalWanted()
                store.update(key, downloadedBytes = done, totalBytes = wanted)
                val pct = if (wanted > 0) ((done * 100) / wanted).toInt().coerceIn(0, 100) else 0
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
        private const val CONNECTION_LIMIT = 12
        private const val DISK_QUEUE_BYTES = 12 * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 2_000L

        const val ACTION_START = "com.keenzero.app.library.START"
        const val ACTION_CANCEL = "com.keenzero.app.library.CANCEL"
        const val ACTION_LIBRARY_CHANGED = "com.keenzero.app.library.CHANGED"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_KEY = "key"
        const val EXTRA_DIR = "dir"
    }
}
