package com.nikhil.niktv.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep provider traffic bounded while reserving capacity for user actions. */
internal class PortalRequestGate(
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val requests = ArrayDeque<Long>()
    private var lastRequestAt: Long? = null

    suspend fun <T> run(
        background: Boolean,
        minimumSpacingMillis: Long,
        checkAllowed: () -> Unit = {},
        block: suspend () -> T
    ): T {
        while (true) {
            val wait = mutex.withLock {
                checkAllowed()
                val timestamp = now()
                while (requests.isNotEmpty() && timestamp - requests.first() >= 60_000L) {
                    requests.removeFirst()
                }
                // Keep the existing 20 requests/minute ceiling. Guides may use
                // the first 12 slots; eight remain available to browse or play.
                val limit = if (background) 12 else 20
                val budgetWait = if (requests.size >= limit) {
                    (requests.elementAt(requests.size - limit) + 60_000L - timestamp).coerceAtLeast(0L)
                } else 0L
                val spacingWait = lastRequestAt?.let {
                    (it + minimumSpacingMillis - timestamp).coerceAtLeast(0L)
                } ?: 0L
                val waitMillis = maxOf(budgetWait, spacingWait)
                if (waitMillis == 0L) {
                    requests.addLast(timestamp)
                    lastRequestAt = timestamp
                    return block()
                }
                waitMillis
            }
            // Never hold the network mutex during a budget/spacing wait: a
            // background guide must not block an eligible playback request.
            pause(wait)
        }
    }
}
