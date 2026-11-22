package com.nikhil.niktv.ui

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.text.CueDecoder
import java.nio.ByteBuffer

/** Retains parsed, finite-duration subtitle packets independently of the AV sample queues. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SparseSubtitleCueRecovery(
    private val maxPackets: Int = 2_048,
    private val maxBytes: Int = 8 * 1_024 * 1_024
) {
    @Volatile
    var selectedTrackId: String? = null
        private set

    /** Schedules nonblocking text-only recovery; true means a requested range is still loading. */
    @Volatile
    var cueLoader: ((trackId: String, timeUs: Long) -> Boolean)? = null

    @Volatile
    var onRelease: (() -> Unit)? = null

    @Volatile
    var onDisabled: (() -> Unit)? = null

    @Volatile
    var canRecoverTrack: ((Format) -> Boolean)? = null

    @Volatile
    var canTrustCompleteExtraction: Boolean = true
        private set

    private class Payload(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = other is Payload && bytes.contentEquals(other.bytes)
    }

    private data class Key(val trackId: String, val timeUs: Long, val data: Payload)
    private data class Packet(val key: Key, val endUs: Long, val nonEmpty: Boolean)
    private val decoder = CueDecoder()
    private val packets = LinkedHashMap<Key, Packet>()
    private val replacementBehaviors = HashMap<String, Int>()
    private var retainedBytes = 0

    init {
        require(maxPackets > 0 && maxBytes > 0)
    }

    /** Called with the actual transcoded packet and its absolute media timestamp. */
    @Synchronized
    fun capture(format: Format, timeUs: Long, data: ByteArray) {
        if (format.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES) return
        val id = format.id
        if (id == null || format.cryptoType != C.CRYPTO_TYPE_NONE || data.isEmpty() || data.size > maxBytes) {
            canTrustCompleteExtraction = false
            return
        }
        val decoded = try {
            decoder.decode(timeUs, data, 0, data.size)
        } catch (_: RuntimeException) {
            // Recovery must not introduce a new failure for an unsupported or damaged sample.
            canTrustCompleteExtraction = false
            return
        }
        if (decoded.durationUs == C.TIME_UNSET || decoded.durationUs <= 0L ||
            decoded.endTimeUs <= timeUs) {
            canTrustCompleteExtraction = false
            return
        }
        val key = Key(id, timeUs, Payload(data.copyOf()))
        replacementBehaviors[id] = format.cueReplacementBehavior
        if (packets.containsKey(key)) return
        packets[key] = Packet(key, decoded.endTimeUs, decoded.cues.isNotEmpty())
        retainedBytes += data.size
        while (packets.size > maxPackets || retainedBytes > maxBytes) {
            canTrustCompleteExtraction = false
            val oldest = packets.entries.iterator()
            val evicted = oldest.next()
            retainedBytes -= evicted.key.data.bytes.size
            oldest.remove()
        }
    }

    @Synchronized
    fun hasActiveCue(trackId: String, timeUs: Long): Boolean =
        activePackets(trackId, timeUs, replacementBehaviors[trackId]).any { it.nonEmpty }

    @Synchronized
    private fun activePackets(format: Format, timeUs: Long): List<Packet> =
        format.id?.let { activePackets(it, timeUs, format.cueReplacementBehavior) }.orEmpty()

    private fun activePackets(trackId: String, timeUs: Long, behavior: Int?): List<Packet> {
        val before = packets.values.filter { it.key.trackId == trackId && it.key.timeUs <= timeUs }
        if (behavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE) {
            // An expired newer packet must not reveal an older replaced cue beneath it.
            val latest = before.maxByOrNull { it.key.timeUs }
            return listOfNotNull(latest?.takeIf { timeUs < it.endUs })
        }
        return before.filter { timeUs < it.endUs }.sortedBy { it.key.timeUs }
    }

    fun wrapRenderer(delegate: Renderer): Renderer = RecoveringRenderer(delegate)

    private fun releaseRecovery() {
        val callback = synchronized(this) { onRelease.also { onRelease = null } }
        callback?.invoke()
    }

    private inner class RecoveringRenderer(private val delegate: Renderer) : Renderer by delegate {
        private var originalStream: SampleStream? = null
        private var recoveringStream: RecoveringStream? = null
        private var offsetUs = 0L
        private var positionUs = 0L

        private fun wrap(formats: Array<out Format>, stream: SampleStream, offset: Long): SampleStream {
            offsetUs = offset
            originalStream = stream
            val format = formats.firstOrNull()
            selectedTrackId = format?.takeIf {
                it.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES && canRecoverTrack?.invoke(it) != false
            }?.id
            return if (format != null && selectedTrackId != null) {
                RecoveringStream(stream, format, positionUs - offset).also { recoveringStream = it }
            } else {
                recoveringStream = null
                stream
            }
        }

        override fun enable(
            configuration: RendererConfiguration, formats: Array<out Format>, stream: SampleStream,
            positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean,
            startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId
        ) {
            this.positionUs = positionUs
            delegate.enable(configuration, formats, wrap(formats, stream, offsetUs), positionUs,
                joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
        }

        override fun replaceStream(
            formats: Array<out Format>, stream: SampleStream, startPositionUs: Long,
            offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId
        ) {
            delegate.replaceStream(formats, wrap(formats, stream, offsetUs), startPositionUs, offsetUs, mediaPeriodId)
        }

        // ExoPlayer compares this identity with the MediaPeriod's original SampleStream.
        override fun getStream(): SampleStream? = originalStream

        override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
            this.positionUs = positionUs
            recoveringStream?.reset(positionUs - offsetUs)
            delegate.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
        }

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            this.positionUs = positionUs
            recoveringStream?.positionUs = positionUs - offsetUs
            delegate.render(positionUs, elapsedRealtimeUs)
        }

        private fun forgetSelection() {
            originalStream = null
            recoveringStream = null
            selectedTrackId = null
        }

        override fun disable() {
            forgetSelection()
            try {
                delegate.disable()
            } finally {
                onDisabled?.invoke()
            }
        }

        override fun reset() {
            forgetSelection()
            try {
                delegate.reset()
            } finally {
                onDisabled?.invoke()
            }
        }

        override fun release() {
            forgetSelection()
            try {
                delegate.release()
            } finally {
                releaseRecovery()
            }
        }
    }

    /** Feeds retained packets through the native renderer, preserving styling and cue end times. */
    private inner class RecoveringStream(
        private val delegate: SampleStream,
        private var format: Format,
        var positionUs: Long
    ) : SampleStream {
        private val emitted = LinkedHashSet<Key>()
        private val injected = HashSet<Key>()
        private var emittedBytes = 0
        private var sentRecoveryFormat = false
        private var probeTargetUs = positionUs
        private var probeComplete = false
        private val discardBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

        fun reset(positionUs: Long) {
            this.positionUs = positionUs
            emitted.clear()
            injected.clear()
            emittedBytes = 0
            sentRecoveryFormat = false
            probeTargetUs = positionUs
            probeComplete = false
        }

        private fun nextRecovered(): Packet? = activePackets(format, positionUs).firstOrNull { it.key !in emitted }

        private fun remember(key: Key) {
            if (emitted.add(key)) emittedBytes += key.data.bytes.size
            while (emitted.size > maxPackets || emittedBytes > maxBytes) {
                val oldest = emitted.first()
                emittedBytes -= oldest.data.bytes.size
                emitted.remove(oldest)
                injected.remove(oldest)
            }
        }

        override fun isReady(): Boolean = nextRecovered() != null || delegate.isReady
        override fun maybeThrowError() = delegate.maybeThrowError()

        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            // Query on every read: a subtitle-only preroll can populate the cache after resetPosition.
            repeat(32) {
                val recovered = nextRecovered()
                if (recovered != null) {
                    if (!sentRecoveryFormat || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                        holder.format = format
                        holder.drmSession = null
                        sentRecoveryFormat = true
                        return C.RESULT_FORMAT_READ
                    }
                    buffer.clear()
                    buffer.timeUs = recovered.key.timeUs
                    buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
                    if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                        buffer.ensureSpaceForWrite(recovered.key.data.bytes.size)
                        checkNotNull(buffer.data).put(recovered.key.data.bytes)
                    }
                    if (readFlags and SampleStream.FLAG_PEEK == 0) {
                        remember(recovered.key)
                        injected += recovered.key
                    }
                    return C.RESULT_BUFFER_READ
                }

                val result = delegate.readData(holder, buffer, readFlags)
                if (result == C.RESULT_FORMAT_READ) {
                    holder.format?.let { format = it }
                    return result
                }
                if (result == C.RESULT_NOTHING_READ ||
                    (result == C.RESULT_BUFFER_READ && buffer.isEndOfStream)) {
                    // Let ordinary native packets through before scheduling extra extraction.
                    val trackId = format.id
                    val loading = if (!probeComplete) {
                        val pending = trackId != null && !hasActiveCue(trackId, probeTargetUs) &&
                            cueLoader?.invoke(trackId, probeTargetUs) == true
                        if (!pending) probeComplete = true
                        pending
                    } else false
                    if (nextRecovered() != null) {
                        buffer.clear()
                        return@repeat
                    }
                    if (loading) {
                        buffer.clear()
                        return C.RESULT_NOTHING_READ
                    }
                    return result
                }
                if (result != C.RESULT_BUFFER_READ) return result
                val key = packetKey(buffer)
                if (key != null && key in injected) {
                    if (readFlags and SampleStream.FLAG_PEEK != 0) {
                        // An invisible duplicate can be consumed even while logically peeking.
                        discardBuffer.clear()
                        delegate.readData(holder, discardBuffer, readFlags and SampleStream.FLAG_PEEK.inv())
                    }
                    buffer.clear()
                    return@repeat
                }
                if (key != null && readFlags and SampleStream.FLAG_PEEK == 0) remember(key)
                return result
            }
            return C.RESULT_NOTHING_READ
        }

        private fun packetKey(buffer: DecoderInputBuffer): Key? {
            val id = format.id ?: return null
            val data = buffer.data?.takeIf { it.position() <= maxBytes } ?: return null
            val bytes = data.writtenBytes()
            return Key(id, buffer.timeUs, Payload(bytes))
        }

        override fun skipData(positionUs: Long): Int {
            this.positionUs = positionUs
            return delegate.skipData(positionUs)
        }
    }
}

private fun ByteBuffer.writtenBytes(): ByteArray {
    val copy = duplicate()
    copy.flip()
    return ByteArray(copy.remaining()).also { copy.get(it) }
}

/** Uses the normal Media3 renderers with recovery confined to their text sample streams. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CaptionSeekRenderersFactory(
    context: Context,
    private val recovery: SparseSubtitleCueRecovery
) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val first = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (index in first until out.size) out[index] = recovery.wrapRenderer(out[index])
    }
}
