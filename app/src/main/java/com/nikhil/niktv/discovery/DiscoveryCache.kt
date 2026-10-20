package com.nikhil.niktv.discovery

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

@Serializable
internal data class DiscoveryCacheEntry(val savedAt: Long, val payload: String) {
    fun fresh(now: Long, ttl: Long) = now - savedAt in 0 until ttl
}

internal class DiscoveryCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 10L * 1024 * 1024) {
    private val mutex get() = diskMutex
    private val json = Json { ignoreUnknownKeys = true }
    private class Flight(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val loads get() = sharedLoads
    suspend fun <T> load(key: String, ttl: Long, serializer: KSerializer<T>, stale: (T) -> Unit = {}, network: suspend () -> T): T {
        val flightKey = "${directory.absolutePath}:$key"
        val flight = synchronized(loads) { loads.getOrPut(flightKey) { Flight() }.also { it.users++ } }
        try { return flight.mutex.withLock {
            val entry = read(key)
            val old = withContext(Dispatchers.IO) { entry?.let { runCatching { json.decodeFromString(serializer, it.payload) }.getOrNull() } }
            if (old != null) { stale(old); if (entry?.fresh(now(), ttl) == true) return@withLock old }
            try {
                val value = network()
                runCatching { write(key, withContext(Dispatchers.IO) { json.encodeToString(serializer, value) }) }.onFailure { if (it is CancellationException) throw it }
                value
            } catch (c: CancellationException) { throw c }
              catch (error: Exception) { old ?: throw error }
        } } finally { synchronized(loads) { flight.users--; if (flight.users == 0) loads.remove(flightKey) } }
    }
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    suspend fun read(key: String): DiscoveryCacheEntry? = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching {
            val f = file(key)
            if (!f.isFile || f.length() > maxBytes) null else json.decodeFromString<DiscoveryCacheEntry>(f.readText())
        }.getOrNull() }
    }
    suspend fun write(key: String, payload: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.mkdirs()
            val encoded = json.encodeToString(DiscoveryCacheEntry.serializer(), DiscoveryCacheEntry(now(), payload))
            if (encoded.toByteArray().size > maxBytes) return@withLock
            val destination = file(key)
            val temp = File(directory, destination.name + ".tmp")
            temp.writeText(encoded)
            if (!temp.renameTo(destination)) { destination.delete(); check(temp.renameTo(destination)) }
            val files = directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()
            var total = 0L
            files.forEach { total += it.length(); if (total > maxBytes) it.delete() }
        }
    }
    companion object {
        private val diskMutex = Mutex()
        private val sharedLoads = mutableMapOf<String, Flight>()
        const val FEED_TTL = 6 * 60 * 60_000L; const val DETAIL_TTL = 24 * 60 * 60_000L
    }
}
