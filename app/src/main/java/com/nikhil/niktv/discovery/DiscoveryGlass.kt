package com.nikhil.niktv.discovery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import com.nikhil.niktv.data.thumbnailArtworkUrl

internal val LocalDiscoveryGlassButtons = staticCompositionLocalOf { false }

internal data class DiscoveryHeaderWidths(val menu: Float, val profile: Float)
internal fun discoveryHeaderWidths(available: Float, desiredMenu: Float, desiredProfile: Float): DiscoveryHeaderWidths {
    val profile = desiredProfile.coerceAtMost(available * .3f)
    return DiscoveryHeaderWidths(desiredMenu.coerceAtMost((available - profile - 12f).coerceAtLeast(0f)), profile)
}

/** Blur only cached artwork, never the controls or live screen content. Works on older Fire OS too. */
@Composable
internal fun DiscoveryGlassPanel(modifier: Modifier, artwork: String?, backdropBounds: Rect,
    focused: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    var position by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val permitted = remember(artwork) { !discoveryArtworkPressure(context) }
    val painter = rememberAsyncImagePainter(remember(artwork, permitted) {
        ImageRequest.Builder(context).data(if (permitted) thumbnailArtworkUrl(artwork, 1280) else null)
            .size(1280, 720).transformations(DiscoveryGlassTransformation()).build()
    })
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.onGloballyPositioned { position = it.positionInRoot() }.clip(shape)
        .background(Color(0x77171B24)).border(if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0x777F899B), shape)) {
        Canvas(Modifier.matchParentSize()) {
            val intrinsic = painter.intrinsicSize
            if (intrinsic.width.isFinite() && intrinsic.width > 0 && intrinsic.height.isFinite() && intrinsic.height > 0 && !backdropBounds.isEmpty) {
                val scale = maxOf(backdropBounds.width / intrinsic.width, backdropBounds.height / intrinsic.height)
                val width = intrinsic.width * scale; val height = intrinsic.height * scale
                val x = backdropBounds.left + (backdropBounds.width - width) / 2 - position.x
                val y = backdropBounds.top + (backdropBounds.height - height) / 2 - position.y
                withTransform({ translate(x, y) }) { with(painter) { draw(Size(width, height), alpha = .68f) } }
            }
            drawRect(Color(0x71101520))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .12f))))
        }
        content()
    }
}
