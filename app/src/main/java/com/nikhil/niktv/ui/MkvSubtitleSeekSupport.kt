package com.nikhil.niktv.ui

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Restores sparse MKV captions without seeking or decoding the playing audio/video again.
 *
 * Main extraction retains the native, parsed subtitle packets. A cold text-stream seek uses a
 * separate bounded reader: parse MKV metadata, fetch the active indexed subtitle cluster, discard
 * AV packets, and let the unchanged TextRenderer consume the recovered packet. Missing subtitle
 * cue indexes use at most 30 seconds / 8 MiB of text-only preroll, never a full-file scan.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MkvSubtitleSeekSupport(private val recovery: SparseSubtitleCueRecovery) : Closeable {
    private data class Source(val uri: Uri, val factory: DataSource.Factory)
    private data class Request(val trackId: String, val timeUs: Long)
    private val lock = Any()
    private val formats = HashMap<String, Format>()
    private val completed = LinkedHashSet<Request>()
    private val covered = ArrayList<Pair<Long, Long>>()
    private var durationUs = C.TIME_UNSET
    private var source: Source? = null
    private var pending: Request? = null
    private var requestGeneration = 0L
    private var task: Future<*>? = null
    @Volatile private var activeRead: DataSource? = null
    @Volatile private var closed = false
    private val worker = Executors.newSingleThreadExecutor { action ->
        Thread(action, "NikTV-caption-seek").apply { isDaemon = true }
    }
    private val cancellationWorker = Executors.newSingleThreadExecutor { action ->
        Thread(action, "NikTV-caption-cancel").apply { isDaemon = true }
    }

    init {
        recovery.cueLoader = ::request
        recovery.onRelease = ::close
        recovery.onDisabled = ::cancelPending
        recovery.canRecoverTrack = { format -> synchronized(lock) { format.id in formats } }
    }

    fun configureSource(uri: Uri, dataSourceFactory: DataSource.Factory) {
        synchronized(lock) {
            check(source == null || source?.uri == uri) { "Caption recovery must be scoped to one media URI" }
            source = Source(uri, dataSourceFactory)
        }
    }

    val extractorsFactory: ExtractorsFactory = object : ExtractorsFactory {
        private val delegate = DefaultExtractorsFactory()

        override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
            delegate.setSubtitleParserFactory(factory)
            return this
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
        override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
            delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
            return this
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory {
            delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
            return this
        }

        override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            wrap(delegate.createExtractors(uri, responseHeaders))

        private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map { extractor ->
            if (extractor is MatroskaExtractor) CapturingExtractor(extractor) else extractor
        }.toTypedArray()
    }

    private inner class CapturingExtractor(private val delegate: Extractor) : Extractor by delegate {
        private var readFromUs = 0L
        override fun seek(position: Long, timeUs: Long) {
            readFromUs = timeUs
            delegate.seek(position, timeUs)
        }
        override fun init(output: ExtractorOutput) {
            val recording = object : ExtractorOutput by output {
                override fun seekMap(map: SeekMap) {
                    synchronized(lock) { durationUs = map.durationUs }
                    output.seekMap(map)
                }
            }
            delegate.init(capturingOutput(recording) { format, timeUs, bytes -> recovery.capture(format, timeUs, bytes) })
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            val result = delegate.read(input, seekPosition)
            if (result == Extractor.RESULT_END_OF_INPUT) synchronized(lock) {
                // A partial read cannot prove a gap: a long cue may begin before its first cluster.
                if (readFromUs == 0L && durationUs != C.TIME_UNSET && durationUs > 0L) {
                    covered += readFromUs to durationUs
                    while (covered.size > 64) covered.removeAt(0)
                }
            }
            return result
        }
    }

    private fun capturingOutput(
        output: ExtractorOutput,
        capture: (Format, Long, ByteArray) -> Unit
    ): ExtractorOutput = object : ExtractorOutput by output {
        override fun track(id: Int, type: Int): TrackOutput {
            val target = output.track(id, type)
            return if (type != C.TRACK_TYPE_TEXT) target else CapturingCaptionOutput(target, {
                if (it.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
                    it.id?.let { id -> synchronized(lock) { formats[id] = it } }
                }
            }, capture)
        }
    }

    private fun request(trackId: String, timeUs: Long): Boolean = synchronized(lock) {
        if (closed || source == null || trackId !in formats || timeUs < 0L ||
            recovery.selectedTrackId != trackId || recovery.hasActiveCue(trackId, timeUs) ||
            (durationUs != C.TIME_UNSET && timeUs >= durationUs) ||
            (recovery.canTrustCompleteExtraction &&
                covered.any { (start, end) -> start <= timeUs && timeUs < end })) return@synchronized false
        val request = Request(trackId, timeUs)
        if (request in completed) return@synchronized false
        if (pending == request) return@synchronized true
        cancelCurrentRead()
        val generation = ++requestGeneration
        pending = request
        val activeSource = checkNotNull(source)
        task = worker.submit {
            var finished = false
            try {
                finished = probe(activeSource, request)
            } catch (failure: Exception) {
                if (!closed && !Thread.currentThread().isInterrupted) {
                    Log.d("NikTvCaptions", "MKV caption seek range unavailable: ${failure.javaClass.simpleName}")
                }
            } finally {
                synchronized(lock) {
                    if (generation == requestGeneration && finished && !closed && !Thread.currentThread().isInterrupted)
                        completed += request
                    while (completed.size > 64) completed.remove(completed.first())
                    if (generation == requestGeneration && pending == request) pending = null
                }
            }
        }
        true
    }

    private fun probe(source: Source, request: Request): Boolean {
        val started = SystemClock.elapsedRealtime()
        val index = MkvSubtitleCueIndex()
        val extractor = IndexedCaptionExtractor(index)
        var seekMap: SeekMap? = null
        var parsedThroughUs = Long.MIN_VALUE
        var bytesRead = 0L
        var scanning = false
        var position = 0L
        var expectedStarts: MutableSet<Long>? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (type != C.TRACK_TYPE_TEXT) return DiscardingTrackOutput()
                return CapturingCaptionOutput(DiscardingTrackOutput(), {}, { format, timeUs, bytes ->
                    if (format.id == request.trackId && !closed && !Thread.currentThread().isInterrupted) {
                        recovery.capture(format, timeUs, bytes)
                        if (bytes.isNotEmpty()) expectedStarts?.remove(timeUs)
                    }
                })
            }
            override fun endTracks() = Unit
            override fun seekMap(map: SeekMap) { seekMap = map }
        }
        extractor.init(output)
        val seek = PositionHolder()
        try {
            while (!closed && !Thread.currentThread().isInterrupted &&
                SystemClock.elapsedRealtime() - started < MAX_PROBE_MILLIS && bytesRead < MAX_PROBE_BYTES) {
                val dataSource = source.factory.createDataSource()
                activeRead = dataSource
                try {
                    val remaining = dataSource.open(DataSpec.Builder().setUri(source.uri).setPosition(position).build())
                    val boundedReader = DataReader { bytes, offset, length ->
                        if (closed || Thread.currentThread().isInterrupted ||
                            SystemClock.elapsedRealtime() - started >= MAX_PROBE_MILLIS || bytesRead >= MAX_PROBE_BYTES)
                            throw EOFException("Caption recovery range limit reached")
                        dataSource.read(bytes, offset, minOf(length.toLong(), MAX_PROBE_BYTES - bytesRead).toInt())
                            .also { if (it > 0) bytesRead += it }
                    }
                    val input = DefaultExtractorInput(boundedReader, position,
                        if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + remaining)
                    while (!closed && !Thread.currentThread().isInterrupted) {
                        val result = extractor.read(input, seek)
                        parsedThroughUs = index.clusterTimeUs
                        if (!scanning && seekMap != null) {
                            val map = checkNotNull(seekMap)
                            if (!map.isSeekable) return false
                            val active = index.active(request.trackId.toIntOrNull(), request.timeUs)
                            val fallbackTimeUs = (request.timeUs - MAX_LOOKBACK_US).coerceAtLeast(0L)
                            position = active.minOfOrNull { it.clusterPosition }
                                ?: map.getSeekPoints(fallbackTimeUs).first.position
                            expectedStarts = active.takeIf { it.isNotEmpty() }?.mapTo(mutableSetOf()) { it.startUs }
                            scanning = true
                            extractor.seek(position, fallbackTimeUs)
                            break
                        }
                        if (scanning && ((expectedStarts?.isEmpty() == true) ||
                                parsedThroughUs > request.timeUs || result == Extractor.RESULT_END_OF_INPUT)) return true
                        if (bytesRead >= MAX_PROBE_BYTES || SystemClock.elapsedRealtime() - started >= MAX_PROBE_MILLIS) return false
                        if (result == Extractor.RESULT_SEEK) { position = seek.position; break }
                        if (result == Extractor.RESULT_END_OF_INPUT) return false
                    }
                } finally {
                    try { dataSource.close() } finally { if (activeRead === dataSource) activeRead = null }
                }
            }
        } finally {
            extractor.release()
            Log.d("NikTvCaptions", "MKV caption recovery track=${request.trackId} targetUs=${request.timeUs} " +
                "bytes=$bytesRead elapsedMs=${SystemClock.elapsedRealtime() - started} parsedThroughUs=$parsedThroughUs")
        }
        return false
    }

    private fun cancelCurrentRead() {
        task?.cancel(true)
        activeRead?.let { reading -> cancellationWorker.execute { runCatching { reading.close() } } }
    }

    fun cancelPending() {
        synchronized(lock) { requestGeneration++; cancelCurrentRead(); pending = null }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            recovery.cueLoader = null
            recovery.onDisabled = null
            recovery.canRecoverTrack = { false }
            cancelCurrentRead()
            pending = null
            completed.clear()
            covered.clear()
            formats.clear()
        }
        worker.shutdownNow()
        cancellationWorker.shutdown()
    }

    private companion object {
        const val MAX_LOOKBACK_US = 30_000_000L
        const val MAX_PROBE_BYTES = 8L * 1_024 * 1_024
        const val MAX_PROBE_MILLIS = 5_000L
    }
}

