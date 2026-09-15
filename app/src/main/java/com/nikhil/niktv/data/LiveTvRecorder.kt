package com.nikhil.niktv.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nikhil.niktv.R
import java.net.URI
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request

data class LiveRecordingState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val sourceUrl: String = "",
    val title: String = "",
    val startedAtMillis: Long = 0L,
    val recordedDurationMillis: Long = 0L,
    val bytesWritten: Long = 0L,
    val error: String? = null
)

data class RecordedLiveTvMedia(
    val uri: android.net.Uri,
    val title: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val durationMillis: Long
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

    fun pause(context: Context) = sendControl(context, LiveTvRecordingService.ACTION_PAUSE)
    fun resume(context: Context) = sendControl(context, LiveTvRecordingService.ACTION_RESUME)

    private fun sendControl(context: Context, action: String) {
        runCatching {
            context.startService(Intent(context, LiveTvRecordingService::class.java).setAction(action))
        }.onFailure {
            mutableState.value = mutableState.value.copy(error = it.message ?: "Recording control failed")
        }
    }

    internal fun update(value: LiveRecordingState) { mutableState.value = value }

    fun statusText(value: LiveRecordingState): String = if (!value.active) "" else
        "${if (value.paused) "Paused" else "REC"} · ${formatDuration(value.recordedDurationMillis)} · ${formatBytes(value.bytesWritten)}"

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
                                createdAtMillis = cursor.getLong(dateIndex).coerceAtLeast(0L) * 1000L,
                                durationMillis = readMediaDuration(context, uri)
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun delete(context: Context, recording: RecordedLiveTvMedia): Boolean =
        runCatching { context.contentResolver.delete(recording.uri, null, null) > 0 }.getOrDefault(false)

    private fun readMediaDuration(context: Context, uri: android.net.Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}

class LiveTvRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var statusJob: Job? = null
    @Volatile private var paused = false
    @Volatile private var activeStreamCall: Call? = null
    @Volatile private var hlsRebaseRequested = false
    @Volatile private var stopRequested = false
    private var accumulatedDurationMillis = 0L
    private var lastResumedAtMillis = 0L
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
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
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
        paused = false
        stopRequested = false
        hlsRebaseRequested = isHls(sourceUrl)
        accumulatedDurationMillis = 0L
        lastResumedAtMillis = startedAt
        val initial = LiveRecordingState(
            active = true,
            sourceUrl = sourceUrl,
            title = title,
            startedAtMillis = startedAt
        )
        LiveTvRecorder.update(initial)
        startForeground(NOTIFICATION_ID, notification(initial))
        statusJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                publishProgress(LiveTvRecorder.state.value.bytesWritten)
            }
        }
        recordingJob = scope.launch {
            var outputUri: android.net.Uri? = null
            try {
                outputUri = createOutput(title)
                contentResolver.openOutputStream(outputUri, "w")!!.use { output ->
                    if (isHls(sourceUrl)) recordHls(sourceUrl, output)
                    else copyStream(sourceUrl, output)
                }
                if (isHls(sourceUrl)) {
                    trimOutputToRecordedDuration(outputUri, accumulatedDurationMillis)
                }
                finishOutput(outputUri)
                LiveTvRecorder.update(LiveRecordingState(error = null))
            } catch (_: CancellationException) {
                if (hlsRebaseRequested || isHls(sourceUrl)) {
                    outputUri?.let { trimOutputToRecordedDuration(it, accumulatedDurationMillis) }
                }
                outputUri?.let(::finishOutput)
                LiveTvRecorder.update(LiveRecordingState())
            } catch (error: Throwable) {
                outputUri?.let(::finishOutput)
                if (LiveTvRecorder.state.value.active) {
                    LiveTvRecorder.update(LiveRecordingState(error = error.message ?: "Recording failed"))
                }
            } finally {
                statusJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        // Set the boundary before cancelling the HTTP call. A segment response
        // can win the cancellation race; appendUrl must never commit it after
        // the user pressed Stop.
        stopRequested = true
        if (!paused) accumulatedDurationMillis += (System.currentTimeMillis() - lastResumedAtMillis).coerceAtLeast(0L)
        paused = true
        statusJob?.cancel()
        publishProgress(LiveTvRecorder.state.value.bytesWritten)
        // Cancelling only the active request lets the writer exit normally and
        // finish trimming/publishing before this service is destroyed.
        activeStreamCall?.cancel()
    }

    private fun setPaused(value: Boolean) {
        if (recordingJob?.isActive != true || paused == value) return
        val now = System.currentTimeMillis()
        if (value) {
            accumulatedDurationMillis += (now - lastResumedAtMillis).coerceAtLeast(0L)
        } else {
            lastResumedAtMillis = now
            hlsRebaseRequested = true
        }
        paused = value
        if (value) activeStreamCall?.cancel()
        publishProgress(LiveTvRecorder.state.value.bytesWritten)
    }

    private fun recordedDurationMillis(): Long = accumulatedDurationMillis +
        if (!paused && recordingJob?.isActive == true) (System.currentTimeMillis() - lastResumedAtMillis).coerceAtLeast(0L) else 0L

    private suspend fun recordHls(initialUrl: String, output: java.io.OutputStream) {
        var failures = 0
        var resolvedPlaylistUrl: String? = null
        val timestampRebaser = MpegTsTimestampRebaser()
        while (resolvedPlaylistUrl == null && !stopRequested) {
            awaitResume()
            if (stopRequested) return
            try {
                resolvedPlaylistUrl = resolveMediaPlaylist(initialUrl)
            } catch (error: java.io.IOException) {
                if (stopRequested) return
                if (paused) continue
                if (!error.isRetriableStreamFailure() || ++failures > MAX_STREAM_RETRIES) throw error
                delay(retryDelay(failures))
            }
        }
        val playlistUrl = resolvedPlaylistUrl ?: return
        val written = LinkedHashSet<String>()
        var bytes = 0L
        failures = 0
        playlistLoop@ while (currentCoroutineContext().isActive && !stopRequested) {
            awaitResume()
            if (stopRequested) return
            val playlist = try {
                getText(playlistUrl)
            } catch (error: java.io.IOException) {
                if (stopRequested) return
                if (paused) continue
                if (!error.isRetriableStreamFailure() || ++failures > MAX_STREAM_RETRIES) throw error
                delay(retryDelay(failures))
                continue
            }
            failures = 0
            val lines = playlist.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val mapUri = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
                ?.substringAfter("URI=", "")?.trim()?.trim('"')
            val mediaSegments = lines.filterNot { it.startsWith("#") }
                .map { resolve(playlistUrl, it) }
            val segments = buildList {
                mapUri?.let { add(resolve(playlistUrl, it)) }
                addAll(mediaSegments)
            }
            val isFinishedPlaylist = lines.any { it == "#EXT-X-ENDLIST" }
            var continueTimelineAtNextSegment = hlsRebaseRequested && !isFinishedPlaylist
            if (continueTimelineAtNextSegment) {
                // A live manifest contains a sliding backlog. Start (and resume) at its
                // live edge so time elapsed before Start or while paused is never recorded.
                written.addAll(mediaSegments.dropLast(1))
                hlsRebaseRequested = false
            }
            for (segment in segments) {
                if (stopRequested) return
                if (segment in written) continue
                awaitResume()
                if (stopRequested) return
                if (hlsRebaseRequested) continue@playlistLoop
                val appended = try {
                    appendUrl(
                        segment,
                        output,
                        timestampRebaser,
                        continueTimelineAtNextSegment && segment in mediaSegments
                    )
                } catch (error: java.io.IOException) {
                    if (stopRequested) return
                    if (paused) break
                    if (!error.isRetriableStreamFailure() || ++failures > MAX_STREAM_RETRIES) throw error
                    delay(retryDelay(failures))
                    break
                }
                if (paused) break
                bytes += appended
                written.add(segment)
                if (segment in mediaSegments) continueTimelineAtNextSegment = false
                failures = 0
                publishProgress(bytes)
            }
            if (isFinishedPlaylist) return
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

    private suspend fun copyStream(url: String, output: java.io.OutputStream) {
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").build()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        var bytes = LiveTvRecorder.state.value.bytesWritten
        var failures = 0
        while (currentCoroutineContext().isActive && !stopRequested) {
            awaitResume()
            if (stopRequested) return

            val call = client.newCall(request)
            activeStreamCall = call
            val bytesBeforeAttempt = bytes
            try {
                call.execute().use { response ->
                    requireSuccessful(response.code, response.isSuccessful, "Stream")
                    val input = response.body?.byteStream() ?: error("Stream returned no data")
                    while (currentCoroutineContext().isActive && !paused && !stopRequested) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        bytes += count
                        if (bytes % (1024 * 1024) < count) publishProgress(bytes)
                    }
                }
                failures = 0
            } catch (error: java.io.IOException) {
                if (stopRequested) return
                if (!paused && currentCoroutineContext().isActive) {
                    if (!error.isRetriableStreamFailure()) throw error
                    failures = if (bytes > bytesBeforeAttempt) 1 else failures + 1
                    if (failures > MAX_STREAM_RETRIES) throw error
                }
            } finally {
                if (activeStreamCall === call) activeStreamCall = null
            }

            // A resumed live stream must reconnect at its current live edge.
            // Do not consume and discard network bytes while paused.
            if (!paused && !stopRequested && currentCoroutineContext().isActive) {
                delay(if (failures == 0) 350L else retryDelay(failures))
            }
        }
    }

    private suspend fun appendUrl(
        url: String,
        output: java.io.OutputStream,
        timestampRebaser: MpegTsTimestampRebaser,
        forceTimestampContinuity: Boolean
    ): Long {
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").build()
        val call = client.newCall(request)
        activeStreamCall = call
        try {
            return call.execute().use { response ->
                requireSuccessful(response.code, response.isSuccessful, "Segment")
                val data = response.body?.bytes() ?: return@use 0L
                currentCoroutineContext().ensureActive()
                if (paused || stopRequested) return@use 0L
                output.write(timestampRebaser.rebase(data, forceTimestampContinuity))
                data.size.toLong()
            }
        } finally {
            if (activeStreamCall === call) activeStreamCall = null
        }
    }

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").build()
        val call = client.newCall(request)
        activeStreamCall = call
        try {
            return call.execute().use { response ->
                requireSuccessful(response.code, response.isSuccessful, "Playlist")
                response.body?.string() ?: error("Playlist returned no data")
            }
        } finally {
            if (activeStreamCall === call) activeStreamCall = null
        }
    }

    private suspend fun awaitResume() {
        while (paused && !stopRequested && currentCoroutineContext().isActive) delay(100L)
    }

    private fun publishProgress(bytes: Long) {
        val current = LiveTvRecorder.state.value
        if (!current.active) return
        val value = current.copy(
            paused = paused,
            bytesWritten = bytes,
            recordedDurationMillis = recordedDurationMillis()
        )
        LiveTvRecorder.update(value)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(value))
    }

    private fun notification(state: LiveRecordingState): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, LiveTvRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LiveTvRecordingService::class.java).setAction(
                if (state.paused) ACTION_RESUME else ACTION_PAUSE
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("${if (state.paused) "Recording paused" else "Recording"} · ${state.title}")
            .setContentText("${formatDuration(state.recordedDurationMillis)} · ${formatBytes(state.bytesWritten)}")
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(
                if (state.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (state.paused) "Resume" else "Pause",
                pauseIntent
            )
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
        const val ACTION_PAUSE = "com.nikhil.niktv.action.PAUSE_LIVE_RECORDING"
        const val ACTION_RESUME = "com.nikhil.niktv.action.RESUME_LIVE_RECORDING"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        private const val CHANNEL_ID = "niktv_live_recordings"
        private const val NOTIFICATION_ID = 2114
        private const val MAX_STREAM_RETRIES = 8
    }

    private fun trimOutputToRecordedDuration(uri: android.net.Uri, durationMillis: Long) {
        if (durationMillis <= 0L) return
        runCatching {
            contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                // Keep the shared descriptor open while scanning, then truncate
                // at a complete 188-byte transport-stream packet boundary.
                val cutoff = FileInputStream(Os.dup(descriptor.fileDescriptor)).use { input ->
                    MpegTsRecordingTrimmer.cutoffBytes(input, durationMillis)
                }
                if (cutoff != null) Os.ftruncate(descriptor.fileDescriptor, cutoff)
            }
        }
    }
}

private class RecordingHttpException(
    val statusCode: Int,
    message: String
) : java.io.IOException(message)

private fun requireSuccessful(code: Int, successful: Boolean, label: String) {
    if (successful) return
    throw RecordingHttpException(code, "$label returned HTTP $code")
}

private fun java.io.IOException.isRetriableStreamFailure(): Boolean =
    this !is RecordingHttpException

private fun retryDelay(attempt: Int): Long =
    (500L * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(8_000L)

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
