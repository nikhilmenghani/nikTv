package com.nikhil.niktv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.data.artworkRequest
import androidx.compose.runtime.remember

/** Shares the restrained Live TV card styling while keeping movie/episode artwork visible. */
@Composable
internal fun PlayerMediaTileContent(item: MediaItem, current: Boolean, isTv: Boolean) {
    val context = LocalContext.current
    val artwork = remember(context, item.id, item.title, item.logo) { artworkRequest(context, item) }
    val accent = Color(0xFF7C8CFF)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
        Color(0xFF292738), Color(0xFF202228), Color(0xFF16181D))))) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * .94f, size.height * .5f)
            drawCircle(accent.copy(alpha = .12f), size.height * .68f, center)
            drawCircle(Color.White.copy(alpha = .09f), size.height * .84f, center,
                style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!item.logo.isNullOrBlank()) AsyncImage(
                    model = artwork, contentDescription = null,
                    modifier = Modifier.size(width = if (isTv) 48.dp else 40.dp, height = 28.dp)
                        .clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
                Text(if (current) "PLAYING" else if (item.episodeNumber != null) "EPISODE" else "MOVIE",
                    color = if (current) accent else Color(0xFFB9BDD0),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis)
            }
            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val episodeLabel = listOfNotNull(item.seasonNumber?.let { "S$it" },
                item.episodeNumber?.let { "E$it" }).joinToString(" · ")
            if (episodeLabel.isNotEmpty()) Text(episodeLabel, color = Color.White.copy(alpha = .68f),
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
