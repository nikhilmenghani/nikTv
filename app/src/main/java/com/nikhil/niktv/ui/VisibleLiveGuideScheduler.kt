package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.currentLiveProgramme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-dispatcher queue: finish a visible lookup, then use the latest focus/row order. */
internal class VisibleLiveGuideScheduler<Key>(
    private val scope: CoroutineScope,
    private val isDue: (Key) -> Boolean,
    private val load: suspend (Key) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val spacingMillis: Long = 650L
) {
    private var visible = emptyList<Key>()
    private var active: Key? = null
    private var worker: Job? = null

    fun pause() = update(visible, paused = true)

    fun update(orderedVisible: List<Key>, paused: Boolean = false) {
        visible = orderedVisible.distinct()
        if (paused || visible.isEmpty() || active?.let { it !in visible } == true) {
            worker?.cancel()
            worker = null
            active = null
        }
        if (paused || visible.isEmpty() || worker?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val next = visible.firstOrNull(isDue) ?: break
                    active = next
                    try {
                        load(next)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onFailure(error)
                    }
                    delay(spacingMillis)
                    active = null
                }
            } finally {
                if (worker === currentCoroutineContext()[Job]) {
                    worker = null
                    active = null
                }
            }
        }
        worker = job
        job.start()
    }
}

/** Input is in visual row order; duplicate pinned tiles need only one guide request. */
internal fun <Key> prioritizeVisibleLiveGuides(visible: List<Key>, focused: Key?, selected: Key? = null): List<Key> =
    visible.distinct().let { ordered ->
        val priority = focused?.takeIf { it in ordered } ?: selected?.takeIf { it in ordered }
        if (priority != null) listOf(priority) + ordered.filterNot { it == priority }
        else ordered
    }

/** Earliest point at which a currently displayed timed programme becomes stale. */
internal fun nextVisibleProgrammeExpiry(items: List<MediaItem>, now: Long): Long? =
    items.asSequence()
        .mapNotNull { it.currentLiveProgramme(now)?.endTimeMillis }
        .filter { it > now }
        .minOrNull()
