package com.nikhil.niktv.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

enum class OfflineDownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED, MISSING }

object OfflineMediaDownloads {
    private fun manager(context: Context) =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(context: Context, mediaKey: String, title: String, url: String): Long {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "NikTV")
        check(directory.exists() || directory.mkdirs()) { "Unable to create NikTV offline storage" }
        val safeTitle = title.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_").trim().take(80).ifBlank { "video" }
        val safeKey = mediaKey.toByteArray().fold(17) { hash, byte -> 31 * hash + byte }.toUInt().toString(16)
        val sourcePath = Uri.parse(url).lastPathSegment.orEmpty().substringBefore('?')
        val extension = sourcePath.substringAfterLast('.', "").lowercase()
        require(extension != "m3u8") {
            "This provider returned an HLS playlist. Segmented offline downloads are not supported yet."
        }
        val fileExtension = extension.takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "mpg", "mpeg") }
            ?.let { ".$it" } ?: ".video"
        val destination = File(directory, "${safeTitle}_$safeKey$fileExtension")
        if (destination.exists()) destination.delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Saving for offline playback in NikTV")
            .setMimeType("video/*")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationUri(Uri.fromFile(destination))
        return manager(context).enqueue(request)
    }

    fun status(context: Context, id: Long): OfflineDownloadStatus {
        val cursor = manager(context).query(DownloadManager.Query().setFilterById(id)) ?: return OfflineDownloadStatus.MISSING
        cursor.use {
            if (!it.moveToFirst()) return OfflineDownloadStatus.MISSING
            return when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> OfflineDownloadStatus.QUEUED
                DownloadManager.STATUS_RUNNING -> OfflineDownloadStatus.DOWNLOADING
                DownloadManager.STATUS_PAUSED -> OfflineDownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> OfflineDownloadStatus.COMPLETE
                DownloadManager.STATUS_FAILED -> OfflineDownloadStatus.FAILED
                else -> OfflineDownloadStatus.MISSING
            }
        }
    }

    fun playableUri(context: Context, id: Long): String? =
        if (status(context, id) == OfflineDownloadStatus.COMPLETE) {
            manager(context).getUriForDownloadedFile(id)?.toString()
        } else null

    fun remove(context: Context, id: Long) {
        manager(context).remove(id)
    }
}
