package com.nikhil.niktv.discovery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nikhil.niktv.data.thumbnailArtworkUrl
import com.nikhil.niktv.ui.remoteCombinedClickable

/** Compose collections use the same compact, inset poster design as Home's recycled rows. */
@Composable
internal fun DiscoveryHomeStyleMediaCard(title: String, artwork: String?, subtitle: String, width: Dp,
    progress: Float?, source: DiscoveryTileSource?, open: () -> Unit, hold: () -> Unit, focus: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val geometry = nativePosterGeometry(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat(),
        LocalDensity.current.fontScale, phone = true)
    var focused by remember { mutableStateOf(false) }
    val reduced = android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val scale by animateFloatAsState(if (focused && !reduced) 1.025f else 1f, label = "saved-media-focus")
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.width(width).height((geometry.posterHeight + 8f).dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(if (focused) 4.dp else 0.dp, shape)
        .clip(shape)
        .background(Color(0xFF151A22), shape)
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF2B303A), shape)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) focus() }
        .semantics(mergeDescendants = true) {
            contentDescription = title
            source?.let { stateDescription = discoveryTileStateDescription(it, null) }
        }
        .remoteCombinedClickable(onClick = open, onLongClick = hold,
            interactionSource = remember { MutableInteractionSource() }, showIndication = true)
        .padding(4.dp)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp)).background(Color(0xFF151A22))) {
            AsyncImage(ImageRequest.Builder(context).data(thumbnailArtworkUrl(artwork, 342))
                .size(342, 513).crossfade(false).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .55f), Color.Black.copy(alpha = .96f)))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 3.dp, end = 3.dp, top = 6.dp, bottom = 10.dp)) {
                Text(title, color = Color.White, fontSize = (10f * geometry.textScale).sp,
                    fontWeight = FontWeight.Bold, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = Color.LightGray,
                    fontSize = (8.5f * geometry.textScale).sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            progress?.let { value -> Canvas(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)) {
                drawRect(Color.DarkGray)
                drawRect(Color(0xFFE50925), size = size.copy(width = size.width * value.coerceIn(0f, 1f)))
            } }
            DiscoveryTileIndicators(source, null, Modifier.align(Alignment.TopCenter))
        }
    }
}
