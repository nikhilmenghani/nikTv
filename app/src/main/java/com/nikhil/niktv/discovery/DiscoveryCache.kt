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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class DiscoveryCacheEntry(val savedAt: Long, val payload: String) {
    fun fresh(now: Long, ttl: Long) = now - savedAt in 0 until ttl
}

internal class DiscoveryCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 10L * 1024 * 1024) {
    private val mutex get() = diskMutex
    private val json = Json { ignoreUnknownKeys = true }
    private var artworkIndex: Pair<Long, List<DiscoveryCard>>? = null

    /** Legacy history can lack a TMDB ID. Reuse only an unambiguous exact public
     * title/type match. Build the bounded disk index off-thread, once per cache
     * directory change; scrolling and focus never initiate network lookups. */
    suspend fun findArtwork(title: String, series: Boolean): DiscoveryCard? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stamp = directory.lastModified()
            val cards = artworkIndex?.takeIf { it.first == stamp }?.second ?: buildList {
                var bytes = 0L
                directory.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                    bytes += file.length()
                    if (bytes <= maxBytes) runCatching {
                        val entry = json.decodeFromString<DiscoveryCacheEntry>(file.readText())
                        val payload = json.parseToJsonElement(entry.payload)
                        val values = when (payload) {
                            is JsonArray -> payload.toList()
                            is JsonObject -> listOf(payload["card"] ?: payload)
                            else -> emptyList()
                        }
                        values.forEach { value ->
                            runCatching { json.decodeFromJsonElement(DiscoveryCard.serializer(), value) }.getOrNull()
                                ?.takeIf { it.id > 0 && !it.adult && it.poster?.startsWith("https://image.tmdb.org/") == true }?.let(::add)
                        }
                    }
                }
            }.also { artworkIndex = stamp to it }
            cards.filter { it.series == series && it.title.trim().equals(title.trim(), true) }
                .distinctBy { it.key }.singleOrNull()
        }
    }
    private class Flight(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val loads get() = sharedLoads
    suspend fun <T> load(key: String, ttl: Long, serializer: KSerializer<T>, stale: (T) -> Unit = {}, network: suspend () -> T): T {
        val flightKey = "${directory.absolutePath}:$key"
        val flight = synchronized(loads) { loads.getOrPut(flightKey) { Flight() }.also { it.users++ } }
        try { return flight.mutex.withLock {
            val entry = read(key)
            val old = withContext(Dispatchers.IO) { entry?.let { runCatching { json.decodeFromString(serializer, it.payload) }.getOrNull() } }
            if (old != null) {
                if (entry?.fresh(now(), ttl) == true) return@withLock old
                stale(old)
            }
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
