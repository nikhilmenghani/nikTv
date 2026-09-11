package com.nikhil.niktv.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@UnstableApi
class HlsExportWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val sourceUrl = inputData.getString(KEY_URL).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "NikTV video" }
        val fileName = inputData.getString(KEY_FILE_NAME).orEmpty().ifBlank { "NikTV video.mp4" }
        if (sourceUrl.isBlank()) return Result.failure()
        setForeground(createForegroundInfo(title, 0))

        val exportDirectory = File(applicationContext.cacheDir, "hls_exports").apply { mkdirs() }
        val temporaryFile = File(exportDirectory, "${id}.mp4")
        temporaryFile.delete()
        return try {
            exportHls(sourceUrl, temporaryFile, title)
            val publicUri = publishVideo(temporaryFile, fileName)
            Result.success(Data.Builder().putString(KEY_OUTPUT_URI, publicUri).build())
        } catch (error: Throwable) {
            Result.failure(Data.Builder().putString(KEY_ERROR, error.message ?: "HLS export failed").build())
        } finally {
            temporaryFile.delete()
        }
    }

    private suspend fun exportHls(sourceUrl: String, output: File, title: String) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(applicationContext)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (continuation.isActive) continuation.resumeWith(kotlin.Result.failure(exportException))
                    }
                })
                .build()
            continuation.invokeOnCancellation { transformer.cancel() }
            transformer.start(
                EditedMediaItem.Builder(MediaItem.fromUri(sourceUrl)).build(),
                output.absolutePath
            )
            val holder = ProgressHolder()
            CoroutineScope(continuation.context).launch {
                while (continuation.isActive) {
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        setProgress(Data.Builder().putInt(KEY_PROGRESS, holder.progress.coerceIn(0, 100)).build())
                        setForeground(createForegroundInfo(title, holder.progress))
                    }
                    delay(750L)
                }
            }
            }
        }

    private suspend fun publishVideo(source: File, fileName: String): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NikTV Offline")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the public HLS export")
            try {
                resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("Could not write the public HLS export")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                uri.toString()
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NikTV Offline"
            ).apply { mkdirs() }
            val target = File(directory, fileName)
            source.copyTo(target, overwrite = true)
            android.media.MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(target.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            android.net.Uri.fromFile(target).toString()
        }
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HLS offline exports", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving $title")
            .setContentText("Creating a playable MP4 · ${progress.coerceIn(0, 100)}%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "niktv-hls-exports"
        private const val NOTIFICATION_ID = 2110
    }
}
