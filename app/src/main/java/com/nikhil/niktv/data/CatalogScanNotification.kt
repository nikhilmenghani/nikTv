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
    const val OPERATION = "operation"

    fun foreground(context: Context, profileId: String, message: String): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Catalog scans", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 7319,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getBroadcast(context, 7319,
            Intent(context, CatalogScanActionReceiver::class.java).setAction(PAUSE).putExtra(PROFILE, profileId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = CatalogOperations.progress(context, CatalogOperations.scan(profileId))
        val notificationMessage = progress?.notificationSummary() ?: message
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NikTV · Scanning catalog")
            .setContentText(notificationMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationMessage))
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                val fraction = progress?.fraction
                if (fraction == null) setProgress(0, 0, true)
                else setProgress(1000, (fraction * 1000).toInt(), false)
            }
            .addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION, notification)
    }

    fun transferForeground(context: Context, operation: String, title: String): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Catalog operations", NotificationManager.IMPORTANCE_LOW))
        val id = if (operation == CatalogOperations.BACKUP) NOTIFICATION + 1 else NOTIFICATION + 2
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getBroadcast(context, id,
            Intent(context, CatalogScanActionReceiver::class.java).setAction(PAUSE).putExtra(OPERATION, operation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = CatalogOperations.progress(context, operation)
        val fraction = progress?.fraction
        val message = CatalogOperations.message(context, operation)
        val notificationMessage = progress?.notificationSummary() ?: message
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (operation == CatalogOperations.BACKUP) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle("NikTV · $title")
            .setContentText(notificationMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationMessage))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                if (fraction == null) setProgress(0, 0, true)
                else setProgress(1000, (fraction * 1000).toInt(), false)
            }
            .addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, notification)
    }

    private fun CatalogOperationProgress.notificationSummary(): String = buildList {
        if (category.isNotBlank()) add(category)
        if (mediaType.isNotBlank()) add(mediaType)
        add(phase)
        if (page > 0) add(if (totalPages > 0) "page $page/$totalPages" else "page $page")
        percentText?.let(::add)
        formatRemainingTime(estimatedRemainingMillis)?.let { add("about $it left") }
        if (totalParts > 0) add("part $part/$totalParts")
        if (totalRecords > 0) add("$totalRecords records")
    }.joinToString(" · ")
}

class CatalogScanActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CatalogScanNotification.PAUSE) return
        val operation = intent.getStringExtra(CatalogScanNotification.OPERATION)
        if (operation in listOf(CatalogOperations.BACKUP, CatalogOperations.RESTORE)) {
            CatalogOperations.control(context, requireNotNull(operation), "Paused")
            return
        }
        val id = intent.getStringExtra(CatalogScanNotification.PROFILE) ?: return
        if (id.isBlank() || id.length > 128) return
        CatalogOperations.control(context, CatalogOperations.scan(id), "Paused")
    }
}
