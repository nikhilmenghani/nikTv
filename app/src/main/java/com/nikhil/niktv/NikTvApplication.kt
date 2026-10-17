package com.nikhil.niktv

import dagger.hilt.android.HiltAndroidApp
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

@HiltAndroidApp
class NikTvApplication : io.github.aedev.flow.FlowApplication() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: android.app.Activity, state: android.os.Bundle?) {
                if (!activity.javaClass.name.startsWith("com.liskovsoft.smartyoutubetv2.")) restoreHostDensity(activity)
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) {
                if (!activity.javaClass.name.startsWith("com.liskovsoft.smartyoutubetv2.")) restoreHostDensity(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        RemoteCredentials.initialize(this)
        RemoteCredentials.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { RemoteCredentials.refresh(this@NikTvApplication) }
        }
    }

    override fun newImageLoader(context: Context): ImageLoader =
        super.newImageLoader(context)

    fun restoreHostDensity(context: Context) {
        listOf(context.resources, resources).forEach { resource ->
            val configuration = resource.configuration
            resource.displayMetrics.density = configuration.densityDpi / 160f
            resource.displayMetrics.scaledDensity = resource.displayMetrics.density * configuration.fontScale
        }
    }
}
