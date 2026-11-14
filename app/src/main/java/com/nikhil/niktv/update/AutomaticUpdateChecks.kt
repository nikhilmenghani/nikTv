package com.nikhil.niktv.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Called only while the app is foreground and playback is idle. */
internal suspend fun automaticUpdateChecks(
    check: suspend () -> UpdateInfo?,
    available: (UpdateInfo) -> Unit,
    attemptFinished: () -> Unit = {},
    failed: (Exception) -> Unit = {},
) {
    while (true) {
        var retry = false
        try {
            check()?.let { available(it); return }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            retry = true
            failed(error)
        } finally {
            attemptFinished()
        }
        delay(if (retry) 60_000L else 30 * 60_000L)
    }
}
