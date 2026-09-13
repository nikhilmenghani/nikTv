package com.nikhil.niktv.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nikhil.niktv.R
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class LiveRecordingState(
    val active: Boolean = false,
    val sourceUrl: String = "",
    val title: String = "",
    val startedAtMillis: Long = 0L,
    val bytesWritten: Long = 0L,
    val error: String? = null
)

data class RecordedLiveTvMedia(
    val uri: android.net.Uri,
    val title: String,
    val sizeBytes: Long,
    val createdAtMillis: Long
)

object LiveTvRecorder {
    private val mutableState = MutableStateFlow(LiveRecordingState())
    val state: StateFlow<LiveRecordingState> = mutableState.asStateFlow()

    fun start(context: Context, title: String, url: String) {
        mutableState.value = LiveRecordingState(
            active = true,
            sourceUrl = url,
            title = title,
            startedAtMillis = System.currentTimeMillis()
        )
        val intent = Intent(context, LiveTvRecordingService::class.java)
            .setAction(LiveTvRecordingService.ACTION_START)
            .putExtra(LiveTvRecordingService.EXTRA_TITLE, title)
            .putExtra(LiveTvRecordingService.EXTRA_URL, url)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure {
                mutableState.value = LiveRecordingState(
                    error = it.message ?: "Unable to start recording"
                )
            }
    }

    fun stop(context: Context) {
        val previous = mutableState.value
        mutableState.value = LiveRecordingState()
        runCatching { context.startService(
            Intent(context, LiveTvRecordingService::class.java)
                .setAction(LiveTvRecordingService.ACTION_STOP)
        ) }.onFailure {
            mutableState.value = previous.copy(error = it.message ?: "Unable to stop recording")
        }
    }

    internal fun update(value: LiveRecordingState) { mutableState.value = value }

    fun recordings(context: Context): List<RecordedLiveTvMedia> {
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
                projection, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                buildList {
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(
                            collection,
                            cursor.getLong(idIndex)
                        )
                        add(
                            RecordedLiveTvMedia(
                                uri = uri,
                                title = cursor.getString(nameIndex).substringBeforeLast('.'),
                                sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                                createdAtMillis = cursor.getLong(dateIndex).coerceAtLeast(0L) * 1000L
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun delete(context: Context, recording: RecordedLiveTvMedia): Boolean =
        runCatching { context.contentResolver.delete(recording.uri, null, null) > 0 }.getOrDefault(false)
}

class LiveTvRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live TV recordings", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Live TV" }
                if (url.isNotBlank() && recordingJob?.isActive != true) startRecording(title, url)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(title: String, sourceUrl: String) {
        val startedAt = System.currentTimeMillis()
        val initial = LiveRecordingState(true, sourceUrl, title, startedAt)
        LiveTvRecorder.update(initial)
        startForeground(NOTIFICATION_ID, notification(initial))
        recordingJob = scope.launch {
            var outputUri: android.net.Uri? = null
            try {
                outputUri = createOutput(title)
                contentResolver.openOutputStream(outputUri, "w")!!.use { output ->
                    if (isHls(sourceUrl)) recordHls(sourceUrl, output, initial)
                    else copyStream(sourceUrl, output, initial)
                }
                finishOutput(outputUri)
                LiveTvRecorder.update(LiveRecordingState(error = null))
            } catch (_: CancellationException) {
                outputUri?.let(::finishOutput)
                LiveTvRecorder.update(LiveRecordingState())
            } catch (error: Throwable) {
                outputUri?.let(::finishOutput)
                if (LiveTvRecorder.state.value.active) {
                    LiveTvRecorder.update(LiveRecordingState(error = error.message ?: "Recording failed"))
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        client.dispatcher.cancelAll()
        recordingJob = null
        LiveTvRecorder.update(LiveRecordingState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun recordHls(initialUrl: String, output: java.io.OutputStream, initial: LiveRecordingState) {
        var playlistUrl = resolveMediaPlaylist(initialUrl)
        val written = LinkedHashSet<String>()
        var bytes = 0L
        while (scope.isActive) {
            val playlist = getText(playlistUrl)
            val lines = playlist.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val mapUri = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
                ?.substringAfter("URI=", "")?.trim()?.trim('"')
            val segments = buildList {
                mapUri?.let { add(resolve(playlistUrl, it)) }
                lines.filterNot { it.startsWith("#") }.forEach { add(resolve(playlistUrl, it)) }
            }
            for (segment in segments) {
                if (!written.add(segment)) continue
                bytes += appendUrl(segment, output)
                publishProgress(initial, bytes)
            }
            if (lines.any { it == "#EXT-X-ENDLIST" }) return
            val targetSeconds = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.substringAfter(':')?.toLongOrNull()?.coerceIn(1, 10) ?: 3L
            delay(targetSeconds * 500L)
        }
    }

    private suspend fun resolveMediaPlaylist(url: String): String {
        val text = getText(url)
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val variants = lines.mapIndexedNotNull { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) lines.getOrNull(index + 1)?.takeUnless { it.startsWith("#") } else null
        }
        return variants.lastOrNull()?.let { resolve(url, it) } ?: url
    }

    private fun copyStream(url: String, output: java.io.OutputStream, initial: LiveRecordingState) {
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Stream returned HTTP ${response.code}" }
            val input = response.body?.byteStream() ?: error("Stream returned no data")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            var bytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                bytes += count
                if (bytes % (1024 * 1024) < count) publishProgress(initial, bytes)
            }
        }
    }

    private fun appendUrl(url: String, output: java.io.OutputStream): Long {
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Segment returned HTTP ${response.code}" }
            return response.body?.byteStream()?.use { it.copyTo(output) } ?: 0L
        }
    }

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Playlist returned HTTP ${response.code}" }
            response.body?.string() ?: error("Playlist returned no data")
        }
    }

    private fun publishProgress(initial: LiveRecordingState, bytes: Long) {
        val value = initial.copy(bytesWritten = bytes)
        LiveTvRecorder.update(value)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(value))
    }

    private fun notification(state: LiveRecordingState): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, LiveTvRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Recording ${state.title}")
            .setContentText("${formatDuration(System.currentTimeMillis() - state.startedAtMillis)} · ${formatBytes(state.bytesWritten)}")
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun createOutput(title: String): android.net.Uri {
        val safe = title.replace(Regex("[\\/:*?\"<>|]+"), "_").trim().take(72).ifBlank { "Live TV" }
        val name = "$safe-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.ts"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2t")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NikTV/Recordings")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directory = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "NikTV/Recordings"
                ).apply { mkdirs() }
                put(MediaStore.Video.Media.DATA, java.io.File(directory, name).absolutePath)
            }
        }
        return contentResolver.insert(recordingCollectionUri(), values)
            ?: error("Unable to create recording file")
    }

    private fun finishOutput(uri: android.net.Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.nikhil.niktv.action.START_LIVE_RECORDING"
        const val ACTION_STOP = "com.nikhil.niktv.action.STOP_LIVE_RECORDING"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        private const val CHANNEL_ID = "niktv_live_recordings"
        private const val NOTIFICATION_ID = 2114
    }
}

private fun isHls(url: String) = url.substringBefore('?').endsWith(".m3u8", true)
private fun recordingCollectionUri(): android.net.Uri =
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
private fun resolve(base: String, child: String): String = URI(base).resolve(child).toString()
private fun formatDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
