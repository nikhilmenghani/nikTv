package com.nikhil.niktv.data

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.work.*
import com.nikhil.niktv.R
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

data class NikTubeDownloadRecord(val video: NikTubeVideo, val height: Int, val audio: String, val workId: String) {
    val key: String get() = "${video.id}-$height-$audio"
}
data class NikTubeDownloadState(val record: NikTubeDownloadRecord, val state: WorkInfo.State?, val percent: Int, val bytes: Long, val error: String?, val file: File?)

object NikTubeDownloads {
    fun directory(context: Context) = File(context.filesDir, "niktube-downloads").apply { mkdirs() }
    fun temporaryDirectory(context: Context) = File(context.filesDir, "niktube-download-work").apply { mkdirs() }
    private fun preferences(context: Context) = context.getSharedPreferences("niktube_downloads", 0)
    fun file(context: Context, record: NikTubeDownloadRecord) = File(directory(context), "${record.key}.mp4")
    fun records(context: Context): List<NikTubeDownloadRecord> = preferences(context).all.values.mapNotNull { value -> runCatching {
        val row = JSONObject(value as String)
        NikTubeDownloadRecord(NikTubeRepository.decode(row.getJSONObject("video")), row.getInt("height"), row.optString("audio"), row.getString("work"))
    }.getOrNull() }
    @Synchronized fun enqueue(context: Context, video: NikTubeVideo, height: Int, audio: String = ""): NikTubeDownloadRecord {
        require(video.id.matches(Regex("[A-Za-z0-9_-]{11}")) && height in 0..4320 && audio.matches(Regex("[A-Za-z0-9._-]{0,80}")))
        val request = OneTimeWorkRequestBuilder<NikTubeDownloadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("video" to video.id, "title" to video.title, "height" to height, "audio" to audio)).build()
        val record = NikTubeDownloadRecord(video, height, audio, request.id.toString())
        preferences(context).edit().putString(record.key, JSONObject().put("video", NikTubeRepository.encode(video)).put("height", height).put("audio", audio).put("work", record.workId).toString()).apply()
        WorkManager.getInstance(context).enqueueUniqueWork("niktube-download:${record.key}", ExistingWorkPolicy.REPLACE, request)
        return record
    }
    suspend fun states(context: Context): List<NikTubeDownloadState> = withContext(Dispatchers.IO) {
        records(context).map { record ->
            val info = runCatching { WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(record.workId)).get() }.getOrNull()
            val output = file(context, record).takeIf { it.isFile && it.length() > 0 }
            NikTubeDownloadState(record, info?.state ?: if (output != null) WorkInfo.State.SUCCEEDED else null, info?.progress?.getInt("percent", 0) ?: 0,
                output?.length() ?: info?.progress?.getLong("bytes", 0) ?: 0, info?.outputData?.getString("error"), output)
        }
    }
    fun cancel(context: Context, record: NikTubeDownloadRecord) { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(record.workId)) }
    @Synchronized fun remove(context: Context, record: NikTubeDownloadRecord) {
        preferences(context).edit().remove(record.key).apply(); cancel(context, record); file(context, record).delete()
    }
    @Synchronized fun commit(context: Context, key: String, work: String, output: File, target: File): Boolean {
        val saved = preferences(context).getString(key, null) ?: return false
        if (runCatching { JSONObject(saved).getString("work") }.getOrNull() != work) return false
        return output.renameTo(target)
    }
    fun bytes(context: Context) = directory(context).listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } + temporaryDirectory(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NikTubeDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private val slots = Semaphore(2) }
    override suspend fun doWork(): Result = slots.withPermit {
        val videoId = inputData.getString("video").orEmpty()
        val height = inputData.getInt("height", 0)
        val audioId = inputData.getString("audio").orEmpty()
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}")) || !audioId.matches(Regex("[A-Za-z0-9._-]{0,80}"))) return@withPermit Result.failure()
        val title = inputData.getString("title") ?: "NikTube video"
        setForeground(foreground(title))
        val staging = File(NikTubeDownloads.temporaryDirectory(applicationContext), id.toString()).apply { mkdirs() }
        val output = File(staging, "output.mp4")
        val finalFile = File(NikTubeDownloads.directory(applicationContext), "$videoId-$height-$audioId.mp4")
        try {
            // A second attempt resolves a fresh permitted stream rather than persisting expiring URLs.
            var completed = false
            for (attempt in 0..1) {
                try {
                    val stream = NikTubeClient(applicationContext).resolve(videoId)
                    withContext(Dispatchers.IO) { transfer(stream, height, audioId, staging, output) }
                    completed = true; break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    staging.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
                    if (attempt == 1 || isStopped || error.message == "Not enough storage") throw error
                    delay(1000)
                }
            }
            check(completed && !isStopped)
            withContext(Dispatchers.IO) {
                validate(output)
                check(!isStopped && NikTubeDownloads.commit(applicationContext, "$videoId-$height-$audioId", id.toString(), output, finalFile)) { "Could not finish the downloaded file" }
            }
            setProgress(workDataOf("percent" to 100, "bytes" to finalFile.length()))
            Result.success(workDataOf("bytes" to finalFile.length()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = if (error.message == "Not enough storage") "Not enough storage" else "Download failed. Retry to request a fresh stream."
            Result.failure(workDataOf("error" to message))
        } finally { staging.listFiles().orEmpty().forEach { it.delete() }; staging.delete() }
    }
    private fun foreground(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("niktube-download", "NikTube downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "niktube-download").setSmallIcon(R.drawable.niktv_logo_foreground)
            .setContentTitle(title).setContentText("Downloading for offline playback").setProgress(0, 0, true).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id.hashCode(), notification)
    }
    private suspend fun transfer(stream: NikTubeStream, height: Int, audioId: String, staging: File, output: File) {
        val audioFile = File(staging, "audio.mp4")
        val videoFile = File(staging, "video.mp4")
        val native = stream.sabr
        if (native != null) {
            val visual = native.tracks.filter { it.type == C.TRACK_TYPE_VIDEO && (height == 0 || it.format.height <= height) }.maxByOrNull { it.format.height }
                ?: error("Selected quality is unavailable")
            val audio = native.tracks.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && (audioId.isBlank() || it.audioTrackId == audioId || it.format.id == audioId) } ?: error("Audio track unavailable")
            val transport = DefaultHttpDataSource.Factory().setDefaultRequestProperties(stream.requestHeaders).setKeepPostFor302Redirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
            listOf(audio to audioFile, visual to videoFile).forEachIndexed { index, (track, target) ->
                com.newtube.sabr.SabrOfflineDownloader.download(native, track, audio, transport, target, { isStopped }) { bytes, position, duration ->
                    val percent = (index * 45 + (position * 45 / duration.coerceAtLeast(1))).toInt().coerceIn(0, 90)
                    setProgressAsync(workDataOf("percent" to percent, "bytes" to (audioFile.length() + videoFile.length())))
                }
            }
            mux(listOf(videoFile, audioFile), output)
        } else if (stream.audioUrl != null) {
            fetch(stream.videoUrl, stream.requestHeaders, videoFile, 0)
            fetch(stream.audioUrl, stream.requestHeaders, audioFile, 45)
            mux(listOf(videoFile, audioFile), output)
        } else {
            // Media3's exporter handles combined MP4 and HLS without a second player session.
            val source = stream.videoUrl
            if (source.substringBefore('?').endsWith(".m3u8", true)) exportHls(source, stream.requestHeaders, output)
            else fetch(source, stream.requestHeaders, output, 0, 90)
        }
    }
    private fun fetch(url: String, headers: Map<String, String>, output: File, start: Int, range: Int = 45) {
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        val request = okhttp3.Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val body = response.body ?: error("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input -> output.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024); var copied = 0L; var reported = 0L
                while (true) {
                    if (isStopped) error("Download canceled")
                    if (StatFs(output.parent).availableBytes < 32L * 1024 * 1024) error("Not enough storage")
                    val count = input.read(buffer); if (count < 0) break
                    target.write(buffer, 0, count); copied += count
                    if (System.currentTimeMillis() - reported > 750) {
                        reported = System.currentTimeMillis()
                        setProgressAsync(workDataOf("percent" to if (total > 0) start + (copied * range / total).toInt().coerceAtMost(range) else start, "bytes" to copied))
                    }
                }
                check(copied > 0 && (total <= 0 || copied == total))
            } }
        }
    }
    private suspend fun exportHls(url: String, headers: Map<String, String>, output: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers))
            val factory = androidx.media3.transformer.ExoPlayerAssetLoader.Factory(applicationContext, androidx.media3.transformer.DefaultDecoderFactory.Builder(applicationContext).build(), androidx.media3.common.util.Clock.DEFAULT, sources)
            val transformer = androidx.media3.transformer.Transformer.Builder(applicationContext).setAssetLoaderFactory(factory).addListener(object : androidx.media3.transformer.Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, result: androidx.media3.transformer.ExportResult) { if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(Unit)) }
                override fun onError(composition: androidx.media3.transformer.Composition, result: androidx.media3.transformer.ExportResult, error: androidx.media3.transformer.ExportException) { if (continuation.isActive) continuation.resumeWith(kotlin.Result.failure(error)) }
            }).build()
            continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
            transformer.start(androidx.media3.transformer.EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(url))
                .setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(androidx.media3.effect.Contrast(.0001f)))).build(), output.path)
            CoroutineScope(continuation.context).launch {
                val progress = androidx.media3.transformer.ProgressHolder()
                while (continuation.isActive) {
                    if (transformer.getProgress(progress) == androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE) setProgress(workDataOf("percent" to (progress.progress * 90 / 100), "bytes" to output.length()))
                    delay(750)
                }
            }
        }
    }
    private fun mux(inputs: List<File>, output: File) {
        val readers = inputs.map { MediaExtractor().apply { setDataSource(it.path) } }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val tracks = readers.map { extractor ->
                val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.let { mime -> mime.startsWith("video/") || mime.startsWith("audio/") } == true } ?: error("Missing media track")
                extractor.selectTrack(index); muxer.addTrack(extractor.getTrackFormat(index))
            }
            muxer.start(); started = true
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            readers.forEachIndexed { index, extractor ->
                while (true) {
                    if (isStopped) error("Download canceled")
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(tracks[index], buffer, info); extractor.advance()
                }
            }
        } finally { if (started) runCatching { muxer.stop() }; muxer.release(); readers.forEach { it.release() } }
    }
    private fun validate(file: File) {
        check(file.length() > 0)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            check(types.any { it.startsWith("video/") } && types.any { it.startsWith("audio/") }) { "Incomplete downloaded media" }
        } finally { extractor.release() }
    }
}
