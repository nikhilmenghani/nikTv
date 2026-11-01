package com.nikhil.niktv.ui

internal data class WatchingConfiguration(val enabled: Boolean = true, val minutes: Int = 120, val episodes: Int = 4)

/** Counts actual playback, never buffering, seeking distance or time spent paused/backgrounded. */
internal class WatchingSession {
    private var identity: String? = null
    private var sampledAt: Long? = null
    private var episodeIdentity: String? = null
    private var automaticNext = false
    var watchedMillis = 0L; private set
    var completedEpisodes = 0; private set
    fun begin(key: String, episodeKey: String? = null) {
        val changedSeries = identity != key
        val changedEpisode = episodeKey != null && episodeIdentity != null && episodeKey != episodeIdentity
        if (changedSeries || (changedEpisode && !automaticNext)) interact()
        if (changedSeries) episodeIdentity = null
        identity = key
        if (episodeKey != null && episodeKey != episodeIdentity) { episodeIdentity = episodeKey; automaticNext = false }
    }
    fun expectNextEpisode() { automaticNext = true }
    fun interact() { watchedMillis = 0; completedEpisodes = 0; sampledAt = null; automaticNext = false }
    fun sample(now: Long, playing: Boolean) {
        if (!playing) { sampledAt = null; return }
        sampledAt?.let { watchedMillis += (now - it).coerceIn(0L, 2_000L) }
        sampledAt = now
    }
    fun completeEpisode() { completedEpisodes++ }
    fun due(config: WatchingConfiguration): Boolean = config.enabled &&
        (watchedMillis >= config.minutes * 60_000L || completedEpisodes >= config.episodes)
}
