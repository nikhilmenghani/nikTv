package com.nikhil.niktv.data

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalProfile
import java.net.URI
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Serializable
enum class LiveTvRecordingStatus {
    SCHEDULED,
    STARTING,
    RECORDING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Serializable
data class LiveTvRecordingChannel(
    val channelId: String,
    val channelTitle: String,
    val logo: String? = null,
    val command: String? = null,
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val epgChannelId: String? = null,
    val streamType: String? = null
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = channelId,
        title = channelTitle,
        logo = logo,
        command = command,
        portalCategoryId = categoryId,
        epgChannelId = epgChannelId,
        streamType = streamType
    )

    companion object {
        fun from(item: MediaItem, categoryTitle: String? = null) =
            LiveTvRecordingChannel(
                channelId = item.id,
                channelTitle = item.title,
                logo = item.logo,
                command = item.command,
                categoryId = item.portalCategoryId,
                categoryTitle = categoryTitle,
                epgChannelId = item.epgChannelId,
                streamType = item.streamType
            )
    }
}

@Serializable
data class LiveTvRecording(
    val id: String,
    val profile: PortalProfile,
    val profileKey: String,
    val title: String,
    val channel: LiveTvRecordingChannel,
    val scheduledStartMillis: Long,
    val scheduledEndMillis: Long? = null,
    val maximumDurationMillis: Long? = null,
    val state: LiveTvRecordingStatus = LiveTvRecordingStatus.SCHEDULED,
    val outputUri: String? = null,
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long = 0L,
    val recordedDurationMillis: Long = 0L,
    val bytesWritten: Long = 0L,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val exactAlarm: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val isActive: Boolean
        get() = state == LiveTvRecordingStatus.STARTING ||
            state == LiveTvRecordingStatus.RECORDING ||
            state == LiveTvRecordingStatus.PAUSED

    val isScheduled: Boolean
        get() = state == LiveTvRecordingStatus.SCHEDULED

    val isPaused: Boolean
        get() = state == LiveTvRecordingStatus.PAUSED

    val isTerminal: Boolean
        get() = state == LiveTvRecordingStatus.COMPLETED ||
            state == LiveTvRecordingStatus.FAILED
}

data class RecordedLiveTvMedia(
    val uri: Uri,
    val title: String,
    val sizeBytes: Long,
    val createdAtMillis: Long
)

private object LiveTvRecordingStore {
    private const val PREFS = "live_tv_recordings_v2"
    private const val KEY_PREFIX = "recording:"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutableRecords = MutableStateFlow<List<LiveTvRecording>>(emptyList())
    private val publicRecords = mutableRecords.asStateFlow()
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mutableRecords.value = prefs.all.entries.mapNotNull { (key, raw) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val encoded = raw as? String ?: return@mapNotNull null
            runCatching { json.decodeFromString<LiveTvRecording>(encoded) }.getOrNull()
        }.sortedWith(
            compareByDescending<LiveTvRecording> { it.updatedAtMillis }
                .thenByDescending { it.createdAtMillis }
        )
        initialized = true
    }

    fun records(context: Context): StateFlow<List<LiveTvRecording>> {
        initialize(context)
        return publicRecords
    }

    fun get(context: Context, id: String): LiveTvRecording? {
        initialize(context)
        return mutableRecords.value.firstOrNull { it.id == id }
    }

    fun all(context: Context): List<LiveTvRecording> {
        initialize(context)
        return mutableRecords.value
    }

    @Synchronized
    fun upsert(
        context: Context,
        recording: LiveTvRecording,
        persist: Boolean = true
    ) {
        initialize(context)
        val next = mutableRecords.value
            .filterNot { it.id == recording.id }
            .plus(recording)
            .sortedWith(
                compareByDescending<LiveTvRecording> { it.updatedAtMillis }
                    .thenByDescending { it.createdAtMillis }
            )
        mutableRecords.value = next
        if (persist) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PREFIX + recording.id, json.encodeToString(recording))
                .commit()
        }
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        initialize(context)
        mutableRecords.value = mutableRecords.value.filterNot { it.id == id }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + id)
            .commit()
    }
}

