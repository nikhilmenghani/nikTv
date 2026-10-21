package com.nikhil.niktv.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Native engine lifecycle work is ordered independently of Compose/navigation. */
internal object NativePlaybackWorker {
    val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NikTvNativePlayback").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
}

/** A hung mpv driver/teardown must not block VLC construction or its media preparation. */
internal object MpvNativePlaybackWorker {
    val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NikTvMpvPlayback").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
}
