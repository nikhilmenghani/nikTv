package com.nikhil.niktv.torrent

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import com.nikhil.niktv.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class TorrentDownloadJob(
    val id: String, val identity: String, val title: String, val source: String,
    val fileIndex: Int, val fileName: String, val sizeBytes: Long, val owner: UserScope,
    val state: String = "queued", val bytes: Long = 0, val error: String? = null,
    val genericFile: Boolean = false
) {
    val extension get() = fileName.substringAfterLast('.', if (genericFile) "bin" else "mp4").lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
    val outputName get() = "$id.$extension"
}

/** Device-local ownership records. Source URLs never enter synced profile metadata or WorkData. */
internal object TorrentOfflineDownloads {
    const val PREFIX = "torrent-download:"
    private val lock = Any()
    private val active = mutableSetOf<String>()
    private val cancelled = mutableSetOf<String>()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun enqueue(context: Context, identity: String, title: String, source: String,
        fileIndex: Int, fileName: String, sizeBytes: Long, owner: UserScope,
        genericFile: Boolean = false): OfflineDownloadHandle = withContext(Dispatchers.IO) {
        require(identity.isNotBlank() && identity.length <= 2048 && source.length <= 65536)
        val localFixture = com.nikhil.niktv.BuildConfig.DEBUG && context.packageName.endsWith(".regression") &&
            TorrentDownloadWorker.regressionOptions?.allowLocalDescriptor == true && Uri.parse(source).scheme == "file"
        require(localFixture || source.startsWith("magnet:?", true) || Uri.parse(source).scheme?.lowercase() in setOf("http", "https")) { "Choose a magnet or HTTPS torrent source" }
        require(fileName.isNotBlank() && fileName.length <= 4096 && sizeBytes > 0)
        val id = UUID.randomUUID()
        val job = TorrentDownloadJob(id.toString(), identity, title.take(200), source, fileIndex, fileName, sizeBytes, owner,
            genericFile = genericFile)
        synchronized(lock) { save(context, job) }
        val work = OneTimeWorkRequestBuilder<TorrentDownloadWorker>().setId(id)
            .setInputData(workDataOf(TorrentDownloadWorker.KEY_JOB to id.toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        try { WorkManager.getInstance(context.applicationContext).enqueue(work).result.get(10, TimeUnit.SECONDS) }
        catch (error: Exception) { synchronized(lock) { save(context, job.copy(state = "failed", error = "Could not queue download")) }; throw error }
        OfflineDownloadHandle(requestId = PREFIX + id, fileType = job.extension.uppercase())
    }

    fun info(context: Context, requestId: String): OfflineDownloadInfo {
        val job = load(context, requestId.removePrefix(PREFIX)) ?: return OfflineDownloadInfo(OfflineDownloadStatus.MISSING)
        if (synchronized(lock) { job.id in cancelled }) return OfflineDownloadInfo(OfflineDownloadStatus.FAILED)
        val completed = ownedOutput(context, job).takeIf { it.isFile && it.length() == job.sizeBytes }
        if (job.state == "complete") return if (completed != null) OfflineDownloadInfo(OfflineDownloadStatus.COMPLETE, 100f, job.sizeBytes, job.sizeBytes)
            else OfflineDownloadInfo(OfflineDownloadStatus.MISSING)
        val state = when (job.state) {
            "failed", "cancelled" -> OfflineDownloadStatus.FAILED
            "downloading" -> OfflineDownloadStatus.DOWNLOADING
            else -> OfflineDownloadStatus.QUEUED
        }
        return OfflineDownloadInfo(state, (job.bytes.toDouble() * 100 / job.sizeBytes).toFloat().coerceIn(0f, 99f), job.bytes, job.sizeBytes)
    }

    fun playableUri(context: Context, requestId: String): String? {
        val job = load(context, requestId.removePrefix(PREFIX)) ?: return null
        if (job.genericFile) return null
        return ownedOutput(context, job).takeIf { job.state == "complete" && it.isFile && it.length() == job.sizeBytes }?.let { Uri.fromFile(it).toString() }
    }
    fun files(context: Context, owner: UserScope): List<TorrentDownloadJob> =
        File(context.filesDir, "torrent_download_jobs").listFiles().orEmpty()
            .filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
            .mapNotNull { load(context, it.nameWithoutExtension) }
            .filter { it.owner == owner && it.genericFile && it.state != "cancelled" }

    fun fileUri(context: Context, job: TorrentDownloadJob): Uri? {
        val saved = load(context, job.id) ?: return null
        if (!saved.genericFile || saved.owner != job.owner || saved.state != "complete") return null
        val file = ownedOutput(context, saved).takeIf { it.isFile && it.length() == saved.sizeBytes } ?: return null
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun remove(context: Context, requestId: String, onComplete: () -> Unit = {}) {
        val id = validId(requestId.removePrefix(PREFIX)) ?: return onComplete()
        val app = context.applicationContext
        cleanup.launch {
            try {
                synchronized(lock) {
                    cancelled.add(id)
                    load(app, id)?.let {
                        runCatching { save(app, it.copy(state = "cancelled", source = "")) }
                        ownedOutput(app, it).delete()
                    }
                }
                WorkManager.getInstance(app).cancelWorkById(UUID.fromString(id))
                synchronized(lock) { if (id !in active) deletePartial(app, id) }
            } catch (_: Exception) {
                // Cancellation is best-effort on a full/unavailable disk, never
                // an uncaught background-coroutine crash. The in-memory tombstone
                // also prevents a running worker from publishing after removal.
            } finally {
                onComplete()
            }
        }
    }

    internal fun claim(context: Context, id: String): TorrentDownloadJob? = synchronized(lock) {
        load(context, id)?.takeIf { it.state != "cancelled" && id !in cancelled && id !in active }?.also { active.add(id) }
    }

    internal fun update(context: Context, id: String, state: String, bytes: Long = 0, error: String? = null) = synchronized(lock) {
        load(context, id)?.takeIf { it.state != "cancelled" && it.state != "complete" }?.let {
            save(context, it.copy(state = state, bytes = bytes.coerceIn(0, it.sizeBytes), error = error))
        }
    }

    internal fun recoverCompleted(context: Context, job: TorrentDownloadJob): String? = synchronized(lock) {
        val latest = load(context, job.id) ?: return null
        if (latest.state == "cancelled" || job.id in cancelled) return null
        val file = ownedOutput(context, latest)
        if (!file.isFile || file.length() != latest.sizeBytes) return null
        save(context, latest.copy(state = "complete", source = "", bytes = latest.sizeBytes, error = null))
        Uri.fromFile(file).toString()
    }

    internal fun publish(context: Context, job: TorrentDownloadJob, verified: File): String = synchronized(lock) {
        check(job.id !in cancelled && load(context, job.id)?.state != "cancelled") { "Download cancelled" }
        val partial = File(context.filesDir, "torrent_partial/${job.id}").canonicalFile
        require(verified.canonicalPath.startsWith(partial.path + File.separator) && verified.isFile && verified.length() == job.sizeBytes)
        val output = ownedOutput(context, job)
        output.parentFile?.mkdirs()
        check(!output.exists() && verified.renameTo(output)) { "Could not finish the offline video" }
        save(context, job.copy(state = "complete", source = "", bytes = job.sizeBytes, error = null))
        Uri.fromFile(output).toString()
    }

    internal fun release(context: Context, id: String, removePartial: Boolean) = synchronized(lock) {
        active.remove(id)
        if (removePartial || id in cancelled || load(context, id)?.state == "cancelled") deletePartial(context, id)
    }

    private fun ownedOutput(context: Context, job: TorrentDownloadJob): File = File(context.filesDir, "torrent_downloads/${job.outputName}")
    private fun deletePartial(context: Context, id: String) {
        if (validId(id) == null) return
        val root = File(context.filesDir, "torrent_partial").canonicalFile
        File(root, id).canonicalFile.takeIf { it.parentFile == root }?.deleteRecursively()
    }
    private fun validId(id: String): String? = runCatching { UUID.fromString(id).toString().takeIf { it == id } }.getOrNull()
    internal fun load(context: Context, id: String): TorrentDownloadJob? = synchronized(lock) {
        validId(id) ?: return null
        runCatching {
            val json = JSONObject(AtomicFile(File(context.filesDir, "torrent_download_jobs/$id.json")).openRead().bufferedReader().use { it.readText() })
            TorrentDownloadJob(id, json.getString("identity"), json.getString("title"), json.getString("source"),
                json.getInt("fileIndex"), json.getString("fileName"), json.getLong("sizeBytes"),
                UserScope(json.getString("accountId"), json.getString("profileId")), json.optString("state", "queued"), json.optLong("bytes"), json.optString("error").takeIf(String::isNotBlank),
                json.optBoolean("genericFile", false))
        }.getOrNull()
    }
    private fun save(context: Context, job: TorrentDownloadJob) {
        require(validId(job.id) != null)
        val root = File(context.filesDir, "torrent_download_jobs").apply { mkdirs() }
        val atomic = AtomicFile(File(root, "${job.id}.json"))
        val json = JSONObject().put("identity", job.identity).put("title", job.title).put("source", job.source)
            .put("fileIndex", job.fileIndex).put("fileName", job.fileName).put("sizeBytes", job.sizeBytes)
            .put("accountId", job.owner.accountId).put("profileId", job.owner.profileId).put("state", job.state)
            .put("bytes", job.bytes).put("error", job.error.orEmpty()).put("genericFile", job.genericFile)
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
