package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard

/** Only the exact torrent file inherits saved time: another encode may be a different cut. */
internal fun torrentCanResume(saved: TorrentLibraryEntry, card: DiscoveryCard?, episode: TmdbEpisode?,
    file: TorrentVideoFile, infoHash: String?): Boolean {
    if (saved.infoHash.isNullOrBlank() || saved.infoHash != infoHash || saved.sizeBytes != file.sizeBytes ||
        saved.fileName != file.name.substringAfterLast('/').substringAfterLast('\\')) return false
    return if (saved.card == null) card == null else saved.tmdbId == card?.id && saved.series == card?.series &&
        (!saved.series || saved.season == episode?.seasonNumber && saved.episode == episode?.episodeNumber)
}
