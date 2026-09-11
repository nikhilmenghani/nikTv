package com.nikhil.niktv.data

import android.content.Context
import android.os.StatFs
import coil3.imageLoader
import com.nikhil.niktv.model.OfflineMediaDownload
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppStorageSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val offlineDownloadBytes: Long,
    val cacheBytes: Long
)

suspend fun appStorageSnapshot(
    context: Context,
    offlineDownloads: List<OfflineMediaDownload>
): AppStorageSnapshot = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val stats = StatFs(appContext.filesDir.absolutePath)

    // OFFLINE_OWNERSHIP_STORAGE_V47
    // Public Downloads/NikTV is shared with update APKs and may contain user files.
    // Count only public files that still have an explicit NikTV offline-download record.
    // Legacy Media3 downloads live in NikTV's private cache and are counted once via
    // cachedMediaBytes(), avoiding both unrelated public files and double-counting.
    val trackedPublicBytes = offlineDownloads
        .asSequence()
        .filter { it.downloadId >= 0L || it.requestId.startsWith("hls-export:") }
        .distinctBy { "${it.downloadId}|${it.requestId}" }
        .sumOf { entry ->
            OfflineMediaDownloads.info(
                appContext,
                entry.requestId,
                entry.downloadId
            ).bytesDownloaded.coerceAtLeast(0L)
        }

    AppStorageSnapshot(
        totalBytes = stats.totalBytes,
        availableBytes = stats.availableBytes,
        offlineDownloadBytes =
            OfflineMediaDownloads.cachedMediaBytes(appContext) + trackedPublicBytes,
        cacheBytes = directorySize(appContext.cacheDir) +
            (appContext.externalCacheDir?.let(::directorySize) ?: 0L)
    )
}

suspend fun clearAppCaches(context: Context): Long = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val before = directorySize(appContext.cacheDir) +
        (appContext.externalCacheDir?.let(::directorySize) ?: 0L)
    runCatching { appContext.imageLoader.memoryCache?.clear() }
    runCatching { appContext.imageLoader.diskCache?.clear() }
    deleteDirectoryContents(appContext.cacheDir, excludedNames = setOf("hls_exports"))
    appContext.externalCacheDir?.let(::deleteDirectoryContents)
    val after = directorySize(appContext.cacheDir) +
        (appContext.externalCacheDir?.let(::directorySize) ?: 0L)
    (before - after).coerceAtLeast(0L)
}

internal fun directorySize(directory: File): Long {
    if (!directory.exists()) return 0L
    if (directory.isFile) return directory.length().coerceAtLeast(0L)
    return directory.listFiles()?.sumOf(::directorySize) ?: 0L
}

private fun deleteDirectoryContents(directory: File, excludedNames: Set<String> = emptySet()) {
    directory.listFiles()?.filterNot { it.name in excludedNames }?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
    }
}
