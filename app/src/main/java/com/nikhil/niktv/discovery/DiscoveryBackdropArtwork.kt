package com.nikhil.niktv.discovery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import kotlinx.coroutines.flow.first

@Composable
internal fun DiscoveryBackdropArtwork(
    requested: String?, reduceMotion: Boolean, alignment: Alignment = Alignment.Center,
    shaded: Boolean = true, viewportShade: Boolean = false, fillWidth: Boolean = false,
    onFailure: (String) -> Unit = {}, firstRevealMillis: Int = 300, prepare: Boolean = true,
) {
    val context = LocalContext.current
    val currentRequest by rememberUpdatedState(requested)
    val reportFailure by rememberUpdatedState(onFailure)
    var prepared by remember { mutableStateOf<Painter?>(null) }
    var visible by remember { mutableStateOf<Painter?>(null) }
    var outgoing by remember { mutableStateOf<Painter?>(null) }
    val progress = remember { Animatable(1f) }

    if (prepare && requested == null) LaunchedEffect(Unit) { prepared = null }
    if (prepare && requested != null) {
        val request = remember(context, requested, shaded, viewportShade) {
            ImageRequest.Builder(context).data(requested).size(1280, 720)
                .apply { if (shaded) transformations(DiscoveryBackdropTransformation(verticalShade = !viewportShade)) }
                .crossfade(false).build()
        }
        key(request) {
            val loader = rememberAsyncImagePainter(request, contentScale = if (fillWidth) ContentScale.FillWidth else ContentScale.Crop)
            LaunchedEffect(loader) {
                val result = loader.state.first { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }
                if (currentRequest != requested) return@LaunchedEffect
                when (result) {
                    is AsyncImagePainter.State.Success -> prepared = result.painter
                    is AsyncImagePainter.State.Error -> reportFailure(requested)
                    else -> Unit
                }
            }
        }
    }

    // Keep the ready image opaque underneath the incoming one. Two simultaneous
    // fades would expose the dark background even when both images are available.
    LaunchedEffect(reduceMotion) {
        // Finish the current fade before drawing the latest prepared image. If several
        // requests finish in that interval, snapshotFlow delivers the last one.
        snapshotFlow { prepared }.collect { next ->
            if (reduceMotion) {
                visible = next
                outgoing = null
                progress.snapTo(1f)
            } else if (visible !== next) {
                val firstReveal = visible == null && outgoing == null
                progress.snapTo(0f)
                outgoing = visible ?: outgoing
                visible = next
                progress.animateTo(1f, tween(if (firstReveal) firstRevealMillis else 300))
                outgoing = null
            }
        }
    }

    val frame = if (fillWidth) Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).aspectRatio(16f / 9f)
        else Modifier.fillMaxSize()
    val scaling = if (fillWidth) ContentScale.FillWidth else ContentScale.Crop
    Box(Modifier.fillMaxSize()) {
        outgoing?.let {
            val modifier = if (visible != null) frame else frame.graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = 1f - progress.value
            }
            Image(it, null, modifier, alignment = alignment, contentScale = scaling)
        }
        visible?.let {
            Image(it, null, frame.graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = progress.value
            }, alignment = alignment, contentScale = scaling)
        }
    }
}
