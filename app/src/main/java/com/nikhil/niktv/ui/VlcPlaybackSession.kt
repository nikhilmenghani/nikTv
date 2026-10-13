package com.nikhil.niktv.ui

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.atomic.AtomicBoolean

/** Reuse LibVLC modules, but give every stream its own player and decoder state. */
internal class VlcPlaybackSession private constructor(
    val lib: LibVLC,
    val player: MediaPlayer,
    private val options: List<String>
) {
    private val closing = AtomicBoolean()
    private val closed = CompletableDeferred<Pair<Long, Long>>()

    fun command(action: (MediaPlayer) -> Unit) {
        if (closing.get()) return
        NativePlaybackWorker.scope.launch {
            if (!closing.get()) runCatching { action(player) }
        }
    }

    suspend fun close(): Pair<Long, Long> {
        if (closing.compareAndSet(false, true)) {
            // This job survives composition cancellation and owns shutdown once.
            NativePlaybackWorker.scope.launch {
                val started = SystemClock.elapsedRealtime()
                var timing = 0L to 0L
                try {
                    withContext(Dispatchers.Main.immediate) { player.setEventListener(null) }
                    timing = runCatching { player.time.coerceAtLeast(0L) to player.length.coerceAtLeast(0L) }
                        .getOrDefault(timing)
                    runCatching { player.stop() }
                    withContext(Dispatchers.Main.immediate) { runCatching { player.detachViews() } }
                    runCatching { player.release() }
                } finally {
                    runCatching { pool.release(options, lib) }
                    closed.complete(timing)
                    android.util.Log.i("NikTvPlayback", "VLC cleanup completed in ${SystemClock.elapsedRealtime() - started}ms on native worker")
                    launch { delay(45_000L); pool.trimIdle() }
                }
            }
        }
        return withContext(NonCancellable) { closed.await() }
    }

    companion object {
        private var appContext: Context? = null
        private val pool = ReusablePlaybackEnginePool<List<String>, LibVLC>(
            create = { LibVLC(requireNotNull(appContext), ArrayList(it)) },
            destroy = { engine -> NativePlaybackWorker.scope.launch { runCatching { engine.release() } } },
            clock = SystemClock::elapsedRealtime
        )

        fun create(context: Context, options: List<String>): VlcPlaybackSession {
            appContext = context.applicationContext
            val lib = pool.acquire(options)
            try {
                android.util.Log.i("NikTvPlayback", "VLC engine id=${System.identityHashCode(lib)}; fresh stream player")
                return VlcPlaybackSession(lib, MediaPlayer(lib), options)
            } catch (failure: Throwable) {
                pool.release(options, lib)
                throw failure
            }
        }
    }
}
