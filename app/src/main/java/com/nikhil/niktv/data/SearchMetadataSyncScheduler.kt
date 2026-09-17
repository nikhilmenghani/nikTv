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
import androidx.work.workDataOf
import com.nikhil.niktv.model.CatalogType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Keeps the sanitized GitHub search index fresh without blocking catalog UI. */
object SearchMetadataSyncScheduler {
    private const val IMMEDIATE_WORK = "niktv-search-metadata-sync"
    private const val PERIODIC_WORK = "niktv-search-metadata-periodic-sync"
    private const val PERIODIC_SCAN_WORK = "niktv-provider-metadata-scan"
    private const val DEBOUNCE_SECONDS = 45L
    private const val PERIODIC_HOURS = 12L
    private const val PERIODIC_SCAN_HOURS = 24L

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val config = GitHubBackupManager(appContext).loadConfig()
        val workManager = WorkManager.getInstance(appContext)
        if (!isEnabled(config)) {
            workManager.cancelUniqueWork(IMMEDIATE_WORK)
            workManager.cancelUniqueWork(PERIODIC_WORK)
            CatalogType.entries.forEach {
                workManager.cancelUniqueWork("$PERIODIC_SCAN_WORK-${it.name}")
            }
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
        listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
            .forEachIndexed { index, type ->
                val scanRequest = PeriodicWorkRequestBuilder<PeriodicCatalogScanWorker>(
                    PERIODIC_SCAN_HOURS, TimeUnit.HOURS
                )
                    .setInitialDelay((index + 1L) * 2L, TimeUnit.HOURS)
                    .setInputData(workDataOf(PeriodicCatalogScanWorker.TYPE_KEY to type.name))
                    .setConstraints(networkConstraints())
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    "$PERIODIC_SCAN_WORK-${type.name}",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    scanRequest
                )
            }
        request(appContext)
    }

    fun request(context: Context) {
        enqueue(context, immediate = false)
    }

    fun requestNow(context: Context): UUID? = enqueue(context, immediate = true)

    private fun enqueue(context: Context, immediate: Boolean): UUID? {
        val appContext = context.applicationContext
        if (!isEnabled(GitHubBackupManager(appContext).loadConfig())) return null
        val builder = OneTimeWorkRequestBuilder<SearchMetadataSyncWorker>()
            .setConstraints(networkConstraints())
        if (!immediate) builder.setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
        val request = builder.build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    private fun isEnabled(config: GitHubBackupConfig): Boolean =
        config.backupMode == BackupMode.GITHUB && config.token.isNotBlank()

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

class PeriodicCatalogScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val type = inputData.getString(TYPE_KEY)
            ?.let { runCatching { CatalogType.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val store = ProfileStore(applicationContext)
        val scanner = SearchCatalogScanner(applicationContext)
        var transientFailure = false

        store.profiles.first().forEach { profile ->
            val session = store.sessionFor(profile) ?: return@forEach
            runCatching {
                scanner.scan(
                    session = session,
                    type = type,
                    requestDelayMillis = PERIODIC_REQUEST_DELAY_MS,
                    refreshCompleted = true
                )
            }.onFailure { transientFailure = true }
        }
        SearchMetadataSyncScheduler.requestNow(applicationContext)
        return if (transientFailure) Result.retry() else Result.success()
    }

    companion object {
        const val TYPE_KEY = "catalog_type"
        private const val PERIODIC_REQUEST_DELAY_MS = 750L
    }
}

class SearchMetadataSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = syncMutex.withLock { syncOnce() }

    private suspend fun syncOnce(): Result {
        val backupManager = GitHubBackupManager(applicationContext)
        val config = backupManager.loadConfig()
        if (config.backupMode != BackupMode.GITHUB || config.token.isBlank()) {
            return Result.success()
        }

        val store = ProfileStore(applicationContext)
        val sync = SearchMetadataSyncManager(applicationContext)
        var transientFailure = false
        val profiles = store.profiles.first()
        val total = (profiles.size * SYNC_TYPES.size).coerceAtLeast(1)
        var completed = 0

        profiles.forEach { profile ->
            val profileKey = profile.cacheKey()
            SYNC_TYPES.forEach { type ->
                try {
                    setProgress(
                        workDataOf(
                            PROGRESS_MESSAGE to "Downloading ${type.title} index for ${profile.name}",
                            PROGRESS_FRACTION to completed.toFloat() / total
                        )
                    )
                    val searchCache = store.searchCatalog(type, profileKey).first()
                    val browseCache = store.browseCatalog(type, profileKey).first()
                    val remote = sync.download(profile, type, config)
                    setProgress(
                        workDataOf(
                            PROGRESS_MESSAGE to "Merging ${type.title} metadata",
                            PROGRESS_FRACTION to (completed + 0.45f) / total
                        )
                    )
                    val local = sync.buildIndex(profile, type, searchCache, browseCache)
                    val merged = sync.merge(local, remote)

                    if (remote != null) {
                        val remoteCache = sync.asLocalSearchCache(remote, profileKey)
                        store.mergeSearchMetadata(remoteCache)
                    }
                    if (merged.items.isNotEmpty() && (remote?.items != merged.items ||
                            SearchMetadataDocuments.legacyProfileId(profile) != sync.anonymousProfileId(profile))) {
                        setProgress(
                            workDataOf(
                                PROGRESS_MESSAGE to "Uploading ${type.title} changes",
                                PROGRESS_FRACTION to (completed + 0.75f) / total
                            )
                        )
                        sync.upload(merged, config, profileName = profile.name)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    return Result.failure()
                } catch (_: Throwable) {
                    transientFailure = true
                }
                completed += 1
            }
        }
        return if (transientFailure) Result.retry() else Result.success()
    }

    companion object {
        private val syncMutex = Mutex()
        const val PROGRESS_MESSAGE = "sync_message"
        const val PROGRESS_FRACTION = "sync_fraction"
        private val SYNC_TYPES = listOf(
            CatalogType.LIVE_TV,
            CatalogType.MOVIES,
            CatalogType.SERIES
        )
    }
}
