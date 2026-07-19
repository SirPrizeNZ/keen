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

        private fun awaitCurrentPiece() {
            val firstPiece = ((torrentOffset + position) / pieceLength).toInt()
            // Refresh the playhead window on every HTTP seek/range read.
            val deadlineEnd = minOf(pieceCount, firstPiece + DEADLINE_WINDOW_PIECES)
            for (piece in firstPiece until deadlineEnd) {
                handle.setPieceDeadline(piece, (piece - firstPiece) * DEADLINE_STEP_MS)
            }
            while (!closed.get() && handle.isValid() && !handle.havePiece(firstPiece)) {
                Thread.sleep(PIECE_POLL_MS)
            }
            if (closed.get() || !handle.isValid()) throw java.io.IOException("Torrent stream closed")
        }

        override fun close() {
            source.close()
            super.close()
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val DEADLINE_WINDOW_PIECES = 12
        private const val DEADLINE_STEP_MS = 250
        private const val PIECE_POLL_MS = 75L
    }
}
