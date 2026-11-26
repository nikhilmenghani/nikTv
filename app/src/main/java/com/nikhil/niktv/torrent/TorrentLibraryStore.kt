package com.nikhil.niktv.torrent

import android.content.Context
import android.content.SharedPreferences
import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.data.UserProfilePreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.portablePublicArtwork
import com.nikhil.niktv.discovery.DiscoveryCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Portable metadata only: no source URLs, magnets, tracker arguments or local playback leases. */
@Serializable
internal data class TorrentLibraryEntry(
    val key: String, val tmdbId: Int? = null, val title: String, val series: Boolean = false,
    val date: String? = null, val poster: String? = null, val backdrop: String? = null, val overview: String? = null,
    val season: Int? = null, val episode: Int? = null, val episodeName: String? = null,
    val episodeAirDate: String? = null, val episodeStill: String? = null,
    val infoHash: String? = null, val fileName: String? = null, val sizeBytes: Long? = null,
    val positionMillis: Long = 0, val durationMillis: Long = 0, val updatedAt: Long = System.currentTimeMillis()
) {
    val card: DiscoveryCard? get() = tmdbId?.takeIf { it > 0 }?.let {
        DiscoveryCard(it, series, title, overview, poster, backdrop, date)
    }
    fun toCard(): DiscoveryCard? = card
    val year: String? get() = date?.take(4)
    val tmdbEpisode: TmdbEpisode? get() = if (series && season != null && episode != null)
        TmdbEpisode(season, episode, episodeName, null, episodeAirDate, episodeStill) else null
    val progress: Float? get() = durationMillis.takeIf { it > 0 }?.let { (positionMillis.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val completed: Boolean get() = durationMillis > 0 && (positionMillis >= durationMillis ||
        durationMillis > 60_000 && (positionMillis.toDouble() / durationMillis >= .95 || durationMillis - positionMillis <= 30_000))
    val subtitle: String get() = listOfNotNull(
        if (series && season != null && episode != null) "S$season:E$episode" else year,
        episodeName?.takeIf(String::isNotBlank)).joinToString(" · ")
    val displayTitle: String get() = if (series && season != null && episode != null) "$title · S$season:E$episode" else title
}

internal data class TorrentLibraryState(
    val continueWatching: List<TorrentLibraryEntry> = emptyList(), val saved: List<TorrentLibraryEntry> = emptyList()
) {
    fun isSaved(card: DiscoveryCard): Boolean = saved.any { it.key == torrentLibraryKey(card.id, card.series) }
    fun isSaved(entry: TorrentLibraryEntry): Boolean = saved.any { it.key == entry.key }
}

internal fun torrentLibraryKey(tmdbId: Int?, series: Boolean, infoHash: String? = null,
    title: String = "", fileName: String? = null, sizeBytes: Long? = null): String =
    if (tmdbId != null && tmdbId > 0) "tmdb-${if (series) "tv" else "movie"}:$tmdbId"
    else "manual:" + MessageDigest.getInstance("SHA-256").digest(
        "${safeHash(infoHash).orEmpty()}|${safeFileName(fileName).orEmpty()}|${sizeBytes ?: 0}|${safeText(title, 300).orEmpty()}".toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }

internal fun torrentLibraryEntry(card: DiscoveryCard? = null, episode: TmdbEpisode? = null,
    file: TorrentVideoFile? = null, infoHash: String? = null, positionMillis: Long = 0,
    durationMillis: Long = 0, titleFallback: String = "Video"): TorrentLibraryEntry =
    TorrentLibraryEntry(torrentLibraryKey(card?.id, card?.series == true, infoHash, card?.title ?: titleFallback, file?.name, file?.sizeBytes),
        card?.id, card?.title ?: titleFallback, card?.series == true, card?.date, card?.poster, card?.backdrop, card?.overview,
        episode?.seasonNumber, episode?.episodeNumber, episode?.name, episode?.airDate, episode?.stillUrl,
        infoHash, file?.name, file?.sizeBytes, positionMillis, durationMillis).portable()

/** Each title has independent progress/save keys, so GitHub merges cannot replace an entire shelf. */
internal object TorrentLibraryStore {
    private const val DOMAIN = "torrent_library_v1"
    private const val LOCAL_SOURCES = "torrent_local_sources_v1"
    private const val LIMIT = 100
    private val edits = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun observe(context: Context, owner: UserScope?): Flow<TorrentLibraryState> {
        if (owner == null) return flowOf(TorrentLibraryState())
        val app = context.applicationContext
        return callbackFlow {
            UserProfilePreferences.warm(app, owner)
            val prefs = preferences(app, owner)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate().map { read(preferences(app, owner)) }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    suspend fun progress(context: Context, owner: UserScope, entry: TorrentLibraryEntry) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        edits.withLock {
            val prefs = preferences(context, owner)
            val next = entry.portable()
            val old = decode(prefs.getString("progress:${next.key}", null))
            // Retain completion as a newer per-title record. Delayed callbacks
            // must not resurrect an older episode/position after it finished.
            if (old != null && old.updatedAt > next.updatedAt) return@withLock
            val enriched = next.enriched(old ?: decode(prefs.getString("saved:${next.key}", null)))
            save(prefs, "progress:", enriched)
        }
    }

    suspend fun toggleSaved(context: Context, owner: UserScope, entry: TorrentLibraryEntry) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        edits.withLock {
            val prefs = preferences(context, owner)
            val next = entry.portable()
            val key = "saved:${next.key}"
            if (prefs.contains(key)) check(prefs.edit().remove(key).commit())
            else save(prefs, "saved:", next.enriched(decode(prefs.getString("progress:${next.key}", null)))
                .copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun removeProgress(context: Context, owner: UserScope, entry: TorrentLibraryEntry) = removeProgress(context, owner, entry.key)
    suspend fun removeSaved(context: Context, owner: UserScope, entry: TorrentLibraryEntry) = removeSaved(context, owner, entry.key)
    suspend fun removeProgress(context: Context, owner: UserScope, key: String) = remove(context, owner, "progress:", key)
    suspend fun removeSaved(context: Context, owner: UserScope, key: String) = remove(context, owner, "saved:", key)

    /** Device-private replay aid. This domain is intentionally not portable and
     * the application disables Android backup. Never include it in library JSON. */
    suspend fun rememberSource(context: Context, owner: UserScope, entryKey: String, source: String) = withContext(Dispatchers.IO) {
        require(safeKey(entryKey)) { "Invalid Torrent library entry." }
        require(source.toByteArray(Charsets.UTF_8).size <= 64 * 1024 && source.none(Char::isISOControl)) { "This torrent source is too long or invalid." }
        require(runCatching { URI(source).scheme?.lowercase(Locale.ROOT) in setOf("magnet", "http", "https", "content") }.getOrDefault(false)) { "This torrent source is not supported." }
        edits.withLock {
            val prefs = context.applicationContext.getSharedPreferences(LOCAL_SOURCES, Context.MODE_PRIVATE)
            val key = localSourceKey(owner, entryKey)
            val editor = prefs.edit().putString(key, json.encodeToString(LocalTorrentSource.serializer(), LocalTorrentSource(source, System.currentTimeMillis())))
            val retained = (prefs.all.asSequence().filter { it.key != key }.mapNotNull { row ->
                (row.value as? String)?.let { raw -> runCatching { json.decodeFromString(LocalTorrentSource.serializer(), raw).updatedAt }.getOrNull() }?.let { row.key to it }
            }.toList() + (key to System.currentTimeMillis())).sortedByDescending { it.second }.take(LIMIT).map { it.first }.toSet()
            prefs.all.keys.filter { it !in retained }.forEach(editor::remove)
            check(editor.commit()) { "Could not remember this device's Torrent source." }
        }
    }

    suspend fun source(context: Context, owner: UserScope, entryKey: String): String? = withContext(Dispatchers.IO) {
        if (!safeKey(entryKey)) return@withContext null
        val raw = context.applicationContext.getSharedPreferences(LOCAL_SOURCES, Context.MODE_PRIVATE)
            .getString(localSourceKey(owner, entryKey), null) ?: return@withContext null
        runCatching { json.decodeFromString(LocalTorrentSource.serializer(), raw).source }.getOrNull()
    }

    private suspend fun remove(context: Context, owner: UserScope, prefix: String, key: String) = withContext(Dispatchers.IO) {
        if (!safeKey(key)) return@withContext
        UserProfilePreferences.warm(context, owner)
        edits.withLock { check(preferences(context, owner).edit().remove(prefix + key).commit()) }
    }

    private fun preferences(context: Context, owner: UserScope) = UserProfilePreferences.get(context, DOMAIN, owner = owner)
    private fun localSourceKey(owner: UserScope, entryKey: String) = MessageDigest.getInstance("SHA-256")
        .digest("${owner.accountId}|${owner.profileId}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ":" + entryKey
    private fun decode(raw: String?): TorrentLibraryEntry? = raw?.let {
        runCatching { json.decodeFromString(TorrentLibraryEntry.serializer(), it).portable() }.getOrNull()
    }
    private fun entries(prefs: SharedPreferences, prefix: String) = prefs.all.asSequence()
        .filter { it.key.startsWith(prefix) }.mapNotNull { decode(it.value as? String) }.toList()

    private fun read(prefs: SharedPreferences): TorrentLibraryState {
        val progress = entries(prefs, "progress:").associateBy(TorrentLibraryEntry::key)
        val saved = entries(prefs, "saved:").map { entry ->
            val current = progress[entry.key]
            if (current == null) entry else current.enriched(entry).copy(updatedAt = entry.updatedAt)
        }.sortedByDescending(TorrentLibraryEntry::updatedAt).take(LIMIT)
        return TorrentLibraryState(progress.values.filter { it.positionMillis >= 5_000 && !it.completed }
            .sortedByDescending(TorrentLibraryEntry::updatedAt).take(LIMIT), saved)
    }

    private fun save(prefs: SharedPreferences, prefix: String, entry: TorrentLibraryEntry) {
        // The generic sync privacy boundary removes URL-valued fields. Encode
        // validated public artwork as TMDB paths, then expand only for display.
        val portable = json.encodeToJsonElement(TorrentLibraryEntry.serializer(), entry).jsonObject.toMutableMap()
        portable["kind"] = JsonPrimitive("torrent-library-v1")
        portable["series"] = JsonPrimitive(entry.series)
        for (field in listOf("poster", "backdrop", "episodeStill")) {
            val image = (portable[field] as? JsonPrimitive)?.contentOrNull?.let(::safeArtwork)
            portable[field] = image?.let { JsonPrimitive(URI(it).rawPath) } ?: JsonNull
        }
        val editor = prefs.edit().putString(prefix + entry.key, JsonObject(portable).toString())
        val retained = (entries(prefs, prefix).filter { it.key != entry.key } + entry)
            .sortedByDescending(TorrentLibraryEntry::updatedAt).take(LIMIT).map { it.key }.toSet()
        prefs.all.keys.filter { it.startsWith(prefix) && it.removePrefix(prefix) !in retained }.forEach(editor::remove)
        check(editor.commit()) { "Could not save the Torrent library." }
    }
}

@Serializable
private data class LocalTorrentSource(val source: String, val updatedAt: Long)

private val linkText = Regex("(?i)(?:https?://|magnet:|file:|content:)[^\\s]+")
private fun safeText(value: String?, limit: Int): String? = value?.replace(linkText, "")
    ?.filterNot(Char::isISOControl)?.trim()?.take(limit)?.takeIf(String::isNotBlank)
private fun safeFileName(value: String?): String? = safeText(value?.substringAfterLast('/')?.substringAfterLast('\\')
    ?.substringBefore('?')?.substringBefore('#'), 250)
private fun safeHash(value: String?): String? = value?.lowercase(Locale.ROOT)?.takeIf {
    it.matches(Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})")) && it.any { c -> c != '0' }
}
private fun safeKey(value: String) = value.matches(Regex("(?:tmdb-(?:tv|movie):[1-9]\\d*|manual:[a-f0-9]{64})"))
private fun safeArtwork(value: String?): String? = runCatching {
    val image = value ?: return null
    val uri = URI(if (image.startsWith("/t/p/")) "https://image.tmdb.org$image" else image)
    if (uri.scheme != "https" || uri.host != "image.tmdb.org" || uri.port != -1 || uri.userInfo != null) null
    else "https://image.tmdb.org${uri.rawPath}".takeIf { portablePublicArtwork(JsonPrimitive(it)) }
}.getOrNull()

private fun TorrentLibraryEntry.portable(): TorrentLibraryEntry {
    val id = tmdbId?.takeIf { it > 0 }
    val cleanTitle = safeText(title, 300) ?: safeFileName(fileName) ?: "Video"
    val cleanFile = safeFileName(fileName)
    val hash = safeHash(infoHash)
    val size = sizeBytes?.takeIf { it > 0 }
    return copy(key = torrentLibraryKey(id, series, hash, cleanTitle, cleanFile, size), tmdbId = id, title = cleanTitle,
        date = date?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) },
        poster = safeArtwork(poster), backdrop = safeArtwork(backdrop), overview = if (id != null) safeText(overview, 4_000) else null,
        season = season?.takeIf { it > 0 }, episode = episode?.takeIf { it > 0 },
        episodeName = safeText(episodeName, 300), episodeAirDate = episodeAirDate?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) },
        episodeStill = safeArtwork(episodeStill), infoHash = hash, fileName = cleanFile, sizeBytes = size,
        positionMillis = positionMillis.coerceAtLeast(0), durationMillis = durationMillis.coerceAtLeast(0), updatedAt = updatedAt.coerceAtLeast(0))
}

private fun TorrentLibraryEntry.enriched(old: TorrentLibraryEntry?): TorrentLibraryEntry = if (old == null || old.key != key) this else copy(
    date = date ?: old.date, poster = poster ?: old.poster, backdrop = backdrop ?: old.backdrop, overview = overview ?: old.overview,
    episodeName = episodeName ?: old.episodeName.takeIf { season == old.season && episode == old.episode },
    episodeAirDate = episodeAirDate ?: old.episodeAirDate.takeIf { season == old.season && episode == old.episode },
    episodeStill = episodeStill ?: old.episodeStill.takeIf { season == old.season && episode == old.episode },
    infoHash = infoHash ?: old.infoHash.takeIf { !series || season == old.season && episode == old.episode },
    fileName = fileName ?: old.fileName.takeIf { !series || season == old.season && episode == old.episode },
    sizeBytes = sizeBytes ?: old.sizeBytes.takeIf { !series || season == old.season && episode == old.episode })
