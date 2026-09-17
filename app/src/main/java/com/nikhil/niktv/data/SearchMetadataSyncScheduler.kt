package com.nikhil.niktv.data

import android.content.Context
import androidx.work.*
import com.nikhil.niktv.model.CatalogType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Local refresh is independent of opt-in catalog backups. */
object SearchMetadataSyncScheduler {
    private const val BACKUP = "niktv-catalog-backup-v1"
    private const val MANUAL = "niktv-catalog-backup-now-v1"
    private const val REFRESH = "niktv-catalog-refresh-v1"

    fun initialize(context: Context) {
        val manager = WorkManager.getInstance(context)
        // Cancel the previous always-on GitHub search-index implementation on upgrade.
        listOf("niktv-search-metadata-sync", "niktv-search-metadata-periodic-sync").forEach(manager::cancelUniqueWork)
        CatalogType.entries.forEach { manager.cancelUniqueWork("niktv-provider-metadata-scan-${it.name}") }
        manager.enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PeriodicCatalogScanWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(30, TimeUnit.MINUTES).setConstraints(constraints()).build())
        if (CatalogPreferences.backupEnabled(context)) {
            manager.enqueueUniquePeriodicWork(BACKUP, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SearchMetadataSyncWorker>(12, TimeUnit.HOURS)
                    .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(constraints()).build())
        } else {
            manager.cancelUniqueWork(BACKUP)
            manager.cancelUniqueWork(MANUAL)
        }
    }

    // Catalog writes stay local. Periodic/manual backup owns uploads, never a save callback.
    fun request(context: Context) = Unit

    fun requestNow(context: Context): UUID? {
        if (!CatalogPreferences.backupEnabled(context)) return null
        val work = OneTimeWorkRequestBuilder<SearchMetadataSyncWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.REPLACE, work)
        BackupActivityLog.record(context, "IPTV catalog backup", "Queued", "Waiting for network and background execution.")
        return work.id
    }

    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>().setConstraints(constraints()).build())
        BackupActivityLog.record(context, "IPTV catalog refresh", "Requested", "A refresh is queued or already active.")
    }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true).build()
}

class PeriodicCatalogScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = scanMutex.withLock {
        val store = ProfileStore(applicationContext)
        val portal = StalkerPortalClient(applicationContext)
        var failed = false
        BackupActivityLog.record(applicationContext, "IPTV catalog refresh", "Started", "Reading provider catalogs into the local database.")
        try {
            for (profile in store.profiles.first()) {
                try {
                    val session = portal.authenticate(profile)
                    for (type in listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)) {
                        val result = SearchCatalogScanner(applicationContext).scan(session, type, 750L)
                        if (result.failures > 0) failed = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
            BackupActivityLog.record(applicationContext, "IPTV catalog refresh", if (failed) "Retry scheduled" else "Completed",
                if (failed) "Some categories could not finish. Existing records are preserved; background work will retry." else "Provider catalog scan completed.")
            if (failed) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            BackupActivityLog.record(applicationContext, "IPTV catalog refresh", "Cancelled", "Saved page checkpoints are retained for the next scan.")
            throw cancelled
        }
    }
    companion object { private val scanMutex = Mutex() }
}

/** Class name retained so already-enqueued work remains resolvable across app upgrades. */
class SearchMetadataSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = backupMutex.withLock {
        if (!CatalogPreferences.backupEnabled(applicationContext)) return@withLock Result.success()
        try {
            setProgress(workDataOf(PROGRESS_MESSAGE to "Backing up IPTV catalog", PROGRESS_FRACTION to 0.1f))
            CatalogBackupManager(applicationContext).uploadAll()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: IllegalArgumentException) {
            CatalogPreferences.status(applicationContext, invalid.message ?: "Configure catalog backup in Settings")
            Result.failure()
        } catch (error: Exception) {
            CatalogPreferences.status(applicationContext, error.message ?: "Catalog backup will retry")
            Result.retry()
        }
    }
    companion object {
        private val backupMutex = Mutex()
        const val PROGRESS_MESSAGE = "sync_message"
        const val PROGRESS_FRACTION = "sync_fraction"
    }
}
