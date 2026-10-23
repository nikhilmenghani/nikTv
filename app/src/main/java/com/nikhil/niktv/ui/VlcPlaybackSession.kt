package com.nikhil.niktv.ui

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.atomic.AtomicBoolean

/** Reuse LibVLC modules, but give every stream its own player and decoder state. */
internal class VlcPlaybackSession private constructor(
    val lib: LibVLC,
    val player: MediaPlayer,
    private val options: List<String>
) {
    private val closing = AtomicBoolean()
    private val closed = CompletableDeferred<Pair<Long, Long>>()

    fun retireAfterFailure() {
        // A fresh MediaPlayer alone still shares LibVLC's engine-level state.
        pool.invalidate(options, lib)
    }

    fun command(action: (MediaPlayer) -> Unit) {
        if (closing.get()) return
        NativePlaybackWorker.scope.launch {
            if (!closing.get()) runCatching { action(player) }
        }
    }

    /** Unlike fire-and-forget commands, a queued resume seek belongs to its caller. */
    suspend fun seekForResume(position: Long): VlcResumeSeekResult? = withContext(NativePlaybackWorker.dispatcher) {
        currentCoroutineContext().ensureActive()
        if (closing.get()) return@withContext null
        val result = player.setTime(position, false)
        VlcResumeSeekResult(result, player.time.coerceAtLeast(0L))
    }

    suspend fun close(): Pair<Long, Long> {
        if (closing.compareAndSet(false, true)) {
            // This job survives composition cancellation and owns shutdown once.
            NativePlaybackWorker.scope.launch {
                val started = SystemClock.elapsedRealtime()
                var timing = 0L to 0L
                try {
                    withContext(Dispatchers.Main.immediate) { player.setEventListener(null) }
                    timing = runCatching { player.time.coerceAtLeast(0L) to player.length.coerceAtLeast(0L) }
                        .getOrDefault(timing)
                    runCatching { player.stop() }
                    withContext(Dispatchers.Main.immediate) { runCatching { player.detachViews() } }
                    runCatching { player.release() }
                } finally {
                    runCatching { pool.release(options, lib) }
                    closed.complete(timing)
                    android.util.Log.i("NikTvPlayback", "VLC cleanup completed in ${SystemClock.elapsedRealtime() - started}ms on native worker")
                    launch { delay(45_000L); pool.trimIdle() }
                }
            }
        }
        return withContext(NonCancellable) { closed.await() }
    }

    companion object {
        private var appContext: Context? = null
        private val pool = ReusablePlaybackEnginePool<List<String>, LibVLC>(
            create = { LibVLC(requireNotNull(appContext), ArrayList(it)) },
            destroy = { engine -> NativePlaybackWorker.scope.launch { runCatching { engine.release() } } },
            clock = SystemClock::elapsedRealtime
        )

        fun create(context: Context, options: List<String>): VlcPlaybackSession {
            val started = SystemClock.elapsedRealtime()
            appContext = context.applicationContext
            val lib = pool.acquire(options)
            try {
                android.util.Log.i("NikTvPlayback", "VLC engine id=${System.identityHashCode(lib)}; fresh stream player")
                return VlcPlaybackSession(lib, MediaPlayer(lib), options).also {
                    android.util.Log.i("NikTvPlayback", "VLC creation elapsedMs=${SystemClock.elapsedRealtime() - started}")
                }
            } catch (failure: Throwable) {
                pool.invalidate(options, lib)
                pool.release(options, lib)
                throw failure
            }
        }
    }
}

internal data class VlcResumeSeekResult(val result: Long, val nativePosition: Long)

