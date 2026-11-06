package com.nikhil.niktv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.video.VideoFrameDecoder
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import com.nikhil.niktv.data.RemoteCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NikTvApplication : Application(), SingletonImageLoader.Factory {
    internal val setupReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                com.nikhil.niktv.data.ProfileStore(this@NikTvApplication).recoverPendingRestore()
                RemoteCredentials.initialize(this@NikTvApplication)
                RemoteCredentials.schedule(this@NikTvApplication)
                setupReady.complete(Unit)
                runCatching { RemoteCredentials.refresh(this@NikTvApplication) }
            } catch (failure: Throwable) {
                setupReady.completeExceptionally(failure)
                android.util.Log.e("NikTvRestore", "Setup recovery failed (${failure.javaClass.simpleName}); profile access remains blocked")
            }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
        .diskCache {
            DiskCache.Builder()
                // Reuse the existing artwork cache instead of forcing a cold cache after upgrading.
                .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .build()
}
