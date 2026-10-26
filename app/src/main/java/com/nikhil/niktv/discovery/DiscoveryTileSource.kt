package com.nikhil.niktv.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.RecentItem

internal enum class DiscoveryTileSource(val label: String, val foreground: Color, val background: Color) {
    IPTV("IPTV", Color(0xFF94D5FF), Color(0xF0153044)),
    ALTERNATE("Alternate", Color(0xFFE1B8FF), Color(0xF0312042)),
    TMDB("TMDB", Color(0xFF8BE6C8), Color(0xF014352E)),
}

/** TMDB artwork/metadata on a provider item does not turn its playback into a TMDB source. */
internal fun MediaItem.discoveryCatalogSource(): DiscoveryTileSource =
    if (id.startsWith("tmdb-movie:") || id.startsWith("tmdb-series:")) DiscoveryTileSource.TMDB else DiscoveryTileSource.IPTV

internal fun RecentItem.discoveryPlaybackSource(): DiscoveryTileSource =
    if (alternateTarget != null) DiscoveryTileSource.ALTERNATE else (series ?: media).discoveryCatalogSource()

internal fun discoveryTileStateDescription(source: DiscoveryTileSource?, matchCount: Int?): String =
    listOfNotNull(source?.let { "Source: ${it.label}" }, matchCount?.let { "$it IPTV matches" }).joinToString(" · ")

/** Decorative text; the containing focusable tile exposes the full source as its state description. */
@Composable
internal fun DiscoveryTileIndicators(source: DiscoveryTileSource?, matchCount: Int?, modifier: Modifier = Modifier) {
    if (source == null && matchCount == null) return
    Row(modifier.fillMaxWidth().padding(6.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        if (source != null) Surface(Modifier.weight(1f, fill = false), shape = RoundedCornerShape(5.dp),
            color = source.background, border = BorderStroke(1.dp, source.foreground.copy(alpha = .45f))) {
            Text(source.label, Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                color = source.foreground, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Spacer(Modifier.weight(1f))
        // The weighted source badge yields space to the independent match count, so they never overlap.
        if (matchCount != null) Surface(Modifier.padding(start = 5.dp), shape = RoundedCornerShape(6.dp), color = Color(0xFFE50925)) {
            Text(matchCount.toString(), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
