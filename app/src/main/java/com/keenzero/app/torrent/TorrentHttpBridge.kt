package com.keenzero.app.torrent

import fi.iki.elonen.NanoHTTPD
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TorrentHttpBridge(
    private val mediaFile: File,
    private val mediaSize: Long,
    private val mimeType: String,
    private val title: String,
    private val torrentOffset: Long,
    private val pieceLength: Int,
    private val pieceCount: Int,
    private val handle: TorrentHandle,
    /**
     * A read has been blocked waiting for [piece] longer than the notify
     * threshold (player seeked past the downloaded window). Fires roughly
     * every 750 ms until the piece arrives; used to surface buffering UI.
     */
    private val onStall: ((piece: Int) -> Unit)? = null,
) : NanoHTTPD(LOOPBACK, 0) {

    private val closed = AtomicBoolean(false)

    /**
     * Kill switch, in the style of the other diagnostic flags: `adb shell touch
     * /data/local/tmp/keen_no_mkv_patch` and restart. Read once per stream rather than per
     * read — this sits on the read path, and the flag is a thing you set before playing,
     * not during.
     *
     * With it on, the container is served exactly as it arrives from the swarm, which is
     * both the old behaviour and the way to tell a patch problem from a stream problem.
     */
    private val patchTracks: Boolean =
        mimeType == MIME_MATROSKA && !File(FLAG_DIR, FLAG_NO_MKV_PATCH).exists()

    /**
     * Where the `Tracks` element starts, or [TRACKS_NONE] once we have looked and found no
     * reason to patch. Resolved from the head of the file, which is the part every stream
     * reads first anyway.
     */
    @Volatile
    private var tracksOffset: Long = TRACKS_UNRESOLVED

    /** Where the header ends and where its padding is; needed to relocate `Tracks`. */
    @Volatile
    private var tracksLocation: MatroskaTracksPatch.Location? = null

    /** The rewritten regions, once a read has actually reached the element. */
    @Volatile
    private var tracksPatches: List<MatroskaTracksPatch.Patch> = emptyList()

    /** Lowest offset any patch touches; reads below it can return immediately. */
    @Volatile
    private var patchFloor: Long = Long.MAX_VALUE

    @Volatile
    private var patchAttempted = false

    private val patchLock = Any()

    /**
     * Who is holding a deadline on which piece, for the whole bridge.
     *
     * Deadlines are a property of the torrent, so a reader can only safely take one back
     * if no other reader still wants it. Ownership per reader was not enough: readers
     * overlap. The player opens the container at the head of the file and then seeks, and
     * the bridge itself reads the header region to build the Matroska patch, so two or
     * three readers routinely hold windows that share pieces. Whichever one closed or
     * moved first reset the shared pieces out from under a reader that was blocked on
     * them, and with no deadline the swarm had nothing to prioritise: playback started,
     * ran out the pieces already on disk, and then sat black.
     *
     * Counted rather than owned, so a piece is only handed back when the last holder
     * lets go.
     */
    /**
     * Which player range request is the live one.
     *
     * The player does not close a range it has finished with politely: it resets the
     * socket and opens another one somewhere else. Filmed on the Mi Box opening a 1.09 GB
     * mp4, it walked forward in nine steps — 8.8 MB, 74, 110, 133, 160, 187, 212, 234,
     * 276 — inside about a minute, each one resetting the connection roughly 100 ms after
     * it was served.
     *
     * Nothing told the reader behind the dead socket to stop. It stayed blocked on its
     * piece with a 48 MB deadline window armed, so the swarm was being asked to prioritise
     * several hundred megabytes scattered across the file at once, at the 1-4 MB/s this
     * box actually gets. Every position was therefore late, including the one the player
     * was really waiting on: 33 seconds blocked on a single piece with the swarm running
     * at 4.3 MB/s and the buffer window long since full.
     *
     * A stream is served to one player, so the newest request is the only playhead there
     * is. Older readers stop waiting, hand their pieces back and let their thread go.
     */
    private val streamGeneration = AtomicInteger()

    private val deadlineHolders = HashMap<Int, Int>()

    /** @return true if this is the first hold on [piece], so a deadline must be set. */
    private fun retainPiece(piece: Int): Boolean = synchronized(deadlineHolders) {
        val next = (deadlineHolders[piece] ?: 0) + 1
        deadlineHolders[piece] = next
        next == 1
    }

    /** @return true if that was the last hold, so the deadline may be reset. */
    private fun releasePiece(piece: Int): Boolean = synchronized(deadlineHolders) {
        val next = (deadlineHolders[piece] ?: 0) - 1
        if (next <= 0) {
            deadlineHolders.remove(piece)
            true
        } else {
            deadlineHolders[piece] = next
            false
        }
    }

    val playerUrl: String get() = "http://$LOOPBACK:$listeningPort/player"
    val streamUrl: String get() = "http://$LOOPBACK:$listeningPort/stream"

    fun startBridge() = start(SOCKET_READ_TIMEOUT, false)

    override fun stop() {
        closed.set(true)
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/player" -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                TorrentPlayerPage.html(title),
            ).apply { addHeader("Cache-Control", "no-store") }
            "/stream" -> serveStream(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveStream(session: IHTTPSession): Response {
        if (mediaSize <= 0) {
            // Reaches the player as a bare "Response code: 500" with nothing to say which
            // of the bridge's two failure modes it was. Name it in the log: this one means
            // the stream was opened against a file whose size we never learned.
            android.util.Log.w(
                "KeenTorrent",
                "bridge 500: mediaSize=$mediaSize file=${mediaFile.name} — stream opened before media was known",
            )
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Invalid media")
        }
        val rangeHeader = session.headers["range"]
        val requestedRange = HttpByteRange.parse(rangeHeader, mediaSize)
        if (rangeHeader != null && requestedRange == null) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range")
                .apply { addHeader("Content-Range", "bytes */$mediaSize") }
        }
        val range = requestedRange ?: HttpByteRange(0, mediaSize - 1)
        // Whether the player seeks or reads straight through decides everything about how
        // much of a 6 GB file has to arrive before a frame appears. One open-ended request
        // from byte 0 means it is scanning, not playing.
        android.util.Log.i(
            "KeenTorrent",
            "stream request: method=${session.method} range=${rangeHeader ?: "none"} " +
                "serving=${range.start}-${range.endInclusive} of $mediaSize mime=$mimeType",
        )
        val status = if (requestedRange == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        val response = if (session.method == Method.HEAD) {
            newFixedLengthResponse(status, mimeType, "")
        } else {
            val generation = streamGeneration.incrementAndGet()
            newFixedLengthResponse(
                status,
                mimeType,
                PieceAwareInputStream(
                    file = mediaFile,
                    start = range.start,
                    remaining = range.length,
                    torrentOffset = torrentOffset,
                    pieceLength = pieceLength,
                    pieceCount = pieceCount,
                    handle = handle,
                    closed = closed,
                    onStall = onStall,
                    isCurrent = { streamGeneration.get() == generation },
                    retainPiece = ::retainPiece,
                    releasePiece = ::releasePiece,
                    patcher = if (patchTracks) ::patchServedBytes else null,
                ),
                range.length,
            )
        }
        response.addHeader("Accept-Ranges", "bytes")
        // No Content-Length here: newFixedLengthResponse already sends one, and adding a
        // second emits the header twice. Duplicate Content-Length is a protocol violation,
        // and OkHttp — which is what the player uses — answers it by treating the length as
        // unknown rather than believing either copy.
        //
        // A source of unknown length is a source that cannot be seeked. MatroskaExtractor
        // then cannot jump to the Cues, never publishes a SeekMap, and preparation never
        // finishes: no tracks, no decoder, no picture, while the loader reads the file
        // start to end for ever. It only showed up on encodes that keep their Cues at the
        // end of the file — one whose index sits near the front prepares without ever
        // needing to seek, which is why this went unnoticed.
        if (requestedRange != null) {
            response.addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/$mediaSize")
        }
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    /**
     * Read a span of the media file with the same piece-awaiting the player gets.
     *
     * Reuses [PieceAwareInputStream] rather than reaching for the file directly: the
     * bytes this needs are as likely to be missing as any others, and the waiting,
     * deadline-setting and handle-invalidation rules are already right in there. The
     * patcher is deliberately not passed on — this is the read the patch is built from,
     * and feeding it back through itself would recurse.
     */
    private fun readRegion(start: Long, length: Int): ByteArray? {
        if (length <= 0 || start < 0 || start >= mediaSize) return null
        val want = minOf(length.toLong(), mediaSize - start).toInt()
        return try {
            PieceAwareInputStream(
                file = mediaFile,
                start = start,
                remaining = want.toLong(),
                torrentOffset = torrentOffset,
                pieceLength = pieceLength,
                pieceCount = pieceCount,
                handle = handle,
                closed = closed,
                onStall = onStall,
                // The bridge's own read, not a playhead: no later request supersedes it.
                isCurrent = { true },
                retainPiece = ::retainPiece,
                releasePiece = ::releasePiece,
                patcher = null,
            ).use { stream ->
                val out = ByteArray(want)
                var read = 0
                while (read < want) {
                    val n = stream.read(out, read, want - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) null else out.copyOf(read)
            }
        } catch (t: Throwable) {
            // Never fatal: failing to patch means serving the file as-is, which is exactly
            // what happened before this existed.
            android.util.Log.w("KeenTorrent", "tracks patch: read $start+$length failed: $t")
            null
        }
    }

    /** Locate `Tracks` from the head of the file. Runs once; cheap and cached after that. */
    private fun resolveTracksOffset() {
        if (tracksOffset != TRACKS_UNRESOLVED) return
        synchronized(patchLock) {
            if (tracksOffset != TRACKS_UNRESOLVED) return
            val head = readRegion(0, TRACKS_HEAD_BYTES)
            val located = head?.let { MatroskaTracksPatch.locate(it) }
            tracksLocation = located
            tracksOffset = located?.tracksOffset ?: TRACKS_NONE
            if (located != null) {
                // The floor decides how much of the file the patcher can ignore outright.
                // Relocating puts a patch near the front, so it cannot simply be "the
                // Tracks element" any more.
                patchFloor = if (located.voidOffset >= 0) {
                    minOf(located.tracksOffset, located.voidOffset)
                } else {
                    located.tracksOffset
                }
                android.util.Log.i(
                    "KeenTorrent",
                    "tracks element at ${located.tracksOffset} of $mediaSize " +
                        "firstCluster=${located.firstClusterOffset} trailing=${located.tracksAreTrailing} " +
                        "void=${located.voidOffset}+${located.voidLength}",
                )
            } else {
                android.util.Log.i("KeenTorrent", "tracks element not located in first $TRACKS_HEAD_BYTES bytes")
            }
        }
    }

    /**
     * Build the rewritten `Tracks` element, once a read has reached it.
     *
     * Deferred to this point on purpose: on the layout that needs it most the element sits
     * at the end of a 6 GB file, so building it eagerly would hold the first request open
     * until the tail arrived. By the time a read lands here the player has asked for these
     * bytes itself, so the wait is one the stream was going to do anyway.
     */
    private fun ensureTracksPatch() {
        if (patchAttempted) return
        synchronized(patchLock) {
            if (patchAttempted) return
            val offset = tracksOffset
            if (offset < 0) { patchAttempted = true; return }
            // The header alone says how long the element is; read it before pulling a span
            // whose size would otherwise be a guess.
            val header = readRegion(offset, TRACKS_HEADER_BYTES)
            val length = header?.let { MatroskaTracksPatch.elementLength(it) } ?: -1
            if (length <= 0 || length > TRACKS_MAX_BYTES) {
                android.util.Log.w("KeenTorrent", "tracks patch: implausible element length=$length")
                patchAttempted = true
                return
            }
            val element = readRegion(offset, length.toInt())
            // Removing the track orphans every cue point that names it, and on a disc rip
            // the subtitle tracks hold most of the cue points — so keeping them looked
            // like the safer choice for seeking. Measured on the box it makes no
            // difference: with all 28 tracks preserved the file reports `seekable=false`
            // exactly as it does with 21 of them removed, because what defeats seeking
            // here is something else entirely. With that settled, removal is the better
            // default — a track that survives only to hand the decoder bytes it cannot
            // read is a subtitle option that appears in the menu and then fails.
            val strategy = if (File(FLAG_DIR, FLAG_MKV_KEEP_TRACKS).exists()) {
                MatroskaTracksPatch.Strategy.STRIP_ENCODINGS
            } else {
                MatroskaTracksPatch.Strategy.REMOVE_TRACK
            }
            val patch = element?.let { MatroskaTracksPatch.patch(it, offset, strategy) }
            val patches = mutableListOf<MatroskaTracksPatch.Patch>()
            if (patch != null) {
                patches += patch
                android.util.Log.i(
                    "KeenTorrent",
                    "tracks patch: removed ${patch.removedTrackNumbers.size} unsupported track(s) " +
                        "${patch.removedTrackNumbers} at $offset (${patch.bytes.size} bytes rewritten)",
                )
            } else {
                android.util.Log.i("KeenTorrent", "tracks patch: nothing to remove")
            }
            // Restate the tracks in front of the clusters when they are behind them. This
            // is what makes the file seekable: see [MatroskaTracksPatch.inlineTracks] for
            // the seek-versus-cues interaction it defuses.
            val location = tracksLocation
            if (element != null && location != null) {
                val needed = MatroskaTracksPatch.inlineTracksSize(element)
                if (needed > 0 && location.canInline(needed)) {
                    val inline = MatroskaTracksPatch.inlineTracks(
                        element,
                        location.voidOffset,
                        location.voidLength,
                    )
                    if (inline != null) {
                        patches += inline
                        android.util.Log.i(
                            "KeenTorrent",
                            "tracks inlined: $needed bytes into the ${location.voidLength}-byte " +
                                "void at ${location.voidOffset}, ahead of cluster " +
                                "${location.firstClusterOffset} — seeking should survive",
                        )
                    }
                } else if (location.tracksAreTrailing) {
                    // Worth saying plainly: playback will work and seeking will not.
                    android.util.Log.w(
                        "KeenTorrent",
                        "tracks are trailing but cannot be inlined (need $needed bytes, " +
                            "void=${location.voidLength} at ${location.voidOffset}) — file will not seek",
                    )
                }
            }
            tracksPatches = patches
            patchAttempted = true
        }
    }

    /**
     * Overlay the rewritten element onto bytes on their way out, if this read touches it.
     *
     * Called after every read, so the common case — a read nowhere near the element — has
     * to cost almost nothing, which is why the offset comparison comes first.
     */
    private fun patchServedBytes(filePosition: Long, buffer: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        // Deliberately on the read path rather than in `serveStream`. Locating the element
        // means reading the head of the file, and on a cold swarm that first piece can be
        // ninety seconds away — measured, not supposed. Waiting for it before the response
        // headers go out turns a slow start into an HTTP timeout and a failure the player
        // reports as a broken stream. Here the wait happens inside a body the player is
        // already reading, which is what it is prepared for.
        if (tracksOffset == TRACKS_UNRESOLVED) resolveTracksOffset()
        if (tracksOffset < 0) return
        if (filePosition + count <= patchFloor) return
        ensureTracksPatch()
        for (patch in tracksPatches) {
            val patchEnd = patch.offset + patch.bytes.size
            val from = maxOf(patch.offset, filePosition)
            val to = minOf(patchEnd, filePosition + count)
            if (from >= to) continue
            System.arraycopy(
                patch.bytes,
                (from - patch.offset).toInt(),
                buffer,
                offset + (from - filePosition).toInt(),
                (to - from).toInt(),
            )
        }
    }

    private class PieceAwareInputStream(
        private val file: File,
        private val start: Long,
        private var remaining: Long,
        private val torrentOffset: Long,
        private val pieceLength: Int,
        private val pieceCount: Int,
        private val handle: TorrentHandle,
        private val closed: AtomicBoolean,
        private val onStall: ((piece: Int) -> Unit)?,
        /** False once a later range request has replaced this one as the playhead. */
        private val isCurrent: () -> Boolean,
        /** Take a share in a piece's deadline; true when this reader is the first holder. */
        private val retainPiece: (Int) -> Boolean,
        /** Give a share back; true when this reader was the last holder. */
        private val releasePiece: (Int) -> Boolean,
        /**
         * Rewrites bytes on their way out, given their absolute position in the file.
         * Null for reads the bridge makes on its own behalf, which must see the file as
         * it really is.
         */
        private val patcher: ((filePosition: Long, buffer: ByteArray, offset: Int, count: Int) -> Unit)?,
    ) : InputStream() {
        /**
         * Opened on the first read, not in the constructor.
         *
         * libtorrent creates the file only when it has a piece to write into it, so on a
         * torrent that has not received a single byte — a dead swarm, or simply the first
         * seconds of a slow one — the path does not exist yet. Opening eagerly threw
         * FileNotFoundException out of `serve`, which the HTTP layer turned into a bare
         * 500 and ExoPlayer treated as a fatal source error: "Playback failed" on a
         * stream that had merely not started yet.
         *
         * [awaitCurrentPiece] already blocks until the piece under the cursor is on disk,
         * and a piece on disk means a file on disk. Opening after it removes the race
         * entirely rather than papering over it with a retry.
         */
        private var source: RandomAccessFile? = null
        private var position = start

        /**
         * The piece range this reader currently holds deadlines on, or -1 when it holds
         * none. Tracked per reader because deadlines are a property of the torrent, not of
         * the reader that set them: see [armDeadlines].
         */
        private var armedFrom = -1
        private var armedTo = -1

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            awaitCurrentPiece()
            val source = source ?: RandomAccessFile(file, "r").also {
                it.seek(start)
                source = it
            }
            val globalOffset = torrentOffset + position
            val inPiece = (globalOffset % pieceLength).toInt()
            val untilPieceEnd = pieceLength - inPiece
            val wanted = minOf(length.toLong(), remaining, untilPieceEnd.toLong()).toInt()
            val readFrom = position
            val count = source.read(buffer, offset, wanted)
            if (count < 0) return -1
            position += count
            remaining -= count
            patcher?.invoke(readFrom, buffer, offset, count)
            return count
        }

        /**
         * Move this reader's deadline window to [from] until [to], releasing only the
         * pieces this reader itself had armed.
         *
         * The obvious thing to do when the playhead moves is `clearPieceDeadlines`, and
         * that is what this used to do. But deadlines belong to the torrent, not to the
         * reader that set them, and a stream routinely has two readers at once: the player
         * opens the container at the head of the file and then seeks, leaving one reader
         * near piece 0 and another wherever playback resumes. A global clear means each
         * reader wipes the other's window every time it moves, so whichever ran last is
         * the only position the swarm is working towards and the other simply waits.
         *
         * Measured on the Mi Box resuming a film at 3:44: two readers, one blocked on
         * piece 1 and one on piece 9, both waiting over thirty seconds while the swarm
         * delivered 2.8 MB/s, and 103 s from launch to picture. It was survivable before
         * only by accident: a 12-piece window from the head reader happened to cover the
         * seek reader's pieces too, so a clear-and-rearm left both positions armed. Once
         * the window was sized correctly the two no longer overlapped and the accident
         * stopped saving it.
         *
         * Ownership alone is not enough either, which is what [freePiece] adds: readers
         * overlap, so the pieces one reader is leaving may be the pieces another is
         * blocked on. Holds are counted for the whole bridge and a deadline is only reset
         * when the last holder lets go.
         *
         * @return false if the handle went away, in which case the caller should stop.
         */
        private fun armDeadlines(from: Int, to: Int): Boolean {
            val step = deadlineStepMsFor(pieceLength)
            // Drop the pieces this reader is leaving before taking the new ones, and only
            // where nobody else is still holding them: another reader may be blocked on
            // this very piece, and taking its deadline away leaves the swarm with no
            // reason to fetch it.
            if (armedFrom >= 0) {
                for (piece in armedFrom until armedTo) {
                    if (piece < from || piece >= to) freePiece(piece)
                }
            }
            // How far the loop got, so a handle that dies mid-window hands back exactly
            // the pieces this reader took.
            var held = from
            try {
                for (piece in from until to) {
                    if (armedFrom < 0 || piece < armedFrom || piece >= armedTo) retainPiece(piece)
                    held = piece + 1
                    handle.setPieceDeadline(piece, (piece - from) * step)
                }
            } catch (_: Throwable) {
                // Handle went away mid-loop: the file is complete, nothing left to ask
                // for. Hand back everything this reader is counted as holding, or the
                // registry keeps those pieces armed for the life of the bridge.
                armedFrom = from
                armedTo = maxOf(held, from)
                releaseDeadlines()
                return false
            }
            armedFrom = from
            armedTo = to
            return true
        }

        /** Give up one piece, resetting its deadline only if no other reader wants it. */
        private fun freePiece(piece: Int) {
            if (!releasePiece(piece)) return
            try {
                handle.resetPieceDeadline(piece)
            } catch (_: Throwable) {
                // Handle gone: nothing to reset.
            }
        }

        /**
         * Give up this reader's claim on the swarm.
         *
         * A reader the player abandons after a seek would otherwise keep its window armed
         * for as long as the torrent lives, competing with the reader that is actually
         * feeding playback.
         */
        private fun releaseDeadlines() {
            if (armedFrom < 0) return
            for (piece in armedFrom until armedTo) freePiece(piece)
            armedFrom = -1
            armedTo = -1
        }

        /**
         * Block until the piece under the read cursor is on disk.
         *
         * Every call into libtorrent here is guarded, because the handle can go away
         * underneath a live stream: the session drops the torrent the instant the
         * download completes, so this box leaves the swarm and never uploads. Once that
         * happens the handle is invalid and `havePiece` throws
         * `RuntimeException: invalid torrent handle used`, which surfaced as a source
         * error and killed playback of a film the moment it finished downloading.
         *
         * An invalid handle is not a failure: it means the file is complete on disk, so
         * the right response is to stop asking libtorrent anything and serve the bytes.
         */
        private fun awaitCurrentPiece() {
            if (!handleUsable()) return
            val firstPiece = ((torrentOffset + position) / pieceLength).toInt()
            // Refresh the playhead window whenever it moves.
            val deadlineEnd = minOf(pieceCount, firstPiece + deadlineWindowFor(pieceLength))
            // Arming a window for a reader the player has already left behind is the
            // whole problem: see [streamGeneration].
            if (isCurrent() && (firstPiece != armedFrom || deadlineEnd != armedTo)) {
                if (!armDeadlines(firstPiece, deadlineEnd)) return
            }
            var waitedMs = 0L
            var nextNotifyMs = STALL_NOTIFY_FIRST_MS
            while (!closed.get() && handleUsable() && !havePieceSafe(firstPiece)) {
                if (!isCurrent()) {
                    // The player has moved on and reset this socket. Stop competing with
                    // the request that replaced us: give the pieces back and let the
                    // thread go. Closing the stream is what releases the deadlines.
                    android.util.Log.i(
                        "KeenTorrent",
                        "bridge superseded: piece=$firstPiece waitedMs=$waitedMs",
                    )
                    throw java.io.IOException("Superseded by a later range request")
                }
                Thread.sleep(PIECE_POLL_MS)
                waitedMs += PIECE_POLL_MS
                if (waitedMs >= nextNotifyMs) {
                    onStall?.invoke(firstPiece)
                    // Once the buffer completes the service stops its progress loop, so a
                    // player blocked in here is invisible: no ticks, no broadcasts, and up
                    // to five minutes of silence before the wait even gives up. Say which
                    // piece is being waited on and for how long — the difference between
                    // "the swarm is still feeding us" and "we are asking for something
                    // nobody is sending" is otherwise unobservable from outside.
                    android.util.Log.i(
                        "KeenTorrent",
                        "bridge waiting: piece=$firstPiece waitedMs=$waitedMs position=$position",
                    )
                    nextNotifyMs = waitedMs + STALL_NOTIFY_EVERY_MS
                }
                // A reader the player abandoned after a far seek must not poll
                // forever with its deadlines cleared; the active request re-arms
                // its own window and is never near this bound.
                if (waitedMs > MAX_PIECE_WAIT_MS) {
                    throw java.io.IOException("Timed out waiting for torrent piece $firstPiece")
                }
            }
            // Only a closed bridge is a real end of stream. A vanished handle just means
            // the download finished and we left the swarm — keep serving from disk.
            if (closed.get()) {
                // The other way a 500 reaches the player, and the one that looks identical
                // from the outside: the session was torn down under a live read.
                android.util.Log.w("KeenTorrent", "bridge 500: stream closed while waiting for piece $firstPiece")
                throw java.io.IOException("Torrent stream closed")
            }
        }

        /** True while libtorrent still owns this torrent; false once it has been removed. */
        private fun handleUsable(): Boolean = try {
            handle.isValid
        } catch (_: Throwable) {
            false
        }

        private fun havePieceSafe(piece: Int): Boolean = try {
            handle.havePiece(piece)
        } catch (_: Throwable) {
            // Handle removed: the file is complete, so treat every piece as present.
            true
        }

        override fun close() {
            // Hand back the swarm before anything else: a reader the player walked away
            // from must not go on holding deadlines the active one needs.
            releaseDeadlines()
            // Null when the stream was closed before a single piece landed, which is
            // exactly the case this lazy open exists for.
            source?.close()
            super.close()
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        private const val MIME_MATROSKA = "video/x-matroska"

        /** Diagnostic flags live here, alongside the WebView ones. */
        private const val FLAG_DIR = "/data/local/tmp"

        /** Serve the container untouched, as it arrives from the swarm. */
        private const val FLAG_NO_MKV_PATCH = "keen_no_mkv_patch"

        /** Keep the offending tracks, blanking only their encodings. */
        private const val FLAG_MKV_KEEP_TRACKS = "keen_mkv_keep_tracks"

        /** Not looked for yet. */
        private const val TRACKS_UNRESOLVED = -2L

        /** Looked for and not found, or not a container this applies to. */
        private const val TRACKS_NONE = -1L

        /**
         * How much of the file to read when locating `Tracks`. The SeekHead that points at
         * it sits at the very front of the segment; 64 KB is far more than any muxer needs
         * and still only the first few pieces the stream fetches regardless.
         */
        private const val TRACKS_HEAD_BYTES = 64 * 1024

        /** Enough to hold the longest possible element ID plus length descriptor. */
        private const val TRACKS_HEADER_BYTES = 12

        /**
         * Refuse to buffer an implausible `Tracks` element. A real one is a few kilobytes
         * even with dozens of tracks; anything vastly larger means the offset was wrong,
         * and reading it would put a large allocation on a 256 MB heap for nothing.
         */
        private const val TRACKS_MAX_BYTES = 4L * 1024 * 1024
        /** The window used when piece length is unknown and no byte budget can be worked out. */
        const val DEADLINE_WINDOW_PIECES = 12

        /**
         * Floor for the read-ahead window, in pieces.
         *
         * Two: the piece under the playhead and the one after it. Anything less and a
         * finished piece leaves the swarm with no deadline to work towards.
         *
         * This used to be [DEADLINE_WINDOW_PIECES], which quietly defeated the byte budget
         * below on exactly the torrents that could least afford it. A 12-piece floor is
         * 96 MB when pieces are 8 MB and 192 MB when they are 16 MB, so a window meant to
         * be 48 MB became two to four times that, and every piece in it fell due inside
         * three seconds — one undifferentiated batch as far as the picker is concerned.
         * The swarm spread its requests across all of it while the player sat waiting for
         * the first piece: measured on the Mi Box, 61 seconds blocked on piece 0 with 806
         * seeds connected and 2.8 MB/s arriving.
         */
        const val MIN_DEADLINE_WINDOW_PIECES = 2

        /**
         * How far ahead of the playhead the swarm is told to fetch.
         *
         * Expressed in BYTES rather than pieces: a 12-piece window is ~3 MB on a small
         * torrent with 256 KB pieces but ~48 MB on a big one with 4 MB pieces, so a fixed
         * piece count gave large films the shortest read-ahead in real time — exactly the
         * files that most need a cushion. Sizing by bytes gives every torrent a
         * comparable number of seconds of video in front of the playhead, which is what
         * stops the "plays, stalls, retries, stalls" cycle on high-bitrate rips.
         *
         * This buffer costs disk, not heap — the device has a 256 MB heap cap, so the
         * cushion deliberately lives in the piece window rather than in the player.
         */
        const val READAHEAD_BYTES = 48L * 1024 * 1024

        fun deadlineWindowFor(pieceLength: Int): Int =
            if (pieceLength <= 0) {
                DEADLINE_WINDOW_PIECES
            } else {
                (READAHEAD_BYTES / pieceLength).toInt()
                    .coerceIn(MIN_DEADLINE_WINDOW_PIECES, 256)
            }

        /**
         * Gap between one piece's deadline and the next, scaled to how long a piece
         * actually takes to arrive.
         *
         * A deadline is a request to have the piece by a given time, and the picker orders
         * its work by them. A flat 250 ms made that ordering meaningless once pieces were
         * large: a 12-piece window was entirely due within three seconds, while a single
         * 8 MB piece needs about three seconds on its own at this box's typical rate. Every
         * piece was late the moment it was armed, so none was more urgent than any other.
         *
         * Scaling by size restores the queue the deadlines are meant to express: the piece
         * the player is blocked on is due now, the next is due about when the first should
         * have landed, and so on. Small-piece torrents are unaffected, since the floor is
         * the old constant.
         */
        fun deadlineStepMsFor(pieceLength: Int): Int =
            if (pieceLength <= 0) {
                DEADLINE_STEP_MS
            } else {
                ((pieceLength / BYTES_PER_MB) * DEADLINE_STEP_MS_PER_MB)
                    .coerceIn(DEADLINE_STEP_MS, 4_000)
            }

        private const val BYTES_PER_MB = 1024 * 1024

        /**
         * Per megabyte of piece, added to a piece's deadline over its predecessor's. At
         * 250 ms this assumes roughly 4 MB/s of useful throughput, which is at the
         * optimistic end of what this box sees, so the ordering stays tight rather than
         * artificially spacing pieces out.
         */
        private const val DEADLINE_STEP_MS_PER_MB = 250
        private const val DEADLINE_STEP_MS = 250
        private const val PIECE_POLL_MS = 75L
        private const val STALL_NOTIFY_FIRST_MS = 450L
        private const val STALL_NOTIFY_EVERY_MS = 750L
        private const val MAX_PIECE_WAIT_MS = 5 * 60 * 1000L
    }
}
