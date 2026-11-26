package com.nikhil.niktv.torrent

import android.content.Context
import com.nikhil.niktv.data.*
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

internal const val TORRENT_OFFLINE_PROFILE = "torrents"

@Serializable
internal data class TorrentOfflineEpisode(val season: Int, val number: Int, val name: String?, val date: String?, val still: String?) {
    fun episode() = TmdbEpisode(season, number, name, null, date, still)
}
@Serializable
internal data class TorrentOfflineSequence(val seasons: List<Int> = emptyList(),
    val completeSeasons: Set<Int> = emptySet(), val episodes: List<TorrentOfflineEpisode> = emptyList())

internal fun torrentOfflineMediaId(card: DiscoveryCard, episode: TmdbEpisode?): String {
    require(!card.series || episode != null) { "Choose an episode first." }
    return if (card.series && episode != null) "torrent:tmdb:${card.id}:${episode.seasonNumber}:${episode.episodeNumber}"
        else "torrent:tmdb:movie:${card.id}"
}

internal fun torrentOfflineMatches(entry: OfflineMediaDownload, card: DiscoveryCard, episode: TmdbEpisode?): Boolean =
    entry.profileKey == TORRENT_OFFLINE_PROFILE && entry.media.id == torrentOfflineMediaId(card, episode) &&
        entry.catalogType == (if (card.series) CatalogType.SERIES else CatalogType.MOVIES)

/** Device-local presentation metadata. A completed file is never advertised to another device. */
internal object TorrentOfflineLibrary {
    private val json = Json { ignoreUnknownKeys = true }
    private const val PREFERENCES = "torrent_offline_metadata_v1"
    private val sequenceEdits = Mutex()
    private fun key(owner: UserScope, request: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${owner.accountId}|${owner.profileId}|$request".toByteArray())
        .joinToString("") { "%02x".format(it) }

    suspend fun register(context: Context, owner: UserScope, handle: OfflineDownloadHandle,
        card: DiscoveryCard, episode: TmdbEpisode?, file: TorrentVideoFile, infoHash: String?): OfflineMediaDownload = withContext(Dispatchers.IO) {
        val metadata = torrentLibraryEntry(card, episode, file, infoHash)
        val title = if (episode == null) card.title else "${card.title} · S${episode.seasonNumber}:E${episode.episodeNumber} · ${episode.name.orEmpty()}"
        val media = card.media.copy(id = torrentOfflineMediaId(card, episode), title = title,
            logo = episode?.stillUrl ?: card.poster, command = null, externalTmdbId = card.id,
            seasonNumber = episode?.seasonNumber, episodeNumber = episode?.episodeNumber,
            episodeAirDate = episode?.airDate, description = episode?.overview ?: card.overview)
        val entry = OfflineMediaDownload(requestId = handle.requestId, profileKey = TORRENT_OFFLINE_PROFILE,
            catalogType = if (card.series) CatalogType.SERIES else CatalogType.MOVIES, media = media,
            series = card.media.takeIf { card.series }, fileType = handle.fileType)
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(key(owner, handle.requestId), json.encodeToString(metadata)).commit())
        check(ProfileStore(context).upsertOfflineDownload(entry, owner))
        entry
    }

    suspend fun metadata(context: Context, owner: UserScope, entry: OfflineMediaDownload): TorrentLibraryEntry? = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key(owner, entry.requestId), null)
            ?.let { runCatching { json.decodeFromString<TorrentLibraryEntry>(it) }.getOrNull() }
    }

    suspend fun forget(context: Context, owner: UserScope, entry: OfflineMediaDownload) = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(key(owner, entry.requestId)).commit()
        ProfileStore(context).removeOfflineDownload(entry.key, entry.requestId, owner)
    }

    private fun sequenceFile(context: Context, owner: UserScope, id: Int) =
        File(context.filesDir, "torrent_offline_metadata/${key(owner, "series:$id")}.json")

    suspend fun sequence(context: Context, owner: UserScope, id: Int): TorrentOfflineSequence = withContext(Dispatchers.IO) {
        readSequence(sequenceFile(context, owner, id))
    }

    private fun readSequence(file: File): TorrentOfflineSequence = runCatching {
        if (!file.isFile || file.length() > 4L * 1024 * 1024) return@runCatching TorrentOfflineSequence()
        AtomicFile(file).openRead().use { json.decodeFromString<TorrentOfflineSequence>(it.bufferedReader().readText()) }
    }.getOrDefault(TorrentOfflineSequence())

    /** A downloaded subset never defines episode order. Cache complete TMDB seasons separately. */
    suspend fun rememberSequence(context: Context, owner: UserScope, id: Int, seasons: List<Int>,
        complete: Map<Int, List<TmdbEpisode>>) = withContext(Dispatchers.IO) {
        if (complete.isEmpty()) return@withContext
        sequenceEdits.withLock {
            val file = sequenceFile(context, owner, id)
            val old = readSequence(file)
            val rows = old.episodes.filterNot { it.season in complete } + complete.flatMap { (_, episodes) ->
                episodes.take(10_000).map { TorrentOfflineEpisode(it.seasonNumber, it.episodeNumber, it.name?.take(200), it.airDate, it.stillUrl) }
            }
            val next = TorrentOfflineSequence((old.seasons + seasons + complete.keys).distinct().sorted(), old.completeSeasons + complete.keys, rows)
            val encoded = json.encodeToString(next).toByteArray(Charsets.UTF_8)
            if (encoded.size > 4 * 1024 * 1024) return@withLock
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(encoded); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
    }
}
