package com.keenzero.app.torrent

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/**
 * Re-cuts (E-)AC-3 audio inside AVI files into whole syncframes before the player sees it.
 *
 * ## Why this exists
 *
 * `AviExtractor` hands every audio chunk to the player as one sample, verbatim. That is
 * only right when a chunk holds whole codec frames. VirtualDub-style muxers do not cut
 * AC-3 that way: they write one chunk per video frame, sized by byte rate, so a 192 kb/s
 * track arrives as 1001-byte chunks of 768-byte syncframes. Every sample after the first
 * starts part-way through a frame.
 *
 * The TV takes AC-3 as passthrough, and the passthrough sink sizes each buffer by parsing
 * the syncframe header it expects at the buffer's start. With fragments it miscounts,
 * logs `Unexpected audio track timestamp discontinuity` about once a second, and audio
 * stops advancing. Video keeps loading until the player's byte budget is full, after which
 * nothing loads and nothing plays. On screen that is a buffering spinner that never
 * clears, over a torrent that is downloading perfectly well (2026-10-08, a 1986 dvdrip
 * stuck at 6 s).
 *
 * ## What it does
 *
 * Audio track outputs of an AVI are wrapped. For (E-)AC-3 they buffer chunk bytes, find
 * syncframes, and emit one sample per frame. Timestamps are anchored on the first chunk
 * after a start or seek and then counted in decoded samples, which is the same approach
 * upstream already takes for MP3 in AVI (`MpegAudioChunkHandler`). Every other track and
 * every other container passes through untouched.
 */
@OptIn(UnstableApi::class)
class AviAc3ExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    // DefaultMediaSourceFactory configures subtitle handling through these. The interface
    // defaults are no-ops, so without forwarding, subtitles inside mkv files would break.
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Deprecated("Forwarded for DefaultMediaSourceFactory")
    @OptIn(ExperimentalApi::class)
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    @OptIn(ExperimentalApi::class)
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int,
    ): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    private fun wrap(extractor: Extractor): Extractor =
        if (extractor is AviExtractor) AviAc3Extractor(extractor) else extractor
}

/** Delegates to [avi] but routes its audio tracks through [Ac3FramingTrackOutput]. */
@OptIn(UnstableApi::class)
internal class AviAc3Extractor(private val avi: Extractor) : Extractor {

    private val framers = mutableListOf<Ac3FramingTrackOutput>()

    override fun sniff(input: ExtractorInput): Boolean = avi.sniff(input)

    override fun init(output: ExtractorOutput) {
        avi.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                if (type != C.TRACK_TYPE_AUDIO) return track
                return Ac3FramingTrackOutput(track).also(framers::add)
            }

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        avi.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        avi.seek(position, timeUs)
        framers.forEach { it.reset() }
    }

    override fun release() = avi.release()

    override fun getUnderlyingImplementation(): Extractor = avi
}

/**
 * A [TrackOutput] that, once its format is (E-)AC-3, turns arbitrary chunk boundaries into
 * one sample per syncframe. Any other format passes straight through.
 */
@OptIn(UnstableApi::class)
internal class Ac3FramingTrackOutput(private val out: TrackOutput) : TrackOutput {

    private var reframing = false

    /** Chunk bytes not yet emitted as a frame. */
    private var pending = ByteArray(32 * 1024)
    private var pendingSize = 0

    /** Bytes dropped from the front of [pending] since the last reset. */
    private var consumedBytes = 0L
    private var firstChunkTimeUs = C.TIME_UNSET
    private var anchorUs = C.TIME_UNSET
    private var samplesSinceAnchor = 0L

    /** True once two consecutive syncframes have lined up, so the stream is trusted. */
    private var locked = false

    private val emitView = ParsableByteArray()
    private val headerBits = ParsableBitArray()

    fun reset() {
        pendingSize = 0
        consumedBytes = 0
        firstChunkTimeUs = C.TIME_UNSET
        anchorUs = C.TIME_UNSET
        samplesSinceAnchor = 0
        locked = false
    }

    override fun format(format: Format) {
        reframing = format.sampleMimeType == MimeTypes.AUDIO_AC3 ||
            format.sampleMimeType == MimeTypes.AUDIO_E_AC3
        out.format(format)
    }

    override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (!reframing) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensureCapacity(pendingSize + length)
        val read = input.read(pending, pendingSize, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        pendingSize += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!reframing) return out.sampleData(data, length, sampleDataPart)
        ensureCapacity(pendingSize + length)
        data.readBytes(pending, pendingSize, length)
        pendingSize += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (!reframing) return out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        if (firstChunkTimeUs == C.TIME_UNSET) firstChunkTimeUs = timeUs
        drainFrames()
    }

    private fun drainFrames() {
        var pos = 0
        while (true) {
            if (pendingSize - pos < 2) break
            if (!locked || !isSync(pos)) {
                locked = false
                val sync = findSync(pos)
                if (sync < 0) {
                    // Keep a trailing 0x0B: it may be the first half of the next syncword.
                    pos = if (pendingSize > 0 && pending[pendingSize - 1] == SYNC_0) pendingSize - 1 else pendingSize
                    break
                }
                pos = sync
            }
            if (pendingSize - pos < HEADER_BYTES) break
            headerBits.reset(pending, pendingSize)
            headerBits.setPosition(pos * 8)
            val info = Ac3Util.parseAc3SyncframeInfo(headerBits)
            val frameSize = info.frameSize
            if (frameSize < HEADER_BYTES || info.sampleRate <= 0 || info.sampleCount <= 0) {
                // A 0x0B77 inside payload, not a frame. Step past it and search again.
                locked = false
                pos++
                continue
            }
            if (pendingSize - pos < frameSize) break
            if (!locked) {
                // Only trust a syncword when the next frame starts exactly where this one ends.
                if (pendingSize - pos < frameSize + 2) break
                if (!isSync(pos + frameSize)) {
                    pos++
                    continue
                }
                locked = true
            }
            if (anchorUs == C.TIME_UNSET) {
                // Bytes skipped before the first whole frame still took time to play.
                val bytesPerSecond = frameSize.toLong() * info.sampleRate / info.sampleCount
                anchorUs = firstChunkTimeUs + (consumedBytes + pos) * C.MICROS_PER_SECOND / bytesPerSecond
            }
            emitView.reset(pending, pendingSize)
            emitView.setPosition(pos)
            out.sampleData(emitView, frameSize)
            out.sampleMetadata(
                anchorUs + samplesSinceAnchor * C.MICROS_PER_SECOND / info.sampleRate,
                C.BUFFER_FLAG_KEY_FRAME,
                frameSize,
                0,
                null,
            )
            samplesSinceAnchor += info.sampleCount
            pos += frameSize
        }
        if (pos > 0) {
            System.arraycopy(pending, pos, pending, 0, pendingSize - pos)
            pendingSize -= pos
            consumedBytes += pos
        }
    }

    private fun isSync(pos: Int): Boolean =
        pos + 1 < pendingSize && pending[pos] == SYNC_0 && pending[pos + 1] == SYNC_1

    private fun findSync(from: Int): Int {
        for (i in from until pendingSize - 1) if (isSync(i)) return i
        return -1
    }

    private fun ensureCapacity(size: Int) {
        if (size > pending.size) pending = pending.copyOf(maxOf(size, pending.size * 2))
    }

    private companion object {
        const val SYNC_0 = 0x0B.toByte()
        const val SYNC_1 = 0x77.toByte()

        /** Enough of a syncframe for [Ac3Util.parseAc3SyncframeInfo] on AC-3 and E-AC-3. */
        const val HEADER_BYTES = 16
    }
}
