package com.nikhil.niktv.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Rebuildable catalogues must not enlarge the preferences protobuf on every save. */
internal object CatalogDiskCache {
    private val access = ReentrantReadWriteLock()
    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile private var residentSearch: Pair<String, Any>? = null
    private val json = Json { ignoreUnknownKeys = true }
    fun clear(context: Context) = access.write {
        residentSearch = null
        File(context.cacheDir, "catalogues-v1").listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }
    private fun file(context: Context, key: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "catalogues-v1").apply { mkdirs() }
        return AtomicFile(File(directory, "$name.json"))
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> read(context: Context, key: String): T? = withContext(Dispatchers.IO) {
        val identity = "${context.cacheDir.absolutePath}:$key"
        access.read { synchronized(locks.getOrPut(identity) { Any() }) {
            residentSearch?.takeIf { it.first == identity && file(context, key).baseFile.exists() }?.second?.let { cached ->
                if (cached is T) return@synchronized cached
            }
            try { file(context, key).openRead().buffered(64 * 1024).use { json.decodeFromStream<T>(it) }.also { value ->
                // One resident search catalogue only, with a heap-relative size
                // cap. Other types/profiles replace it; large files stay disk-backed.
                if (value != null && key.startsWith("search:") && file(context, key).baseFile.length() <= Runtime.getRuntime().maxMemory() / 12) {
                    residentSearch = identity to (value as Any)
                }
            } }
            catch (_: java.io.IOException) { null }
            catch (_: kotlinx.serialization.SerializationException) { null }
        } }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> write(context: Context, key: String, value: T) = withContext(Dispatchers.IO) {
        val identity = "${context.cacheDir.absolutePath}:$key"
        access.read { synchronized(locks.getOrPut(identity) { Any() }) {
            val destination = file(context, key)
            val output = destination.startWrite()
            try {
                // The streaming JSON encoder emits many tiny writes. Batch them
                // instead of issuing a filesystem write for each field/character.
                // Keep the raw stream open for AtomicFile's sync/commit operation.
                val buffered = java.io.BufferedOutputStream(output, 64 * 1024)
                json.encodeToStream(value, buffered)
                buffered.flush()
                destination.finishWrite(output)
                if (residentSearch?.first == identity) residentSearch = null
            } catch (error: Throwable) {
                destination.failWrite(output)
                throw error
            }
        } }
    }
}
