package com.nikhil.niktv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.SearchContentType

/** The original catalogue identity is retained; a resolved URL is never a tile identity. */
data class AlternateSourceRequest(
    val item: MediaItem,
    val type: CatalogType,
    val series: MediaItem? = null,
    val season: Int? = null,
    val episode: Int? = null
)

internal val LocalChooseIptvMovieVersion = staticCompositionLocalOf<((com.nikhil.niktv.data.TmdbMovie) -> Unit)?> { null }

internal val LocalPlayAlternateSource = staticCompositionLocalOf<((AlternateSourceRequest) -> Unit)?> { null }

/** Explicit live context wins over a surrounding movie/series screen's inferred type. */
internal fun alternateSourceRequest(
    item: MediaItem,
    type: CatalogType?,
    series: MediaItem? = null,
    season: Int? = null,
    episode: Int? = null
): AlternateSourceRequest? {
    if (type == CatalogType.LIVE_TV || type == CatalogType.RADIO ||
        item.searchResultType == SearchContentType.LIVE_TV || item.channelNumber != null ||
        item.liveProgramme != null || item.liveSchedule.isNotEmpty()) return null
    val resolvedType = when {
        item.searchResultType == SearchContentType.MOVIES -> CatalogType.MOVIES
        item.searchResultType == SearchContentType.SERIES -> CatalogType.SERIES
        series != null || item.episodeNumber != null || episode != null -> CatalogType.SERIES
        item.id.startsWith("tmdb-movie:") -> CatalogType.MOVIES
        item.id.startsWith("tmdb-series:") -> CatalogType.SERIES
        else -> type
    }
    if (resolvedType != CatalogType.MOVIES && resolvedType != CatalogType.SERIES) return null
    return AlternateSourceRequest(item, resolvedType, series,
        (season ?: item.seasonNumber)?.takeIf { it >= 0 },
        (episode ?: item.episodeNumber)?.takeIf { it > 0 })
}

@Composable
internal fun AlternateSourceMenuItem(
    item: MediaItem,
    type: CatalogType? = null,
    dismiss: () -> Unit,
    series: MediaItem? = null,
    season: Int? = null,
    episode: Int? = null
) {
    val play = LocalPlayAlternateSource.current ?: return
    val inferredType = type ?: LocalRecommendationTypeFor.current?.invoke(item, null)
    val request = alternateSourceRequest(item, inferredType, series, season, episode) ?: return
    NikDropdownMenuItem(
        text = { Text("Play from another source") },
        leadingIcon = { Icon(Icons.Default.AltRoute, contentDescription = null) },
        onClick = { dismiss(); play(request) }
    )
}

/** For metadata-only tiles which do not otherwise have a catalogue actions menu. */
@Composable
internal fun AlternateSourceOnlyMenu(expanded: Boolean, item: MediaItem, type: CatalogType, dismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss,
        containerColor = Color(0xFF202020), shape = RoundedCornerShape(12.dp)) {
        AlternateSourceMenuItem(item, type, dismiss)
    }
}
