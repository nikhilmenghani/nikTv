package com.nikhil.niktv.data

import android.content.Context
import android.os.StatFs
import com.nikhil.niktv.model.OfflineMediaDownload
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalType
import com.nikhil.niktv.torrent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal fun storagePortalId(key: String): String = when (key) {
    "torrents", "sports", "playfy" -> key
    "" -> "unassigned"
    else -> (if (key.startsWith("catalog-v5|STALKER|")) "stalker:" else if (key.startsWith("catalog-v5|XTREAM|")) "xtream:" else "iptv:") + MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun storagePortalLabel(id: String, portals: List<PortalProfile>): String = when (id) {
    "torrents" -> "Torrents"
    "sports" -> "Sports"
    "playfy" -> "Playfy TV"
    "unassigned" -> "Other / older files"
    else -> portals.firstOrNull { storagePortalId(it.cacheKey()) == id }?.displayName
        ?: if (id.startsWith("stalker:")) "Stalker" else if (id.startsWith("xtream:")) "Xtream" else "Previous IPTV connection"
}

internal enum class StorageFileKind(val label: String) {
    STREAM("Stream cache"), DOWNLOAD("Offline download"), RECORDING("Live TV recording"), FILE("Downloaded file")
}
internal data class ProfileStorageFile(
    val id: String, val title: String, val portal: String, val kind: StorageFileKind,
    val bytes: Long, val inUse: Boolean = false, val status: String? = null,
    val download: OfflineMediaDownload? = null, val jobId: String? = null,
    val streamId: String? = null, val recording: RecordedLiveTvMedia? = null
)
internal data class ProfileStorageSnapshot(
    val totalBytes: Long, val availableBytes: Long, val files: List<ProfileStorageFile>,
    val artworkBytes: Long, val subtitleBytes: Long, val temporaryBytes: Long, val legacyMediaBytes: Long
)
internal data class StorageClearResult(val removed: Int, val protected: Int)

/** Physical files are device-local. Nothing here edits viewing history, portals or sync records. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object ProfileStorage {
    private fun streamOwner(owner: UserScope) = "${owner.accountId}|${owner.profileId}"

    suspend fun snapshot(context: Context, owner: UserScope?): ProfileStorageSnapshot = withContext(Dispatchers.IO) {
        val stats = StatFs(context.filesDir.path)
        val files = if (owner == null) emptyList() else files(context, owner)
        val shared = appStorageSnapshot(context, emptyList())
        ProfileStorageSnapshot(stats.totalBytes, stats.availableBytes, files, shared.artworkCacheBytes,
            shared.subtitleCacheBytes, shared.temporaryCacheBytes, OfflineMediaDownloads.cachedMediaBytes(context))
    }

    private suspend fun files(context: Context, owner: UserScope): List<ProfileStorageFile> {
        val downloads = ProfileStore(context).scoped(owner).offlineDownloads.first()
        val jobs = TorrentOfflineDownloads.jobs(context, owner)
        val activePlayback = CatalogPlaybackActivity.playing
        val output = mutableListOf<ProfileStorageFile>()
        downloads.forEach { entry ->
            val job = jobs.firstOrNull { entry.requestId == TorrentOfflineDownloads.PREFIX + it.id }
            val info = OfflineMediaDownloads.info(context, entry.requestId, entry.downloadId)
            if (info.status == OfflineDownloadStatus.MISSING && job == null) return@forEach
            output += ProfileStorageFile("download:${entry.key}", entry.media.title, storagePortalId(entry.profileKey),
                StorageFileKind.DOWNLOAD, job?.let { TorrentOfflineDownloads.storedBytes(context, it) } ?: info.bytesDownloaded,
                inUse = activePlayback, status = info.status.name.lowercase().replaceFirstChar(Char::uppercase),
                download = entry, jobId = job?.id)
        }
        jobs.filter { job -> downloads.none { it.requestId == TorrentOfflineDownloads.PREFIX + job.id } }.forEach { job ->
            output += ProfileStorageFile("job:${job.id}", job.title, "torrents",
                if (job.genericFile) StorageFileKind.FILE else StorageFileKind.DOWNLOAD,
                TorrentOfflineDownloads.storedBytes(context, job), inUse = activePlayback && !job.genericFile,
                status = job.state.replaceFirstChar(Char::uppercase), jobId = job.id)
        }
        TorrentStreamCache.entries(context, streamOwner(owner)).forEach { stream ->
            output += ProfileStorageFile("stream:${stream.id}", stream.names.firstOrNull() ?: "Torrent stream",
                "torrents", StorageFileKind.STREAM, stream.bytes, stream.inUse,
                status = if (stream.inUse) "In use" else "Saved chunks · ${stream.names.size.coerceAtLeast(1)} file(s)", streamId = stream.id)
        }
        val ownership = RecordingOwnership(context)
        val live = LiveTvRecorder.state.value
        ownership.visible(LiveTvRecorder.recordings(context, includeDuration = false), owner).forEach { recording ->
            output += ProfileStorageFile("recording:${recording.uri}", recording.title,
                ownership.portal(recording.uri) ?: "unassigned", StorageFileKind.RECORDING, recording.sizeBytes,
                inUse = live.active && live.owner == owner && (live.outputUri == null || live.outputUri == recording.uri.toString()),
                recording = recording)
        }
        return output
    }

    /** Re-read the captured owner before each deletion; a stale page cannot broaden the selection. */
    suspend fun clear(context: Context, owner: UserScope, ids: Set<String>): StorageClearResult = withContext(Dispatchers.IO) {
        val users = UserSyncStore.get(context)
        check(users.selectedAccount.value == owner.accountId && users.profiles.first().any { it.id == owner.profileId }) { "Reopen Storage for this profile." }
        val targets = files(context, owner).filter { it.id in ids }
        var removed = 0
        var protected = 0
        for (item in targets) {
            if (item.inUse) { protected++; continue }
            if (item.streamId != null) {
                if (TorrentStreamCache.clearEntry(context, streamOwner(owner), item.streamId)) removed++ else protected++
                continue
            }
            if (item.recording != null) {
                val current = LiveTvRecorder.state.value
                if (current.active && current.owner == owner && (current.outputUri == null || current.outputUri == item.recording.uri.toString())) protected++
                else if (LiveTvRecorder.delete(context, item.recording)) removed++
                else error("Could not remove this recording. Check file access in Android settings.")
                continue
            }
            val entry = item.download
            if (entry != null) {
                // Older downloads can be referenced by two profiles. Remove this
                // person's record while preserving any other person's physical file.
                val profiles = users.profiles.first().filter { it.id != owner.profileId }
                val shared = profiles.any { profile ->
                    ProfileStore(context).scoped(UserScope(owner.accountId, profile.id)).offlineDownloads.first().any {
                        if (entry.downloadId >= 0) it.downloadId == entry.downloadId
                        else entry.requestId.isNotBlank() && it.requestId == entry.requestId
                    }
                }
                if (ProfileStore(context).removeOfflineDownload(entry.key, entry.requestId, owner)) {
                    if (!shared) OfflineMediaDownloads.remove(context, entry.requestId, entry.downloadId)
                    if (entry.profileKey == TORRENT_OFFLINE_PROFILE) TorrentOfflineLibrary.forget(context, owner, entry)
                    removed++
                }
            } else if (item.jobId != null) {
                val job = TorrentOfflineDownloads.load(context, item.jobId)
                check(job?.owner == owner) { "This download belongs to another profile." }
                TorrentOfflineDownloads.remove(context, TorrentOfflineDownloads.PREFIX + item.jobId)
                removed++
            }
        }
        StorageClearResult(removed, protected)
    }

    suspend fun clearShared(context: Context, kind: String) = withContext(Dispatchers.IO) {
        check(!CatalogPlaybackActivity.playing) { "Stop playback before clearing shared caches." }
        when (kind) {
            "artwork" -> clearArtworkCache(context)
            "subtitles" -> clearSubtitleCache(context)
            "temporary" -> clearTemporaryCaches(context)
            "legacy" -> {
                check(OfflineMediaDownloads.manager(context).currentDownloads.none {
                    it.state in setOf(androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING, androidx.media3.exoplayer.offline.Download.STATE_QUEUED)
                }) { "Wait for offline downloads to finish before clearing shared media cache." }
                val cache = OfflineMediaDownloads.cache(context)
                cache.keys.toList().forEach { cache.removeResource(it) }
                Unit
            }
            else -> error("Unknown cache type")
        }
    }
}
