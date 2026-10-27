package com.nikhil.niktv.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SportsPlaybackSession(
    val active: Boolean = false,
    val slug: String = "",
    val sources: List<SportsStream> = emptyList(),
    val selectedIndex: Int? = null,
    val current: SportsStream? = null,
    val resolving: Boolean = false,
    val attemptId: Long = 0,
    val attempts: Int = 0,
    val error: String? = null,
)

internal class SportsPlaybackController(
    private val scope: CoroutineScope,
    private val loadSources: suspend (String) -> List<SportsStream>,
    private val resolve: suspend (SportsStream) -> SportsStream,
) {
    private val mutable = MutableStateFlow(SportsPlaybackSession())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var sequence = 0L
    private var preferred = 0
    private var refreshed = false
    private var original: SportsStream? = null
    private val failed = mutableSetOf<SportsStream>()
    private val queue = ArrayDeque<Int>()

    fun start(slug: String, sources: List<SportsStream>, selected: Int = 0) {
        job?.cancel()
        generation++
        preferred = if (sources.isEmpty()) selected.coerceAtLeast(0) else selected.coerceIn(0, sources.lastIndex)
        refreshed = false
        original = null
        failed.clear()
        fillQueue(sources, preferred)
        mutable.value = SportsPlaybackSession(active = true, slug = slug, sources = sources, resolving = true, attemptId = ++sequence)
        next()
    }

    fun failed(attemptId: Long) {
        val session = mutable.value
        if (!session.active || session.resolving || session.current == null || session.attemptId != attemptId) return
        original?.let { failed += identity(it) }
        mutable.update { it.copy(current = null, resolving = true, error = null, attemptId = ++sequence) }
        next()
    }

    fun retry() {
        val session = mutable.value
        if (!session.active) return
        start(session.slug, emptyList(), preferred)
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        queue.clear()
        original = null
        mutable.value = SportsPlaybackSession(attemptId = ++sequence)
    }

    private fun identity(stream: SportsStream) = stream.copy(title = "")

    private fun fillQueue(sources: List<SportsStream>, first: Int) {
        queue.clear()
        if (sources.isEmpty()) return
        val start = first.coerceIn(0, sources.lastIndex)
        val seen = mutableSetOf<SportsStream>()
        repeat(sources.size) { offset ->
            val index = (start + offset) % sources.size
            val key = identity(sources[index])
            if (key !in failed && seen.add(key)) queue.add(index)
        }
    }

    private fun next() {
        job?.cancel()
        val owner = generation
        job = scope.launch {
            while (owner == generation && mutable.value.active) {
                if (queue.isEmpty()) {
                    if (refreshed) { exhausted(); return@launch }
                    // A second metadata fetch can rotate expired signed URLs. Do not retry
                    // unchanged failed sources or loop indefinitely when the provider is down.
                    refreshed = true
                    val fresh = try { loadSources(mutable.value.slug) }
                    catch (_: TimeoutCancellationException) { exhausted(); return@launch }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { exhausted(); return@launch }
                    if (owner != generation) return@launch
                    mutable.update { it.copy(sources = fresh) }
                    fillQueue(fresh, preferred)
                    if (queue.isEmpty()) { exhausted(); return@launch }
                }
                val index = queue.removeFirst()
                val source = mutable.value.sources.getOrNull(index) ?: continue
                original = source
                val attempt = ++sequence
                mutable.update { it.copy(selectedIndex = index, current = null, resolving = true, attemptId = attempt, attempts = it.attempts + 1, error = null) }
                try {
                    val resolved = resolve(source)
                    if (owner != generation) return@launch
                    mutable.update { it.copy(current = resolved, resolving = false) }
                    return@launch
                } catch (_: TimeoutCancellationException) { failed += identity(source) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed += identity(source) }
            }
        }
    }

    private fun exhausted() {
        mutable.update { it.copy(current = null, resolving = false, error = "None of this event's sources could play right now. Retry to fetch fresh links, or choose another event.") }
    }
}
