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
    const val CHECK_ONLY = "check_only"
    const val UPDATE_CHECK_TAG = "niktv-catalog-update-check"
    internal const val FULL_REFRESH_TAG = "niktv-catalog-full-refresh"
    internal const val RESUME_SCAN_TAG = "niktv-catalog-resume"
    internal const val MEDIA_TYPE_TAG_PREFIX = "niktv-catalog-type:"
    fun configureProfile(context: Context, profile: PortalProfile) {
        val id = CatalogScanPreferences.id(profile)
        val hours = CatalogScanPreferences.hours(context, id)
        val manager = WorkManager.getInstance(context)
        val name = "$REFRESH-$id"
        if (hours == 0) manager.cancelUniqueWork(name)
        else manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PeriodicCatalogScanWorker>(hours.toLong(), TimeUnit.HOURS)
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .setInputData(workDataOf(PROFILE_ID to id, FULL_SCAN to true))
                .addTag(FULL_REFRESH_TAG)
                .setConstraints(constraints()).build())
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
        if (!resume) CatalogScanPreferences.activeType(context, id, null)
        val targetType = if (resume) CatalogScanPreferences.activeType(context, id) else null
        if (resume) CatalogOperations.control(context, CatalogOperations.scan(id), "Ready")
        if (CatalogOperations.held(context, CatalogOperations.scan(id))) return
        val input = Data.Builder().putString(PROFILE_ID, id).putBoolean(RESUME_ONLY, resume)
            .apply { targetType?.let { putString(MEDIA_TYPE, it) } }.build()
        val work = OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
            .setInputData(input).setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                if (resume) addTag(RESUME_SCAN_TAG)
                targetType?.let { addTag("$MEDIA_TYPE_TAG_PREFIX$it") }
            }.build()
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id",
            if (resume) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, work)
        CatalogScanPreferences.status(context, id, "Scan requested; queued or already running. Existing listings remain searchable.")
        CatalogOperations.message(context, CatalogOperations.scan(id), "Scan queued. Waiting for network/background execution; saved listings remain available.")
        BackupActivityLog.record(context, "Catalog scan · ${profile.name}", "Requested")
    }

    fun fullScan(context: Context, profile: PortalProfile) {
        val id = CatalogScanPreferences.id(profile)
        CatalogScanPreferences.activeType(context, id, null)
        listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES).forEach {
            CatalogScanPreferences.refreshStartedAt(context, id, it.name, 0L)
        }
        CatalogOperations.control(context, CatalogOperations.scan(id), "Ready")
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(workDataOf(PROFILE_ID to id, FULL_SCAN to true))
                .addTag(FULL_REFRESH_TAG)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        CatalogScanPreferences.status(context, id, "Full scan requested. Existing records stay available while every provider page is refreshed.")
        CatalogOperations.message(context, CatalogOperations.scan(id),
            "Full scan queued. Live TV, Movies and Series will be refreshed from the beginning.")
        BackupActivityLog.record(context, "Catalog scan · ${profile.name}", "Full scan requested")
    }

    fun refreshType(context: Context, profile: PortalProfile, type: CatalogType) {
        val id = CatalogScanPreferences.id(profile)
        CatalogScanPreferences.activeType(context, id, type.name)
        CatalogScanPreferences.refreshStartedAt(context, id, type.name, 0L)
        CatalogOperations.control(context, CatalogOperations.scan(id), "Ready")
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(workDataOf(PROFILE_ID to id, MEDIA_TYPE to type.name, FULL_SCAN to true))
                .addTag(FULL_REFRESH_TAG)
                .addTag("$MEDIA_TYPE_TAG_PREFIX${type.name}")
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        CatalogScanPreferences.status(context, id,
            "${type.title} refresh requested. Existing records stay available while provider pages are revisited.")
        CatalogOperations.message(context, CatalogOperations.scan(id), "${type.title} refresh queued.")
        BackupActivityLog.record(context, "Catalog scan · ${profile.name}", "${type.title} refresh requested")
    }

    fun resumeType(context: Context, profile: PortalProfile, type: CatalogType) {
        val id = CatalogScanPreferences.id(profile)
        CatalogScanPreferences.activeType(context, id, type.name)
        CatalogOperations.control(context, CatalogOperations.scan(id), "Ready")
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(workDataOf(PROFILE_ID to id, MEDIA_TYPE to type.name, RESUME_ONLY to true))
                .addTag(RESUME_SCAN_TAG)
                .addTag("$MEDIA_TYPE_TAG_PREFIX${type.name}")
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        CatalogScanPreferences.status(context, id, "Resuming ${type.title} from its last committed provider page.")
        CatalogOperations.message(context, CatalogOperations.scan(id), "${type.title} resume queued.")
        BackupActivityLog.record(context, "Catalog scan · ${profile.name}", "${type.title} resume requested")
    }

    fun checkForUpdates(context: Context, profile: PortalProfile, type: CatalogType? = null) {
        val id = CatalogScanPreferences.id(profile)
        if (CatalogOperations.held(context, CatalogOperations.scan(id))) return
        val input = Data.Builder().putString(PROFILE_ID, id).putBoolean(CHECK_ONLY, true)
            .apply { type?.let { putString(MEDIA_TYPE, it.name) } }.build()
        val work = OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
            .setInputData(input)
            .addTag(UPDATE_CHECK_TAG)
            .apply { type?.let { addTag("$MEDIA_TYPE_TAG_PREFIX${it.name}") } }
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$REFRESH-now-$id",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            work
        )
        val label = type?.title ?: "Live TV, Movies and Series"
        CatalogOperations.message(context, CatalogOperations.scan(id), "Update check queued for $label.")
        BackupActivityLog.record(context, "Catalog update check · ${profile.name}", "Queued", label)
    }

    fun resumeRestored(context: Context, profile: PortalProfile) {
        val id = CatalogScanPreferences.id(profile)
        CatalogScanPreferences.activeType(context, id, null)
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
                .addTag(RESUME_SCAN_TAG)
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
                val pending = manual.lastOrNull { !it.state.isFinished }
                    ?: periodic.lastOrNull { !it.state.isFinished }
                val pendingTags = pending?.tags.orEmpty()
                val targetType = pendingTags.firstOrNull { it.startsWith(MEDIA_TYPE_TAG_PREFIX) }
                    ?.removePrefix(MEDIA_TYPE_TAG_PREFIX)
                    ?: CatalogScanPreferences.activeType(app, id)
                val checkOnly = UPDATE_CHECK_TAG in pendingTags
                val fullScan = FULL_REFRESH_TAG in pendingTags
                val input = Data.Builder().putString(PROFILE_ID, id)
                    .putBoolean(CHECK_ONLY, checkOnly)
                    .putBoolean(FULL_SCAN, fullScan)
                    .putBoolean(RESUME_ONLY, !checkOnly && !fullScan)
                    .apply { targetType?.let { putString(MEDIA_TYPE, it) } }.build()
                val replacement = OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                    .setInputData(input)
                    .setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .apply {
                        if (checkOnly) addTag(UPDATE_CHECK_TAG)
                        if (fullScan) addTag(FULL_REFRESH_TAG)
                        if (!checkOnly && !fullScan) addTag(RESUME_SCAN_TAG)
                        targetType?.let { addTag("$MEDIA_TYPE_TAG_PREFIX$it") }
                    }.build()
                manager.enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.REPLACE,
                    replacement)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                CatalogOperations.message(app, CatalogOperations.scan(id), "Could not reschedule the scan. Saved progress is retained; try again.")
            }
        }
    }

    fun continueScan(context: Context, id: String, type: CatalogType? = null) {
        val input = Data.Builder().putString(PROFILE_ID, id).putBoolean(RESUME_ONLY, true)
            .apply { type?.let { putString(MEDIA_TYPE, it.name) } }.build()
        WorkManager.getInstance(context).enqueueUniqueWork("$REFRESH-now-$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PeriodicCatalogScanWorker>()
                .setInputData(input).setInitialDelay(10, TimeUnit.SECONDS)
                .addTag(RESUME_SCAN_TAG)
                .apply { type?.let { addTag("$MEDIA_TYPE_TAG_PREFIX${it.name}") } }
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
        val selectedType = inputData.getString(SearchMetadataSyncScheduler.MEDIA_TYPE)
            ?.let { runCatching { CatalogType.valueOf(it) }.getOrNull() }
        val checkOnly = inputData.getBoolean(SearchMetadataSyncScheduler.CHECK_ONLY, false)
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
            setForeground(CatalogScanNotification.foreground(context, id,
                if (checkOnly) "Checking catalog updates. You can leave the app."
                else "Preparing catalog scan. You can leave the app."))
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

            if (checkOnly) {
                val requestedTypes = selectedType?.let(::listOf) ?: types
                val checker = CatalogUpdateChecker(context)
                requestedTypes.forEachIndexed { index, type ->
                    CatalogOperations.check(context, control)
                    CatalogOperations.progress(context, control, CatalogOperationProgress(
                        phase = "Checking provider updates",
                        mediaType = type.title,
                        mediaPosition = index + 1,
                        mediaCount = requestedTypes.size
                    ))
                    checker.check(session, type,
                        checkControl = { CatalogOperations.check(context, control) }) { stage ->
                            CatalogOperations.message(context, control, stage)
                        }
                }
                val checkedRows = CatalogRepository(context).observeUpdateStatuses(profile).first()
                    .filter { row -> requestedTypes.any { it.name == row.type } }
                val updates = checkedRows.count {
                    it.updateState in setOf(CatalogUpdateState.UPDATE_AVAILABLE, CatalogUpdateState.CHANGED)
                }
                val unresolved = checkedRows.count {
                    it.updateState in setOf(CatalogUpdateState.INCOMPLETE, CatalogUpdateState.NOT_SCANNED, CatalogUpdateState.FAILED)
                }
                val message = when {
                    updates > 0 -> "Update check complete · $updates ${if (updates == 1) "media type needs" else "media types need"} attention."
                    unresolved > 0 -> "Update check complete · $unresolved ${if (unresolved == 1) "media type could" else "media types could"} not be compared."
                    else -> "Update check complete · No changes detected."
                }
                CatalogScanPreferences.status(context, id, message)
                CatalogOperations.message(context, control, message)
                CatalogOperations.progress(context, control, CatalogOperationProgress(phase = "Complete"))
                CatalogScanPreferences.cursor(context, id, -1)
                CatalogScanPreferences.activeType(context, id, null)
                BackupActivityLog.record(context, "Catalog update check · ${profile.name}", "Completed", message)
                return@withLock Result.success()
            }

            val repository = CatalogRepository(context)
            var cursor = CatalogScanPreferences.cursor(context, id)
            val scanTypes: List<CatalogType>
            if (selectedType != null) {
                scanTypes = listOf(selectedType)
                if (fullScan && CatalogScanPreferences.refreshStartedAt(context, id, selectedType.name) == 0L) {
                    val generation = repository.restartScan(profile, selectedType)
                    CatalogOperations.clearPageTotals(context, control, selectedType.name)
                    CatalogScanPreferences.refreshStartedAt(context, id, selectedType.name, generation)
                }
                else if (resumeOnly && repository.scanCheckpoint(profile, selectedType).complete &&
                    CatalogScanPreferences.refreshStartedAt(context, id, selectedType.name) == 0L) {
                    val message = "${selectedType.title} is already complete. Check for provider updates or choose Refresh."
                    CatalogScanPreferences.status(context, id, message)
                    CatalogOperations.message(context, control, message)
                    CatalogScanPreferences.activeType(context, id, null)
                    return@withLock Result.success()
                }
                BackupActivityLog.record(context, operation,
                    if (fullScan) "Started" else "Resumed",
                    "${selectedType.title} only; saved listings stay available.")
            } else if (fullScan) {
                val startingFresh = types.all {
                    CatalogScanPreferences.refreshStartedAt(context, id, it.name) == 0L
                }
                types.forEach { type ->
                    if (CatalogScanPreferences.refreshStartedAt(context, id, type.name) == 0L) {
                        val generation = repository.restartScan(profile, type)
                        CatalogOperations.clearPageTotals(context, control, type.name)
                        CatalogScanPreferences.refreshStartedAt(context, id, type.name, generation)
                    }
                }
                if (startingFresh || cursor < 0) {
                    cursor = 0
                    CatalogScanPreferences.cursor(context, id, cursor)
                }
                scanTypes = types.drop(cursor.coerceAtLeast(0))
                BackupActivityLog.record(context, operation, "Started", "Updating channels, movies and series; saved listings stay available.")
            } else if (resumeOnly && cursor < 0) {
                cursor = repository.resumeScanIndex(profile)
                if (cursor < 0) {
                    CatalogOperations.message(context, control,
                        "The scan is already complete. Check for provider updates or choose Refresh all.")
                    return@withLock Result.success()
                }
                CatalogScanPreferences.cursor(context, id, cursor)
                scanTypes = types.drop(cursor)
                CatalogOperations.message(context, control,
                    "Resuming ${types[cursor].title} from the last saved provider page.")
                BackupActivityLog.record(context, operation, "Resumed",
                    "Continuing ${types[cursor].title} from restored Room page cursors.")
            } else if (cursor < 0) {
                types.forEach { type ->
                    val generation = repository.restartScan(profile, type)
                    CatalogOperations.clearPageTotals(context, control, type.name)
                    CatalogScanPreferences.refreshStartedAt(context, id, type.name, generation)
                }
                cursor = 0
                CatalogScanPreferences.cursor(context, id, cursor)
                scanTypes = types
                BackupActivityLog.record(context, operation, "Started",
                    "Scheduled refresh started from Live TV; saved listings stay available.")
            } else scanTypes = types.drop(cursor)
            val deadline = android.os.SystemClock.elapsedRealtime() + 480_000L
            for (type in scanTypes) {
                CatalogOperations.check(context, control)
                val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val result = SearchCatalogScanner(context).scan(session, type, 2_000L,
                    refreshCompleted = false, timeBudgetMillis = remaining,
                    mediaPosition = if (selectedType == null) types.indexOf(type) + 1 else 1,
                    mediaCount = if (selectedType == null) types.size else 1) { progress ->
                    CatalogScanPreferences.status(context, id,
                        "${type.title} · ${progress.categoryTitle} · category ${progress.categoryPosition}/${progress.categoryCount} · page ${progress.page} · ${progress.discoveredItems} items")
                }
                if (result.deferred || result.failures > 0) {
                    if (result.existingCatalogRetainedAfterEmptyResponse) {
                        val message = "${type.title} returned no categories twice. Existing records were retained; retry after checking the provider category mapping."
                        repository.saveUpdateStatus(CatalogTypeUpdateRow(
                            profile = profile.cacheKey(),
                            type = type.name,
                            state = CatalogUpdateState.FAILED.name,
                            detail = message,
                            checkedAt = System.currentTimeMillis()
                        ))
                        CatalogScanPreferences.status(context, id, message)
                        CatalogOperations.message(context, control, message)
                        CatalogScanPreferences.cursor(context, id, -1)
                        CatalogScanPreferences.activeType(context, id, null)
                        return@withLock Result.success()
                    }
                    CatalogScanPreferences.status(context, id, if (CatalogPlaybackActivity.playing)
                        "Paused during playback; scan progress saved." else if (result.failures > 0)
                        "Some provider pages failed; saved progress will retry automatically." else
                        "Scan progress saved; continuing in the background…")
                    CatalogOperations.message(context, control, CatalogScanPreferences.status(context, id))
                    CatalogOperations.check(context, control)
                    if (result.deferred && !CatalogPlaybackActivity.playing && result.failures == 0) {
                        SearchMetadataSyncScheduler.continueScan(context, id, selectedType)
                        return@withLock Result.success()
                    }
                    return@withLock Result.retry()
                }
                val typeCompletedAt = System.currentTimeMillis()
                val refreshStartedAt = CatalogScanPreferences.refreshStartedAt(context, id, type.name)
                check(repository.reconcileSuccessfulRefresh(
                    profile, type, result.providerCategories, refreshStartedAt,
                    confirmedEmpty = result.providerConfirmedEmpty, at = typeCompletedAt
                )) { "${type.title} provider scan could not be reconciled safely" }
                check(repository.scanCheckpoint(profile, type).complete) {
                    "${type.title} provider scan ended without a complete Room checkpoint"
                }
                check(repository.markTypeSynced(profile, type, typeCompletedAt)) {
                    "${type.title} provider scan could not promote a completed baseline"
                }
                if (selectedType != null) CatalogScanPreferences.refreshStartedAt(context, id, type.name, 0L)
                if (selectedType == null) CatalogScanPreferences.cursor(context, id, types.indexOf(type) + 1)
            }
            CatalogOperations.check(context, control)
            val now = System.currentTimeMillis()
            if (selectedType != null) {
                CatalogScanPreferences.activeType(context, id, null)
                val remainingType = repository.resumeScanIndex(profile)
                // Per-type cards own incomplete/resume state. Keeping a global cursor
                // here would mislabel this successful targeted operation as interrupted.
                CatalogScanPreferences.cursor(context, id, -1)
                if (remainingType < 0) CatalogScanPreferences.completed(context, id, now)
                val message = "Complete · ${selectedType.title} refreshed from the provider."
                CatalogScanPreferences.status(context, id, message)
                CatalogOperations.message(context, control, message)
                CatalogOperations.progress(context, control, CatalogOperationProgress(phase = "Complete", mediaType = selectedType.title))
                BackupActivityLog.record(context, operation, "Completed", "${selectedType.title} refresh completed.")
                if (CatalogPreferences.backupEnabled(context)) {
                    SearchMetadataSyncScheduler.requestNow(context, profile = profile, type = selectedType)
                }
                return@withLock Result.success()
            }
            CatalogScanPreferences.completed(context, id, now)
            CatalogScanPreferences.cursor(context, id, -1)
            CatalogScanPreferences.activeType(context, id, null)
            types.forEach { CatalogScanPreferences.refreshStartedAt(context, id, it.name, 0L) }
            CatalogScanPreferences.status(context, id, "Complete · ${java.util.Date(now)} · Room backup catalog contains channels, movies and series.")
            CatalogOperations.message(context, control, CatalogScanPreferences.status(context, id))
            CatalogOperations.progress(context, control, CatalogOperationProgress(phase = "Complete"))
            BackupActivityLog.record(context, operation, "Completed", "Room backup catalog is ready to inspect or upload; app browsing remains provider/cache based.")
            if (CatalogPreferences.backupEnabled(context)) SearchMetadataSyncScheduler.requestNow(context, profile = profile)
            Result.success()
        } catch (held: CatalogOperationHeld) {
            BackupActivityLog.record(context, operation, held.state, "Committed pages retained. Resume from Catalog & backup.")
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (checkOnly) {
                val failedTypes = selectedType?.let(::listOf)
                    ?: listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
                val repository = CatalogRepository(context)
                failedTypes.forEach { type ->
                    repository.saveUpdateStatus(CatalogTypeUpdateRow(
                        profile = profile.cacheKey(), type = type.name,
                        state = CatalogUpdateState.FAILED.name,
                        detail = "Could not connect to the provider. Try the check again.",
                        checkedAt = System.currentTimeMillis()
                    ))
                }
                CatalogOperations.message(context, control, "Provider update check failed. Try again.")
                return@withLock Result.failure()
            }
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
        } catch (memory: OutOfMemoryError) {
            val message = "Backup ran out of memory while preparing a catalog snapshot. Install the streaming-backup update and retry."
            CatalogPreferences.status(applicationContext, message)
            CatalogOperations.message(applicationContext, CatalogOperations.BACKUP, message)
            CatalogOperations.progress(applicationContext, CatalogOperations.BACKUP, CatalogOperationProgress(phase = "Failed"))
            BackupActivityLog.record(applicationContext, "IPTV catalog backup", "Failed", message)
            Result.failure()
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
