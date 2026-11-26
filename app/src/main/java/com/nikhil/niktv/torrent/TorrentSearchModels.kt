package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

internal enum class TorrentContentKind(val label: String, val media: Boolean) {
    VIDEO("Video", true), AUDIO("Audio", true), FILE("File", false)
}

internal enum class TorrentSearchScope(val label: String, val apiCategory: String) {
    ALL("All files", "0"), MOVIES("Movies", "201,207"), SERIES("Series", "205,208")
}

internal fun torrentSearchScope(surface: com.nikhil.niktv.model.DashboardSurface) = when (surface) {
    com.nikhil.niktv.model.DashboardSurface.MOVIES -> TorrentSearchScope.MOVIES
    com.nikhil.niktv.model.DashboardSurface.SERIES -> TorrentSearchScope.SERIES
    else -> TorrentSearchScope.ALL
}

internal val TORRENT_VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts")
internal val TORRENT_AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
internal fun torrentMediaFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in TORRENT_VIDEO_EXTENSIONS + TORRENT_AUDIO_EXTENSIONS

internal fun torrentContentKind(title: String, category: String?): TorrentContentKind {
    val extension = Regex("(?i)\\.([a-z0-9]{2,8})(?:\\s|$|\\])").findAll(title).lastOrNull()?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    if (extension in TORRENT_VIDEO_EXTENSIONS) return TorrentContentKind.VIDEO
    if (extension in TORRENT_AUDIO_EXTENSIONS) return TorrentContentKind.AUDIO
    if (extension in setOf("pdf", "epub", "mobi", "zip", "rar", "7z", "iso", "exe", "apk", "dmg", "txt", "cbz", "cbr",
            "srt", "vtt", "ass", "ssa", "sub", "csv", "json", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "deb", "rpm", "msi")) return TorrentContentKind.FILE
    val number = category?.toIntOrNull()
    if ((number != null && number in 200..299) || category?.contains(Regex("(?i)video|movie|television|tv")) == true) return TorrentContentKind.VIDEO
    if ((number != null && number in 100..199) || category?.contains(Regex("(?i)audio|music")) == true) return TorrentContentKind.AUDIO
    if (number != null && number >= 300) return TorrentContentKind.FILE
    if (Regex("(?i)(?:s\\d{1,3}e\\d{1,5}|\\b(?:480|720|1080|2160)p\\b|\\b(?:hdtv|pdtv|webrip|web-dl|bluray|bdrip|dvdrip|x264|x265)\\b)").containsMatchIn(title)) return TorrentContentKind.VIDEO
    return TorrentContentKind.FILE
}

/** Search listings are hints; the engine still verifies metadata, files and actual peer availability. */
internal data class TorrentSource(
    val infoHash: String,
    val title: String,
    val magnetUri: String,
    val torrentUrl: String?,
    val sizeBytes: Long,
    val seeders: Int,
    val quality: String?,
    val provider: String,
    val seasonPack: Boolean = false,
    val kind: TorrentContentKind = TorrentContentKind.VIDEO
) {
    val source: String get() = magnetUri.ifBlank { torrentUrl.orEmpty() }
    val playable: Boolean get() = seeders > 0 && source.isNotBlank()
}

internal data class TorrentSearchSnapshot(
    val results: List<TorrentSource> = emptyList(),
    val pending: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap()
) {
    val finished: Boolean get() = pending.isEmpty()
}

internal fun torrentTitleKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

// Recognize episodic intent separately from the bounded numeric parser. An
// oversized/malformed episode must not become a season-pack match instead.
private val episodeMarker = Regex("(?i)(?<![a-z0-9])(?:s\\d+[ ._-]*e\\d+|\\d+x\\d+)")
private val episodeCode = Regex("(?i)(?<![a-z0-9])(?:s(\\d{1,4})[ ._-]*e(\\d{1,6})(?!\\d)|(\\d{1,4})x(\\d{1,6})(?!\\d))")
private val seasonMarker = Regex("\\b(?:s|season ?)(\\d{1,4})\\b")
private val titlePrefixMetadata = Regex("(?:\\d{4}|(?:480|576|720|1080|1440|2160|4320)p|4k|8k)")

internal fun torrentEpisodeFileMatches(path: String, season: Int, episode: Int): Boolean =
    episodeNameMatches(path.substringAfterLast('/').substringAfterLast('\\'), season, episode)

private fun episodeNameMatches(name: String, season: Int, episode: Int): Boolean =
    episodeCode.findAll(name).any { code ->
        val s = code.groupValues[1].ifBlank { code.groupValues[3] }.toIntOrNull()
        val e = code.groupValues[2].ifBlank { code.groupValues[4] }.toIntOrNull()
        s == season && e == episode
    }

