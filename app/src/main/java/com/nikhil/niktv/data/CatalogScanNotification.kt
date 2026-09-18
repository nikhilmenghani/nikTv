package com.nikhil.niktv.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.nikhil.niktv.MainActivity

internal object CatalogScanNotification {
    private const val CHANNEL = "catalog_scans"
    private const val NOTIFICATION = 7319
    const val PAUSE = "com.nikhil.niktv.PAUSE_CATALOG_SCAN"
    const val PROFILE = "profile_id"

    fun foreground(context: Context, profileId: String, message: String): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Catalog scans", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 7319,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getBroadcast(context, 7319,
            Intent(context, CatalogScanActionReceiver::class.java).setAction(PAUSE).putExtra(PROFILE, profileId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NikTV · Scanning catalog")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION, notification)
    }
}

class CatalogScanActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CatalogScanNotification.PAUSE) return
        val id = intent.getStringExtra(CatalogScanNotification.PROFILE) ?: return
        if (id.isBlank() || id.length > 128) return
        CatalogOperations.control(context, CatalogOperations.scan(id), "Paused")
    }
}
