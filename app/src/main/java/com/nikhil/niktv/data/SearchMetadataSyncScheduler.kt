package com.nikhil.niktv.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nikhil.niktv.model.CatalogType
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Keeps the sanitized GitHub search index fresh without blocking catalog UI. */
object SearchMetadataSyncScheduler {
    private const val IMMEDIATE_WORK = "niktv-search-metadata-sync"
    private const val PERIODIC_WORK = "niktv-search-metadata-periodic-sync"
    private const val DEBOUNCE_SECONDS = 45L
    private const val PERIODIC_HOURS = 12L

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val config = GitHubBackupManager(appContext).loadConfig()
        val workManager = WorkManager.getInstance(appContext)
        if (!isEnabled(config)) {
            workManager.cancelUniqueWork(IMMEDIATE_WORK)
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SearchMetadataSyncWorker>(
            PERIODIC_HOURS, TimeUnit.HOURS
        ).setConstraints(networkConstraints()).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        request(appContext)
    }

    fun request(context: Context) {
        val appContext = context.applicationContext
        if (!isEnabled(GitHubBackupManager(appContext).loadConfig())) return
        val request = OneTimeWorkRequestBuilder<SearchMetadataSyncWorker>()
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun isEnabled(config: GitHubBackupConfig): Boolean =
        config.backupMode == BackupMode.GITHUB && config.token.isNotBlank()

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

class SearchMetadataSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val backupManager = GitHubBackupManager(applicationContext)
        val config = backupManager.loadConfig()
        if (config.backupMode != BackupMode.GITHUB || config.token.isBlank()) {
            return Result.success()
        }

        val store = ProfileStore(applicationContext)
        val sync = SearchMetadataSyncManager(applicationContext)
        var transientFailure = false

        store.profiles.first().forEach { profile ->
            val profileKey = profile.cacheKey()
            SYNC_TYPES.forEach { type ->
                try {
                    val searchCache = store.searchCatalog(type, profileKey).first()
                    val browseCache = store.browseCatalog(type, profileKey).first()
                    val remote = sync.download(profile, type, config)
                    val local = sync.buildIndex(profile, type, searchCache, browseCache)
                    val merged = sync.merge(local, remote)

                    if (remote != null) {
                        val remoteCache = sync.asLocalSearchCache(remote, profileKey)
                        store.mergeSearchMetadata(remoteCache)
                    }
                    if (merged.items.isNotEmpty() && remote?.items != merged.items) {
                        sync.upload(merged, config)
                    }
                } catch (_: IllegalArgumentException) {
                    // Oversized/invalid documents are isolated to this type.
                } catch (_: Throwable) {
                    transientFailure = true
                }
            }
        }
        return if (transientFailure) Result.retry() else Result.success()
    }

    companion object {
        private val SYNC_TYPES = listOf(
            CatalogType.LIVE_TV,
            CatalogType.MOVIES,
            CatalogType.SERIES
        )
    }
}