/** Avoid attaching another remake, sequel, episode, trailer or CAM to a selected TMDB identity. */
internal fun torrentListingMatches(name: String, card: DiscoveryCard, episode: TmdbEpisode? = null,
    aliases: List<String> = emptyList()): Boolean {
    val key = torrentTitleKey(name)
    if (Regex("\\b(sample|trailer|preview|cam|hdcam|camrip|telesync|telecine)\\b").containsMatchIn(key)) return false
    val title = (listOf(card.title) + aliases).map(::torrentTitleKey).filter { it.isNotBlank() }
        .sortedByDescending(String::length).firstOrNull { key == it || key.startsWith("$it ") } ?: return false
    val suffix = key.removePrefix(title).trim()
    val year = card.date?.take(4)?.toIntOrNull()
    if (!card.series) {
        // The release year must be the first year after the actual title, not an
        // unrelated upload date later in a listing. Titles containing years work.
        val release = Regex("^\\d{4}\\b").find(suffix)?.value?.toIntOrNull()
        return year != null && release == year && !episodeMarker.containsMatchIn(name)
    }
    episode ?: return false
    // A canonical prefix is not sufficient for TV: "Friends From College"
    // must never match "Friends" just because both contain S01E01. Permit
    // only explicit year/resolution metadata before the first season marker.
    val intent = listOfNotNull(episodeMarker.find(suffix), seasonMarker.find(suffix))
        .minByOrNull { it.range.first } ?: return false
    val beforeIntent = suffix.substring(0, intent.range.first).trim()
    if (beforeIntent.isNotEmpty() && beforeIntent.split(' ').any { !titlePrefixMetadata.matches(it) }) return false
    val release = Regex("^\\d{4}\\b").find(suffix)?.value?.toIntOrNull()
    val airYear = episode.airDate?.take(4)?.toIntOrNull()
    if (release != null && release != year && release != airYear) return false
    if (episodeMarker.containsMatchIn(name)) return episodeNameMatches(name, episode.seasonNumber, episode.episodeNumber)
    return torrentSeasonPack(name, episode.seasonNumber)
}

internal fun torrentSeasonPack(name: String, season: Int): Boolean {
    if (episodeMarker.containsMatchIn(name)) return false
    val key = torrentTitleKey(name)
    return seasonMarker.findAll(key).any {
        it.groupValues[1].toIntOrNull() == season
    }
}

internal fun torrentQuality(name: String): String? =
    Regex("(?i)\\b(2160|1080|720|480)p\\b").find(name)?.value?.lowercase(Locale.ROOT)
        ?: if (Regex("(?i)\\b(4k|uhd)\\b").containsMatchIn(name)) "2160p" else null

internal fun torrentMagnet(hash: String, title: String): String {
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    return "magnet:?xt=urn:btih:$hash&dn=${encode(title)}&tr=${encode("udp://tracker.opentrackr.org:1337/announce")}" +
        "&tr=${encode("udp://open.stealth.si:80/announce")}&tr=${encode("udp://tracker.torrent.eu.org:451/announce") }"
}

internal fun validatedTorrentHash(value: String?): String? = value?.lowercase(Locale.ROOT)
    ?.takeIf { it.matches(Regex("[a-f0-9]{40}")) && it.any { c -> c != '0' } }

internal fun matchingTorrentMagnet(value: String?, hash: String): String? = runCatching {
    val uri = URI(value ?: return null)
    if (!uri.scheme.equals("magnet", true)) return null
    // java.net.URI treats magnet:? as opaque, so read its raw scheme-specific part.
    val query = uri.rawSchemeSpecificPart.removePrefix("?")
    val hashes = query.split('&').mapNotNull { part ->
        val pair = part.split('=', limit = 2)
        if (pair.first() == "xt" && pair.size == 2) URLDecoder.decode(pair[1], "UTF-8").lowercase(Locale.ROOT) else null
    }
    value.takeIf { hashes == listOf("urn:btih:$hash") }
}.getOrNull()

internal fun mergeTorrentSources(rows: List<TorrentSource>, requireSeeders: Boolean = true): List<TorrentSource> = rows.filter {
    it.source.isNotBlank() && if (requireSeeders) it.seeders > 0 else it.seeders >= 0
}
    .groupBy(TorrentSource::infoHash).values.map { duplicates ->
        val best = duplicates.maxBy(TorrentSource::seeders)
        best.copy(torrentUrl = best.torrentUrl ?: duplicates.firstNotNullOfOrNull { it.torrentUrl },
            provider = duplicates.flatMap { it.provider.split(" · ") }.distinct().sorted().joinToString(" · "))
    }.sortedWith(compareByDescending<TorrentSource> { it.seeders }
        .thenByDescending { it.quality?.removeSuffix("p")?.toIntOrNull() ?: 0 }.thenBy { it.sizeBytes })
        .take(200)