/** CueDuration is optional; use only explicitly indexed finite active intervals. */
internal class MkvSubtitleCueIndex {
    internal data class Entry(val trackId: Int, val startUs: Long, val endUs: Long, val clusterPosition: Long)
    private val entries = ArrayList<Entry>()
    var clusterTimeUs: Long = Long.MIN_VALUE
    fun add(entry: Entry) {
        if (entry.trackId >= 0 && entry.startUs >= 0L && entry.endUs > entry.startUs &&
            entry.clusterPosition >= 0L && entries.size < 100_000) entries += entry
    }
    fun active(trackId: Int?, timeUs: Long): List<Entry> =
        entries.filter { it.trackId == trackId && it.startUs <= timeUs && timeUs < it.endUs }.distinct()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class IndexedCaptionExtractor(private val index: MkvSubtitleCueIndex) :
    MatroskaExtractor(DefaultSubtitleParserFactory()) {
    private var segmentPosition = 0L
    private var scaleNs = 1_000_000L
    private var cueTime = -1L
    private var cueTrack = -1
    private var cueCluster = -1L
    private var cueDuration = -1L
    private val pointEntries = ArrayList<Triple<Int, Long, Long>>()

    override fun getElementType(id: Int): Int = when (id) {
        CUE_TRACK, CUE_DURATION -> EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT
        else -> super.getElementType(id)
    }
    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        super.startMasterElement(id, contentPosition, contentSize)
        when (id) {
            SEGMENT -> segmentPosition = contentPosition
            CUE_POINT -> { cueTime = -1L; pointEntries.clear() }
            CUE_TRACK_POSITIONS -> { cueTrack = -1; cueCluster = -1L; cueDuration = -1L }
        }
    }
    override fun integerElement(id: Int, value: Long) {
        super.integerElement(id, value)
        when (id) {
            TIMECODE_SCALE -> scaleNs = value
            TIMECODE -> index.clusterTimeUs = scaled(value)
            CUE_TIME -> cueTime = scaled(value)
            CUE_TRACK -> cueTrack = value.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
            CUE_CLUSTER_POSITION -> cueCluster = value
            CUE_DURATION -> cueDuration = scaled(value)
        }
    }
    override fun endMasterElement(id: Int) {
        if (id == CUE_TRACK_POSITIONS && cueDuration > 0L) {
            pointEntries += Triple(cueTrack, cueCluster, cueDuration)
        } else if (id == CUE_POINT && cueTime >= 0L) {
            pointEntries.forEach { (track, cluster, duration) ->
                if (cluster >= 0L && duration <= Long.MAX_VALUE - cueTime && cluster <= Long.MAX_VALUE - segmentPosition)
                    index.add(MkvSubtitleCueIndex.Entry(track, cueTime, cueTime + duration, segmentPosition + cluster))
            }
        }
        super.endMasterElement(id)
    }
    private fun scaled(value: Long): Long =
        if (value >= 0L && scaleNs > 0L && value <= Long.MAX_VALUE / scaleNs) value * scaleNs / 1_000L else -1L

