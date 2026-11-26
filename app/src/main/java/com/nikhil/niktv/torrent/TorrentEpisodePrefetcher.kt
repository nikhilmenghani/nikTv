package com.nikhil.niktv.torrent

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class TorrentPrefetchGate { CANCEL, PAUSE, RUN }

/** Hysteresis prevents a background transfer from repeatedly starting/stopping at one boundary. */
internal fun torrentPrefetchGate(current: TorrentStreamStatus?, paused: Boolean, foreground: Boolean = false): TorrentPrefetchGate {
    if (foreground) return TorrentPrefetchGate.RUN
    if (current == null || current.error != null || current.totalBytes <= 0 ||
        current.stage.equals("Idle", true) || current.stage.equals("Closed", true) ||
        current.stage.equals("Stopped", true)) return TorrentPrefetchGate.CANCEL
    if (current.downloadedBytes >= current.totalBytes) return TorrentPrefetchGate.RUN
    val threshold = (if (paused) 6L else 3L) * 1024 * 1024
    return if (current.bufferedBytes >= threshold) TorrentPrefetchGate.RUN else TorrentPrefetchGate.PAUSE
}

internal fun torrentPrefetchCandidates(snapshot: TorrentSearchSnapshot, preferredQuality: String?,
    preferSameQuality: Boolean): List<TorrentSource> = if (!snapshot.finished) emptyList() else
    mergeTorrentSources(snapshot.results).sortedWith(
        compareByDescending<TorrentSource> { preferSameQuality && preferredQuality != null && it.quality.equals(preferredQuality, true) }
            .thenByDescending(TorrentSource::seeders).thenBy { it.sizeBytes }).take(3)

/** Owns the background lease until the playing ViewModel adopts or closes it. */
internal class PreparedTorrentEpisode internal constructor(
    val target: TorrentEpisodeTarget, val source: TorrentSource,
    val file: TorrentVideoFile, val url: String, val engine: TorrentStreamEngine,
    private val gateScope: CoroutineScope, private val adopted: AtomicBoolean
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    suspend fun adopt() {
        check(!closed.get()) { "This prepared episode is no longer available." }
        adopted.set(true)
        gateScope.coroutineContext[Job]?.cancelAndJoin()
        try {
            check(!closed.get()) { "This prepared episode is no longer available." }
            engine.setDownloadLimit(0)
            engine.setBackgroundPaused(false)
        } catch (error: Throwable) { close(); throw error }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        adopted.set(true)
        gateScope.cancel()
        engine.close()
    }
}

private class CurrentTorrentUnavailable : CancellationException("The current episode is no longer available for background preparation")

/** A next-episode transfer never owns or pauses the engine that is already playing. */
internal suspend fun prepareTorrentEpisode(
    context: Context, repository: TorrentSearchRepository, target: TorrentEpisodeTarget,
    currentStatus: StateFlow<TorrentStreamStatus>?, background: Boolean,
    preferredQuality: String?, onStatus: (String) -> Unit,
    foreground: StateFlow<Boolean>? = null, streamCacheOwner: String? = null
): PreparedTorrentEpisode {
    onStatus("Finding sources for the next episode…")
    val snapshot = repository.searchSources(target.card, target.episode)
    val playback: Flow<TorrentStreamStatus?> = currentStatus ?: flowOf(null)
    val priority: Flow<Boolean> = foreground ?: flowOf(false)
    if (background) {
        onStatus("Waiting for the current episode to buffer…")
        combine(playback, priority) { status, important -> torrentPrefetchGate(status, paused = true, foreground = important) }.first {
            when (it) {
                TorrentPrefetchGate.CANCEL -> throw CurrentTorrentUnavailable()
                TorrentPrefetchGate.RUN -> true
                TorrentPrefetchGate.PAUSE -> false
            }
        }
    }
    val candidates = torrentPrefetchCandidates(snapshot, preferredQuality,
        preferSameQuality = background && foreground?.value != true && torrentPrefetchGate(currentStatus?.value, true) == TorrentPrefetchGate.RUN)
    if (candidates.isEmpty()) throw IOException("No seeded source was found for the next episode.")
    var lastFailure: Throwable? = null
    for (source in candidates) {
        currentCoroutineContext().ensureActive()
        if (background && torrentPrefetchGate(currentStatus?.value, true, foreground?.value == true) == TorrentPrefetchGate.CANCEL)
            throw CurrentTorrentUnavailable()
        val engine = TorrentStreamEngine(context.applicationContext,
            TorrentEngineOptions(downloadLimitBytesPerSecond = if (background && foreground?.value != true) 256 * 1024 else 0,
                streamCacheOwner = streamCacheOwner))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val adopted = AtomicBoolean(false)
        var handedOff = false
        try {
            var paused = background && torrentPrefetchGate(currentStatus?.value, true, foreground?.value == true) != TorrentPrefetchGate.RUN
            engine.setBackgroundPaused(paused)
            if (background) scope.launch {
                try {
                    var promoted = false
                    combine(playback, priority) { current, important -> current to important }.collect { (current, important) ->
                        if (important && !promoted) { engine.setDownloadLimit(0); promoted = true }
                        if (!adopted.get()) when (torrentPrefetchGate(current, paused, promoted)) {
                            TorrentPrefetchGate.CANCEL -> { engine.close(); throw CurrentTorrentUnavailable() }
                            TorrentPrefetchGate.PAUSE -> { if (!paused) { paused = true; engine.setBackgroundPaused(true) } }
                            TorrentPrefetchGate.RUN -> { if (paused) { paused = false; engine.setBackgroundPaused(false) } }
                        }
                    }
                } catch (_: CancellationException) { }
                catch (_: Exception) { if (!adopted.get()) engine.close() }
            }
            scope.launch {
                try {
                    engine.status.collect { status ->
                        if (!adopted.get()) onStatus(if (status.backgroundPaused) "Paused while the current episode buffers…"
                            else "${status.stage} · ${status.peers} peers · ${status.downloadBytesPerSecond / 1024} KiB/s")
                    }
                } catch (_: CancellationException) { }
                catch (_: Exception) { if (!adopted.get()) engine.close() }
            }
            val files = engine.prepare(source.source)
            val file = selectTorrentEpisodeFile(files, target.episode)
                ?: throw IOException("This source does not identify exactly one matching episode.")
            val url = engine.play(file.index)
            currentCoroutineContext().ensureActive()
            if (background && torrentPrefetchGate(currentStatus?.value, false, foreground?.value == true) == TorrentPrefetchGate.CANCEL)
                throw CurrentTorrentUnavailable()
            val prepared = PreparedTorrentEpisode(target, source, file, url, engine, scope, adopted)
            onStatus("Next episode is ready")
            handedOff = true
            return prepared
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (background && torrentPrefetchGate(currentStatus?.value, true, foreground?.value == true) == TorrentPrefetchGate.CANCEL)
                throw CurrentTorrentUnavailable()
            lastFailure = error
        } finally {
            if (!handedOff) { adopted.set(true); scope.cancel(); engine.close() }
        }
    }
    throw IOException(lastFailure?.message ?: "The next episode could not be prepared.", lastFailure)
}
