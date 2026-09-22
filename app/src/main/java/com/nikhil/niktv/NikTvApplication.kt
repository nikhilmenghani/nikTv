package com.nikhil.niktv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import com.nikhil.niktv.data.RemoteCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NikTvApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        RemoteCredentials.initialize(this)
        RemoteCredentials.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { RemoteCredentials.refresh(this@NikTvApplication) }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // Leave headroom for large Xtream JSON responses. Posters
                    // remain in the 256 MB disk cache and are decoded on demand.
                    .maxSizePercent(context, 0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .build()
}
