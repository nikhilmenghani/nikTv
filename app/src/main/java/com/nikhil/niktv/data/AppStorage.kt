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
    val temporaryCacheBytes: Long,
    val recordingBytes: Long = 0L,
    val mediaCacheBytes: Long = 0L,
    val nikTubeDownloadBytes: Long = 0L
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    val flowArtworkDirectory = File(appContext.cacheDir, "image_cache")
    val subtitleDirectory = File(appContext.cacheDir, "subtitles")
    val hlsDirectory = File(appContext.cacheDir, "hls_exports")
    val namedCacheBytes = directorySize(artworkDirectory) + directorySize(flowArtworkDirectory) + directorySize(subtitleDirectory)
    val allCacheBytes = directorySize(appContext.cacheDir) +
        (appContext.externalCacheDir?.let(::directorySize) ?: 0L)
    android.util.Log.i("NikTvStorage", "catalogues=${directorySize(File(appContext.cacheDir, "catalogues-v1"))}, artwork=${directorySize(artworkDirectory)}, other=${(allCacheBytes - namedCacheBytes - directorySize(File(appContext.cacheDir, "catalogues-v1"))).coerceAtLeast(0L)} bytes")

    AppStorageSnapshot(
        totalBytes = stats.totalBytes,
        availableBytes = stats.availableBytes,
        offlineDownloadBytes = trackedPublicBytes,
        nikTubeDownloadBytes = NikTubeDownloads.bytes(appContext),
        mediaCacheBytes = OfflineMediaDownloads.cachedMediaBytes(appContext) + directorySize(hlsDirectory),
        artworkCacheBytes = directorySize(artworkDirectory) + directorySize(flowArtworkDirectory),
        subtitleCacheBytes = directorySize(subtitleDirectory),
        hlsWorkingBytes = 0L,
        temporaryCacheBytes = (allCacheBytes - namedCacheBytes - directorySize(hlsDirectory)).coerceAtLeast(0L),
        recordingBytes = LiveTvRecorder.recordings(appContext, includeDuration = false)
            .distinctBy { it.uri }.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    )
}

suspend fun clearArtworkCache(context: Context): Long = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val directory = File(appContext.cacheDir, "artwork_cache")
    val flowDirectory = File(appContext.cacheDir, "image_cache")
    val before = directorySize(directory) + directorySize(flowDirectory)
    runCatching { appContext.imageLoader.memoryCache?.clear() }
    runCatching { appContext.imageLoader.diskCache?.clear() }
    deleteDirectoryContents(directory)
    val after = directorySize(directory) + directorySize(flowDirectory)
    (before - after).coerceAtLeast(0L)
}

suspend fun clearSubtitleCache(context: Context): Long =
    clearDirectory(context, File(context.cacheDir, "subtitles"))

suspend fun clearHlsWorkingFiles(context: Context): Long =
    clearDirectory(context, File(context.cacheDir, "hls_exports"))

suspend fun clearTemporaryCaches(context: Context): Long = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    // Flow owns live SimpleCache/OkHttp instances. Deleting their files here would
    // invalidate open indexes; its own storage screen clears them through their owners.
    val excluded = setOf("artwork_cache", "image_cache", "subtitles", "hls_exports", "data",
        "exoplayer", "exoplayer_music", "http_cache")
    val before = unnamedCacheSize(appContext, excluded)
    deleteDirectoryContents(appContext.cacheDir, excluded, setOf("workdb.sqlite"))
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
        ?.filterNot { it.name in excludedNames || it.name.startsWith("workdb.sqlite") }
        ?.sumOf(::directorySize) ?: 0L) +
        (context.externalCacheDir?.let(::directorySize) ?: 0L)

internal fun directorySize(directory: File): Long {
    if (!directory.exists()) return 0L
    if (directory.isFile) return directory.length().coerceAtLeast(0L)
    return directory.listFiles()?.sumOf(::directorySize) ?: 0L
}

private fun deleteDirectoryContents(
    directory: File,
    excludedNames: Set<String> = emptySet(),
    excludedPrefixes: Set<String> = emptySet()
) {
    directory.listFiles()?.filterNot { child ->
        child.name in excludedNames || excludedPrefixes.any(child.name::startsWith)
    }?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
    }
}
