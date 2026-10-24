package com.nikhil.niktv.data

import android.content.Context
import android.util.AtomicFile
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class MediaIdentity(
    val profile: String, val type: CatalogType, val providerId: String, val providerTitle: String,
    val movie: TmdbMovie? = null, val series: TmdbSeries? = null,
    val method: String = "matched", val checkedAt: Long = System.currentTimeMillis()
) {
    val tmdbId: Int? get() = movie?.id ?: series?.id
    val artwork: String? get() = movie?.posterUrl ?: series?.posterUrl
    val key: String get() = "$profile|${type.name}|$providerId"
}

/** Durable provider-to-TMDB identities, separate from provider-supplied externalTmdbId. */
internal class MediaIdentityRepository(context: Context, private val tmdb: TmdbClient) {
    private val file = AtomicFile(File(context.filesDir, "media-identities-v1.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val requests = Array(64) { Mutex() }
    private var entries: Map<String, MediaIdentity>? = null
    @Volatile private var generation = 0

    private suspend fun read(): Map<String, MediaIdentity> = withContext(Dispatchers.IO) {
        mutex.withLock {
            entries ?: run {
                val loaded = try {
                    file.openRead().bufferedReader().use { json.decodeFromString<List<MediaIdentity>>(it.readText()) }
                } catch (_: java.io.IOException) { emptyList() }
                  catch (_: kotlinx.serialization.SerializationException) { emptyList() }
                loaded.associateBy { it.key }.also { entries = it }
            }
        }
    }

    suspend fun save(identity: MediaIdentity, expectedGeneration: Int? = null) = withContext(Dispatchers.IO) {
        read()
        mutex.withLock {
            if (expectedGeneration != null && generation != expectedGeneration) return@withLock
            // A background title search may finish after a user confirms a match.
            // It must never replace that stronger evidence (including with a miss).
            val previous = entries.orEmpty()[identity.key]
            if (identity.method in setOf("matched", "matched-series-v2", "matched-v3", "matched-series-v3") && previous?.providerTitle == identity.providerTitle &&
                previous.method in setOf("confirmed", "provider")) return@withLock
            val updated = (entries.orEmpty() + (identity.key to identity)).values
                .sortedByDescending { it.checkedAt }.take(4_000)
            val stream = file.startWrite()
            try {
                stream.write(json.encodeToString(updated).toByteArray())
                file.finishWrite(stream)
                entries = updated.associateBy { it.key }
            } catch (error: Throwable) { file.failWrite(stream); throw error }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { generation++; entries = emptyMap(); file.delete() }
    }

    suspend fun cached(profile: String, type: CatalogType, item: MediaItem): MediaIdentity? =
        read()["$profile|${type.name}|${item.id}"]?.takeIf {
            it.providerTitle == item.title &&
                (item.externalTmdbId == null || it.tmdbId == item.externalTmdbId)
        }

    suspend fun resolve(profile: String, type: CatalogType, item: MediaItem): MediaIdentity? {
        if (type !in setOf(CatalogType.MOVIES, CatalogType.SERIES) || item.isAdult) return null
        val key = "$profile|${type.name}|${item.id}"
        return requests[(key.hashCode() ushr 1) % requests.size].withLock {
            val cached = cached(profile, type, item)
            val matchMethod = if (type == CatalogType.SERIES) "matched-series-v3" else "matched-v3"
            if (cached != null && cached.providerTitle == item.title &&
                (item.externalTmdbId == null || cached.tmdbId == item.externalTmdbId) &&
                (cached.tmdbId != null || (cached.method == matchMethod && System.currentTimeMillis() - cached.checkedAt < 24 * 60 * 60_000L))) {
                return@withLock cached.takeIf { it.tmdbId != null }
            }
            if (!tmdb.configured) return@withLock null
            val requestGeneration = generation
            // Failed network requests are not cached as "no match".
            val identity = MediaIdentity(profile, type, item.id, item.title,
                movie = if (type == CatalogType.MOVIES) tmdb.confidentlyMatchMovie(item) else null,
                series = if (type == CatalogType.SERIES) tmdb.confidentlyMatchSeries(item) else null,
                method = if (item.externalTmdbId != null) "provider" else matchMethod)
            save(identity, requestGeneration)
            cached(profile, type, item)?.takeIf { it.tmdbId != null }
        }
    }
}

/** A yearless remake is ambiguous even when both candidates have exactly the same title. */
internal fun <T> unambiguousTmdbMatch(title: String, candidates: List<T>,
    names: (T) -> List<String>, year: (T) -> Int?, id: (T) -> Int): T? {
    val years = Regex("\\b(?:19|20)\\d{2}\\b")
    val cleanTitle = providerTmdbTitle(title)
    val requestedYear = years.findAll(cleanTitle).lastOrNull()?.value?.toIntOrNull()
    val wanted = cleanTitle.tmdbLookupTitle()
    return candidates.filter { candidate ->
        // "1917" and "2001: A Space Odyssey" contain years in their actual names.
        val yearIsPartOfName = requestedYear != null && names(candidate).any {
            years.findAll(it).any { match -> match.value.toInt() == requestedYear }
        }
        names(candidate).any { it.tmdbLookupTitle() == wanted } &&
            (requestedYear == null || yearIsPartOfName || year(candidate) == requestedYear)
    }.distinctBy(id).singleOrNull()
}
