package com.nikhil.niktv.discovery

import com.nikhil.niktv.discovery.components.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.thumbnailArtworkUrl
import com.nikhil.niktv.ui.remoteCombinedClickable
import com.nikhil.niktv.ui.EpisodeViewingStatus
import com.nikhil.niktv.ui.usesShortWideLayout
import com.nikhil.niktv.ui.isTvLikeDevice
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource

internal val LocalSeriesArtworkGlass = staticCompositionLocalOf { false }

/** Shared landscape episode treatment. Provider-specific actions remain with the caller. */
@Composable
internal fun DiscoveryEpisodeTile(title: String, artwork: String?, label: String, metadata: String,
    modifier: Modifier = Modifier, progress: Float? = null, remaining: String? = null,
    resume: Boolean = false, listView: Boolean = false, overview: String? = null, compactList: Boolean = false,
    portrait: Boolean = false,
    onFocus: () -> Unit = {}, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val television = LocalContext.current.isTvLikeDevice(configuration)
    val shortWide = configuration.usesShortWideLayout(television)
    val compactTvList = compactList && television
    val compact = configuration.screenWidthDp < 600
    val shape = RoundedCornerShape(10.dp)
    val glass = LocalSeriesArtworkGlass.current
    Surface(modifier.then(if (compactList) Modifier.fillMaxHeight() else Modifier)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .remoteCombinedClickable(onClick = onClick, onLongClick = onLongClick,
            interactionSource = remember { MutableInteractionSource() }, showIndication = false),
        shape = shape, color = (if (focused) Color(0xFF303A49) else Color(0xFF161B23))
            .copy(alpha = if (glass) { if (focused) .78f else .48f } else 1f),
        border = BorderStroke(if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color.White.copy(alpha = .12f))) {
        val image: @Composable (Modifier) -> Unit = { imageModifier ->
            Box(imageModifier.aspectRatio(16f / 9f).clip(shape).background(Color(0xFF222936)), contentAlignment = Alignment.Center) {
                if (artwork.isNullOrBlank()) Icon(Icons.Default.Movie, null, tint = Color.Gray)
                else AsyncImage(thumbnailArtworkUrl(artwork, 342), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                progress?.takeIf { it > 0 }?.let {
                    LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        color = Color(0xFFE50925), trackColor = Color(0xFF4B4F58), drawStopIndicator = {})
                }
            }
        }
        if (portrait) Box(Modifier.fillMaxSize()) {
            if (!artwork.isNullOrBlank()) AsyncImage(thumbnailArtworkUrl(artwork, 342), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Movie, null, Modifier.align(Alignment.Center), tint = Color.Gray)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .25f to Color(0x22090B10), .6f to Color(0xE6090B10), 1f to Color(0xFF090B10))))
            val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = Color(0xFFCED2DA), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                Text(title, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metadata.ifBlank { " " }, color = Color(0xFFB9BDC7), fontSize = 10.sp,
                    lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(30.dp * scale)) {
                    EpisodeViewingStatus(remaining, resume, compact = true, stacked = true)
                }
            }
            progress?.takeIf { it > 0 }?.let {
                LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Color(0xFFE50925), trackColor = Color(0xFF4B4F58), drawStopIndicator = {})
            }
        } else if (listView) Row(Modifier.padding(if (shortWide || compactList) 5.dp else if (compact) 10.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(if (shortWide || compactList) 8.dp else if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            image(Modifier.width(if (shortWide) 64.dp else if (compactTvList) 88.dp else if (compactList) 104.dp else if (compact) 92.dp else 112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (shortWide || compactList) 2.dp else 4.dp)) {
                Text(title, Modifier.fillMaxWidth(), color = Color.White, style = if (shortWide || compactList) MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 14.sp) else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = if (shortWide || compactTvList) 1 else 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(label.takeIf(String::isNotBlank), metadata.takeIf(String::isNotBlank),
                    overview?.takeIf { shortWide && it.isNotBlank() }).joinToString(" · "),
                    color = Color(0xFFB9BDC7), style = if (shortWide || compactList) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp) else MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                EpisodeViewingStatus(remaining, resume, compact = shortWide, singleLine = compactList)
                if (!shortWide && !compactList && !overview.isNullOrBlank()) Text(overview, color = Color(0xFFB9BDC7),
                    style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.PlayArrow, null, Modifier.size(if (shortWide) 18.dp else 24.dp), tint = Color.White)
        } else Column {
            image(Modifier.fillMaxWidth())
            Column(Modifier.padding(if (shortWide) 5.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (shortWide) 2.dp else 5.dp)) {
                Text(label, color = Color(0xFFB9BDC7), style = if (shortWide) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp) else MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(title, color = Color.White, style = if (shortWide) MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp) else MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metadata.ifBlank { " " }, color = Color(0xFFB9BDC7),
                    style = if (shortWide) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp) else MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Every card reserves one status line, including unwatched episodes.
                // A watch/progress update must not change the grid's card geometry.
                val statusHeight = if (shortWide) (14f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
                    else with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }.coerceAtLeast(18.dp)
                Box(Modifier.fillMaxWidth().height(statusHeight), contentAlignment = Alignment.CenterStart) {
                    EpisodeViewingStatus(remaining, resume, compact = shortWide, singleLine = true)
                }
            }
        }
    }
}
