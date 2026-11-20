package com.nikhil.niktv.update

import android.app.DownloadManager
import android.net.Uri
import java.io.File

internal data class UpdateInstallerDownload(
    val id: Long,
    val file: File,
    val bytes: Long,
    val completed: Boolean,
)

internal fun DownloadManager.installerDownloads(): List<UpdateInstallerDownload> =
    query(DownloadManager.Query())?.use { cursor ->
        val localUriColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
        val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
        val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
        val bytesColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        buildList {
            while (cursor.moveToNext()) {
                val uri = cursor.getString(localUriColumn)?.let(Uri::parse)
                val path = uri?.takeIf { it.scheme == "file" && it.authority.isNullOrEmpty() }?.path
                    ?: continue
                add(UpdateInstallerDownload(
                    cursor.getLong(idColumn),
                    File(path),
                    cursor.getLong(bytesColumn).coerceAtLeast(0L),
                    cursor.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL,
                ))
            }
        }
    }.orEmpty()

private val installerName = Regex(
    """NikTV-(dev-v|v)?(\d+\.\d+\.\d+)(?:-(?:firetv|arm64-v8a|armeabi-v7a|universal))?(?: \(\d+\))?\.apk""",
    RegexOption.IGNORE_CASE,
)

internal fun olderUpdateInstallers(
    downloadDirectory: File,
    channel: UpdateChannel,
    installedVersion: String,
    downloads: List<UpdateInstallerDownload>,
    protectedDownloadId: Long,
): List<UpdateInstallerDownload> {
    val directory = downloadDirectory.canonicalFile
    fun isOlderInstaller(file: File): Boolean {
        if (file.parentFile != directory) return false
        val match = installerName.matchEntire(file.name) ?: return false
        val (prefix, version) = match.destructured
        if (prefix.equals("dev-v", ignoreCase = true) != channel.development) return false
        if (version.split('.').any { it.toIntOrNull() == null }) return false
        return AppUpdates.compareAppVersions(version, installedVersion) < 0
    }

    val registered = downloads.mapNotNull { download ->
        val path = runCatching { download.file.parentFile?.canonicalFile }.getOrNull()
        if (path != directory) return@mapNotNull null
        val file = File(directory, download.file.name)
        // Download Manager can expose a legacy alias for the parent. Check the actual leaf as well.
        if (runCatching { file.canonicalFile }.getOrNull() != file || !isOlderInstaller(file)) {
            return@mapNotNull null
        }
        download.copy(file = file)
    }.groupBy { it.file }

    return buildList {
        for (records in registered.values) {
            if (records.all { it.completed && it.id != protectedDownloadId }) add(records.first())
        }
        for (file in directory.listFiles().orEmpty()) {
            if (file in registered || !isOlderInstaller(file) || !file.isFile ||
                runCatching { file.canonicalFile }.getOrNull() != file) continue
            add(UpdateInstallerDownload(NO_DOWNLOAD_ID, file, file.length(), completed = true))
        }
    }
}

internal const val NO_DOWNLOAD_ID = -1L
