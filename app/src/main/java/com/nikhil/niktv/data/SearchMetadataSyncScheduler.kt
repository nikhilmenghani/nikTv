package com.nikhil.niktv.data

import android.content.Context
import androidx.work.*
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PortalProfile
import kotlinx.coroutines.*
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
    private const val RESTORE = "niktv-catalog-restore-now-v1"
    private const val REFRESH = "niktv-catalog-refresh-v1"

    fun initialize(context: Context) {
        val manager = WorkManager.getInstance(context)
        // Cancel the previous always-on GitHub search-index implementation on upgrade.
        listOf("niktv-search-metadata-sync", "niktv-search-metadata-periodic-sync").forEach(manager::cancelUniqueWork)
        CatalogType.entries.forEach { manager.cancelUniqueWork("niktv-provider-metadata-scan-${it.name}") }
        manager.cancelUniqueWork(REFRESH)
        manager.cancelUniqueWork("$REFRESH-now")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ProfileStore(context).profiles.first().forEach { configureProfile(context, it) }
        }
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

    fun requestNow(context: Context, resume: Boolean = false, profile: PortalProfile? = null, type: CatalogType? = null): UUID? {
        if (!CatalogPreferences.backupEnabled(context)) return null
        if (resume) CatalogOperations.control(context, CatalogOperations.BACKUP, "Ready")
        if (CatalogOperations.held(context, CatalogOperations.BACKUP)) return null
        val workBuilder = OneTimeWorkRequestBuilder<SearchMetadataSyncWorker>().setConstraints(constraints())
        val input = mutableMapOf<String, Any>()
        profile?.let { input[PROFILE_ID] = CatalogScanPreferences.id(it) }
        type?.let { input[MEDIA_TYPE] = it.name }
        if (input.isNotEmpty()) workBuilder.setInputData(Data.Builder().putAll(input).build())
        val work = workBuilder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        CatalogOperations.message(context, CatalogOperations.BACKUP, "Backup queued. Waiting for network/background execution.")
        CatalogOperations.progress(context, CatalogOperations.BACKUP, CatalogOperationProgress("Queued"))
        BackupActivityLog.record(context, "IPTV catalog backup${profile?.let { " · ${it.name}" }.orEmpty()}", "Queued", "Waiting for network and background execution.")
        return work.id
    }

    fun requestRestore(context: Context, profile: PortalProfile, resume: Boolean = false, type: CatalogType? = null): UUID? {
        if (resume) CatalogOperations.control(context, CatalogOperations.RESTORE, "Ready")
        if (CatalogOperations.held(context, CatalogOperations.RESTORE)) return null
        val restoreInput = Data.Builder().putString(PROFILE_ID, CatalogScanPreferences.id(profile))
            .apply { type?.let { putString(MEDIA_TYPE, it.name) } }.build()
        val work = OneTimeWorkRequestBuilder<CatalogRestoreWorker>()
            .setInputData(restoreInput).setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(RESTORE, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        CatalogOperations.message(context, CatalogOperations.RESTORE, "Restore queued. Waiting for network/background execution.")
        CatalogOperations.progress(context, CatalogOperations.RESTORE, CatalogOperationProgress("Queued", category = profile.name))
        BackupActivityLog.record(context, "IPTV catalog restore · ${profile.name}", "Queued", "The latest snapshots from all devices will be merged.")
        return work.id
    }

    const val PROFILE_ID = "profile_id"
    const val MEDIA_TYPE = "media_type"
    const val RESUME_ONLY = "resume_only"
    const val FULL_SCAN = "full_scan"
    fun configureProfile(context: Context, profile: PortalProfile) {
        val id = CatalogScanPreferences.id(profile)
        val hours = CatalogScanPreferences.hours(context, id)
        val manager = WorkManager.getInstance(context)
        val name = "$REFRESH-$id"
        if (hours == 0) manager.cancelUniqueWork(name)
        else manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PeriodicCatalogScanWorker>(hours.toLong(), TimeUnit.HOURS)
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .setInputData(workDataOf(PROFILE_ID to id)).setConstraints(constraints()).build())
    }

    /** Ignore the next scheduled periodic run; only running periodic work is active now. */
    fun observeScanWork(context: Context, id: String) = kotlinx.coroutines.flow.combine(
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("$REFRESH-now-$id"),
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("$REFRESH-$id")
    ) { manual, periodic ->
        manual.filterNot { it.state.isFinished } + periodic.filter {
            it.state == WorkInfo.State.RUNNING ||
                (!it.state.isFinished && it.runAttemptCount > 0)
        }
    }

    fun refresh(context: Context, profile: PortalProfile? = null, resume: Boolean = false) {
        if (profile == null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                ProfileStore(context).profiles.first().forEach { refresh(context, it) }
            }
            return
        }
        val id = CatalogScanPreferences.id(profile)
        if (resume) CatalogOperations.control(context, CatalogOperations.scan(id), "Ready")
        if (CatalogOperations.held(context, CatalogOperations.scan(id))) return
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", if (resume) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(workDataOf(PROFILE_ID to id, RESUME_ONLY to resume)).setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        CatalogScanPreferences.status(context, id, "Scan requested; queued or already running. Existing listings remain searchable.")
        CatalogOperations.message(context, CatalogOperations.scan(id), "Scan queued. Waiting for network/background execution; saved listings remain available.")
        BackupActivityLog.record(context, "Catalog scan · ${profile.name}", "Requested")
    }

    fun fullScan(context: Context, profile: PortalProfile) {
        val id = CatalogScanPreferences.id(profile)
        CatalogOperations.control(context, CatalogOperations.scan(id), "Ready")
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(workDataOf(PROFILE_ID to id, FULL_SCAN to true))
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        CatalogScanPreferences.status(context, id, "Full scan requested. Existing records stay available while every provider page is refreshed.")
        CatalogOperations.message(context, CatalogOperations.scan(id),
            "Full scan queued. Live TV, Movies and Series will be refreshed from the beginning.")
        BackupActivityLog.record(context, "Catalog scan · ${profile.name}", "Full scan requested")
    }

    fun resumeRestored(context: Context, profile: PortalProfile) {
        val id = CatalogScanPreferences.id(profile)
        val restoredCursor = CatalogScanPreferences.restoredCursor(context, id)
        if (CatalogScanPreferences.restoredAt(context, id) == 0L || restoredCursor < 0) {
            CatalogOperations.message(context, CatalogOperations.scan(id),
                "No incomplete restored scan is available. Restore a catalog snapshot first or choose Full scan.")
            return
        }
        CatalogScanPreferences.cursor(context, id, restoredCursor)
        CatalogOperations.control(context, CatalogOperations.scan(id), "Ready")
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(workDataOf(PROFILE_ID to id, RESUME_ONLY to true))
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        CatalogOperations.message(context, CatalogOperations.scan(id),
            "Restored resume selected. Continuing from the backed-up ${listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)[restoredCursor].title} page cursor.")
        BackupActivityLog.record(context, "Catalog scan · ${profile.name}", "Restored resume requested")
    }

    /** User-requested retry replaces delayed/blocked work, retaining Room's committed cursor. */
    fun retryQueued(context: Context, profile: PortalProfile) {
        val app = context.applicationContext
        val id = CatalogScanPreferences.id(profile)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = WorkManager.getInstance(app)
                val periodic = manager.getWorkInfosForUniqueWork("$REFRESH-$id").get()
                val manual = manager.getWorkInfosForUniqueWork("$REFRESH-now-$id").get()
                if ((manual + periodic).any { it.state == WorkInfo.State.RUNNING } ||
                    CatalogOperations.held(app, CatalogOperations.scan(id))) return@launch
                // Replace a pending periodic retry too, otherwise it can restart a completed manual scan later.
                if (periodic.any { !it.state.isFinished && it.runAttemptCount > 0 }) {
                    manager.cancelUniqueWork("$REFRESH-$id").result.get()
                    configureProfile(app, profile)
                }
                CatalogOperations.message(app, CatalogOperations.scan(id), "Retry requested. Waiting for a connection and Android to start the scan.")
                manager.enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                        .setInputData(workDataOf(PROFILE_ID to id, RESUME_ONLY to false))
                        .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                CatalogOperations.message(app, CatalogOperations.scan(id), "Could not reschedule the scan. Saved progress is retained; try again.")
            }
        }
    }

    fun continueScan(context: Context, id: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(workDataOf(PROFILE_ID to id, RESUME_ONLY to true)).setInitialDelay(10, TimeUnit.SECONDS)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true).build()
}

class PeriodicCatalogScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = scanMutex.withLock {
        val context = applicationContext
        val id = inputData.getString(SearchMetadataSyncScheduler.PROFILE_ID) ?: return@withLock Result.success()
        val profile = ProfileStore(context).profiles.first().firstOrNull { CatalogScanPreferences.id(it) == id }
            ?: return@withLock Result.success()
        val operation = "Catalog scan · ${profile.name}"
        val control = CatalogOperations.scan(id)
        var notificationJob: Job? = null
        try {
            CatalogOperations.check(context, control)
            val resumeOnly = inputData.getBoolean(SearchMetadataSyncScheduler.RESUME_ONLY, false)
            val fullScan = inputData.getBoolean(SearchMetadataSyncScheduler.FULL_SCAN, false)
            if (CatalogPlaybackActivity.playing) {
                CatalogOperations.message(context, control, "Waiting for playback to finish; saved progress will resume automatically.")
                CatalogScanPreferences.status(context, id, "Paused during playback; saved scan progress will resume automatically.")
                return@withLock Result.retry()
            }
            setForeground(CatalogScanNotification.foreground(context, id, "Preparing catalog scan. You can leave the app."))
            notificationJob = CoroutineScope(currentCoroutineContext()).launch {
                var lastMessage = ""
                while (isActive) {
                    val message = CatalogOperations.message(context, control)
                    if (message != lastMessage) {
                        setForeground(CatalogScanNotification.foreground(context, id, message))
                        lastMessage = message
                    }
                    delay(2_000L)
                }
            }
            CatalogScanPreferences.status(context, id, "Connecting to ${profile.name}…")
            CatalogOperations.message(context, control, "Connecting to ${profile.name}…")
            val session = StalkerPortalClient(context).authenticate(profile)
            CatalogOperations.check(context, control)
            val types = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
            var cursor = CatalogScanPreferences.cursor(context, id)
            if (fullScan) {
                types.forEach { CatalogRepository(context).restartScan(profile, it) }
                cursor = 0
                CatalogScanPreferences.cursor(context, id, cursor)
                BackupActivityLog.record(context, operation, "Started", "Updating channels, movies and series; saved listings stay available.")
            } else if (resumeOnly && cursor < 0) {
                cursor = CatalogRepository(context).resumeScanIndex(profile)
                if (cursor < 0) {
                    CatalogOperations.message(context, control,
                        "The restored scan is already complete. Choose Full scan to refresh every provider page.")
                    return@withLock Result.success()
                }
                CatalogScanPreferences.cursor(context, id, cursor)
                CatalogOperations.message(context, control,
                    "Resuming ${types[cursor].title} from the last saved provider page.")
                BackupActivityLog.record(context, operation, "Resumed",
                    "Continuing ${types[cursor].title} from restored Room page cursors.")
            } else if (cursor < 0) {
                types.forEach { CatalogRepository(context).restartScan(profile, it) }
                cursor = 0
                CatalogScanPreferences.cursor(context, id, cursor)
                BackupActivityLog.record(context, operation, "Started",
                    "Scheduled refresh started from Live TV; saved listings stay available.")
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 480_000L
            for (index in cursor until types.size) {
                CatalogOperations.check(context, control)
                val type = types[index]
                val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val result = SearchCatalogScanner(context).scan(session, type, 2_000L,
                    refreshCompleted = false, timeBudgetMillis = remaining) { progress ->
                    CatalogScanPreferences.status(context, id,
                        "${type.title} · ${progress.categoryTitle} · category ${progress.categoryPosition}/${progress.categoryCount} · page ${progress.page} · ${progress.discoveredItems} items")
                }
                if (result.deferred || result.failures > 0) {
                    CatalogScanPreferences.status(context, id, if (CatalogPlaybackActivity.playing)
                        "Paused during playback; scan progress saved." else if (result.failures > 0)
                        "Some provider pages failed; saved progress will retry automatically." else
                        "Scan progress saved; continuing in the background…")
                    CatalogOperations.message(context, control, CatalogScanPreferences.status(context, id))
                    CatalogOperations.check(context, control)
                    if (result.deferred && !CatalogPlaybackActivity.playing && result.failures == 0) {
                        SearchMetadataSyncScheduler.continueScan(context, id)
                        return@withLock Result.success()
                    }
                    return@withLock Result.retry()
                }
                CatalogScanPreferences.cursor(context, id, index + 1)
            }
            CatalogOperations.check(context, control)
            val now = System.currentTimeMillis()
            CatalogScanPreferences.completed(context, id, now)
            CatalogScanPreferences.cursor(context, id, -1)
            CatalogScanPreferences.status(context, id, "Complete · ${java.util.Date(now)} · Room backup catalog contains channels, movies and series.")
            CatalogOperations.message(context, control, CatalogScanPreferences.status(context, id))
            BackupActivityLog.record(context, operation, "Completed", "Room backup catalog is ready to inspect or upload; app browsing remains provider/cache based.")
            if (CatalogPreferences.backupEnabled(context)) SearchMetadataSyncScheduler.requestNow(context, profile = profile)
            Result.success()
        } catch (held: CatalogOperationHeld) {
            BackupActivityLog.record(context, operation, held.state, "Committed pages retained. Resume from Catalog & backup.")
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            CatalogScanPreferences.status(context, id, "Provider scan could not finish; progress saved and retry scheduled.")
            if (!CatalogOperations.held(context, control)) CatalogOperations.message(context, control, CatalogScanPreferences.status(context, id))
            BackupActivityLog.record(context, operation, "Retry scheduled", "Check provider connectivity if this continues.")
            Result.retry()
        } finally {
            notificationJob?.cancelAndJoin()
        }
    }
    companion object { private val scanMutex = Mutex() }
}

