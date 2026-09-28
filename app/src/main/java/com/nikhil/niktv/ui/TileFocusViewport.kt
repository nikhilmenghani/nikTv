package com.nikhil.niktv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Keep visible tiles stationary rather than moving them to the TV focus pivot. */
internal fun tileFocusScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
    offset >= 0f && offset + size <= containerSize -> 0f
    offset <= 0f && offset + size >= containerSize -> 0f
    offset < 0f -> offset
    else -> offset + size - containerSize
}

@OptIn(ExperimentalFoundationApi::class)
private val TileBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        tileFocusScrollDistance(offset, size, containerSize)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StableTileFocusViewport(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides TileBringIntoViewSpec, content = content)
}