object LiveTvRecordingManager {
    private const val MIN_FREE_BYTES = 64L * 1024L * 1024L
    private const val PERIODIC_RECOVERY_NAME = "niktv-live-recording-recovery"
    private val managerInitialized = AtomicBoolean(false)
    private val recoveryConfigured = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (!managerInitialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        LiveTvRecordingStore.initialize(appContext)
        ensurePeriodicRecovery(appContext)
        LiveTvRecordingStore.all(appContext)
            .filter { it.isScheduled && it.scheduledStartMillis > System.currentTimeMillis() }
            .forEach { recording ->
                val exact = LiveTvRecordingScheduler.schedule(appContext, recording)
                if (exact != recording.exactAlarm) {
                    LiveTvRecordingStore.upsert(
                        appContext,
                        recording.copy(
                            exactAlarm = exact,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                }
            }
        enqueueRecoveryNow(appContext)
    }

    fun records(context: Context): StateFlow<List<LiveTvRecording>> {
        val appContext = context.applicationContext
        initialize(appContext)
        return LiveTvRecordingStore.records(appContext)
    }

    fun startNow(
        context: Context,
        profile: PortalProfile,
        channel: MediaItem,
        title: String = channel.title,
        categoryTitle: String? = null,
        scheduledEndMillis: Long? = null,
        maximumDurationMillis: Long? = null
    ): String {
        val appContext = context.applicationContext
        initialize(appContext)
        val now = System.currentTimeMillis()
        val recording = LiveTvRecording(
            id = UUID.randomUUID().toString(),
            profile = profile,
            profileKey = profile.cacheKey(),
            title = title.ifBlank { channel.title.ifBlank { "Live TV" } },
            channel = LiveTvRecordingChannel.from(channel, categoryTitle),
            scheduledStartMillis = now,
            scheduledEndMillis = scheduledEndMillis?.takeIf { it > now },
            maximumDurationMillis = maximumDurationMillis?.takeIf { it > 0L },
            state = LiveTvRecordingStatus.STARTING
        )
        val storageProblem = storageProblem(appContext)
        if (storageProblem != null) {
            LiveTvRecordingStore.upsert(
                appContext,
                recording.copy(
                    state = LiveTvRecordingStatus.FAILED,
                    endedAtMillis = now,
                    errorCode = "STORAGE",
                    errorMessage = storageProblem,
                    updatedAtMillis = now
                )
            )
            return recording.id
        }

        LiveTvRecordingStore.upsert(appContext, recording)
        dispatchStart(
            appContext,
            recording.id,
            fallbackState = LiveTvRecordingStatus.FAILED
        )
        return recording.id
    }

    fun schedule(
        context: Context,
        profile: PortalProfile,
        channel: MediaItem,
        title: String = channel.title,
        startAtMillis: Long,
        endAtMillis: Long? = null,
        categoryTitle: String? = null,
        maximumDurationMillis: Long? = null
    ): String {
        val appContext = context.applicationContext
        initialize(appContext)
        val now = System.currentTimeMillis()
        if (startAtMillis <= now + 1_000L) {
            return startNow(
                context = appContext,
                profile = profile,
                channel = channel,
                title = title,
                categoryTitle = categoryTitle,
                scheduledEndMillis = endAtMillis,
                maximumDurationMillis = maximumDurationMillis
            )
        }

        val recording = LiveTvRecording(
            id = UUID.randomUUID().toString(),
            profile = profile,
            profileKey = profile.cacheKey(),
            title = title.ifBlank { channel.title.ifBlank { "Live TV" } },
            channel = LiveTvRecordingChannel.from(channel, categoryTitle),
            scheduledStartMillis = startAtMillis,
            scheduledEndMillis = endAtMillis?.takeIf { it > startAtMillis },
            maximumDurationMillis = maximumDurationMillis?.takeIf { it > 0L },
            state = LiveTvRecordingStatus.SCHEDULED
        )
        LiveTvRecordingStore.upsert(appContext, recording)
        val exact = LiveTvRecordingScheduler.schedule(appContext, recording)
        LiveTvRecordingStore.upsert(
            appContext,
            recording.copy(
                exactAlarm = exact,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        return recording.id
    }

    fun startScheduledNow(context: Context, id: String) {
        val appContext = context.applicationContext
        val recording = LiveTvRecordingStore.get(appContext, id) ?: return
        if (!recording.isScheduled) return

        LiveTvRecordingScheduler.cancel(appContext, id)
        val now = System.currentTimeMillis()
        val storageProblem = storageProblem(appContext)
        if (storageProblem != null) {
            LiveTvRecordingStore.upsert(
                appContext,
                recording.copy(
                    state = LiveTvRecordingStatus.FAILED,
                    endedAtMillis = now,
                    errorCode = "STORAGE",
                    errorMessage = storageProblem,
                    updatedAtMillis = now
                )
            )
            return
        }

        val updated = recording.copy(
            state = LiveTvRecordingStatus.STARTING,
            scheduledStartMillis = now,
            exactAlarm = false,
            errorCode = null,
            errorMessage = null,
            updatedAtMillis = now
        )
        LiveTvRecordingStore.upsert(appContext, updated)
        dispatchStart(
            appContext,
            id,
            fallbackState = LiveTvRecordingStatus.SCHEDULED
        )
    }

    fun pause(context: Context, id: String) =
        sendControl(context, LiveTvRecordingService.ACTION_PAUSE, id)

    fun resume(context: Context, id: String) =
        sendControl(context, LiveTvRecordingService.ACTION_RESUME, id)

    fun stop(context: Context, id: String) {
        val appContext = context.applicationContext
        val recording = LiveTvRecordingStore.get(appContext, id) ?: return
        if (recording.isScheduled) {
            LiveTvRecordingScheduler.cancel(appContext, id)
            LiveTvRecordingStore.remove(appContext, id)
            return
        }
        if (recording.isActive) {
            sendControl(appContext, LiveTvRecordingService.ACTION_STOP, id)
        }
    }

    fun extend(context: Context, id: String, extensionMillis: Long) {
        if (extensionMillis <= 0L) return
        val appContext = context.applicationContext
        val recording = LiveTvRecordingStore.get(appContext, id) ?: return
        when {
            recording.isScheduled ->
                extendPersisted(appContext, id, extensionMillis)
            recording.isActive ->
                sendExtendControl(
                    appContext,
                    id,
                    extensionMillis
                )
        }
    }

    internal fun extendPersisted(
        context: Context,
        id: String,
        extensionMillis: Long
    ): Boolean {
        if (extensionMillis <= 0L) return false
        val recording = LiveTvRecordingStore.get(context, id) ?: return false
        if (!recording.isActive && !recording.isScheduled) return false

        val updated = when {
            recording.scheduledEndMillis != null ->
                recording.copy(
                    scheduledEndMillis = recording.scheduledEndMillis + extensionMillis
                )
            recording.maximumDurationMillis != null ->
                recording.copy(
                    maximumDurationMillis =
                        recording.maximumDurationMillis + extensionMillis
                )
            recording.isActive ->
                recording.copy(
                    maximumDurationMillis =
                        recording.recordedDurationMillis + extensionMillis
                )
            else ->
                recording.copy(
                    scheduledEndMillis =
                        recording.scheduledStartMillis + extensionMillis
                )
        }.copy(updatedAtMillis = System.currentTimeMillis())

        LiveTvRecordingStore.upsert(context, updated)
        return true
    }

    fun delete(context: Context, id: String): Boolean {
        val appContext = context.applicationContext
        val recording = LiveTvRecordingStore.get(appContext, id) ?: return false
        if (recording.isActive) return false

        LiveTvRecordingScheduler.cancel(appContext, id)
        recording.outputUri?.let { encoded ->
            runCatching {
                appContext.contentResolver.delete(Uri.parse(encoded), null, null)
            }
        }
        LiveTvRecordingStore.remove(appContext, id)
        return true
    }

    fun exactSchedulingAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return alarmManager?.canScheduleExactAlarms() == true
    }

    fun statusText(recording: LiveTvRecording): String = when (recording.state) {
        LiveTvRecordingStatus.SCHEDULED -> {
            val time = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
            ).format(Date(recording.scheduledStartMillis))
            "Scheduled · $time"
        }
        LiveTvRecordingStatus.STARTING ->
            "Starting · ${formatDuration(recording.recordedDurationMillis)}"
        LiveTvRecordingStatus.RECORDING ->
            "REC · ${formatDuration(recording.recordedDurationMillis)} · ${formatBytes(recording.bytesWritten)}"
        LiveTvRecordingStatus.PAUSED ->
            "Paused · ${formatDuration(recording.recordedDurationMillis)} · ${formatBytes(recording.bytesWritten)}"
        LiveTvRecordingStatus.COMPLETED ->
            "Completed · ${formatDuration(recording.recordedDurationMillis)} · ${formatBytes(recording.bytesWritten)}"
        LiveTvRecordingStatus.FAILED ->
            "Failed${recording.errorMessage?.let { " · $it" }.orEmpty()}"
    }

    fun formatDuration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1_000L
        return "%02d:%02d:%02d".format(
            Locale.US,
            seconds / 3_600L,
            seconds / 60L % 60L,
            seconds % 60L
        )
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L ->
            "%.1f GB".format(Locale.US, bytes / 1_073_741_824.0)
        bytes >= 1_048_576L ->
            "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
        bytes >= 1_024L ->
            "%.1f KB".format(Locale.US, bytes / 1_024.0)
        else -> "$bytes B"
    }

    fun legacyRecordings(context: Context): List<RecordedLiveTvMedia> {
        val collection = recordingCollectionUri()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/NikTV/Recordings%")
        } else {
            selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            args = arrayOf("%/Download/NikTV/Recordings/%")
        }
        return runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                buildList {
                    val idIndex =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns.DISPLAY_NAME
                        )
                    val sizeIndex =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateIndex =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns.DATE_ADDED
                        )
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(
                            collection,
                            cursor.getLong(idIndex)
                        )
                        add(
                            RecordedLiveTvMedia(
                                uri = uri,
                                title = cursor.getString(nameIndex)
                                    .substringBeforeLast('.'),
                                sizeBytes = cursor.getLong(sizeIndex)
                                    .coerceAtLeast(0L),
                                createdAtMillis = cursor.getLong(dateIndex)
                                    .coerceAtLeast(0L) * 1_000L
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun deleteLegacyRecording(
        context: Context,
        recording: RecordedLiveTvMedia
    ): Boolean =
        runCatching {
            context.contentResolver.delete(recording.uri, null, null) > 0
        }.getOrDefault(false)

    internal fun recover(context: Context) {
        val appContext = context.applicationContext
        LiveTvRecordingStore.initialize(appContext)
        val now = System.currentTimeMillis()

        LiveTvRecordingStore.all(appContext).forEach { recording ->
            when {
                recording.isScheduled -> {
                    val end = recording.scheduledEndMillis
                    if (end != null && end <= now) {
                        LiveTvRecordingStore.upsert(
                            appContext,
                            recording.copy(
                                state = LiveTvRecordingStatus.FAILED,
                                endedAtMillis = now,
                                errorCode = "MISSED_WINDOW",
                                errorMessage =
                                    "The scheduled recording window ended before recovery.",
                                updatedAtMillis = now
                            )
                        )
                    } else if (recording.scheduledStartMillis <= now + 30_000L) {
                        startScheduledNow(appContext, recording.id)
                    } else {
                        val exact =
                            LiveTvRecordingScheduler.schedule(
                                appContext,
                                recording
                            )
                        if (exact != recording.exactAlarm) {
                            LiveTvRecordingStore.upsert(
                                appContext,
                                recording.copy(
                                    exactAlarm = exact,
                                    updatedAtMillis = now
                                )
                            )
                        }
                    }
                }
                recording.isActive &&
                    recording.id !in LiveTvRecordingService.runningRecordingIds -> {
                    val ended = recording.scheduledEndMillis?.let { it <= now } == true
                    if (ended) {
                        LiveTvRecordingStore.upsert(
                            appContext,
                            recording.copy(
                                state =
                                    if (
                                        recording.outputUri != null &&
                                        recording.bytesWritten > 0L
                                    ) {
                                        LiveTvRecordingStatus.COMPLETED
                                    } else {
                                        LiveTvRecordingStatus.FAILED
                                    },
                                endedAtMillis = now,
                                errorCode =
                                    if (recording.bytesWritten > 0L) null
                                    else "MISSED_WINDOW",
                                errorMessage =
                                    if (recording.bytesWritten > 0L) null
                                    else "The recording window ended before recovery.",
                                updatedAtMillis = now
                            )
                        )
                    } else {
                        dispatchStart(
                            appContext,
                            recording.id,
                            fallbackState = recording.state
                        )
                    }
                }
            }
        }
    }

    internal fun handleAlarm(context: Context, id: String) {
        val appContext = context.applicationContext
        val recording = LiveTvRecordingStore.get(appContext, id) ?: return
        if (!recording.isScheduled) return

        val now = System.currentTimeMillis()
        if (recording.scheduledStartMillis > now + 5_000L) {
            LiveTvRecordingScheduler.schedule(appContext, recording)
            return
        }
        startScheduledNow(appContext, id)
    }

    internal fun enqueueRecoveryNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "niktv-live-recording-recovery-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LiveTvRecordingRecoveryWorker>().build()
        )
    }

