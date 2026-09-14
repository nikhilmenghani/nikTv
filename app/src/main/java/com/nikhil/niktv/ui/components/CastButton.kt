package com.nikhil.niktv.ui.components

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.nikhil.niktv.R

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.size(48.dp),
        factory = { context ->
            val themedContext = ContextThemeWrapper(context, androidx.mediarouter.R.style.Theme_MediaRouter)
            MediaRouteButton(themedContext).apply {
                CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
                this.setAlwaysVisible(true)
            }
        }
    )
}
