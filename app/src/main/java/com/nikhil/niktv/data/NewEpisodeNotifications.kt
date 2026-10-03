package com.nikhil.niktv.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nikhil.niktv.MainActivity
import kotlinx.coroutines.sync.Mutex

internal object NewEpisodeNotifications {
    val refreshMutex = Mutex()
    const val INTERVAL_MILLIS = 15 * 60_000L
    private const val CHANNEL = "new_episodes"

    fun supported(context: Context): Boolean =
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_TELEVISION &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    fun post(context: Context, profileName: String, discovery: EpisodeDiscovery) {
        if (discovery.added.isEmpty() || !supported(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "New episodes of series you follow in NikTV" })
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val watched = discovery.updated
        val title = "$profileName · ${watched.series.title}"
        val message = if (discovery.added.size == 1) "New episode: ${discovery.added.first().title}"
            else "${discovery.added.size} new episodes available"
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        // Separate series and profiles even when episode IDs overlap.
        manager.notify("new-episode:${watched.key}", 1, notification)
    }
}
