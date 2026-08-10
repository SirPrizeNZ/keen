package com.keenzero.app.torrent

/**
 * Rewrites the `Tracks` element of a Matroska stream so that tracks ExoPlayer refuses to
 * parse disappear instead of failing the whole file.
 *
 * ## Why this exists
 *
 * `MatroskaExtractor` supports exactly one content-compression algorithm — header
 * stripping (`ContentCompAlgo` 3). Anything else is fatal, and fatal for the *file*, not
 * for the track:
 *
 * ```java
 * case ID_CONTENT_COMPRESSION_ALGORITHM:
 *   // This extractor only supports header stripping.
 *   if (value != 3) {
 *     throw ParserException.createForMalformedContainer(
 *         "ContentCompAlgo " + value + " not supported", null);
 *   }
 * ```
 *
 * The throw is unconditional: it happens while reading the `Tracks` element, long before
 * any track could be deselected, and it takes the video and audio down with it. Encodes
 * that zlib-compress their PGS subtitle tracks — a common way to ship a disc's full
 * subtitle set — are therefore unplayable, however ordinary their video is. The film that
 * exposed this carried one HEVC track, six AAC tracks, and twenty-one zlib-compressed PGS
 * subtitle tracks; the twenty-one were the only reason the other seven would not play.
 *
 * ## What it does
 *
 * Every `TrackEntry` that declares an unsupported `ContentCompAlgo` is overwritten in
 * place with an EBML `Void` element of **exactly the same length**. Two properties make
 * this safe:
 *
 *  - `Void` is a global EBML element, legal anywhere, and the extractor skips it as an
 *    element it does not recognise. The offending `ContentCompAlgo` is never read, so the
 *    throw never happens.
 *  - Because the replacement is byte-for-byte the same length, every absolute offset in
 *    the file — the `Cues` index, the `SeekHead` table, the segment size — remains valid.
 *    Nothing downstream can tell the difference, and no other element has to be touched.
 *
 * The removed tracks' sample data is still present in the clusters, and that is fine:
 * upstream ignores blocks belonging to a track it never saw.
 *
 * ```java
 * Track track = tracks.get(blockTrackNumber);
 * // Ignore the block if we don't know about the track to which it belongs.
 * if (track == null) { input.skipFully(contentSize - blockTrackNumberLength); ... return; }
 * ```
 *
 * The cost is honest and bounded: those subtitle tracks become unavailable. They were
 * never playable — the alternative is not "subtitles" but "no film".
 *
 * ## Fidelity
 *
 * The removal condition mirrors upstream's throw condition exactly: a `ContentCompAlgo`
 * element that is *present* and *not 3*. A track whose compression is absent, or is
 * header stripping, is left untouched — so a file upstream can already play is passed
 * through byte-identical, and this code can only ever affect files that would otherwise
 * fail outright.
 */
object MatroskaTracksPatch {

    private const val ID_EBML = 0x1A45DFA3
    private const val ID_SEGMENT = 0x18538067
    private const val ID_SEEK_HEAD = 0x114D9B74
    private const val ID_SEEK = 0x4DBB
    private const val ID_SEEK_ID = 0x53AB
    private const val ID_SEEK_POSITION = 0x53AC
    private const val ID_TRACKS = 0x1654AE6B
    private const val ID_TRACK_ENTRY = 0xAE
    private const val ID_TRACK_NUMBER = 0xD7
    private const val ID_CONTENT_ENCODINGS = 0x6D80
    private const val ID_CONTENT_ENCODING = 0x6240
    private const val ID_CONTENT_COMPRESSION = 0x5034
    private const val ID_CONTENT_COMP_ALGO = 0x4254
    private const val ID_VOID = 0xEC

    private const val ID_CLUSTER = 0x1F43B675

    /** The one algorithm `MatroskaExtractor` implements. */
    private const val ALGO_HEADER_STRIPPING = 3L

    /** Width of the length descriptor used when restating `Tracks` inline. */
    private const val INLINE_SIZE_BYTES = 8

    /** `Tracks` ID plus that descriptor. */
    private const val INLINE_HEADER_BYTES = 4 + INLINE_SIZE_BYTES

