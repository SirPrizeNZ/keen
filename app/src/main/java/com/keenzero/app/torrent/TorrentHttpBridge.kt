package com.keenzero.app.torrent

import fi.iki.elonen.NanoHTTPD
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

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
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Invalid media")
        }
        val rangeHeader = session.headers["range"]
        val requestedRange = HttpByteRange.parse(rangeHeader, mediaSize)
        if (rangeHeader != null && requestedRange == null) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range")
                .apply { addHeader("Content-Range", "bytes */$mediaSize") }
        }
        val range = requestedRange ?: HttpByteRange(0, mediaSize - 1)
        val status = if (requestedRange == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        val response = if (session.method == Method.HEAD) {
            newFixedLengthResponse(status, mimeType, "")
        } else {
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
                ),
                range.length,
            )
        }
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", range.length.toString())
        if (requestedRange != null) {
            response.addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/$mediaSize")
        }
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private class PieceAwareInputStream(
        file: File,
        start: Long,
        private var remaining: Long,
        private val torrentOffset: Long,
        private val pieceLength: Int,
        private val pieceCount: Int,
        private val handle: TorrentHandle,
        private val closed: AtomicBoolean,
        private val onStall: ((piece: Int) -> Unit)?,
    ) : InputStream() {
        private val source = RandomAccessFile(file, "r")
        private var position = start

        init {
            source.seek(start)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            awaitCurrentPiece()
            val globalOffset = torrentOffset + position
            val inPiece = (globalOffset % pieceLength).toInt()
            val untilPieceEnd = pieceLength - inPiece
            val wanted = minOf(length.toLong(), remaining, untilPieceEnd.toLong()).toInt()
            val count = source.read(buffer, offset, wanted)
            if (count < 0) return -1
            position += count
            remaining -= count
            return count
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
            if (!havePieceSafe(firstPiece)) {
                // Seek past the downloaded window: stale deadlines keep the swarm
                // busy at the old playhead — drop them so bandwidth moves here now.
                try {
                    handle.clearPieceDeadlines()
                } catch (_: Throwable) {
                }
            }
            // Refresh the playhead window on every HTTP seek/range read.
            val deadlineEnd = minOf(pieceCount, firstPiece + deadlineWindowFor(pieceLength))
            try {
                for (piece in firstPiece until deadlineEnd) {
                    handle.setPieceDeadline(piece, (piece - firstPiece) * DEADLINE_STEP_MS)
                }
            } catch (_: Throwable) {
                // Handle went away mid-loop: the file is complete, nothing left to ask for.
                return
            }
            var waitedMs = 0L
            var nextNotifyMs = STALL_NOTIFY_FIRST_MS
            while (!closed.get() && handleUsable() && !havePieceSafe(firstPiece)) {
                Thread.sleep(PIECE_POLL_MS)
                waitedMs += PIECE_POLL_MS
                if (waitedMs >= nextNotifyMs) {
                    onStall?.invoke(firstPiece)
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
            if (closed.get()) throw java.io.IOException("Torrent stream closed")
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
            source.close()
            super.close()
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        /** Floor for the read-ahead window, and the value used when piece length is unknown. */
        const val DEADLINE_WINDOW_PIECES = 12

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
                (READAHEAD_BYTES / pieceLength).toInt().coerceIn(DEADLINE_WINDOW_PIECES, 256)
            }
        private const val DEADLINE_STEP_MS = 250
        private const val PIECE_POLL_MS = 75L
        private const val STALL_NOTIFY_FIRST_MS = 450L
        private const val STALL_NOTIFY_EVERY_MS = 750L
        private const val MAX_PIECE_WAIT_MS = 5 * 60 * 1000L
    }
}