    private companion object {
        const val SEGMENT = 0x18538067
        const val TIMECODE_SCALE = 0x2AD7B1
        const val TIMECODE = 0xE7
        const val CUE_POINT = 0xBB
        const val CUE_TIME = 0xB3
        const val CUE_TRACK_POSITIONS = 0xB7
        const val CUE_TRACK = 0xF7
        const val CUE_CLUSTER_POSITION = 0xF1
        const val CUE_DURATION = 0xB2
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class CapturingCaptionOutput(
    private val delegate: TrackOutput,
    private val onFormat: (Format) -> Unit,
    private val capture: (Format, Long, ByteArray) -> Unit
) : TrackOutput by delegate {
    private var currentFormat: Format? = null
    private val pending = ByteArrayOutputStream()
    private var overflow = false

    override fun format(format: Format) {
        currentFormat = format
        onFormat(format)
        delegate.format(format)
    }
    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (!overflow && pending.size() + length <= 1_024 * 1_024) pending.write(data, offset, length)
        else { overflow = true; pending.reset() }
    }
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        delegate.sampleData(DataReader { bytes, offset, count ->
            input.read(bytes, offset, count).also { if (it > 0) append(bytes, offset, it) }
        }, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        append(data.data, data.position, length)
        delegate.sampleData(data, length, sampleDataPart)
    }
    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val bytes = pending.toByteArray()
        val start = bytes.size - offset - size
        val format = currentFormat
        if (format != null) {
            if (!overflow && start >= 0 && cryptoData == null)
                capture(format, timeUs, bytes.copyOfRange(start, start + size))
            else capture(format, timeUs, ByteArray(0)) // Invalidates complete-coverage trust.
        }
        pending.reset()
        if (!overflow && offset > 0 && offset <= bytes.size) append(bytes, bytes.size - offset, offset)
        overflow = false
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
