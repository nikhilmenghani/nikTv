package com.nikhil.niktv.data

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import java.io.File
import java.util.concurrent.Executors

enum class OfflineDownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED, MISSING }
data class OfflineDownloadInfo(
    val status: OfflineDownloadStatus,
    val percent: Float? = null,
    val bytesDownloaded: Long = 0L
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

    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("NikTV/0.1 Android")
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

    fun enqueue(context: Context, mediaKey: String, title: String, url: String): String {
        val id = requestId(mediaKey)
        val uri = Uri.parse(url)
        val hls = uri.lastPathSegment.orEmpty().substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val request = DownloadRequest.Builder(id, uri)
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_UNKNOWN)
            .setData(title.toByteArray())
            .build()
        DownloadService.sendAddDownload(context, NikTvDownloadService::class.java, request, false)
        return id
    }

    fun status(context: Context, requestId: String): OfflineDownloadStatus {
        if (requestId.isBlank()) return OfflineDownloadStatus.MISSING
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

    fun info(context: Context, requestId: String): OfflineDownloadInfo {
        if (requestId.isBlank()) return OfflineDownloadInfo(OfflineDownloadStatus.MISSING)
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
            bytesDownloaded = download.bytesDownloaded
        )
    }

    fun playableUri(context: Context, requestId: String, sourceUrl: String): String? =
        sourceUrl.takeIf { it.isNotBlank() && status(context, requestId) == OfflineDownloadStatus.COMPLETE }

    fun remove(context: Context, requestId: String) {
        if (requestId.isNotBlank()) {
            DownloadService.sendRemoveDownload(context, NikTvDownloadService::class.java, requestId, false)
        }
    }
}

@UnstableApi
class NikTvDownloadService : DownloadService(
    2107,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    OfflineMediaDownloads.CHANNEL_ID,
    com.nikhil.niktv.R.string.offline_download_channel,
    0
) {
    private val notificationHelper by lazy { DownloadNotificationHelper(this, OfflineMediaDownloads.CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = OfflineMediaDownloads.manager(this)
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            getString(com.nikhil.niktv.R.string.offline_download_progress),
            downloads,
            notMetRequirements
        )
}