    private fun ensurePeriodicRecovery(context: Context) {
        if (!recoveryConfigured.compareAndSet(false, true)) return
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                PERIODIC_RECOVERY_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<LiveTvRecordingRecoveryWorker>(
                    15,
                    TimeUnit.MINUTES
                ).build()
            )
    }

    private fun dispatchStart(
        context: Context,
        id: String,
        fallbackState: LiveTvRecordingStatus
    ): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, LiveTvRecordingService::class.java)
                    .setAction(LiveTvRecordingService.ACTION_START)
                    .putExtra(LiveTvRecordingService.EXTRA_RECORDING_ID, id)
            )
            true
        }.getOrElse { error ->
            val current = LiveTvRecordingStore.get(appContext, id)
            if (current != null) {
                LiveTvRecordingStore.upsert(
                    appContext,
                    current.copy(
                        state = fallbackState,
                        errorCode = "SERVICE_START",
                        errorMessage =
                            error.message ?: "Unable to start recording service",
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            if (fallbackState == LiveTvRecordingStatus.SCHEDULED) {
                enqueueRecoveryNow(appContext)
            }
            false
        }
    }

    private fun sendControl(context: Context, action: String, id: String) {
        val appContext = context.applicationContext
        runCatching {
            appContext.startService(
                Intent(appContext, LiveTvRecordingService::class.java)
                    .setAction(action)
                    .putExtra(LiveTvRecordingService.EXTRA_RECORDING_ID, id)
            )
        }.onFailure { error ->
            val current = LiveTvRecordingStore.get(appContext, id) ?: return@onFailure
            LiveTvRecordingStore.upsert(
                appContext,
                current.copy(
                    errorCode = "CONTROL",
                    errorMessage = error.message ?: "Recording control failed",
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    private fun sendExtendControl(
        context: Context,
        id: String,
        extensionMillis: Long
    ) {
        runCatching {
            context.startService(
                Intent(context, LiveTvRecordingService::class.java)
                    .setAction(LiveTvRecordingService.ACTION_EXTEND)
                    .putExtra(
                        LiveTvRecordingService.EXTRA_RECORDING_ID,
                        id
                    )
                    .putExtra(
                        LiveTvRecordingService.EXTRA_EXTENSION_MILLIS,
                        extensionMillis
                    )
            )
        }.onFailure { error ->
            val current = LiveTvRecordingStore.get(context, id)
                ?: return@onFailure
            LiveTvRecordingStore.upsert(
                context,
                current.copy(
                    errorCode = "CONTROL",
                    errorMessage =
                        error.message ?: "Recording extension failed",
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    internal fun storageProblem(context: Context): String? {
        if (
            Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return "Storage permission is required to save Live TV recordings."
        }

        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return "External media storage is not currently available."
        }

        val available = runCatching {
            StatFs(Environment.getExternalStorageDirectory().absolutePath)
                .availableBytes
        }.getOrDefault(0L)

        if (available in 1 until MIN_FREE_BYTES) {
            return "At least ${formatBytes(MIN_FREE_BYTES)} of free storage is required."
        }
        return null
    }
}

private object LiveTvRecordingScheduler {
    private const val BACKUP_PREFIX = "niktv-live-recording-backup:"

    fun schedule(context: Context, recording: LiveTvRecording): Boolean {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            ?: return false
        val pending = alarmPendingIntent(appContext, recording.id)
        val triggerAt =
            recording.scheduledStartMillis.coerceAtLeast(
                System.currentTimeMillis() + 1_000L
            )
        var exact = false

        try {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending
                )
                exact = true
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pending
            )
            exact = false
        }

        val delayMillis =
            (recording.scheduledStartMillis - System.currentTimeMillis())
                .coerceAtLeast(0L)
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            BACKUP_PREFIX + recording.id,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LiveTvRecordingRecoveryWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
        )
        return exact
    }

    fun cancel(context: Context, id: String) {
        val appContext = context.applicationContext
        appContext.getSystemService(AlarmManager::class.java)
            ?.cancel(alarmPendingIntent(appContext, id))
        WorkManager.getInstance(appContext)
            .cancelUniqueWork(BACKUP_PREFIX + id)
    }

    private fun alarmPendingIntent(
        context: Context,
        id: String
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            stableRequestCode(id, 41),
            Intent(context, LiveTvRecordingAlarmReceiver::class.java)
                .setAction(LiveTvRecordingService.ACTION_START)
                .putExtra(LiveTvRecordingService.EXTRA_RECORDING_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class LiveTvRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id =
            intent?.getStringExtra(LiveTvRecordingService.EXTRA_RECORDING_ID)
                .orEmpty()
        if (id.isNotBlank()) {
            LiveTvRecordingManager.handleAlarm(context, id)
        }
    }
}

class LiveTvRecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                LiveTvRecordingManager.initialize(context)
        }
    }
}

class LiveTvRecordingRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        runCatching {
            LiveTvRecordingManager.recover(applicationContext)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
}

class LiveTvRecordingService : Service() {
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimes =
        ConcurrentHashMap<String, RunningRecording>()
    @Volatile private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveTvRecordingStore.initialize(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Live TV recordings",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val id = intent
            ?.getStringExtra(EXTRA_RECORDING_ID)
            .orEmpty()
        when (intent?.action) {
            ACTION_START -> if (id.isNotBlank()) startRuntime(id)
            ACTION_STOP -> if (id.isNotBlank()) stopRuntime(id)
            ACTION_PAUSE -> if (id.isNotBlank()) setPaused(id, true)
            ACTION_RESUME -> if (id.isNotBlank()) setPaused(id, false)
            ACTION_EXTEND -> if (id.isNotBlank()) {
                LiveTvRecordingManager.extendPersisted(
                    this,
                    id,
                    intent.getLongExtra(
                        EXTRA_EXTENSION_MILLIS,
                        EXTEND_STEP_MILLIS
                    )
                )
                LiveTvRecordingStore.get(this, id)?.let(::notifyRecording)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRuntime(id: String) {
        if (runtimes.containsKey(id)) return
        val stored = LiveTvRecordingStore.get(this, id) ?: return
        if (stored.isTerminal) return

        if (
            stored.isScheduled &&
            stored.scheduledStartMillis > System.currentTimeMillis() + 5_000L
        ) {
            LiveTvRecordingScheduler.schedule(this, stored)
            return
        }

        val runtime = RunningRecording(
            id = id,
            client = newRecordingHttpClient(),
            paused = stored.state == LiveTvRecordingStatus.PAUSED,
            bytes = stored.bytesWritten,
            accumulatedDurationMillis = stored.recordedDurationMillis,
            resumedAtMillis =
                if (stored.state == LiveTvRecordingStatus.PAUSED) 0L
                else System.currentTimeMillis(),
            outputUri = stored.outputUri?.let(Uri::parse)
        )
        runtimes[id] = runtime
        runningRecordingIds.add(id)

        val starting = stored.copy(
            state = LiveTvRecordingStatus.STARTING,
            errorCode = null,
            errorMessage = null,
            updatedAtMillis = System.currentTimeMillis()
        )
        LiveTvRecordingStore.upsert(this, starting)
        ensureForeground()
        notifyRecording(starting)

        runtime.job = serviceScope.launch {
            runRecording(runtime)
        }
    }

    private suspend fun runRecording(runtime: RunningRecording) {
        var outputUri = runtime.outputUri
        try {
            LiveTvRecordingManager.storageProblem(this)?.let {
                throw IllegalStateException(it)
            }

            val stored =
                LiveTvRecordingStore.get(this, runtime.id)
                    ?: error("Recording metadata disappeared")
            val portal = StalkerPortalClient(applicationContext)
            val session = portal.authenticate(stored.profile)
            val sourceUrl = portal.playableUrl(
                session,
                stored.channel.toMediaItem(),
                CatalogType.LIVE_TV
            )

            var current =
                LiveTvRecordingStore.get(this, runtime.id)
                    ?: error("Recording metadata disappeared")
            if (current.isTerminal || current.isScheduled) return

            val activeOutputUri =
                outputUri ?: createOutput(current).also { created ->
                    outputUri = created
                    runtime.outputUri = created
                    current = current.copy(
                        outputUri = created.toString(),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    LiveTvRecordingStore.upsert(this, current)
                }

            val openMode =
                if (current.bytesWritten > 0L) "wa" else "w"
            val output =
                contentResolver.openOutputStream(
                    activeOutputUri,
                    openMode
                ) ?: error("Unable to open recording output")

            output.use { stream ->
                val now = System.currentTimeMillis()
                if (!runtime.paused && runtime.resumedAtMillis <= 0L) {
                    runtime.resumedAtMillis = now
                }
                val recordingState =
                    if (runtime.paused) {
                        LiveTvRecordingStatus.PAUSED
                    } else {
                        LiveTvRecordingStatus.RECORDING
                    }
                current =
                    LiveTvRecordingStore.get(this, runtime.id)
                        ?: current
                val runningRecord = current.copy(
                    state = recordingState,
                    startedAtMillis =
                        current.startedAtMillis.takeIf { it > 0L } ?: now,
                    outputUri = activeOutputUri.toString(),
                    errorCode = null,
                    errorMessage = null,
                    updatedAtMillis = now
                )
                LiveTvRecordingStore.upsert(this, runningRecord)
                notifyRecording(runningRecord)

                runtime.progressJob = serviceScope.launch {
                    monitorRuntime(runtime)
                }

                if (
                    isHls(sourceUrl) ||
                    stored.channel.streamType
                        ?.contains("hls", ignoreCase = true) == true
                ) {
                    recordHls(runtime, sourceUrl, stream)
                } else {
                    copyTransportStream(runtime, sourceUrl, stream)
                }
            }

            if (!runtime.stopRequested) {
                completeRuntime(runtime)
            }
        } catch (cancelled: CancellationException) {
            if (runtime.stopRequested) {
                completeRuntime(runtime)
            } else {
                throw cancelled
            }
        } catch (error: Throwable) {
            failRuntime(runtime, error)
        } finally {
            runtime.progressJob?.cancel()
            cleanupRuntime(runtime.id)
        }
    }

    private suspend fun monitorRuntime(runtime: RunningRecording) {
        while (currentCoroutineContext().isActive) {
            delay(1_000L)
            val current =
                LiveTvRecordingStore.get(this, runtime.id) ?: break
            val now = System.currentTimeMillis()
            val duration = runtime.recordedDurationMillis(now)

            val reachedScheduledEnd =
                current.scheduledEndMillis?.let { now >= it } == true
            val reachedMaximum =
                current.maximumDurationMillis
                    ?.let { duration >= it } == true

            if (reachedScheduledEnd || reachedMaximum) {
                runtime.stopRequested = true
                runtime.activeCall?.cancel()
                runtime.job?.cancel()
                break
            }

            val updated = current.copy(
                state =
                    if (runtime.paused) {
                        LiveTvRecordingStatus.PAUSED
                    } else {
                        LiveTvRecordingStatus.RECORDING
                    },
                recordedDurationMillis = duration,
                bytesWritten = runtime.bytes,
                updatedAtMillis = now
            )
            val persist =
                now - runtime.lastPersistAtMillis >= 5_000L
            LiveTvRecordingStore.upsert(
                this,
                updated,
                persist = persist
            )
            if (persist) {
                runtime.lastPersistAtMillis = now
            }
            notifyRecording(updated)
        }
    }

    private fun stopRuntime(id: String) {
        val runtime = runtimes[id] ?: return
        runtime.captureRunningDuration()
        runtime.stopRequested = true
        runtime.activeCall?.cancel()
        runtime.job?.cancel()
    }

    private fun setPaused(id: String, value: Boolean) {
        val runtime = runtimes[id] ?: return
        if (runtime.paused == value) return

        val now = System.currentTimeMillis()
        if (value) {
            runtime.captureRunningDuration(now)
            runtime.paused = true
            runtime.activeCall?.cancel()
        } else {
            runtime.paused = false
            runtime.resumedAtMillis = now
        }

        val current =
            LiveTvRecordingStore.get(this, id) ?: return
        val updated = current.copy(
            state =
                if (value) {
                    LiveTvRecordingStatus.PAUSED
                } else {
                    LiveTvRecordingStatus.RECORDING
                },
            recordedDurationMillis =
                runtime.recordedDurationMillis(now),
            bytesWritten = runtime.bytes,
            errorCode = null,
            errorMessage = null,
            updatedAtMillis = now
        )
        LiveTvRecordingStore.upsert(this, updated)
        notifyRecording(updated)
    }

    private suspend fun recordHls(
        runtime: RunningRecording,
        initialUrl: String,
        output: java.io.OutputStream
    ) {
        val playlistUrl =
            resolveMediaPlaylist(runtime, initialUrl)
        val written = LinkedHashSet<String>()

        while (currentCoroutineContext().isActive) {
            while (
                runtime.paused &&
                currentCoroutineContext().isActive
            ) {
                delay(150L)
            }

            val playlist = try {
                getText(runtime, playlistUrl)
            } catch (error: java.io.IOException) {
                if (runtime.paused) continue
                throw error
            }
            val lines =
                playlist.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toList()

            val mapUri =
                lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
                    ?.substringAfter("URI=", "")
                    ?.trim()
                    ?.trim('"')

            val segments = buildList {
                mapUri?.let { add(resolve(playlistUrl, it)) }
                lines.filterNot { it.startsWith("#") }
                    .forEach { add(resolve(playlistUrl, it)) }
            }

            for (segment in segments) {
                if (!written.add(segment)) continue
                if (runtime.paused) break
                val appended = try {
                    appendUrl(runtime, segment, output)
                } catch (error: java.io.IOException) {
                    if (runtime.paused) 0L else throw error
                }
                runtime.bytes += appended
            }

            if (lines.any { it == "#EXT-X-ENDLIST" }) return

            val targetSeconds =
                lines.firstOrNull {
                    it.startsWith("#EXT-X-TARGETDURATION:")
                }
                    ?.substringAfter(':')
                    ?.toLongOrNull()
                    ?.coerceIn(1L, 10L)
                    ?: 3L
            delay(targetSeconds * 500L)
        }
    }

    private suspend fun resolveMediaPlaylist(
        runtime: RunningRecording,
        url: String
    ): String {
        val text = getText(runtime, url)
        val lines =
            text.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
        val variants =
            lines.mapIndexedNotNull { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    lines.getOrNull(index + 1)
                        ?.takeUnless { it.startsWith("#") }
                } else {
                    null
                }
            }
        return variants.lastOrNull()
            ?.let { resolve(url, it) }
            ?: url
    }

    private suspend fun copyTransportStream(
        runtime: RunningRecording,
        url: String,
        output: java.io.OutputStream
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)

        while (currentCoroutineContext().isActive) {
            while (
                runtime.paused &&
                currentCoroutineContext().isActive
            ) {
                delay(150L)
            }

            val request = streamRequest(url)
            val call = runtime.client.newCall(request)
            runtime.activeCall = call
            try {
                call.execute().use { response ->
                    requireSuccessful(response, "Stream")
                    val input =
                        response.body?.byteStream()
                            ?: error("Stream returned no data")
                    while (
                        currentCoroutineContext().isActive &&
                        !runtime.paused
                    ) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        runtime.bytes += count
                    }
                }
            } catch (error: java.io.IOException) {
                if (!runtime.paused && currentCoroutineContext().isActive) {
                    throw error
                }
            } finally {
                if (runtime.activeCall === call) {
                    runtime.activeCall = null
                }
            }

            if (
                !runtime.paused &&
                currentCoroutineContext().isActive
            ) {
                delay(350L)
            }
        }
    }

    private fun appendUrl(
        runtime: RunningRecording,
        url: String,
        output: java.io.OutputStream
    ): Long {
        val call = runtime.client.newCall(streamRequest(url))
        runtime.activeCall = call
        try {
            return call.execute().use { response ->
                requireSuccessful(response, "Segment")
                val input = response.body?.byteStream() ?: return@use 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                var written = 0L
                while (!runtime.paused) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    written += count
                }
                written
            }
        } finally {
            if (runtime.activeCall === call) {
                runtime.activeCall = null
            }
        }
    }

    private fun getText(
        runtime: RunningRecording,
        url: String
    ): String {
        val call = runtime.client.newCall(streamRequest(url))
        runtime.activeCall = call
        try {
            return call.execute().use { response ->
                requireSuccessful(response, "Playlist")
                response.body?.string()
                    ?: error("Playlist returned no data")
            }
        } finally {
            if (runtime.activeCall === call) {
                runtime.activeCall = null
            }
        }
    }

    private fun completeRuntime(runtime: RunningRecording) {
        if (!runtime.finalized.compareAndSet(false, true)) return
        runtime.captureRunningDuration()
        val now = System.currentTimeMillis()
        val current =
            LiveTvRecordingStore.get(this, runtime.id) ?: return
        val uri = runtime.outputUri
        val finalUri =
            if (uri != null && runtime.bytes > 0L) {
                finishOutput(uri)
                uri.toString()
            } else {
                uri?.let {
                    runCatching {
                        contentResolver.delete(it, null, null)
                    }
                }
                null
            }

        val completed = current.copy(
            state = LiveTvRecordingStatus.COMPLETED,
            outputUri = finalUri,
            endedAtMillis = now,
            recordedDurationMillis =
                runtime.recordedDurationMillis(now),
            bytesWritten = runtime.bytes,
            errorCode = null,
            errorMessage = null,
            updatedAtMillis = now
        )
        LiveTvRecordingStore.upsert(this, completed)
    }

    private fun failRuntime(
        runtime: RunningRecording,
        error: Throwable
    ) {
        if (!runtime.finalized.compareAndSet(false, true)) return
        runtime.captureRunningDuration()
        val now = System.currentTimeMillis()
        val current =
            LiveTvRecordingStore.get(this, runtime.id) ?: return
        val failure = friendlyFailure(error)
        val uri = runtime.outputUri
        val finalUri =
            if (uri != null && runtime.bytes > 0L) {
                finishOutput(uri)
                uri.toString()
            } else {
                uri?.let {
                    runCatching {
                        contentResolver.delete(it, null, null)
                    }
                }
                null
            }

        LiveTvRecordingStore.upsert(
            this,
            current.copy(
                state = LiveTvRecordingStatus.FAILED,
                outputUri = finalUri,
                endedAtMillis = now,
                recordedDurationMillis =
                    runtime.recordedDurationMillis(now),
                bytesWritten = runtime.bytes,
                errorCode = failure.first,
                errorMessage = failure.second,
                updatedAtMillis = now
            )
        )
    }

    private fun cleanupRuntime(id: String) {
        runtimes.remove(id)
        runningRecordingIds.remove(id)
        getSystemService(NotificationManager::class.java)
            .cancel(notificationId(id))
        refreshForegroundState()
    }

    private fun ensureForeground() {
        if (!foregroundStarted) {
            startForeground(
                SUMMARY_NOTIFICATION_ID,
                summaryNotification()
            )
            foregroundStarted = true
        }
        getSystemService(NotificationManager::class.java)
            .notify(
                SUMMARY_NOTIFICATION_ID,
                summaryNotification()
            )
    }

    private fun refreshForegroundState() {
        val manager =
            getSystemService(NotificationManager::class.java)
        if (runtimes.isEmpty()) {
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            manager.cancel(SUMMARY_NOTIFICATION_ID)
            stopSelf()
        } else {
            ensureForeground()
        }
    }

    private fun notifyRecording(recording: LiveTvRecording) {
        if (!runtimes.containsKey(recording.id)) return
        ensureForeground()
        getSystemService(NotificationManager::class.java)
            .notify(
                notificationId(recording.id),
                recordingNotification(recording)
            )
    }

    private fun summaryNotification(): Notification {
        val count = runtimes.size.coerceAtLeast(1)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("NikTV Live TV recordings")
            .setContentText(
                if (count == 1) {
                    "1 recording session"
                } else {
                    "$count concurrent recording sessions"
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(
                NotificationCompat.GROUP_ALERT_SUMMARY
            )
            .build()
    }

    private fun recordingNotification(
        recording: LiveTvRecording
    ): Notification {
        val pauseOrResume =
            if (recording.isPaused) ACTION_RESUME else ACTION_PAUSE
        val pauseLabel =
            if (recording.isPaused) "Resume" else "Pause"
        val pauseIcon =
            if (recording.isPaused) {
                android.R.drawable.ic_media_play
            } else {
                android.R.drawable.ic_media_pause
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(
                when (recording.state) {
                    LiveTvRecordingStatus.STARTING ->
                        "Preparing · ${recording.title}"
                    LiveTvRecordingStatus.PAUSED ->
                        "Paused · ${recording.title}"
                    else -> "Recording · ${recording.title}"
                }
            )
            .setContentText(
                "${LiveTvRecordingManager.formatDuration(recording.recordedDurationMillis)} · " +
                    LiveTvRecordingManager.formatBytes(recording.bytesWritten)
            )
            .setSubText(recording.profile.name)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(
                NotificationCompat.GROUP_ALERT_SUMMARY
            )
            .addAction(
                pauseIcon,
                pauseLabel,
                controlPendingIntent(
                    recording.id,
                    pauseOrResume,
                    1
                )
            )
            .addAction(
                android.R.drawable.ic_input_add,
                "+15 min",
                controlPendingIntent(
                    recording.id,
                    ACTION_EXTEND,
                    2
                )
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                controlPendingIntent(
                    recording.id,
                    ACTION_STOP,
                    3
                )
            )
            .build()
    }

    private fun controlPendingIntent(
        id: String,
        action: String,
        actionCode: Int
    ): PendingIntent =
        PendingIntent.getService(
            this,
            stableRequestCode(id, actionCode),
            Intent(this, LiveTvRecordingService::class.java)
                .setAction(action)
                .putExtra(EXTRA_RECORDING_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

    private fun createOutput(
        recording: LiveTvRecording
    ): Uri {
        val safe =
            recording.title
                .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
                .trim()
                .take(72)
                .ifBlank { "Live TV" }
        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                Locale.US
            ).format(Date())
        val name =
            "$safe-${recording.id.take(8)}-$timestamp.ts"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2t")
            if (Build.VERSION.SDK_INT >= 29) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/NikTV/Recordings"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directory =
                    java.io.File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        "NikTV/Recordings"
                    ).apply { mkdirs() }
                put(
                    MediaStore.Video.Media.DATA,
                    java.io.File(directory, name).absolutePath
                )
            }
        }

        return contentResolver.insert(
            recordingCollectionUri(),
            values
        ) ?: error("Unable to create recording file")
    }

    private fun finishOutput(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START =
            "com.nikhil.niktv.action.START_LIVE_RECORDING"
        const val ACTION_STOP =
            "com.nikhil.niktv.action.STOP_LIVE_RECORDING"
        const val ACTION_PAUSE =
            "com.nikhil.niktv.action.PAUSE_LIVE_RECORDING"
        const val ACTION_RESUME =
            "com.nikhil.niktv.action.RESUME_LIVE_RECORDING"
        const val ACTION_EXTEND =
            "com.nikhil.niktv.action.EXTEND_LIVE_RECORDING"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_EXTENSION_MILLIS = "extension_millis"

        private const val CHANNEL_ID =
            "niktv_live_recordings"
        private const val GROUP_KEY =
            "com.nikhil.niktv.LIVE_RECORDINGS"
        private const val SUMMARY_NOTIFICATION_ID = 2114
        private const val EXTEND_STEP_MILLIS =
            15L * 60L * 1_000L

        internal val runningRecordingIds =
            ConcurrentHashMap.newKeySet<String>()
    }

    private class RunningRecording(
        val id: String,
        val client: OkHttpClient,
        @Volatile var paused: Boolean,
        @Volatile var bytes: Long,
        @Volatile var accumulatedDurationMillis: Long,
        @Volatile var resumedAtMillis: Long,
        @Volatile var outputUri: Uri?
    ) {
        @Volatile var activeCall: Call? = null
        @Volatile var stopRequested: Boolean = false
        @Volatile var lastPersistAtMillis: Long = 0L
        var job: Job? = null
        var progressJob: Job? = null
        val finalized = AtomicBoolean(false)

        fun captureRunningDuration(
            now: Long = System.currentTimeMillis()
        ) {
            if (!paused && resumedAtMillis > 0L) {
                accumulatedDurationMillis +=
                    (now - resumedAtMillis).coerceAtLeast(0L)
                resumedAtMillis = now
            }
        }

        fun recordedDurationMillis(
            now: Long = System.currentTimeMillis()
        ): Long =
            accumulatedDurationMillis +
                if (!paused && resumedAtMillis > 0L) {
                    (now - resumedAtMillis).coerceAtLeast(0L)
                } else {
                    0L
                }
    }
}

private class RecordingHttpException(
    val statusCode: Int,
    message: String
) : java.io.IOException(message)

private fun newRecordingHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

private fun streamRequest(url: String): Request =
    Request.Builder()
        .url(url)
        .header("User-Agent", "NikTV/0.1 Android")
        .build()

private fun requireSuccessful(
    response: Response,
    label: String
) {
    if (response.isSuccessful) return
    throw RecordingHttpException(
        response.code,
        "$label returned HTTP ${response.code}"
    )
}

private fun friendlyFailure(
    error: Throwable
): Pair<String, String> {
    if (error is RecordingHttpException) {
        if (
            error.statusCode in setOf(
                401,
                403,
                409,
                429,
                456,
                503
            )
        ) {
            return "PROVIDER_CONNECTION_LIMIT" to
                (
                    "Provider rejected the recording stream (HTTP " +
                        "${error.statusCode}). Your IPTV connection limit " +
                        "may be reached or the session may no longer be authorized."
                    )
        }
        return "HTTP_${error.statusCode}" to
            (error.message ?: "Recording stream failed")
    }

    val message =
        error.message
            ?.takeIf(String::isNotBlank)
            ?: "Recording failed"
    val lower = message.lowercase(Locale.US)
    val httpStatus =
        Regex("(?i)\\bHTTP\\s+(\\d{3})\\b")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    val looksLikeConnectionLimit =
        httpStatus in setOf(401, 403, 409, 429, 456, 503) ||
            lower.contains("max connection") ||
            lower.contains("maximum connection") ||
            lower.contains("connection limit") ||
            lower.contains("too many connection") ||
            lower.contains("too many streams")

    return if (looksLikeConnectionLimit) {
        "PROVIDER_CONNECTION_LIMIT" to
            "Provider connection limit or stream authorization limit reached. $message"
    } else if (
        lower.contains("storage") ||
        lower.contains("recording output")
    ) {
        "STORAGE" to message
    } else {
        "RECORDING_FAILED" to message
    }
}

private fun isHls(url: String): Boolean =
    url.substringBefore('?')
        .substringBefore('#')
        .endsWith(".m3u8", ignoreCase = true)

private fun recordingCollectionUri(): Uri =
    if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Downloads.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

private fun resolve(base: String, child: String): String =
    URI(base).resolve(child).toString()

private fun notificationId(id: String): Int =
    2_200 + (id.hashCode() and 0x3fffffff)

private fun stableRequestCode(
    id: String,
    actionCode: Int
): Int =
    ((id.hashCode() * 31) + actionCode) and 0x7fffffff
