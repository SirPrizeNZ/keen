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
    /** Where playback will begin, 0..1 of the media file; 0 for a fresh start. */
    @Volatile private var resumeFraction: Float = 0f

    /** One file in the torrent, as plain data. */
    private class FileSlot(
        val index: Int,
        val name: String,
        val absPath: String,
        val size: Long,
        val offset: Long,
    )

    /**
     * Everything the streaming setup needs, copied out of libtorrent's objects.
     *
     * [TorrentInfo] and its `files()` wrap native memory owned by the handle. Neither
     * survives being held across two service commands: re-reading `handle.torrentFile()`
     * after the picker returned null ("Magnet metadata unavailable"), and caching the
     * TorrentInfo instead gave back a structure reporting zero files ("Torrent contains no
     * files"). Read it all once, while it is definitely valid, and never look again.
     */
    private class TorrentLayout(
        val numFiles: Int,
        val pieceLength: Int,
        val numPieces: Int,
        val slots: List<FileSlot>,
    )

    /** Setup paused at the file picker, waiting for [ACTION_SELECT_FILE]. */
    private class PendingChoice(
        val id: String,
        val root: File,
        val handle: org.libtorrent4j.TorrentHandle,
        val layout: TorrentLayout,
    )

    @Volatile private var pendingChoice: PendingChoice? = null

    /**
     * The live stream's setup, kept so a later file in the same pack can take over
     * without a new torrent session.
     *
     * Playing the next episode is not a new download: the swarm connection, the metadata
     * and every piece already on disk are the same torrent. Holding the layout and root
     * here is what lets [ACTION_PLAY_FILE] re-point the bridge at another file in a
     * fraction of a second, instead of tearing the session down and paying the full
     * magnet-resolve wait between two episodes.
     */
    @Volatile private var liveMedia: PendingChoice? = null

    /** Feature files in the live torrent, in name (episode) order, and which one is playing. */
    @Volatile private var packOrder: List<Int> = emptyList()
    @Volatile private var packNames: List<String> = emptyList()
    @Volatile private var playingIndex: Int = -1

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
        // The user picked which file of a multi-video torrent to stream. Metadata is
        // already in hand and the handle is live, so this resumes the paused setup
        // rather than starting anything.
        if (intent?.action == ACTION_SELECT_FILE) {
            val chosen = intent.getIntExtra(EXTRA_FILE_INDEX, -1)
            val pending = pendingChoice
            if (pending == null || chosen < 0) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            pendingChoice = null
            // The clock restarts from the choice: whatever the swarm did while the picker
            // was open should not count against the metadata watchdog.
            startedAtMs = System.currentTimeMillis()
            Log.i(TAG, "picker_choice index=$chosen resuming")
            // Recomputed by the UI for the file actually chosen; the value from start-up
            // was the torrent's, from before anyone knew which file this would be.
            resumeFraction = intent.getFloatExtra(EXTRA_RESUME_FRACTION, resumeFraction)
            worker.execute {
                try {
                    configureMedia(pending.id, pending.root, pending.handle, chosen, pending.layout)
                } catch (error: Throwable) {
                    Log.e(TAG, "File selection failed", error)
                    sendFailure(pending.id, error.message ?: error.javaClass.simpleName)
                    cleanup()
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }
        // Next episode: same torrent, different file. The handle stays live and every
        // piece already fetched stays on disk — only the bridge and the buffer window
        // move. Distinct from ACTION_SELECT_FILE, which answers the opening picker and
        // requires a paused, not-yet-configured session.
        if (intent?.action == ACTION_PLAY_FILE) {
            val chosen = intent.getIntExtra(EXTRA_FILE_INDEX, -1)
            val live = liveMedia
            if (live == null || chosen < 0 || !live.handle.isValid) {
                Log.w(TAG, "play_file ignored index=$chosen live=${live != null}")
                return START_NOT_STICKY
            }
            startedAtMs = System.currentTimeMillis()
            // A new episode starts at the top, whatever fraction the last one resumed at.
            resumeFraction = intent.getFloatExtra(EXTRA_RESUME_FRACTION, 0f)
            Log.i(TAG, "play_file index=$chosen")
            worker.execute {
                try {
                    stopProgressLoop()
                    // configureMedia refuses to run while a bridge is up (it is the
                    // "already streaming" guard); this is the one place that is meant to
                    // replace one, so close it first.
                    bridge?.stop()
                    bridge = null
                    configureMedia(live.id, live.root, live.handle, chosen, live.layout)
                } catch (error: Throwable) {
                    Log.e(TAG, "Next-file switch failed", error)
                    sendFailure(live.id, error.message ?: error.javaClass.simpleName)
                }
            }
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
        resumeFraction = intent.getFloatExtra(EXTRA_RESUME_FRACTION, 0f)
        val cookies = intent.getStringExtra(EXTRA_COOKIES)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val torrentFile = intent.getStringExtra(EXTRA_TORRENT_FILE)
        worker.execute {
            cleanup()
            requestId = id
            startedAtMs = System.currentTimeMillis()
            try {
                if (!torrentUrl.isNullOrBlank()) {
                    startFromTorrentUrl(torrentUrl, torrentFile, cookies, userAgent, id)
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

    /** Take the .torrent the site offered, then stream it like a magnet with known metadata. */
    private fun startFromTorrentUrl(
        url: String,
        /** Bytes the page already read for us, spooled to disk. Null → fetch [url] here. */
        spooledPath: String?,
        cookies: String?,
        userAgent: String?,
        id: String,
    ) {
        sendProgress(id, STAGE_FETCHING_TORRENT, percent = -1)
        val spooled = spooledPath?.let(::File)?.takeIf { it.isFile }
        val bytes = try {
            spooled?.readBytes() ?: fetchTorrentFile(url, cookies, userAgent)
        } finally {
            // The .torrent itself is a courier, not content. It has served its whole
            // purpose the instant its bytes are in memory, so it goes now — not on
            // teardown, where a crash or a process kill would strand it on disk.
            spooled?.delete()
        }
        if (id != requestId) return
        val info = try {
            TorrentInfo.bdecode(bytes)
        } catch (error: Throwable) {
            // The overwhelmingly common cause is a bot-check or error page served with a
            // 200 in place of the file. Say so, rather than blaming the torrent.
            throw IOException(
                if (looksLikeHtml(bytes)) {
                    "The site returned a web page instead of the torrent — it may be asking for a check"
                } else {
                    "Not a valid .torrent file"
                },
            )
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
        // Claim ownership process-wide BEFORE any data lands here, so a teardown still
        // running for the previous magnet cannot delete this session's directory.
        ACTIVE_SESSION_DIR.set(root)
        return root
    }

    private fun createSession(id: String, root: File): SessionManager {
        val session = SessionManager(false)
        manager = session
        session.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.METADATA_RECEIVED.swig(),
                AlertType.ADD_TORRENT.swig(),
                AlertType.TORRENT_FINISHED.swig(),
            )

            override fun alert(alert: Alert<*>) {
                // Download complete: leave the swarm outright, keeping the files.
                //
                // Deliberately a removal, not an upload-rate limit of zero. A throttled
                // seed is still a seed — still announcing to the tracker, still holding
                // peer connections, still able to serve pieces. Removing the handle takes
                // this box out of the swarm entirely, so nothing can be uploaded from it
                // after the download finishes. Re-adding (a repair, or a re-star) rechecks
                // the pieces already on disk, so nothing is re-downloaded needlessly.
                if (alert.type() == AlertType.TORRENT_FINISHED) {
                    val finishedHash =
                        (alert as org.libtorrent4j.alerts.TorrentAlert<*>).handle().infoHash()
                    worker.execute {
                        val handle = session.find(finishedHash) ?: return@execute
                        if (handle.isValid) {
                            // false: keep the downloaded files, drop only the swarm membership.
                            session.remove(handle)
                            Log.i(TAG, "Download finished; left the swarm (no seeding): $finishedHash")
                        }
                        sendBroadcast(
                            Intent(ACTION_DOWNLOAD_COMPLETE)
                                .setPackage(packageName)
                                .putExtra(EXTRA_REQUEST_ID, id)
                                .putExtra(EXTRA_INFO_HASH, finishedHash.toString()),
                        )
                    }
                    return
                }
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

    /** A .torrent starts with a bencoded dict (`d`); anything opening in markup is a page. */
    private fun looksLikeHtml(bytes: ByteArray): Boolean =
        String(bytes.copyOf(minOf(bytes.size, 512)), Charsets.ISO_8859_1)
            .trimStart()
            .startsWith("<", ignoreCase = true)

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
            // Trackers routinely gate the download link on the referring page, and a
            // request advertising no acceptable type at all reads as a scraper.
            connection.setRequestProperty("Accept", "application/x-bittorrent,*/*")
            runCatching { URL(current) }.getOrNull()?.let { u ->
                connection.setRequestProperty("Referer", "${u.protocol}://${u.host}/")
            }
            try {
                val code = connection.responseCode
                if (code in 301..308) {
                    val next = connection.getHeaderField("Location")
                        ?: throw IOException("Torrent download redirect without target")
                    check(++redirects <= MAX_REDIRECTS) { "Too many redirects fetching .torrent" }
                    current = URL(URL(current), next).toString()
                    continue
                }
                // 403 here is almost always a bot check the page itself had already
                // cleared; the in-page read is what gets past it, and reaching this line
                // means that read was unavailable (cross-origin, or it timed out).
                if (code == 403 || code == 503) {
                    throw IOException("The site blocked the torrent download (HTTP $code) — try the magnet link")
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
    private fun configureMedia(
        id: String,
        root: File,
        handle: org.libtorrent4j.TorrentHandle,
        /** Index the user picked, or null to choose automatically / ask. */
        chosenIndex: Int? = null,
        /** Metadata captured before the picker; avoids re-reading it off the handle. */
        knownLayout: TorrentLayout? = null,
    ) {
        if (id != requestId || bridge != null) return
        val layout = knownLayout ?: readLayout(handle, root)
        check(layout.numFiles > 0) { "Torrent contains no files" }
        val slots = layout.slots
        var largestIndex = -1
        var largestAnyIndex = 0
        // Every playable file, so a season pack or a 4-movie collection can be offered
        // to the user instead of silently resolving to whichever happens to be biggest.
        val videoIndices = mutableListOf<Int>()
        for (slot in slots) {
            if (slot.size > slots[largestAnyIndex].size) largestAnyIndex = slot.index
            val ext = slot.name.substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTENSIONS) {
                videoIndices.add(slot.index)
                if (largestIndex < 0 || slot.size > slots[largestIndex].size) {
                    largestIndex = slot.index
                }
            }
        }
        // Prefer the largest recognizable video; fall back to largest file overall.
        if (largestIndex < 0) largestIndex = largestAnyIndex

        // Only real features are worth asking about. A single-film torrent routinely
        // carries artwork, a readme, a sample and a trailer beside the movie; offering
        // those as choices turns a one-press play into a puzzle. Anything small, or named
        // like a sample or trailer, is excluded — and if that leaves one candidate there
        // is nothing to ask, so it plays.
        val features = videoIndices.filter { index ->
            val slot = slots[index]
            slot.size >= MIN_FEATURE_BYTES && !JUNK_VIDEO_NAME.containsMatchIn(slot.name)
        }

        if (chosenIndex != null) {
            largestIndex = chosenIndex
        } else if (features.size > 1) {
            // Stop fetching before asking. Every file sits at default priority until
            // prioritizeFiles runs, and SEQUENTIAL_DOWNLOAD pulls from the front of the
            // torrent — so the first file in the pack downloaded while the picker was up,
            // whichever one the user was about to choose. Pausing is the blunt, reversible
            // way to hold that; setting every file to IGNORE instead left the torrent in a
            // state its metadata did not survive.
            handle.pause()
            pendingChoice = PendingChoice(id, root, handle, layout)
            Log.i(TAG, "picker_open paused files=${features.size} of=${layout.numFiles}")
            val ordered = features.sortedBy { slots[it].name.lowercase() }
            sendBroadcast(
                Intent(ACTION_CHOOSE_FILE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_REQUEST_ID, id)
                    .putExtra(EXTRA_FILE_INDICES, ordered.toIntArray())
                    .putExtra(EXTRA_FILE_NAMES, ordered.map { slots[it].name }.toTypedArray())
                    .putExtra(EXTRA_FILE_SIZES, ordered.map { slots[it].size }.toLongArray()),
            )
            return
        }
        // From here the session is committed to a file. Remember what the pack holds and
        // where in it we are, so the UI can offer the next episode when this one ends.
        // Name order is the same order the picker lists them in — for the season packs
        // this is aimed at, that is episode order.
        liveMedia = PendingChoice(id, root, handle, layout)
        packOrder = features.sortedBy { slots[it].name.lowercase() }
        packNames = packOrder.map { slots[it].name }
        playingIndex = largestIndex

        val chosen = slots[largestIndex]
        val mediaSize = chosen.size
        if (!TorrentSpacePolicy.canDownloadWholeFile(root.usableSpace, mediaSize)) {
            // TODO(TASK-TORRENT-MVP-01): implement the feasibility doc's sparse
            // sliding window and F2FS hole-punch fallback for low-space files.
            // Say which file and how much room there is. The old wording ("plus 2 GB
            // reserve; sliding window is not yet implemented") read as an internal note
            // and, worse, as an accusation that the box was full — the honest reading is
            // that this particular file is bigger than the disk.
            fun gb(bytes: Long) = String.format(java.util.Locale.US, "%.1f GB", bytes / 1.0e9)
            sendFailure(
                id,
                "${chosen.name} is ${gb(mediaSize)} and Keen has " +
                    "${gb(root.usableSpace)} free. Keen needs room for the whole file plus " +
                    "2 GB while streaming.",
            )
            stopSelf()
            return
        }

        val priorities = Array(layout.numFiles) { Priority.IGNORE }
        priorities[largestIndex] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        // No-op unless the picker paused it above.
        handle.resume()

        val mediaFile = File(chosen.absPath)
        val title = mediaFile.name.ifBlank { "Torrent video" }
        val firstPiece = (chosen.offset / layout.pieceLength).toInt()
        val lastPiece = ((chosen.offset + mediaSize - 1) / layout.pieceLength).toInt()
        val server = TorrentHttpBridge(
            mediaFile = mediaFile,
            mediaSize = mediaSize,
            mimeType = mimeType(title),
            title = title,
            torrentOffset = chosen.offset,
            pieceLength = layout.pieceLength,
            pieceCount = layout.numPieces,
            handle = handle,
            // Player seeked past the downloaded window and reads are blocked:
            // surface buffering progress over the playhead window so the UI can
            // bring the loader back until playback can resume.
            onStall = { piece -> sendSeekBufferProgress(id, piece, lastPiece, layout.pieceLength) },
        )
        bridge = server
        server.startBridge()
        mediaHandle = handle

        // Hand the URL over the moment the bridge can answer, not when buffering finishes.
        //
        // The player used to be built on READY, so two long waits ran back to back: fill
        // the buffer window, and only then let the player open the stream — which on an
        // mkv means fetching the cues from the end of the file before a single frame can
        // be decoded. Measured on the box, that second wait was ~35 s of "Starting
        // playback…" after the counter already said 100%. Starting the player here runs
        // its container read against the same pieces the buffer loop is already pulling,
        // so the two overlap and the film is decoding by the time the buffer is full.
        // Reads block in the bridge until pieces land, which is exactly what should
        // happen; the loading surface stays up until a frame actually renders either way.
        sendBroadcast(mediaIntent(ACTION_STREAM_OPEN, id, server, title, mediaFile.absolutePath))

        // Head pieces must exist before the player opens or the video element sits
        // on a black frame with no feedback. Tail pieces cover mp4 moov-at-end /
        // mkv cues that players fetch immediately via a range request.
        val headBytes = headBufferBytesFor(mediaSize)
        val headCount = ((headBytes + layout.pieceLength - 1) / layout.pieceLength)
            .toInt().coerceIn(1, lastPiece - firstPiece + 1)
        // Buffer where the player will actually start reading. Resuming a part-watched
        // title used to fill the head of the file, announce 99%, and only then discover
        // the player wanted a point half an hour in — a second, invisible wait.
        val spanPieces = lastPiece - firstPiece + 1
        val startPiece = (firstPiece + (spanPieces * resumeFraction).toInt())
            .coerceIn(firstPiece, maxOf(firstPiece, lastPiece - headCount + 1))
        val headPieces = (startPiece until minOf(lastPiece + 1, startPiece + headCount)).toList()
        Log.i(TAG, "buffer window start=$startPiece count=$headCount fraction=$resumeFraction")
        val tailPieces =
            (maxOf(firstPiece + headCount, lastPiece - TAIL_BUFFER_PIECES + 1)..lastPiece).toList()
        val bufferPieces = headPieces + tailPieces
        // Head streams in playback order. The tail pieces gate readiness just as hard
        // (every buffer piece must land before ACTION_READY), so they must NOT be the
        // least-urgent request — a deprioritised tail straggling in from a slow peer was
        // the "buffer sits at 99% for a minute" stall: head finished, percent maxed, and
        // the only thing left was a tail piece nobody had asked for urgently. Give the
        // tail the same front-of-queue urgency as the first head pieces so it arrives in
        // parallel and the number never parks just short of done.
        headPieces.forEachIndexed { i, piece -> handle.setPieceDeadline(piece, i * 250) }
        tailPieces.forEach { piece -> handle.setPieceDeadline(piece, 250) }
        startBufferLoop(id, server, title, mediaFile.absolutePath, bufferPieces, layout.pieceLength)
    }

    /**
     * The stream's identity, sent both when the bridge opens and when buffering completes.
     *
     * Both broadcasts describe the same file; only the timing differs, so they carry the
     * same payload and the UI decides what to do with each.
     */
    private fun mediaIntent(
        action: String,
        id: String,
        server: TorrentHttpBridge,
        title: String,
        mediaPath: String,
    ): Intent = Intent(action)
        .setPackage(packageName)
        .putExtra(EXTRA_REQUEST_ID, id)
        .putExtra(EXTRA_PLAYER_URL, server.playerUrl)
        .putExtra(EXTRA_STREAM_URL, server.streamUrl)
        .putExtra(EXTRA_TITLE, title)
        // Card artwork decodes a frame straight off this file. Going through the bridge
        // instead would re-arm the piece deadlines at the poster's timestamp and stall
        // real playback.
        .putExtra(EXTRA_MEDIA_PATH, mediaPath)
        // What else is in this pack, so the player can offer the next episode without
        // reopening the picker. Empty for a single film.
        .putExtra(EXTRA_PACK_INDICES, packOrder.toIntArray())
        .putExtra(EXTRA_PACK_NAMES, packNames.toTypedArray())
        .putExtra(EXTRA_FILE_INDEX, playingIndex)

    /** Mid-playback seek stall: buffering percent over the deadline window at [piece]. */
    private fun sendSeekBufferProgress(id: String, piece: Int, lastPiece: Int, pieceLength: Int) {
        try {
            val handle = mediaHandle ?: return
            if (id != requestId || !handle.isValid) return
            // Must match the bridge's own read-ahead window or the buffering percent
            // measures a different span than the one actually being fetched.
            val windowEnd = minOf(
                lastPiece + 1,
                piece + TorrentHttpBridge.deadlineWindowFor(pieceLength),
            )
            val window = piece until windowEnd
            val have = window.count { handle.havePiece(it) }
            val size = (windowEnd - piece).coerceAtLeast(1)
            val status = handle.status()
            sendProgress(
                id,
                // Distinct from the initial buffer loop's STAGE_BUFFERING. They used to
                // share a stage, so suppressing the start-up stall also blanked the real
                // 0 -> 100 progress, peers, seeds and speed: the stream ran fine and the
                // UI showed nothing.
                STAGE_SEEK_BUFFERING,
                percent = (have * 100) / size,
                peers = status.numPeers(),
                seeds = status.numSeeds(),
                speedBps = status.downloadRate().toLong(),
                swarmSeeds = swarmSeedsOf(status),
                swarmPeers = swarmPeersOf(status),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Seek buffer progress failed", error)
        }
    }

    /** Pre-metadata (magnet): report peer discovery so the wait never looks dead. */
    private fun startBufferLoop(
        id: String,
        server: TorrentHttpBridge,
        title: String,
        mediaPath: String,
        bufferPieces: List<Int>,
        pieceLen: Int,
    ) {
        stopProgressLoop()
        val bufferSet = bufferPieces.toHashSet()
        val totalBytes = bufferPieces.size.toLong() * pieceLen
        // Buffering progress must only ever climb — piece/block counts can momentarily
        // dip between ticks (a partial piece re-requested, peers churning), and a number
        // that jumps backwards reads as broken. Latch the high-water mark.
        var reportedPercent = 0
        // A .torrent arrives with its metadata already known, so a stream started from one
        // lands straight in this loop and never passes the pre-metadata watchdog above.
        // This loop had no deadline of its own, which is why a torrent nobody is seeding
        // sat on "Connecting to peers…" at 00% and "—/—" for ever, with no way to tell
        // that from a slow start. Time it from here.
        val loopStartedAt = System.currentTimeMillis()
        progressTask = ticker.scheduleWithFixedDelay({
            try {
                val handle = mediaHandle
                if (id != requestId || handle == null || !handle.isValid) return@scheduleWithFixedDelay
                val whole = bufferPieces.count { handle.havePiece(it) }
                if (whole >= bufferPieces.size) {
                    stopProgressLoop()
                    sendBroadcast(mediaIntent(ACTION_READY, id, server, title, mediaPath))
                } else {
                    // Byte-accurate: completed buffer pieces plus the finished blocks
                    // of any in-flight buffer piece. The buffer window is only a
                    // handful of whole pieces, so counting whole pieces alone quantises
                    // the readout into ~20% jumps that sit at 0 until the first piece
                    // lands; block-level progress makes the number climb smoothly.
                    var haveBytes = whole.toLong() * pieceLen
                    for (partial in handle.downloadQueue) {
                        val idx = partial.pieceIndex()
                        val blocks = partial.blocksInPiece()
                        if (idx in bufferSet && blocks > 0 && !handle.havePiece(idx)) {
                            haveBytes += pieceLen.toLong() * partial.finished() / blocks
                        }
                    }
                    // Cap below 100 — only the ready gate (all critical pieces present)
                    // may report a finished buffer, so the number never claims 100 while
                    // a required piece is still missing.
                    val rawPercent = if (totalBytes <= 0) 0
                    else ((haveBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
                    val percent = rawPercent.coerceAtLeast(reportedPercent)
                    reportedPercent = percent
                    val status = handle.status()
                    Log.i(
                        TAG,
                        "tick pct=$percent whole=$whole/${bufferPieces.size} " +
                            "peers=${status.numPeers()} seeds=${status.numSeeds()} " +
                            "listPeers=${status.listPeers()} listSeeds=${status.listSeeds()} " +
                            "complete=${status.numComplete()} incomplete=${status.numIncomplete()} " +
                            "conn=${status.numConnections()} rate=${status.downloadRate()} " +
                            "payload=${status.downloadPayloadRate()} done=${status.totalDone()} " +
                            "state=${status.state()}",
                    )
                    // Nothing has arrived, and it has been long enough that "still
                    // starting" is no longer an honest description. Report the drought as
                    // its own stage — the session is left running, so a late peer simply
                    // puts the stage back to buffering on the next tick.
                    //
                    // Deliberately gated on bytes, not on the seed count: a swarm figure
                    // of 0 from a scrape-less tracker means nothing (that reading is what
                    // sent us chasing a phantom bug), whereas "no piece has landed in
                    // thirty seconds" is a fact about this box.
                    val drought = haveBytes == 0L &&
                        status.totalDone() == 0L &&
                        System.currentTimeMillis() - loopStartedAt > NO_PEERS_NOTICE_MS
                    sendProgress(
                        id,
                        if (drought) STAGE_NO_PEERS else STAGE_BUFFERING,
                        percent = percent,
                        peers = status.numPeers(),
                        seeds = status.numSeeds(),
                        speedBps = status.downloadRate().toLong(),
                        swarmSeeds = swarmSeedsOf(status),
                        swarmPeers = swarmPeersOf(status),
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
                // Time spent reading the file picker is not time the swarm failed to
                // answer. mediaHandle is only set once setup finishes, so without this the
                // 120 s metadata watchdog kept counting while the user chose a film, then
                // declared the torrent dead and tore the session down underneath them.
                if (pendingChoice != null) return@scheduleWithFixedDelay
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
        swarmSeeds: Int = -1,
        swarmPeers: Int = -1,
    ) {
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_REQUEST_ID, id)
                .putExtra(EXTRA_STAGE, stage)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_PEERS, peers)
                .putExtra(EXTRA_SEEDS, seeds)
                .putExtra(EXTRA_SPEED_BPS, speedBps)
                .putExtra(EXTRA_SWARM_SEEDS, swarmSeeds)
                .putExtra(EXTRA_SWARM_PEERS, swarmPeers),
        )
    }

    /** Bare file name of [index], for messages the user reads. */
    private fun mediaFileName(files: org.libtorrent4j.FileStorage, index: Int): String =
        files.filePath(index).substringAfterLast('/').ifBlank { "This file" }

    /**
     * Copy the torrent's file table out of libtorrent while the handle definitely owns
     * valid metadata. Everything downstream reads this, never the native objects.
     */
    private fun readLayout(handle: org.libtorrent4j.TorrentHandle, root: File): TorrentLayout {
        val info = handle.torrentFile() ?: error("Magnet metadata unavailable")
        val files = info.files()
        val slots = (0 until files.numFiles()).map { index ->
            FileSlot(
                index = index,
                name = mediaFileName(files, index),
                absPath = files.filePath(index, root.absolutePath),
                size = files.fileSize(index),
                offset = files.fileOffset(index),
            )
        }
        return TorrentLayout(
            numFiles = files.numFiles(),
            pieceLength = info.pieceLength(),
            numPieces = info.numPieces(),
            slots = slots,
        )
    }

    /**
     * Swarm size, as every desktop client reports it, rather than our own socket count.
     *
     * `numSeeds`/`numPeers` are *connections this box currently holds* — capped at
     * [CONNECTION_LIMIT], built up over the first seconds and churning by one or two as
     * peers come and go. That is why the box read "2 seeds, 1 leech" and then "1 / 0" on
     * a torrent a laptop was showing as 9 / 5: both numbers were right, they were
     * answering different questions, and ours is the one that looks like a dying torrent.
     *
     * `numComplete`/`numIncomplete` are the tracker's scrape figures — the whole swarm,
     * the number the user recognises. They are -1 until an announce comes back, so fall
     * back to the peers we know of from tracker/DHT/PEX (`listSeeds`/`listPeers`), and
     * only then to live connections.
     *
     * The three sources are combined with `max`, deliberately, not as a first-match chain.
     * A chain that stops at the first non-negative reading believes a *zero*: a tracker
     * that answers the announce without scrape fields, or a DHT-only torrent, reports
     * `numComplete = 0` — a real 0, not the -1 that means "unknown" — so the chain locked
     * onto it and never looked at the peers we were visibly downloading from. That is the
     * "0 seeders while the file is arriving at 3 MB/s" readout. None of these counts can
     * legitimately exceed the swarm, so the largest is always the closest answer.
     */
    private fun swarmSeedsOf(status: org.libtorrent4j.TorrentStatus): Int = maxOf(
        status.numComplete(),
        status.listSeeds(),
        status.numSeeds(),
    ).coerceAtLeast(0)

    private fun swarmPeersOf(status: org.libtorrent4j.TorrentStatus): Int = maxOf(
        status.numIncomplete(),
        status.listPeers() - status.listSeeds(),
        status.numPeers() - status.numSeeds(),
    ).coerceAtLeast(0)

    private fun sendFailure(id: String, message: String) {
        sendBroadcast(
            Intent(ACTION_ERROR)
                .setPackage(packageName)
                .putExtra(EXTRA_REQUEST_ID, id)
                .putExtra(EXTRA_ERROR, message),
        )
    }

    @Synchronized
    /**
     * Tear down this session without destroying one that replaced it. Stop+start in quick
     * succession used to delete the whole torrent root from onDestroy while the new session
     * was building, leaving it with nowhere to write and stuck at ~1 peer. Ownership is
     * process-wide (both instances share this process) and teardown/startup are serialised.
     */
    private fun cleanup() = synchronized(LIFECYCLE_LOCK) {
        stopProgressLoop()
        mediaHandle = null
        bridge?.stop()
        bridge = null
        manager?.stop()
        manager = null
        // Release ownership only if this session still holds it. If a newer session has
        // claimed it, the CAS fails and `keep` below protects that newer directory.
        ACTIVE_SESSION_DIR.compareAndSet(sessionDir, null)
        val keep = ACTIVE_SESSION_DIR.get()
        val torrentRoot = File(cacheDir, "torrent")
        if (torrentRoot.exists()) {
            if (keep == null) {
                // Nothing newer is running: reclaim everything, including anything left
                // behind by a process kill mid-stream.
                if (!torrentRoot.deleteRecursively()) {
                    Log.w(TAG, "Could not completely delete torrent cache: ${torrentRoot.absolutePath}")
                }
            } else {
                torrentRoot.listFiles()?.forEach { child ->
                    if (child.absolutePath != keep.absolutePath) child.deleteRecursively()
                }
                Log.i(TAG, "cleanup: kept active session ${keep.name}, purged stale sessions")
            }
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
        /** Service → UI: this torrent holds more than one video; ask which to stream. */
        const val ACTION_CHOOSE_FILE = "com.keenzero.app.torrent.CHOOSE_FILE"
        /** UI → service: stream this file index and ignore the rest. */
        const val ACTION_SELECT_FILE = "com.keenzero.app.torrent.SELECT_FILE"
        /** UI → service: move the live stream to another file in the same torrent. */
        const val ACTION_PLAY_FILE = "com.keenzero.app.torrent.PLAY_FILE"
        const val ACTION_READY = "com.keenzero.app.torrent.READY"
        /**
         * Service → UI: the bridge will answer now; build the player and let it start
         * opening the container while the buffer window is still filling.
         */
        const val ACTION_STREAM_OPEN = "com.keenzero.app.torrent.STREAM_OPEN"
        const val ACTION_ERROR = "com.keenzero.app.torrent.ERROR"
        const val ACTION_PROGRESS = "com.keenzero.app.torrent.PROGRESS"

        /** Whole file is on disk and this box has left the swarm. */
        const val ACTION_DOWNLOAD_COMPLETE = "com.keenzero.app.torrent.DOWNLOAD_COMPLETE"
        const val EXTRA_INFO_HASH = "info_hash"

        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_TORRENT_URL = "torrent_url"
        /** Path to a .torrent the browser process already fetched and spooled for us. */
        const val EXTRA_TORRENT_FILE = "torrent_file"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PLAYER_URL = "player_url"
        const val EXTRA_STREAM_URL = "stream_url"

        /** Absolute path of the media file the bridge serves (Continue-card frame grabs). */
        const val EXTRA_FILE_INDICES = "file_indices"
        const val EXTRA_FILE_NAMES = "file_names"
        const val EXTRA_FILE_SIZES = "file_sizes"
        const val EXTRA_FILE_INDEX = "file_index"
        const val EXTRA_MEDIA_PATH = "media_path"

        /** The whole pack in episode order, carried on READY for the next-episode button. */
        const val EXTRA_PACK_INDICES = "pack_indices"
        const val EXTRA_PACK_NAMES = "pack_names"
        const val EXTRA_TITLE = "title"

        /** Fraction of the file where playback will resume, so buffering starts there. */
        const val EXTRA_RESUME_FRACTION = "resume_fraction"
        const val EXTRA_ERROR = "error"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_PEERS = "peers"
        const val EXTRA_SEEDS = "seeds"
        /** Whole-swarm counts (tracker scrape / known peers), not our own connections. */
        const val EXTRA_SWARM_SEEDS = "swarm_seeds"
        const val EXTRA_SWARM_PEERS = "swarm_peers"
        const val EXTRA_SPEED_BPS = "speed_bps"

        const val STAGE_FETCHING_TORRENT = "fetching_torrent"
        const val STAGE_CONNECTING = "connecting"
        const val STAGE_METADATA = "metadata"
        /**
         * Tried, and got nothing: the buffer window has been open for
         * [NO_PEERS_NOTICE_MS] with not one byte to show for it.
         *
         * Not a failure — the session stays up and the stage clears itself the moment a
         * piece lands. It exists so the wait stops lying. `EXTRA_PEERS` separates the two
         * cases the user needs to tell apart: 0 means nobody answered at all, above 0
         * means we are connected to leechers who have nothing to give.
         */
        const val STAGE_NO_PEERS = "no_peers"
        const val STAGE_BUFFERING = "buffering"

        /** Mid-playback seek outran the downloaded window — not start-up buffering. */
        const val STAGE_SEEK_BUFFERING = "seek_buffering"


        /**
         * Below this a video is an extra, not the thing you asked for — samples,
         * trailers, "proof" clips. Feature-length video does not fit in 50 MB, and a
         * genuinely short film simply falls through to the automatic pick.
         */
        const val MIN_FEATURE_BYTES = 50L * 1024 * 1024

        /** Named like an extra, whatever its size. */
        val JUNK_VIDEO_NAME = Regex("""\b(sample|trailer|preview|proof|rarbg|screens?)\b""", RegexOption.IGNORE_CASE)

        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "m4v", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "3gp",
        )

        /**
         * Session directory currently owned by a live/starting stream, shared across every
         * service instance in this process. A teardown must never delete this one — that
         * is what broke back-to-back magnets.
         */
        private val ACTIVE_SESSION_DIR = java.util.concurrent.atomic.AtomicReference<File?>(null)

        /** Serialises teardown against startup so they cannot interleave. */
        private val LIFECYCLE_LOCK = Any()

        private const val TAG = "KeenTorrent"
        private const val CHANNEL_ID = "keen_torrent_streaming"
        private const val NOTIFICATION_ID = 1002
        /**
         * Peer connections. Lowered from 60: the TV box's Wi-Fi firmware watchdog-reset
         * twice in one day under sustained streaming, reloading the driver from cold
         * (`Unknown iface name: wlan0` / `Waiting for the driver ready`) while the RF link
         * itself was clean — -47 dBm, zero retries, zero loss. Cheap TV-box radios fall
         * over on concurrent-socket count long before they run out of bandwidth, and 40
         * peers saturates a 1200 Mbps link on this hardware just as well as 60.
         */
        private const val CONNECTION_LIMIT = 40
        private const val ACTIVE_DOWNLOADS = 1
        private const val DISK_QUEUE_BYTES = 24 * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 750L
        private const val METADATA_TIMEOUT_MS = 120_000L

        /**
         * How long a buffer window may produce nothing before we say so on screen.
         *
         * Long enough that an ordinary slow start never trips it — tracker announce, DHT
         * bootstrap and the first handshakes routinely take fifteen-odd seconds on this
         * box — and short enough that a genuinely dead torrent does not hold a silent
         * screen for two minutes.
         */
        private const val NO_PEERS_NOTICE_MS = 30_000L
        private const val FETCH_TIMEOUT_MS = 20_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_TORRENT_FILE_BYTES = 20 * 1024 * 1024
        private const val HEAD_BUFFER_BYTES = 6L * 1024 * 1024
        private const val TAIL_BUFFER_PIECES = 2

        /**
         * Bytes to bank before playback starts, scaled to the film's size.
         *
         * A flat 6 MB is a couple of seconds on a 4 GB remux and perfectly adequate on a
         * 700 MB rip. Since bitrate tracks file size closely for a given runtime, size is
         * a good enough proxy: big files wait longer up front and then hold, instead of
         * starting instantly and stalling every few seconds.
         */
        fun headBufferBytesFor(mediaSize: Long): Long = when {
            mediaSize >= 8L * 1024 * 1024 * 1024 -> 32L * 1024 * 1024
            mediaSize >= 4L * 1024 * 1024 * 1024 -> 24L * 1024 * 1024
            mediaSize >= 2L * 1024 * 1024 * 1024 -> 16L * 1024 * 1024
            else -> HEAD_BUFFER_BYTES
        }
    }
}
