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
    val artworkCacheBytes: Long,
    val subtitleCacheBytes: Long,
    val hlsWorkingBytes: Long,
    val temporaryCacheBytes: Long
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

    val artworkDirectory = File(appContext.cacheDir, "artwork_cache")
    val subtitleDirectory = File(appContext.cacheDir, "subtitles")
    val hlsDirectory = File(appContext.cacheDir, "hls_exports")
    val namedCacheBytes = directorySize(artworkDirectory) +
        directorySize(subtitleDirectory) + directorySize(hlsDirectory)
    val allCacheBytes = directorySize(appContext.cacheDir) +
        (appContext.externalCacheDir?.let(::directorySize) ?: 0L)

    AppStorageSnapshot(
        totalBytes = stats.totalBytes,
        availableBytes = stats.availableBytes,
        offlineDownloadBytes =
            OfflineMediaDownloads.cachedMediaBytes(appContext) + trackedPublicBytes,
        artworkCacheBytes = directorySize(artworkDirectory),
        subtitleCacheBytes = directorySize(subtitleDirectory),
        hlsWorkingBytes = directorySize(hlsDirectory),
        temporaryCacheBytes = (allCacheBytes - namedCacheBytes).coerceAtLeast(0L)
    )
}

suspend fun clearArtworkCache(context: Context): Long = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val directory = File(appContext.cacheDir, "artwork_cache")
    val before = directorySize(directory)
    runCatching { appContext.imageLoader.memoryCache?.clear() }
    runCatching { appContext.imageLoader.diskCache?.clear() }
    val after = directorySize(directory)
    (before - after).coerceAtLeast(0L)
}

suspend fun clearSubtitleCache(context: Context): Long =
    clearDirectory(context, File(context.cacheDir, "subtitles"))

suspend fun clearHlsWorkingFiles(context: Context): Long =
    clearDirectory(context, File(context.cacheDir, "hls_exports"))

suspend fun clearTemporaryCaches(context: Context): Long = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val excluded = setOf("artwork_cache", "subtitles", "hls_exports")
    val before = unnamedCacheSize(appContext, excluded)
    deleteDirectoryContents(appContext.cacheDir, excluded)
    appContext.externalCacheDir?.let(::deleteDirectoryContents)
    (before - unnamedCacheSize(appContext, excluded)).coerceAtLeast(0L)
}

private suspend fun clearDirectory(context: Context, directory: File): Long =
    withContext(Dispatchers.IO) {
        val before = directorySize(directory)
        deleteDirectoryContents(directory)
        (before - directorySize(directory)).coerceAtLeast(0L)
    }

private fun unnamedCacheSize(context: Context, excludedNames: Set<String>): Long =
    (context.cacheDir.listFiles()
        ?.filterNot { it.name in excludedNames }
        ?.sumOf(::directorySize) ?: 0L) +
        (context.externalCacheDir?.let(::directorySize) ?: 0L)

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
