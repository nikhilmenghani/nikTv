package com.nikhil.niktv.data

import com.nikhil.niktv.model.*
import java.net.URLEncoder

/** Rebuild Xtream paths with current credentials; a backup's embedded URL may belong to an old password. */
internal fun xtreamPlaybackUrl(profile: PortalProfile, item: MediaItem, type: CatalogType): String {
    val root = profile.portalUrl.trim().trimEnd('/').removeSuffix("/player_api.php").removeSuffix("/get.php")
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    require(item.id.isNotBlank()) { "Missing provider stream ID" }
    val extension = if (type == CatalogType.LIVE_TV || type == CatalogType.RADIO) "ts" else {
        item.command?.substringBefore('?')?.substringAfterLast('.', "")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            ?: item.streamType?.takeIf { it in setOf("mp4", "mkv", "avi", "ts", "m3u8", "mov") }
            ?: "mp4"
    }
    val path = when (type) {
        CatalogType.LIVE_TV, CatalogType.RADIO -> "live"
        CatalogType.MOVIES -> "movie"
        CatalogType.SERIES -> "series"
    }
    return "$root/$path/${encode(profile.username)}/${encode(profile.password)}/${encode(item.id)}.$extension"
}
