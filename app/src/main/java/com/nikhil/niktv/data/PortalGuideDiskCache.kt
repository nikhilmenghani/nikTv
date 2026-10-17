package com.nikhil.niktv.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.security.MessageDigest

/** Rebuildable programme metadata only. At most two profile files; no Room or credentials. */
@OptIn(ExperimentalSerializationApi::class)
internal class PortalGuideDiskCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "live-guide-v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(key: String): File = File(directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    suspend fun read(key: String): PortalGuideCache? = withContext(Dispatchers.IO) {
        val file = file(key)
        if (!file.exists() || file.length() > 8 * 1024 * 1024L) return@withContext null
        AtomicFile(file).openRead().buffered(64 * 1024).use { json.decodeFromStream<PortalGuideCache>(it) }
            .takeIf { it.key == key }
    }

    suspend fun write(cache: PortalGuideCache) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val destination = AtomicFile(file(cache.key))
        val output = destination.startWrite()
        try {
            val buffered = java.io.BufferedOutputStream(output, 64 * 1024)
            json.encodeToStream(cache, buffered)
            buffered.flush()
            if (output.channel.size() > 8 * 1024 * 1024L) throw java.io.IOException("Programme cache exceeds its size limit")
            destination.finishWrite(output)
        } catch (error: Exception) {
            destination.failWrite(output)
            throw error
        }
        directory.listFiles()?.filter { it.name.endsWith(".json") }
            ?.filter { it != file(cache.key) }?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
        Unit
    }
}
