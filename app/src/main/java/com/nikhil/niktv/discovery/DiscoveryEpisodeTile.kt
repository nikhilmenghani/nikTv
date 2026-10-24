package com.nikhil.niktv.discovery

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.thumbnailArtworkUrl
import com.nikhil.niktv.ui.remoteCombinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource

/** Shared landscape episode treatment. Provider-specific actions remain with the caller. */
@Composable
internal fun DiscoveryEpisodeTile(title: String, artwork: String?, label: String, metadata: String,
    modifier: Modifier = Modifier, progress: Float? = null, remaining: String? = null,
    resume: Boolean = false, listView: Boolean = false, overview: String? = null,
    onFocus: () -> Unit = {}, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val shape = RoundedCornerShape(10.dp)
    Surface(modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .remoteCombinedClickable(onClick = onClick, onLongClick = onLongClick,
            interactionSource = remember { MutableInteractionSource() }, showIndication = false),
        shape = shape, color = if (focused) Color(0xFF303A49) else Color(0xFF161B23),
        border = BorderStroke(if (focused) 2.dp else 1.dp,
            if (focused) Color.White else if (resume) Color(0xFF9B3744) else Color.White.copy(alpha = .12f))) {
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
        if (listView) Row(Modifier.padding(if (compact) 10.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            image(Modifier.width(if (compact) 92.dp else 112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(label, metadata).filter(String::isNotBlank).joinToString(" · "),
                    color = Color(0xFFB9BDC7), style = MaterialTheme.typography.labelMedium)
                if (!overview.isNullOrBlank()) Text(overview, color = Color(0xFFB9BDC7),
                    style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis)
                remaining?.let { Text(it, color = if (resume) Color(0xFFFFA0AA) else Color(0xFFB9BDC7),
                    style = MaterialTheme.typography.labelSmall) }
            }
            Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp), tint = Color.White)
        } else Column {
            image(Modifier.fillMaxWidth())
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, color = Color(0xFFB9BDC7), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(title, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(metadata.takeIf { it.isNotBlank() }, remaining).joinToString(" · ").ifBlank { " " },
                    color = if (resume) Color(0xFFFFA0AA) else Color(0xFFB9BDC7),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