    /** An EBML `Void` cannot be expressed in fewer bytes than an ID and a length. */
    private const val MIN_VOID_BYTES = 2

    /**
     * What the head of the file says about where things are.
     *
     * @param tracksOffset absolute offset of the `Tracks` element.
     * @param firstClusterOffset absolute offset of the first `Cluster`, or -1 if the head
     *        did not reach one.
     * @param voidOffset absolute offset of the largest `Void` element before that cluster,
     *        or -1 if there is none. Padding a muxer left behind, and the only space in
     *        the file that can be rewritten without moving anything.
     * @param voidLength that element's total length, header included.
     */
    data class Location(
        val tracksOffset: Long,
        val firstClusterOffset: Long = -1,
        val voidOffset: Long = -1,
        val voidLength: Int = 0,
    ) {
        /**
         * True when `Tracks` sits after the first cluster — the layout that makes the
         * extractor seek backwards for it, and the only case worth relocating.
         */
        val tracksAreTrailing: Boolean
            get() = firstClusterOffset in 0 until tracksOffset

        /** Room to write a replacement `Tracks` element in front of the clusters. */
        fun canInline(neededBytes: Int): Boolean =
            tracksAreTrailing && voidOffset >= 0 && voidLength >= neededBytes
    }

    /** Rewritten bytes, to be laid over the file at [offset]. */
    class Patch(val offset: Long, val bytes: ByteArray, val removedTrackNumbers: List<Long>)

    /**
     * What to blank out for a track whose compression upstream cannot read.
     *
     * The choice is not cosmetic. `Cues` entries name the track they index, and on the
     * encode that prompted this, the subtitle tracks carry the overwhelming majority of
     * the cue points. Removing a track therefore also orphans every cue point that names
     * it, which is why both strategies exist and why the right one is decided by measuring
     * seekability on a real file rather than by reasoning about it.
     */
    enum class Strategy {
        /** Blank the whole `TrackEntry`: the track ceases to exist for the extractor. */
        REMOVE_TRACK,

        /**
         * Blank only the `ContentEncodings` block: the track survives, keeping its cue
         * points resolvable, and merely stops claiming a compression that cannot be read.
         */
        STRIP_ENCODINGS,
    }

    /**
     * Find the `Tracks` element from the first bytes of the file.
     *
     * Two layouts have to be handled, and the difference is the entire reason this class
     * exists. Most encodes put `Tracks` before the first cluster, where a linear parse
     * finds it. Some put it at the very end and leave only a `SeekHead` pointer at the
     * front — which is the layout that also breaks older extractors outright. Both are
     * resolved here from the same head bytes.
     *
     * @return null if the head is not Matroska, or is too short to say where `Tracks` is.
     */
    fun locate(head: ByteArray, available: Int = head.size): Location? {
        val r = Reader(head, available)
        // EBML header, then Segment. Anything else is not a Matroska stream we can read.
        val first = r.readElement() ?: return null
        if (first.id != ID_EBML) return null
        r.seek(first.contentEnd)
        val segment = r.readElement() ?: return null
        if (segment.id != ID_SEGMENT) return null

        val segmentBody = segment.contentStart
        var tracksOffset = -1L
        var voidOffset = -1L
        var voidLength = 0
        var p = segmentBody
        while (true) {
            val el = r.at(p).readElement() ?: break
            when (el.id) {
                // Already in front of us. Keep scanning anyway: the caller still wants to
                // know where the clusters begin before deciding anything.
                ID_TRACKS -> if (tracksOffset < 0) tracksOffset = el.start
                ID_SEEK_HEAD ->
                    if (tracksOffset < 0) {
                        findTracksSeek(r, el)?.let { tracksOffset = segmentBody + it }
                    }
                // Padding. The largest one wins, since a replacement has to fit whole.
                ID_VOID -> {
                    val length = (el.contentEnd - el.start).toInt()
                    if (length > voidLength) {
                        voidOffset = el.start
                        voidLength = length
                    }
                }
                // Everything before the first cluster is header; this is where it ends.
                ID_CLUSTER -> return if (tracksOffset < 0) {
                    null
                } else {
                    Location(tracksOffset, el.start, voidOffset, voidLength)
                }
                else -> Unit
            }
            val next = el.contentEnd
            if (next <= p || next >= available) break
            p = next
        }
        // Ran out of head before reaching a cluster. A location without one is still
        // usable for patching in place; it just rules out relocating anything.
        return if (tracksOffset < 0) null else Location(tracksOffset)
    }

