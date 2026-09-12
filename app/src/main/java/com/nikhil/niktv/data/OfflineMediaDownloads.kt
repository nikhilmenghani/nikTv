package com.nikhil.niktv.data

import android.app.Notification
import android.app.DownloadManager as SystemDownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class OfflineDownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED, MISSING }
data class OfflineDownloadInfo(
    val status: OfflineDownloadStatus,
    val percent: Float? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null
)
data class OfflineDownloadHandle(
    val requestId: String = "",
    val downloadId: Long = -1L,
    val fileType: String = ""
)

@UnstableApi
object OfflineMediaDownloads {
    const val CHANNEL_ID = "niktv_offline_downloads"
    private var databaseInstance: StandaloneDatabaseProvider? = null
    private var cacheInstance: SimpleCache? = null
    private var managerInstance: DownloadManager? = null

    @Synchronized
    private fun database(context: Context): StandaloneDatabaseProvider =
        databaseInstance ?: StandaloneDatabaseProvider(context.applicationContext).also {
            databaseInstance = it
        }

    @Synchronized
    fun cache(context: Context): SimpleCache = cacheInstance ?: SimpleCache(
        File(context.applicationContext.filesDir, "offline_media_cache"),
        NoOpCacheEvictor(),
        database(context)
    ).also { cacheInstance = it }

    // OFFLINE_OWNERSHIP_STORAGE_V47: this cache directory is app-private and dedicated to NikTV
    // offline media. Public Downloads/NikTV files are never enumerated or swept.
    fun cachedMediaBytes(context: Context): Long =
        runCatching { cache(context).cacheSpace.coerceAtLeast(0L) }.getOrDefault(0L)

