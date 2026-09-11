package com.nikhil.niktv.data

import android.content.Context
import android.os.StatFs
import android.os.Environment
import coil3.imageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppStorageSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val offlineDownloadBytes: Long,
    val cacheBytes: Long
)

suspend fun appStorageSnapshot(context: Context): AppStorageSnapshot = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val stats = StatFs(appContext.filesDir.absolutePath)
    AppStorageSnapshot(
        totalBytes = stats.totalBytes,
        availableBytes = stats.availableBytes,
        offlineDownloadBytes = directorySize(File(appContext.filesDir, "offline_media_cache")) +
            offlineMediaDirectorySize(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NikTV")
            ) +
            // Keep legacy exports visible in storage totals without moving/deleting them.
            directorySize(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NikTV Offline")
            ),
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

private val offlineMediaExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")

private fun offlineMediaDirectorySize(directory: File): Long {
    if (!directory.exists()) return 0L
    if (directory.isFile) {
        return if (directory.extension.lowercase() in offlineMediaExtensions) {
            directory.length().coerceAtLeast(0L)
        } else {
            0L
        }
    }
    return directory.listFiles()?.sumOf(::offlineMediaDirectorySize) ?: 0L
}

private fun deleteDirectoryContents(directory: File, excludedNames: Set<String> = emptySet()) {
    directory.listFiles()?.filterNot { it.name in excludedNames }?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
    }
}