    /** Scan a `SeekHead` for the entry pointing at `Tracks`. Returns a segment-relative offset. */
    private fun findTracksSeek(r: Reader, seekHead: Element): Long? {
        var p = seekHead.contentStart
        while (p < seekHead.contentEnd) {
            val seek = r.at(p).readElement() ?: return null
            if (seek.id == ID_SEEK) {
                var q = seek.contentStart
                var id: Long? = null
                var pos: Long? = null
                while (q < seek.contentEnd) {
                    val f = r.at(q).readElement() ?: return null
                    when (f.id) {
                        ID_SEEK_ID -> id = r.uint(f)
                        ID_SEEK_POSITION -> pos = r.uint(f)
                    }
                    if (f.contentEnd <= q) return null
                    q = f.contentEnd
                }
                if (id == ID_TRACKS.toLong() && pos != null) return pos
            }
            if (seek.contentEnd <= p) return null
            p = seek.contentEnd
        }
        return null
    }

    /**
     * Total length of the `Tracks` element whose header starts at the beginning of [head],
     * or -1 if [head] does not hold a complete element header. Used to learn how many
     * bytes to fetch before [patch] can run.
     */
    fun elementLength(head: ByteArray, available: Int = head.size): Long {
        val el = Reader(head, available).readElement() ?: return -1
        if (el.id != ID_TRACKS) return -1
        return el.contentEnd - el.start
    }

    /**
     * Rewrite [tracks] — the complete `Tracks` element, starting at its own ID — removing
     * every track upstream would throw on.
     *
     * @return null when there is nothing to remove, so callers can skip the overlay
     *         entirely and serve the file untouched.
     */
    fun patch(
        tracks: ByteArray,
        offset: Long,
        strategy: Strategy = Strategy.REMOVE_TRACK,
    ): Patch? {
        val r = Reader(tracks, tracks.size)
        val root = r.readElement() ?: return null
        if (root.id != ID_TRACKS) return null

        val doomed = mutableListOf<Element>()
        val removedNumbers = mutableListOf<Long>()
        var p = root.contentStart
        while (p < root.contentEnd) {
            val entry = r.at(p).readElement() ?: return null
            if (entry.id == ID_TRACK_ENTRY && hasUnsupportedCompression(r, entry)) {
                when (strategy) {
                    Strategy.REMOVE_TRACK -> doomed += entry
                    // Every ContentEncodings block in the entry, not just the offending
                    // one: they are alternatives applied in order, and leaving a sibling
                    // behind would leave the extractor reading a partial description.
                    Strategy.STRIP_ENCODINGS ->
                        forEachDescendant(r, entry, ID_CONTENT_ENCODINGS) { doomed += it }
                }
                removedNumbers += trackNumber(r, entry) ?: -1L
            }
            if (entry.contentEnd <= p) return null
            p = entry.contentEnd
        }
        if (doomed.isEmpty()) return null

        val out = tracks.copyOf()
        for (entry in doomed) {
            val from = entry.start.toInt()
            val length = (entry.contentEnd - entry.start).toInt()
            // A Void needs an ID byte and at least a one-byte length, so anything shorter
            // than two bytes cannot be represented. No real TrackEntry is that small, but
            // refuse rather than corrupt the stream if one ever is.
            if (!writeVoid(out, from, length)) return null
        }
        return Patch(offset, out, removedNumbers)
    }

    /**
     * Bytes needed to restate [tracks] as a compact element: header plus every entry the
     * extractor can actually use, plus the smallest possible padding.
     */
    fun inlineTracksSize(tracks: ByteArray): Int {
        val kept = keptEntries(tracks) ?: return -1
        if (kept.isEmpty()) return -1
        return INLINE_HEADER_BYTES + kept.sumOf { it.size } + MIN_VOID_BYTES
    }

