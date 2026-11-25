package com.nikhil.niktv.update

import android.app.DownloadManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Android owns the transfer, including network retries and process death. */
internal fun backgroundUpdateRequest(context: Context, update: UpdateInfo, fileName: String): DownloadManager.Request =
    DownloadManager.Request(Uri.parse(update.downloadUrl))
        .setTitle("NikTV ${update.version}")
        .setDescription("Downloading update in the background")
        .setMimeType("application/vnd.android.package-archive")
        .setAllowedOverMetered(false)
        .setAllowedOverRoaming(false)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName")

internal fun backgroundUpdateWork(update: UpdateInfo): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<BackgroundUpdateDownloadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
        .setInputData(workDataOf("version" to update.version, "url" to update.downloadUrl, "package" to update.updatePackage.name))
        .build()

internal fun reusableUpdateDownload(state: UpdateDownloadState, update: UpdateInfo): Long? {
    val active = when (state) {
        is UpdateDownloadState.Queued -> Triple(state.downloadId, state.version, state.downloadUrl)
        is UpdateDownloadState.Downloading -> Triple(state.downloadId, state.version, state.downloadUrl)
        is UpdateDownloadState.Paused -> Triple(state.downloadId, state.version, state.downloadUrl)
        is UpdateDownloadState.Ready -> Triple(state.downloadId, state.version, state.downloadUrl)
        is UpdateDownloadState.Installing -> Triple(state.downloadId, state.version, state.downloadUrl)
        is UpdateDownloadState.InstallerLaunched -> Triple(state.downloadId, state.version, state.downloadUrl)
        else -> return null
    }
    return active.first.takeIf { AppUpdates.compareAppVersions(update.version, active.second) <= 0 &&
        (update.version != active.second || update.downloadUrl == active.third) }
}

class BackgroundUpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { return try {
        AppUpdates.initialize(applicationContext)
        val version = inputData.getString("version") ?: return Result.failure()
        val url = inputData.getString("url") ?: return Result.failure()
        val kind = runCatching { UpdatePackage.valueOf(inputData.getString("package").orEmpty()) }.getOrDefault(UpdatePackage.AUTO)
        if (AppUpdates.compareAppVersions(version, com.nikhil.niktv.BuildConfig.VERSION_NAME) > 0) {
            AppUpdates.download(applicationContext, UpdateInfo(version, url, kind), background = true)
        }
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: SecurityException) { Result.failure() }
      catch (_: IllegalArgumentException) { Result.failure() }
      catch (_: Exception) { Result.retry() }
    }
}

/** Reconcile Download Manager completion even when the UI process was absent. */
class UpdateDownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { AppUpdates.refreshCompletedDownload(context.applicationContext, id) }
            catch (_: Exception) { android.util.Log.w("AppUpdates", "Could not reconcile completed update") }
            finally { result.finish() }
        }
    }
}
