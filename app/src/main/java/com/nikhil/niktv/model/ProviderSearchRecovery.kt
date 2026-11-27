package com.nikhil.niktv.model

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep the same page pending during provider backoff; never turn a failure into an empty page. */
internal suspend fun <T> recoverProviderSearch(
    retryAfterSeconds: (Exception) -> Long?,
    transientFailure: (Exception) -> Boolean = { it is IOException },
    onWait: (Long) -> Unit = {},
    pause: suspend (Long) -> Unit = { delay(it) },
    request: suspend () -> T
): T {
    var retries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try { return request() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val providerWait = retryAfterSeconds(error)
            val seconds = if (providerWait != null) providerWait.coerceAtLeast(1L)
                else if (transientFailure(error) && retries < 2) (1L shl retries++)
                else throw error
            onWait(seconds)
            pause(seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L)
        }
    }
}

internal fun providerCooldownSeconds(retryAfter: Long?, strikes: Int): Long =
    maxOf(retryAfter?.coerceAtLeast(0L) ?: 30L, 30L * (1L shl (strikes.coerceIn(1, 5) - 1)))

internal fun providerSearchWaitLabel(seconds: Long): String {
    val remaining = seconds.coerceAtLeast(0)
    return if (remaining >= 3600) "${remaining / 3600}h ${(remaining % 3600) / 60}m ${remaining % 60}s"
        else if (remaining >= 60) "${remaining / 60}m ${remaining % 60}s" else "${remaining}s"
}