    /**
     * Restate the usable tracks as a `Tracks` element occupying a region of padding in
     * front of the clusters.
     *
     * ## Why move it at all
     *
     * When `Tracks` sits after the first cluster, the extractor reaches that cluster
     * needing two things it does not have: the track definitions and the cue index. It
     * sets a seek for each. The seeks then interleave, and every seek runs `reset()`,
     * which does this while the seek map is still unsent:
     *
     * ```java
     * if (!sentSeekMap) {
     *   perTrackCues.clear();
     * }
     * ```
     *
     * So the jump away to fetch `Tracks` throws away every cue point collected so far.
     * The `Cues` element is opened, abandoned, and never re-read; `hasAnyCues` is false
     * when the map is finally built, and the file is published as `SeekMap.Unseekable`.
     * Playback works and seeking silently does not — a seek restarts the film from zero.
     *
     * Putting the definitions in front of the clusters removes the first of the two
     * seeks: `readTracks` is already true when the cluster arrives, so only the cue seek
     * remains, nothing interrupts it, and the index survives.
     *
     * ## Why this is safe
     *
     * The region is `Void` — padding a muxer left to make room for later edits, which
     * nothing points at and nothing indexes. The replacement fills it exactly, so the
     * file's length and every absolute offset in it are untouched. The tracks written are
     * copied verbatim from the real element, so the extractor sees precisely what the
     * muxer wrote, only sooner.
     *
     * @return null when the tracks cannot be restated inside [regionLength].
     */
    fun inlineTracks(tracks: ByteArray, regionOffset: Long, regionLength: Int): Patch? {
        val kept = keptEntries(tracks) ?: return null
        if (kept.isEmpty()) return null
        val keptBytes = kept.sumOf { it.size }
        val padding = regionLength - INLINE_HEADER_BYTES - keptBytes
        if (padding < MIN_VOID_BYTES) return null

        val out = ByteArray(regionLength)
        out[0] = 0x16
        out[1] = 0x54
        out[2] = 0xAE.toByte()
        out[3] = 0x6B
        // A fixed eight-byte length descriptor. EBML permits a wider descriptor than the
        // value needs, and using one means the header size is known before the body is
        // measured — which is what lets the element be sized to fill the region exactly.
        writeFixedSize(out, 4, (keptBytes + padding).toLong(), INLINE_SIZE_BYTES)
        var at = INLINE_HEADER_BYTES
        for (entry in kept) {
            entry.copyInto(out, at)
            at += entry.size
        }
        if (!writeVoid(out, at, padding)) return null
        return Patch(regionOffset, out, emptyList())
    }

    /** Every `TrackEntry` the extractor can read, copied whole. */
    private fun keptEntries(tracks: ByteArray): List<ByteArray>? {
        val r = Reader(tracks, tracks.size)
        val root = r.readElement() ?: return null
        if (root.id != ID_TRACKS) return null
        val kept = mutableListOf<ByteArray>()
        var p = root.contentStart
        while (p < root.contentEnd) {
            val entry = r.at(p).readElement() ?: return null
            if (entry.id == ID_TRACK_ENTRY && !hasUnsupportedCompression(r, entry)) {
                kept += tracks.copyOfRange(entry.start.toInt(), entry.contentEnd.toInt())
            }
            if (entry.contentEnd <= p) return null
            p = entry.contentEnd
        }
        return kept
    }