    suspend fun clearCachedMedia(context: Context): Long = withContext(Dispatchers.IO) {
        val mediaCache = cache(context)
        val before = mediaCache.cacheSpace.coerceAtLeast(0L)
        mediaCache.keys.toList().forEach { key ->
            runCatching { mediaCache.removeResource(key) }
        }
        (before - mediaCache.cacheSpace.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(
            DefaultDataSource.Factory(
                context.applicationContext,
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setUserAgent("NikTV/0.1 Android")
            )
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Synchronized
    fun manager(context: Context): DownloadManager = managerInstance ?: DownloadManager(
        context.applicationContext,
        database(context),
        cache(context),
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("NikTV/0.1 Android"),
        Executors.newFixedThreadPool(3)
    ).also {
        it.maxParallelDownloads = 2
        managerInstance = it
    }

    fun requestId(mediaKey: String): String = "niktv:${mediaKey.hashCode().toUInt().toString(16)}:$mediaKey"

    fun enqueue(context: Context, mediaKey: String, title: String, url: String): OfflineDownloadHandle {
        val id = requestId(mediaKey)
        val uri = Uri.parse(url)
        val hls = isSegmentedOfflineStream(url)
        val safeTitle = title.replace(Regex("[\\/:*?\"<>|]+"), "_").trim().take(80).ifBlank { "NikTV video" }
        val uniqueSuffix = mediaKey.hashCode().toUInt().toString(16)
        if (hls) {
            val work = OneTimeWorkRequestBuilder<HlsExportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(
                    HlsExportWorker.KEY_URL to url,
                    HlsExportWorker.KEY_TITLE to title,
                    HlsExportWorker.KEY_FILE_NAME to "$safeTitle-$uniqueSuffix.mp4"
                ))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(work)
            return OfflineDownloadHandle(
                requestId = "$HLS_WORK_PREFIX${work.id}",
                fileType = "HLS → MP4"
            )
        }
        val extension = uri.lastPathSegment.orEmpty().substringBefore('?')
            .substringAfterLast('.', "mp4").lowercase()
            .takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v") } ?: "mp4"
        val fileName = "$safeTitle-$uniqueSuffix.$extension"
        val request = SystemDownloadManager.Request(uri)
            .setTitle(title)
            .setMimeType(MimeTypes.VIDEO_UNKNOWN)
            .setNotificationVisibility(SystemDownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NikTV/$fileName")
        val downloadId = context.applicationContext
            .getSystemService(SystemDownloadManager::class.java).enqueue(request)
        return OfflineDownloadHandle(downloadId = downloadId, fileType = extension.uppercase())
    }

    fun status(context: Context, requestId: String): OfflineDownloadStatus {
        if (requestId.isBlank()) return OfflineDownloadStatus.MISSING
        hlsWorkInfo(context, requestId)?.let { work ->
            if (work.state == WorkInfo.State.SUCCEEDED &&
                work.outputData.getString(HlsExportWorker.KEY_OUTPUT_URI).isNullOrBlank() &&
                persistedHlsOutput(context, requestId).isNullOrBlank()
            ) return OfflineDownloadStatus.FAILED
            return work.toOfflineInfo().status
        }
        if (persistedHlsOutput(context, requestId) != null) return OfflineDownloadStatus.COMPLETE
        val download = runCatching { manager(context).downloadIndex.getDownload(requestId) }.getOrNull()
            ?: return OfflineDownloadStatus.MISSING
        return when (download.state) {
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> OfflineDownloadStatus.QUEUED
            Download.STATE_DOWNLOADING -> OfflineDownloadStatus.DOWNLOADING
            Download.STATE_STOPPED -> OfflineDownloadStatus.PAUSED
            Download.STATE_COMPLETED -> OfflineDownloadStatus.COMPLETE
            Download.STATE_FAILED, Download.STATE_REMOVING -> OfflineDownloadStatus.FAILED
            else -> OfflineDownloadStatus.MISSING
        }
    }

    fun status(context: Context, requestId: String, downloadId: Long): OfflineDownloadStatus {
        if (downloadId < 0L) return status(context, requestId)
        return systemDownloadInfo(context, downloadId).status
    }

    fun info(context: Context, requestId: String): OfflineDownloadInfo {
        if (requestId.isBlank()) return OfflineDownloadInfo(OfflineDownloadStatus.MISSING)
        hlsWorkInfo(context, requestId)?.let { work ->
            if (work.state == WorkInfo.State.SUCCEEDED &&
                work.outputData.getString(HlsExportWorker.KEY_OUTPUT_URI).isNullOrBlank() &&
                persistedHlsOutput(context, requestId).isNullOrBlank()
            ) return OfflineDownloadInfo(OfflineDownloadStatus.FAILED)
            return work.toOfflineInfo()
        }
        persistedHlsOutput(context, requestId)?.let { output ->
            val size = mediaStoreSize(context, Uri.parse(output))
            return OfflineDownloadInfo(OfflineDownloadStatus.COMPLETE, 100f, size, size.takeIf { it > 0L })
        }
        val download = runCatching { manager(context).downloadIndex.getDownload(requestId) }.getOrNull()
            ?: return OfflineDownloadInfo(OfflineDownloadStatus.MISSING)
        val status = when (download.state) {
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> OfflineDownloadStatus.QUEUED
            Download.STATE_DOWNLOADING -> OfflineDownloadStatus.DOWNLOADING
            Download.STATE_STOPPED -> OfflineDownloadStatus.PAUSED
            Download.STATE_COMPLETED -> OfflineDownloadStatus.COMPLETE
            Download.STATE_FAILED, Download.STATE_REMOVING -> OfflineDownloadStatus.FAILED
            else -> OfflineDownloadStatus.MISSING
        }
        return OfflineDownloadInfo(
            status = status,
            percent = download.percentDownloaded.takeIf { it >= 0f }?.coerceIn(0f, 100f),
            bytesDownloaded = download.bytesDownloaded,
            totalBytes = download.contentLength.takeIf { it > 0L }
        )
    }

    fun info(context: Context, requestId: String, downloadId: Long): OfflineDownloadInfo =
        if (downloadId >= 0L) systemDownloadInfo(context, downloadId) else info(context, requestId)

    fun playableUri(context: Context, requestId: String, sourceUrl: String, downloadId: Long = -1L): String? {
        if (downloadId >= 0L) {
            return context.getSystemService(SystemDownloadManager::class.java)
                .getUriForDownloadedFile(downloadId)?.toString()
                ?.takeIf { status(context, requestId, downloadId) == OfflineDownloadStatus.COMPLETE }
        }
        hlsWorkInfo(context, requestId)?.let { work ->
            if (work.state == WorkInfo.State.SUCCEEDED) {
                return work.outputData.getString(HlsExportWorker.KEY_OUTPUT_URI)
                    ?: persistedHlsOutput(context, requestId)
            }
        }
        persistedHlsOutput(context, requestId)?.let { return it }
        return sourceUrl.takeIf { it.isNotBlank() && status(context, requestId) == OfflineDownloadStatus.COMPLETE }
    }

    /** Returns a grantable URI for a completed, physical offline media file. */
    fun shareableUri(
        context: Context,
        requestId: String,
        downloadId: Long = -1L
    ): Uri? {
        if (status(context, requestId, downloadId) != OfflineDownloadStatus.COMPLETE) return null

        val storedUri = when {
            downloadId >= 0L -> context.applicationContext
                .getSystemService(SystemDownloadManager::class.java)
                .getUriForDownloadedFile(downloadId)
            requestId.startsWith(HLS_WORK_PREFIX) -> persistedHlsOutput(context, requestId)?.let(Uri::parse)
            else -> null
        } ?: return null

        return when (storedUri.scheme?.lowercase()) {
            "content" -> storedUri
            "file" -> {
                val file = File(storedUri.path.orEmpty()).takeIf { it.isFile } ?: return null
                runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }.getOrNull()
            }
            else -> null
        }
    }

    fun remove(context: Context, requestId: String, downloadId: Long = -1L) {
        if (downloadId >= 0L) {
            // System DownloadManager owns this exact NikTV download id. Removing it
            // deletes only that tracked file; no shared-folder scan is performed.
            context.applicationContext.getSystemService(SystemDownloadManager::class.java).remove(downloadId)
            return
        }
        if (requestId.startsWith(HLS_WORK_PREFIX)) {
            // WorkManager may prune a completed job before the user clears it. The
            // persisted output URI remains NikTV's ownership record, so use it even
            // when WorkInfo is no longer available and delete only that exact URI.
            val work = hlsWorkInfo(context, requestId)
            val output = work?.outputData?.getString(HlsExportWorker.KEY_OUTPUT_URI)
                ?: persistedHlsOutput(context, requestId)
            output?.let { storedOutput ->
                runCatching {
                    val outputUri = Uri.parse(storedOutput)
                    if (outputUri.scheme == "file") File(outputUri.path.orEmpty()).delete()
                    else context.contentResolver.delete(outputUri, null, null)
                }
            }
            work?.let {
                WorkManager.getInstance(context.applicationContext).cancelWorkById(it.id)
            }
            clearPersistedHlsOutput(context, requestId)
            return
        }
        if (requestId.isNotBlank()) {
            DownloadService.sendRemoveDownload(context, NikTvDownloadService::class.java, requestId, false)
        }
    }

    private fun systemDownloadInfo(context: Context, downloadId: Long): OfflineDownloadInfo {
        val manager = context.applicationContext.getSystemService(SystemDownloadManager::class.java)
        manager.query(SystemDownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return OfflineDownloadInfo(OfflineDownloadStatus.MISSING)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(SystemDownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(SystemDownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(SystemDownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0L }
            return OfflineDownloadInfo(
                status = when (status) {
                    SystemDownloadManager.STATUS_PENDING -> OfflineDownloadStatus.QUEUED
                    SystemDownloadManager.STATUS_RUNNING -> OfflineDownloadStatus.DOWNLOADING
                    SystemDownloadManager.STATUS_PAUSED -> OfflineDownloadStatus.PAUSED
                    SystemDownloadManager.STATUS_SUCCESSFUL -> OfflineDownloadStatus.COMPLETE
                    SystemDownloadManager.STATUS_FAILED -> OfflineDownloadStatus.FAILED
                    else -> OfflineDownloadStatus.MISSING
                },
                percent = total?.let { (downloaded.toFloat() * 100f / it).coerceIn(0f, 100f) },
                bytesDownloaded = downloaded.coerceAtLeast(0L),
                totalBytes = total
            )
        }
        return OfflineDownloadInfo(OfflineDownloadStatus.MISSING)
    }

    private fun hlsWorkInfo(context: Context, requestId: String): WorkInfo? {
        if (!requestId.startsWith(HLS_WORK_PREFIX)) return null
        val id = runCatching { UUID.fromString(requestId.removePrefix(HLS_WORK_PREFIX)) }.getOrNull() ?: return null
        return runCatching {
            WorkManager.getInstance(context.applicationContext).getWorkInfoById(id).get()
        }.getOrNull()
    }

    private fun WorkInfo.toOfflineInfo(): OfflineDownloadInfo {
        val progress = progress.getInt(HlsExportWorker.KEY_PROGRESS, 0).coerceIn(0, 100)
        val data = if (state == WorkInfo.State.SUCCEEDED) outputData else this.progress
        val bytes = data.getLong(HlsExportWorker.KEY_BYTES, 0L).coerceAtLeast(0L)
        val total = data.getLong(HlsExportWorker.KEY_TOTAL_BYTES, -1L).takeIf { it > 0L }
        return OfflineDownloadInfo(
            status = when (state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> OfflineDownloadStatus.QUEUED
                WorkInfo.State.RUNNING -> OfflineDownloadStatus.DOWNLOADING
                WorkInfo.State.SUCCEEDED -> OfflineDownloadStatus.COMPLETE
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> OfflineDownloadStatus.FAILED
            },
            percent = when (state) {
                WorkInfo.State.SUCCEEDED -> 100f
                WorkInfo.State.RUNNING -> progress.toFloat()
                else -> null
            },
            bytesDownloaded = bytes,
            totalBytes = total
        )
    }

    private fun persistedHlsOutput(context: Context, requestId: String): String? {
        val id = requestId.removePrefix(HLS_WORK_PREFIX).takeIf { requestId.startsWith(HLS_WORK_PREFIX) }
            ?: return null
        return context.applicationContext
            .getSharedPreferences(HlsExportWorker.OUTPUT_PREFERENCES, Context.MODE_PRIVATE)
            .getString(id, null)
    }

    private fun clearPersistedHlsOutput(context: Context, requestId: String) {
        val id = requestId.removePrefix(HLS_WORK_PREFIX).takeIf { requestId.startsWith(HLS_WORK_PREFIX) }
            ?: return
        context.applicationContext
            .getSharedPreferences(HlsExportWorker.OUTPUT_PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(id).apply()
    }

    private fun mediaStoreSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
            } ?: 0L
    }.getOrDefault(0L)

    private const val HLS_WORK_PREFIX = "hls-export:"
}

internal fun isSegmentedOfflineStream(url: String): Boolean =
    url.substringBefore('?')
        .substringAfterLast('/')
        .endsWith(".m3u8", ignoreCase = true)

@UnstableApi
class NikTvDownloadService : DownloadService(
    2107,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    OfflineMediaDownloads.CHANNEL_ID,
    com.nikhil.niktv.R.string.offline_download_channel,
    0
) {
    override fun getDownloadManager(): DownloadManager = OfflineMediaDownloads.manager(this)
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val downloadedBytes = active.sumOf { it.bytesDownloaded }
        val knownTotals = active.mapNotNull { it.contentLength.takeIf { length -> length > 0L } }
        val totalBytes = knownTotals.sum().takeIf { knownTotals.size == active.size && active.isNotEmpty() }
        val percent = totalBytes?.takeIf { it > 0L }
            ?.let { ((downloadedBytes * 100L) / it).toInt().coerceIn(0, 100) }
            ?: active.firstOrNull()?.percentDownloaded?.takeIf { it >= 0f }?.toInt()?.coerceIn(0, 100)
        val title = active.singleOrNull()?.request?.data?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?.let { "Downloading $it" }
            ?: if (active.isNotEmpty()) "Downloading ${active.size} items" else "Preparing offline download"
        val sizes = buildString {
            percent?.let { append("$it% · ") }
            append(formatBytes(downloadedBytes))
            totalBytes?.let { append(" / ${formatBytes(it)}") }
        }
        return NotificationCompat.Builder(this, OfflineMediaDownloads.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(sizes)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sizes))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100.0) "${value.toInt()} ${units[unit]}" else "%.1f %s".format(value, units[unit])
    }
}