/** Class name retained so already-enqueued work remains resolvable across app upgrades. */
class SearchMetadataSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = backupMutex.withLock {
        if (!CatalogPreferences.backupEnabled(applicationContext)) return@withLock Result.success()
        var notificationJob: Job? = null
        try {
            CatalogOperations.check(applicationContext, CatalogOperations.BACKUP)
            setProgress(workDataOf(PROGRESS_MESSAGE to "Backing up IPTV catalog", PROGRESS_FRACTION to 0.1f))
            setForeground(CatalogScanNotification.transferForeground(applicationContext, CatalogOperations.BACKUP, "Uploading catalog"))
            notificationJob = CoroutineScope(currentCoroutineContext()).launch {
                while (isActive) {
                    setForeground(CatalogScanNotification.transferForeground(applicationContext, CatalogOperations.BACKUP, "Uploading catalog"))
                    delay(2_000L)
                }
            }
            val selectedId = inputData.getString(SearchMetadataSyncScheduler.PROFILE_ID)
            val selectedProfile = selectedId?.let { id ->
                ProfileStore(applicationContext).profiles.first().firstOrNull { CatalogScanPreferences.id(it) == id }
            }
            val selectedType = inputData.getString(SearchMetadataSyncScheduler.MEDIA_TYPE)?.let(CatalogType::valueOf)
            when {
                selectedProfile == null -> CatalogBackupManager(applicationContext).uploadAll()
                selectedType != null -> CatalogBackupManager(applicationContext).upload(selectedProfile, selectedType)
                else -> CatalogBackupManager(applicationContext).upload(selectedProfile)
            }
            Result.success()
        } catch (held: CatalogOperationHeld) { Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: IllegalArgumentException) {
            CatalogPreferences.status(applicationContext, invalid.message ?: "Configure catalog backup in Settings")
            CatalogOperations.message(applicationContext, CatalogOperations.BACKUP, "Backup settings are incomplete. See activity for details.")
            Result.failure()
        } catch (error: Exception) {
            CatalogPreferences.status(applicationContext, error.message ?: "Catalog backup will retry")
            if (!CatalogOperations.held(applicationContext, CatalogOperations.BACKUP))
                CatalogOperations.message(applicationContext, CatalogOperations.BACKUP, "Backup failed; saved files retained. Retry scheduled. See activity for details.")
            Result.retry()
        } finally {
            notificationJob?.cancelAndJoin()
        }
    }
    companion object {
        private val backupMutex = Mutex()
        const val PROGRESS_MESSAGE = "sync_message"
        const val PROGRESS_FRACTION = "sync_fraction"
    }
}

class CatalogRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = restoreMutex.withLock {
        val id = inputData.getString(SearchMetadataSyncScheduler.PROFILE_ID) ?: return@withLock Result.failure()
        val profile = ProfileStore(applicationContext).profiles.first()
            .firstOrNull { CatalogScanPreferences.id(it) == id } ?: return@withLock Result.failure()
        var notificationJob: Job? = null
        try {
            CatalogOperations.check(applicationContext, CatalogOperations.RESTORE)
            setForeground(CatalogScanNotification.transferForeground(applicationContext, CatalogOperations.RESTORE, "Restoring catalog"))
            notificationJob = CoroutineScope(currentCoroutineContext()).launch {
                while (isActive) {
                    setForeground(CatalogScanNotification.transferForeground(applicationContext, CatalogOperations.RESTORE, "Restoring catalog"))
                    delay(2_000L)
                }
            }
            val type = inputData.getString(SearchMetadataSyncScheduler.MEDIA_TYPE)?.let(CatalogType::valueOf)
            if (type == null) CatalogBackupManager(applicationContext).restore(profile)
            else CatalogBackupManager(applicationContext).restore(profile, type)
            Result.success()
        } catch (_: CatalogOperationHeld) {
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CatalogOperations.message(applicationContext, CatalogOperations.RESTORE,
                "Restore failed; completed merges are retained. ${error.message.orEmpty()}")
            Result.retry()
        } finally {
            notificationJob?.cancelAndJoin()
        }
    }
    companion object { private val restoreMutex = Mutex() }
}
