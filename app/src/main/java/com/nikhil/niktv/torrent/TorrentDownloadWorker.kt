package com.nikhil.niktv.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import com.nikhil.niktv.BuildConfig
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import java.io.IOException

/** Foreground, process-independent native download. No loopback URL is persisted. */
class TorrentDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB) ?: return@withContext Result.failure()
        val job = TorrentOfflineDownloads.claim(applicationContext, jobId) ?: return@withContext Result.failure()
        var engine: TorrentStreamEngine? = null
        var discardPartial = false
        var permitAcquired = false
        try {
            TorrentOfflineDownloads.recoverCompleted(applicationContext, job)?.let { uri ->
                discardPartial = true
                return@withContext Result.success(workDataOf("output_uri" to uri))
            }
            // WorkManager may run many independent jobs. Waiting in foreground
            // is cancellable and creates no session or competing network stream.
            setForeground(notification(job, 0, 0, waiting = true))
            downloadSlot.acquire()
            permitAcquired = true
            currentCoroutineContext().ensureActive()
            TorrentOfflineDownloads.update(applicationContext, jobId, "downloading")
            val testOptions = regressionOptions.takeIf { BuildConfig.DEBUG && applicationContext.packageName.endsWith(".regression") }
            val running = TorrentStreamEngine(applicationContext, (testOptions ?: TorrentEngineOptions(downloadLimitBytesPerSecond = 512 * 1024)).copy(
                cacheLimitBytes = job.sizeBytes, persistentCacheId = jobId, preserveCacheOnClose = true,
                allowDownloadFiles = job.genericFile)).also { engine = it }
            val files = running.prepare(job.source)
            val video = files.firstOrNull { it.index == job.fileIndex && it.name == job.fileName && it.sizeBytes == job.sizeBytes }
                ?: files.singleOrNull { it.name == job.fileName && it.sizeBytes == job.sizeBytes }
                ?: error("The chosen video is no longer present in this torrent")
            coroutineScope {
                val progress = launch {
                    var last = 0L
                    running.status.collect { status ->
                        if (SystemClock.elapsedRealtime() - last >= 1_000) {
                            last = SystemClock.elapsedRealtime()
                            val bytes = status.downloadedBytes.coerceIn(0, job.sizeBytes)
                            val percent = (bytes.toDouble() * 100 / job.sizeBytes).toInt().coerceIn(0, 99)
                            TorrentOfflineDownloads.update(applicationContext, jobId, "downloading", bytes)
                            setProgress(workDataOf("progress" to percent, "bytes" to bytes, "total_bytes" to job.sizeBytes))
                            setForeground(notification(job, percent, bytes))
                        }
                    }
                }
                try {
                    running.selectDownload(video.index)
                    val completed = running.completedFile()
                    // Rename only after native disk/alert workers have stopped.
                    running.closeAndAwait()
                    currentCoroutineContext().ensureActive()
                    val uri = TorrentOfflineDownloads.publish(applicationContext, job, completed)
                    discardPartial = true
                    Result.success(workDataOf("output_uri" to uri, "bytes" to job.sizeBytes, "total_bytes" to job.sizeBytes))
                } finally { progress.cancelAndJoin() }
            }
        } catch (cancelled: CancellationException) {
            TorrentOfflineDownloads.update(applicationContext, jobId, "queued")
            throw cancelled
        } catch (failure: Exception) {
            val retry = failure is IOException && runAttemptCount < 2
            discardPartial = !retry
            TorrentOfflineDownloads.update(applicationContext, jobId, if (retry) "queued" else "failed",
                engine?.status?.value?.downloadedBytes ?: 0, failure.message?.take(200) ?: "Download failed")
            if (retry) Result.retry() else Result.failure()
        } finally {
            withContext(NonCancellable) {
                try {
                    engine?.closeAndAwait()
                    TorrentOfflineDownloads.release(applicationContext, jobId, discardPartial)
                } finally {
                    if (permitAcquired) downloadSlot.release()
                }
            }
        }
    }

    private fun notification(job: TorrentDownloadJob, progress: Int, bytes: Long, waiting: Boolean = false): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Torrent downloads", NotificationManager.IMPORTANCE_LOW))
        val cancelIntent = Intent(applicationContext, TorrentDownloadCancelReceiver::class.java).putExtra(KEY_JOB, job.id)
        val cancel = PendingIntent.getBroadcast(applicationContext, id.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val sizes = "${Formatter.formatFileSize(applicationContext, bytes)} / ${Formatter.formatFileSize(applicationContext, job.sizeBytes)}"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(if (waiting) "Queued · ${job.title}" else "Downloading ${job.title}")
            .setContentText(if (waiting) "Waiting for the current download" else "$sizes · $progress%").setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, progress, progress == 0).addAction(0, "Cancel", cancel).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(22_000 + (id.hashCode() and 0xFFFF), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(22_000 + (id.hashCode() and 0xFFFF), notification)
    }

    companion object {
        const val KEY_JOB = "torrent_job_id"
        /** Only read by the isolated debuggable regression package. */
        @Volatile internal var regressionOptions: TorrentEngineOptions? = null
        private val downloadSlot = Semaphore(1)
        private const val CHANNEL = "niktv-torrent-downloads"
    }
}

class TorrentDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(TorrentDownloadWorker.KEY_JOB) ?: return
        val pending = goAsync()
        TorrentOfflineDownloads.remove(context, TorrentOfflineDownloads.PREFIX + jobId) { pending.finish() }
    }
}
