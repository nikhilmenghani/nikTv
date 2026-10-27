package com.nikhil.niktv.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class SportsPlaybackSession(
    val active: Boolean = false,
    val slug: String = "",
    val sources: List<SportsStream> = emptyList(),
    /** Pending cursor, independent of the committed playback occurrence. */
    val selectedIndex: Int? = null,
    val current: SportsStream? = null,
    val currentIndex: Int? = null,
    val resolving: Boolean = false,
    /** Changes only when a replacement commits, or this event closes. */
    val attemptId: Long = 0,
    val attempts: Int = 0,
    val error: String? = null,
)

/**
 * Metadata/URL coordination only: never owns a decoder or a playback watchdog.
 * Navigation retains the active source until its replacement resolves successfully.
 * Only an explicit terminal player failure permits automatic movement to another source.
 */
internal class SportsPlaybackController(
    private val scope: CoroutineScope,
    private val loadSources: suspend (String) -> List<SportsStream>,
    private val resolve: suspend (SportsStream) -> SportsStream,
    private val maxAttempts: Int = 4,
) {
    private data class Occurrence(val source: SportsStream, val index: Int, val duplicate: Int, val logicalDuplicate: Int)
    private data class AttemptKey(val source: SportsStream, val duplicate: Int)
    private val mutable = MutableStateFlow(SportsPlaybackSession())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var sequence = 0L
    private var refreshed = false
    private var fallbackAllowed = false
    private var pendingFreshRetry = false
    private var committed: Occurrence? = null
    private var preferred: Occurrence? = null
    private var handledFailure: Long? = null
    private val attempted = mutableSetOf<AttemptKey>()
    private val queue = ArrayDeque<Occurrence>()

    init { require(maxAttempts > 0) }

    fun start(slug: String, sources: List<SportsStream>, selected: Int = 0) {
        if (selected !in sources.indices) return
        val previous = mutable.value.takeIf { it.slug == slug }
        cancelWork()
        resetCycle()
        if (previous == null) committed = null
        val target = occurrence(sources, selected)
        preferred = target
        queue.add(target)
        mutable.value = SportsPlaybackSession(active = true, slug = slug, sources = sources,
            selectedIndex = selected, current = previous?.current, currentIndex = previous?.currentIndex,
            resolving = true, attemptId = previous?.attemptId ?: ++sequence)
        launchResolution(generation, refreshFirst = false)
    }

    /** Capture the committed attempt ID in the callback; never read a newer one from stale work. */
    fun failed(attemptId: Long) {
        val session = mutable.value
        if (!session.active || session.resolving || session.current == null ||
            session.attemptId != attemptId || handledFailure == attemptId) return
        handledFailure = attemptId
        cancelWork()
        fallbackAllowed = true
        committed?.let { attempted += attemptKey(it) }
        fillQueue(session.sources, ((session.currentIndex ?: -1) + 1).coerceAtLeast(0))
        mutable.update { it.copy(resolving = true, error = null) }
        launchResolution(generation, refreshFirst = false)
    }

    /** Explicit Retry fetches fresh metadata and retains the same source occurrence and picture. */
    fun retry() {
        val session = mutable.value
        if (!session.active) return
        val selected = preferred ?: committed ?: return
        cancelWork()
        resetCycle()
        pendingFreshRetry = true
        preferred = selected
        mutable.update { it.copy(resolving = true, attempts = 0, error = null) }
        launchResolution(generation, refreshFirst = true)
    }

    fun dismissError() { mutable.update { it.copy(error = null) } }

    /** Cancel pending retries on pause/background; direct navigation remains a separate user request. */
    fun cancelPendingFallback() {
        if ((!fallbackAllowed && !pendingFreshRetry) || !mutable.value.resolving) return
        cancelWork()
        fallbackAllowed = false
        pendingFreshRetry = false
        queue.clear()
        mutable.update { it.copy(selectedIndex = it.currentIndex, resolving = false, error = null) }
    }

    fun stop() {
        cancelWork()
        resetCycle()
        committed = null
        preferred = null
        mutable.value = SportsPlaybackSession(attemptId = ++sequence)
    }

    private fun cancelWork() { generation++; job?.cancel(); job = null }

    private fun resetCycle() {
        refreshed = false
        fallbackAllowed = false
        pendingFreshRetry = false
        handledFailure = null
        attempted.clear()
        queue.clear()
    }

    private fun signature(source: SportsStream) = source.copy(title = "")
    // Feeds lack source IDs: same-title occurrences remain distinct when signed URLs rotate.
    private fun logical(source: SportsStream) = source.copy(link = "", api = "", headers = emptyMap(), drmLicense = "")

    private fun occurrence(sources: List<SportsStream>, index: Int): Occurrence {
        val source = sources[index]
        return Occurrence(source, index,
            sources.take(index).count { signature(it) == signature(source) },
            sources.take(index).count { logical(it) == logical(source) })
    }

    private fun attemptKey(value: Occurrence) = AttemptKey(signature(value.source), value.duplicate)

    private fun locate(sources: List<SportsStream>, old: Occurrence): Int? {
        val exact = sources.indices.filter { signature(sources[it]) == signature(old.source) }
        return exact.getOrNull(old.duplicate) ?: sources.indices.filter { logical(sources[it]) == logical(old.source) }
            .getOrNull(old.logicalDuplicate)
    }

    private fun fillQueue(sources: List<SportsStream>, first: Int) {
        queue.clear()
        if (sources.isEmpty()) return
        val start = first.coerceIn(0, sources.lastIndex)
        repeat(sources.size) { offset ->
            val item = occurrence(sources, (start + offset) % sources.size)
            if (attemptKey(item) !in attempted) queue.add(item)
        }
    }

    private suspend fun refreshSources(owner: Long): Boolean {
        refreshed = true
        val fresh = try { withTimeout(30_000) { loadSources(mutable.value.slug) } }
        catch (_: TimeoutCancellationException) { if (owner == generation) exhausted(); return false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (owner == generation) exhausted(); return false }
        if (owner != generation) return false
        val target = preferred?.let { locate(fresh, it) }
        val activeIndex = committed?.let { locate(fresh, it) }
        mutable.update { it.copy(sources = fresh, selectedIndex = target, currentIndex = activeIndex) }
        if (fallbackAllowed) fillQueue(fresh, target ?: 0)
        else {
            queue.clear()
            target?.let { occurrence(fresh, it) }?.takeIf { attemptKey(it) !in attempted }?.let(queue::add)
        }
        return true
    }

    private fun launchResolution(owner: Long, refreshFirst: Boolean) {
        job = scope.launch {
            if (refreshFirst && !refreshSources(owner)) return@launch
            while (owner == generation && mutable.value.active) {
                if (mutable.value.attempts >= maxAttempts) { exhausted(); return@launch }
                if (queue.isEmpty()) {
                    if (refreshed) { exhausted(); return@launch }
                    if (!refreshSources(owner)) return@launch
                    if (queue.isEmpty()) { exhausted(); return@launch }
                }
                val candidate = queue.removeFirst()
                if (!attempted.add(attemptKey(candidate))) continue
                mutable.update { it.copy(selectedIndex = candidate.index, resolving = true,
                    attempts = it.attempts + 1, error = null) }
                try {
                    val resolved = withTimeout(30_000) { resolve(candidate.source) }
                    if (owner != generation) return@launch
                    committed = candidate
                    preferred = candidate
                    pendingFreshRetry = false
                    handledFailure = null
                    mutable.update { it.copy(current = resolved, currentIndex = candidate.index,
                        selectedIndex = candidate.index, resolving = false, attemptId = ++sequence, error = null) }
                    return@launch
                } catch (_: TimeoutCancellationException) { if (owner != generation) return@launch }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (owner != generation) return@launch }
            }
        }
    }

    private fun exhausted() {
        mutable.update { it.copy(resolving = false,
            error = if (fallbackAllowed) "The available sources could not play. Retry to fetch fresh links, or choose another source."
                else "This source could not be resolved. Retry to fetch a fresh link, or choose another source.") }
    }
}
