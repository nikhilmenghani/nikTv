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

/** Rebuildable catalogues must not enlarge the preferences protobuf on every save. */
internal object CatalogDiskCache {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    fun clear(context: Context) = synchronized(lock) {
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
        synchronized(lock) {
            try { file(context, key).openRead().use { json.decodeFromStream<T>(it) } }
            catch (_: java.io.IOException) { null }
            catch (_: kotlinx.serialization.SerializationException) { null }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> write(context: Context, key: String, value: T) = withContext(Dispatchers.IO) {
        synchronized(lock) {
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
            } catch (error: Throwable) {
                destination.failWrite(output)
                throw error
            }
        }
    }
}