/** Single-shot VOD resume with bounded readiness and native-position acknowledgement. */
internal class VlcResumeSeekPolicy(
    requestedPosition: Long,
    private val readinessTimeoutMillis: Long = 5_000L,
    private val acknowledgementTimeoutMillis: Long = 4_000L,
    private val toleranceMillis: Long = 500L
) {
    enum class Kind { PENDING, ISSUE_SEEK, ACKNOWLEDGED, UNAVAILABLE }
    data class Decision(val kind: Kind, val target: Long)
    private enum class Phase { READY_WAIT, SEEK_WAIT, ACKNOWLEDGED, UNAVAILABLE }
    private var phase = if (requestedPosition > 0L) Phase.READY_WAIT else Phase.ACKNOWLEDGED
    private var target = requestedPosition.coerceAtLeast(0L)
    private var lastSampleAt: Long? = null
    private var lastSampleActive = false
    private var activeElapsedMillis = 0L
    private var commandCompleted = false
    private var commandSucceeded = false
    private var commandClockSequence = 0L
    private var candidateClockSequence = 0L
    private var candidatePosition: Long? = null

    fun commandCompleted(now: Long, result: Long?, clockSequence: Long) {
        if (phase != Phase.SEEK_WAIT) return
        commandCompleted = true
        commandSucceeded = result != null && result >= 0L
        commandClockSequence = clockSequence
        candidateClockSequence = clockSequence
        candidatePosition = null
        activeElapsedMillis = 0L
        lastSampleAt = now
    }

    fun sample(now: Long, started: Boolean, playbackRequested: Boolean, seekable: Boolean,
        duration: Long, actual: Long, clockSequence: Long): Decision {
        if (lastSampleActive) activeElapsedMillis += lastSampleAt?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        lastSampleAt = now
        lastSampleActive = started && playbackRequested
        when (phase) {
            Phase.ACKNOWLEDGED -> return Decision(Kind.ACKNOWLEDGED, target)
            Phase.UNAVAILABLE -> return Decision(Kind.UNAVAILABLE, target)
            Phase.READY_WAIT -> {
                // Playing/length can precede HLS demuxer seekability. Paused,
                // already-started media may be positioned without resuming it.
                if (started && seekable && duration > 0L) {
                    target = target.coerceAtMost((duration - 1_000L).coerceAtLeast(0L))
                    if (target == 0L) {
                        phase = Phase.ACKNOWLEDGED
                        return Decision(Kind.ACKNOWLEDGED, target)
                    }
                    phase = Phase.SEEK_WAIT
                    activeElapsedMillis = 0L
                    return Decision(Kind.ISSUE_SEEK, target)
                }
                if (activeElapsedMillis >= readinessTimeoutMillis) phase = Phase.UNAVAILABLE
            }
            Phase.SEEK_WAIT -> {
                // getTime() immediately echoes setTime's requested value, before
                // the demuxer/decoder clock has actually moved. Require fresh
                // TimeChanged events and a second consistent sample; otherwise
                // that optimistic value can acknowledge before an older keyframe
                // timestamp arrives and makes the UI jump backwards.
                if (commandCompleted && commandSucceeded && clockSequence > commandClockSequence &&
                    clockSequence > candidateClockSequence) {
                    candidateClockSequence = clockSequence
                    if (actual >= target - toleranceMillis && actual <= target + activeElapsedMillis + toleranceMillis) {
                        val previous = candidatePosition
                        if (previous != null && (if (playbackRequested) actual >= previous + 100L
                                else kotlin.math.abs(actual - previous) <= toleranceMillis)) {
                            phase = Phase.ACKNOWLEDGED
                            return Decision(Kind.ACKNOWLEDGED, target)
                        }
                        // Keep a valid sample over small native clock rounding.
                        if (previous == null || actual < previous - toleranceMillis) candidatePosition = actual
                    } else candidatePosition = null
                }
                if ((commandCompleted && !commandSucceeded) ||
                    activeElapsedMillis >= (if (commandCompleted) acknowledgementTimeoutMillis else readinessTimeoutMillis))
                    phase = Phase.UNAVAILABLE
            }
        }
        return Decision(if (phase == Phase.UNAVAILABLE) Kind.UNAVAILABLE else Kind.PENDING, target)
    }
}
