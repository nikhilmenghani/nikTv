package com.nikhil.niktv.ui

internal const val MAX_DECODER_RECOVERIES = 3
internal fun allowDecoderRecovery(attempts: Int): Boolean = attempts < MAX_DECODER_RECOVERIES

/** Only a completion event can advance; timeline estimates while paused/seeking cannot. */
internal class EpisodeCompletionGate {
    private var completed = false
    fun claim(ended: Boolean, requested: Boolean, hasNext: Boolean, cancelled: Boolean): Boolean {
        if (completed || !ended || !requested || !hasNext || cancelled) return false
        completed = true
        return true
    }
}

/** Count only eligible foreground/startup time, not user pauses or focus loss. */
internal class PlaybackStartupDeadline(private val limitMillis: Long = 25_000L) {
    private var sampledAt: Long? = null
    private var elapsed = 0L
    fun expired(now: Long, eligible: Boolean, ready: Boolean): Boolean {
        if (ready) { sampledAt = null; elapsed = 0L; return false }
        if (!eligible) { sampledAt = null; return false }
        sampledAt?.let { elapsed += (now - it).coerceAtLeast(0L) }
        sampledAt = now
        return elapsed >= limitMillis
    }
}

/** Suppression never overwrites explicit user intent. */
internal class PlaybackAudioIntent {
    var requested = true
        private set
    var suppressed = false
        private set
    var ducked = false
        private set
    val shouldPlay get() = requested && !suppressed
    fun userRequest(play: Boolean) { requested = play }
    fun transientLoss(duck: Boolean = false) { ducked = duck; suppressed = !duck }
    fun gain() { suppressed = false; ducked = false }
    fun permanentLoss() { requested = false; suppressed = false; ducked = false }
    fun noisy() = permanentLoss()
}

/** Failed candidates are bounded and isolated to one committed playback session. */
internal class DecoderSessionFailures(private val maxSessions: Int = 8) {
    private val bySession = linkedMapOf<String, MutableSet<String>>()
    @Synchronized fun record(session: String, decoder: String) {
        bySession.getOrPut(session) { mutableSetOf() }.add(decoder.lowercase())
        while (bySession.size > maxSessions) bySession.remove(bySession.keys.first())
    }
    @Synchronized fun failed(session: String): Set<String> = bySession[session]?.toSet().orEmpty()
}

internal fun adjacentQueueIndex(index: Int, size: Int, delta: Int, wrap: Boolean): Int? {
    if (size <= 1 || index !in 0 until size) return null
    val target = index + delta
    return if (target in 0 until size) target else if (wrap) Math.floorMod(target, size) else null
}

internal fun samePlaybackAttempt(current: com.nikhil.niktv.model.PlayingMedia, expected: com.nikhil.niktv.model.PlayingMedia): Boolean =
    current.media.id == expected.media.id && current.url == expected.url && current.playbackAttemptId == expected.playbackAttemptId

/** Keep pages/metadata that arrived while a selection was resolving in the same context. */
internal fun reconcilePlaybackQueueSnapshot(
    supplied: List<com.nikhil.niktv.model.MediaItem>,
    current: List<com.nikhil.niktv.model.MediaItem>,
    selectedId: String
): List<com.nikhil.niktv.model.MediaItem> {
    if (supplied.isEmpty() || current.none { it.id == selectedId }) return supplied
    val currentIds = current.mapTo(mutableSetOf()) { it.id }
    return if (supplied.all { it.id in currentIds }) current else supplied
}
