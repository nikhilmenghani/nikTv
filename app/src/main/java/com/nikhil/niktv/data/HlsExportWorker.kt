package com.nikhil.niktv.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.effect.Contrast
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
            val completedBytes = temporaryFile.length().coerceAtLeast(0L)
            setProgress(progressData(99, completedBytes, completedBytes))
            val publicUri = publishVideo(temporaryFile, fileName)
            applicationContext.getSharedPreferences(OUTPUT_PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(id.toString(), publicUri).apply()
            Result.success(
                Data.Builder().putAll(progressData(100, completedBytes, completedBytes))
                    .putString(KEY_OUTPUT_URI, publicUri)
                    .build()
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to export HLS stream $sourceUrl", error)
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
                EditedMediaItem.Builder(MediaItem.fromUri(sourceUrl))
                    // Directly transmuxing this provider's MPEG-TS H.264 samples into MP4
                    // produces malformed NAL/parameter-set data (green macroblocks). A
                    // effectively neutral GPU effect deliberately selects Transformer's
                    // decode/encode path, yielding a standards-compliant MP4.
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(
                                // A mathematically exact no-op is optimized back into the
                                // broken transmux path. This imperceptible adjustment keeps
                                // the clean decode/encode path enabled.
                                Contrast(0.0001f)
                            )
                        )
                    )
                    .build(),
                output.absolutePath
            )
            val holder = ProgressHolder()
            CoroutineScope(continuation.context).launch {
                while (continuation.isActive) {
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        // 100% from Transformer means transcoding/remuxing is done, but the
                        // MP4 still has to be committed to MediaStore. Reserve 100% for the
                        // point at which the public file is actually openable.
                        val visibleProgress = holder.progress.coerceIn(0, 99)
                        setProgress(progressData(visibleProgress, output.length().coerceAtLeast(0L)))
                        setForeground(createForegroundInfo(title, visibleProgress))
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
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NikTV")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = applicationContext.contentResolver
            // Android only permits DCIM/Movies/Pictures in the Video collection.
            // Files requested under Download must be inserted through Downloads.
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
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
                "NikTV"
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
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val OUTPUT_PREFERENCES = "hls_export_outputs"
        private const val TAG = "HlsExportWorker"
        private const val CHANNEL_ID = "niktv-hls-exports"
        private const val NOTIFICATION_ID = 2110
    }

    private fun progressData(progress: Int, bytes: Long, totalBytes: Long = -1L): Data =
        Data.Builder()
            .putInt(KEY_PROGRESS, progress.coerceIn(0, 100))
            .putLong(KEY_BYTES, bytes.coerceAtLeast(0L))
            .putLong(KEY_TOTAL_BYTES, totalBytes)
            .build()
}