    /** Write [value] as an EBML length descriptor of exactly [length] bytes. */
    private fun writeFixedSize(dst: ByteArray, at: Int, value: Long, length: Int) {
        var v = value or (1L shl (7 * length))
        for (i in length - 1 downTo 0) {
            dst[at + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    /** True when this entry declares a compression `MatroskaExtractor` would throw on. */
    private fun hasUnsupportedCompression(r: Reader, entry: Element): Boolean {
        var found = false
        forEachDescendant(r, entry, ID_CONTENT_ENCODINGS) { encodings ->
            forEachDescendant(r, encodings, ID_CONTENT_ENCODING) { encoding ->
                forEachDescendant(r, encoding, ID_CONTENT_COMPRESSION) { compression ->
                    forEachDescendant(r, compression, ID_CONTENT_COMP_ALGO) { algo ->
                        // Absent means upstream never reads it and never throws; only an
                        // explicit, unsupported value is grounds for removing the track.
                        if (r.uint(algo) != ALGO_HEADER_STRIPPING) found = true
                    }
                }
            }
        }
        return found
    }

    private fun trackNumber(r: Reader, entry: Element): Long? {
        var number: Long? = null
        forEachDescendant(r, entry, ID_TRACK_NUMBER) { number = r.uint(it) }
        return number
    }

    private inline fun forEachDescendant(r: Reader, parent: Element, id: Int, body: (Element) -> Unit) {
        var p = parent.contentStart
        while (p < parent.contentEnd) {
            val el = r.at(p).readElement() ?: return
            if (el.id == id) body(el)
            if (el.contentEnd <= p) return
            p = el.contentEnd
        }
    }

    /**
     * Overwrite [length] bytes at [at] with a single `Void` element of that exact length.
     *
     * The length descriptor is widened until the remaining payload fits, which is what
     * lets an arbitrary run of bytes be represented exactly rather than approximately —
     * the property the whole approach depends on.
     */
    private fun writeVoid(dst: ByteArray, at: Int, length: Int): Boolean {
        if (length < 2) return false
        for (sizeLen in 1..8) {
            val payload = length - 1 - sizeLen
            if (payload < 0) return false
            // All-ones in a size descriptor means "unknown length", so it cannot be used
            // as a real value; the highest usable value is one below it.
            val max = (1L shl (7 * sizeLen)) - 2
            if (payload > max) continue
            dst[at] = ID_VOID.toByte()
            var v = payload.toLong() or (1L shl (7 * sizeLen))
            for (i in sizeLen - 1 downTo 0) {
                dst[at + 1 + i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            java.util.Arrays.fill(dst, at + 1 + sizeLen, at + length, 0)
            return true
        }
        return false
    }

    private class Element(
        val id: Int,
        val start: Long,
        val contentStart: Long,
        val contentEnd: Long,
    )

    /**
     * A cursor over a byte array that reads EBML IDs, sizes and unsigned integers, and
     * returns null rather than throwing whenever the buffer runs out. Every caller here
     * works on a partial view of a 6 GB file, so "not enough bytes yet" is an ordinary
     * outcome, not an error.
     */
    private class Reader(private val buf: ByteArray, private val limit: Int) {
        private var pos = 0

        fun at(p: Long): Reader { pos = p.toInt(); return this }
        fun seek(p: Long) { pos = p.toInt() }

        fun readElement(): Element? {
            val start = pos
            if (start < 0 || start >= limit) return null
            val id = readId() ?: return null
            val size = readSize() ?: return null
            val contentStart = pos.toLong()
            // An unknown-size element (all size bits set) has no computable end; treat the
            // rest of the buffer as its content rather than inventing a boundary.
            val end = if (size < 0) limit.toLong() else contentStart + size
            return Element(id, start.toLong(), contentStart, end)
        }

        private fun readId(): Int? {
            if (pos >= limit) return null
            val first = buf[pos].toInt() and 0xFF
            var len = 0
            for (i in 0 until 4) if (first and (0x80 shr i) != 0) { len = i + 1; break }
            if (len == 0 || pos + len > limit) return null
            var v = 0
            for (i in 0 until len) v = (v shl 8) or (buf[pos + i].toInt() and 0xFF)
            pos += len
            return v
        }

        /** @return the size, or -1 for an unknown-size element, or null if truncated. */
        private fun readSize(): Long? {
            if (pos >= limit) return null
            val first = buf[pos].toInt() and 0xFF
            var len = 0
            for (i in 0 until 8) if (first and (0x80 shr i) != 0) { len = i + 1; break }
            if (len == 0 || pos + len > limit) return null
            var v = (first and (0xFF shr len)).toLong()
            for (i in 1 until len) v = (v shl 8) or (buf[pos + i].toInt() and 0xFF).toLong()
            pos += len
            return if (v == (1L shl (7 * len)) - 1) -1 else v
        }

        fun uint(el: Element): Long {
            var v = 0L
            var i = el.contentStart.toInt()
            val end = minOf(el.contentEnd, limit.toLong()).toInt()
            while (i < end) { v = (v shl 8) or (buf[i].toInt() and 0xFF).toLong(); i++ }
            return v
        }
    }
}
