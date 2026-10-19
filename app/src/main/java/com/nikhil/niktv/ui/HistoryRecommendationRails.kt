package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nikhil.niktv.data.HistoryRecommendationRow
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.model.FavoriteItem
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.FavoriteSource
import com.nikhil.niktv.model.MediaItem

@Composable
internal fun HistoryRecommendationStatus(message: String, compact: Boolean = false) {
    if (compact) Column {
        ModernHubSectionHeading("Recommendations")
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else ModernSectionHeader("Recommendations", message)
}

private data class RecommendationTile(
    val media: MediaItem, val kind: FavoriteKind, val year: Int?, val open: () -> Unit
)

/** Same card and rail primitives as Continue Watching; keep the discovery click path. */
@Composable
internal fun HistoryRecommendationRail(row: HistoryRecommendationRow,
    openMovie: (TrendingMovie) -> Unit, openSeries: (TrendingSeries) -> Unit,
    favorites: List<FavoriteItem>, toggleFavorite: (FavoriteItem) -> Unit,
    dismiss: () -> Unit,
    compact: Boolean = false) {
    val tiles = row.movies.map { movie ->
        RecommendationTile(movie.asMediaItem(), FavoriteKind.MOVIE, movie.releaseYear,
            { openMovie(TrendingMovie(movie)) })
    } + row.series.map { series ->
        RecommendationTile(series.asMediaItem(), FavoriteKind.SERIES, series.firstAirYear,
            { openSeries(TrendingSeries(series)) })
    }
    fun favorite(tile: RecommendationTile) = FavoriteItem(tile.kind, tile.media,
        profileKey = row.profile, source = FavoriteSource.TMDB)
    fun subtitle(tile: RecommendationTile) = listOfNotNull(
        if (tile.kind == FavoriteKind.MOVIE) "Movie" else "Series", tile.year?.toString()
    ).joinToString(" · ") + "\nIPTV lookup on select"

    if (compact) Column {
        RecommendationHeading(row.title, dismiss)
        CompactHomeMediaRow(rememberLazyListState()) {
            items(tiles, key = { it.media.id }) { tile ->
                val entry = favorite(tile)
                ModernCompactMediaCard(item = tile.media, subtitle = subtitle(tile),
                    onClick = tile.open, isFavorite = favorites.any { it.key == entry.key },
                    onFavorite = { toggleFavorite(entry) }, homeCardScale = 0.82f)
            }
        }
    } else Column {
        RecommendationHeading(row.title, dismiss)
        ModernRail("", tiles, { it.media }, { it.open() },
        aspectRatio = { 2f / 3f }, subtitle = ::subtitle,
        titleMaxLines = 3, subtitleMaxLines = 3,
        isFavorite = { tile -> favorites.any { it.key == favorite(tile).key } },
        toggleFavorite = { toggleFavorite(favorite(it)) })
    }
}

@Composable
private fun RecommendationHeading(title: String, dismiss: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
            ModernHubSectionHeading(title)
        }
        NikTvTextActionButton(onClick = dismiss) { Text("Dismiss") }
    }
}

/** Available to tile menus across catalog, search and Home. */
internal val LocalShowRecommendations = androidx.compose.runtime.staticCompositionLocalOf<
    ((MediaItem, com.nikhil.niktv.model.CatalogType?) -> Unit)?> { null }

internal val LocalRecommendationTypeFor = androidx.compose.runtime.staticCompositionLocalOf<
    ((MediaItem, com.nikhil.niktv.model.CatalogType?) -> com.nikhil.niktv.model.CatalogType?)?> { null }

@Composable
internal fun ShowRecommendationsMenuItem(item: MediaItem,
    type: com.nikhil.niktv.model.CatalogType? = null, dismiss: () -> Unit) {
    val action = LocalShowRecommendations.current
    val resolvedType = LocalRecommendationTypeFor.current?.invoke(item, type)
    if (resolvedType in setOf(com.nikhil.niktv.model.CatalogType.MOVIES, com.nikhil.niktv.model.CatalogType.SERIES) && action != null && !item.isAdult && item.channelNumber == null &&
        item.liveProgramme == null && item.episodeNumber == null &&
        type != com.nikhil.niktv.model.CatalogType.LIVE_TV) {
        NikDropdownMenuItem(text = { Text("Show recommendations on Home") },
            onClick = { dismiss(); action(item, resolvedType) })
    }
}
