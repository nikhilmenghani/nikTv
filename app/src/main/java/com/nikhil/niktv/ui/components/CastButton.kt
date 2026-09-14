package com.nikhil.niktv.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.nikhil.niktv.ui.PlayerChromeIconButton

@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    onCastConnected: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isCastConnected by remember { mutableStateOf(false) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .build()
    }

    // Watch route changes so icon updates live when user connects/disconnects
    DisposableEffect(Unit) {
        val mediaRouter = try { MediaRouter.getInstance(context) } catch (_: Exception) { null }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
                val connected = !route.isDefault && route.matchesSelector(selector)
                isCastConnected = connected
                if (connected) onCastConnected?.invoke()
            }
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
                isCastConnected = false
            }
        }

        mediaRouter?.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        // Check initial state
        isCastConnected = mediaRouter?.selectedRoute?.let {
            !it.isDefault && it.matchesSelector(selector)
        } == true

        onDispose {
            mediaRouter?.removeCallback(callback)
        }
    }

    PlayerChromeIconButton(
        icon = if (isCastConnected) Icons.Default.CastConnected else Icons.Default.Cast,
        contentDescription = if (isCastConnected) "Cast connected" else "Cast to a device",
        selected = isCastConnected,
        onClick = {
            try {
                val mediaRouter = MediaRouter.getInstance(context)
                if (isCastConnected) {
                    // Already connected — show the controller dialog (disconnect / volume)
                    val dialog = MediaRouteControllerDialog(context)
                    dialog.show()
                } else {
                    // Not connected — show the chooser dialog to pick a device
                    val dialog = MediaRouteChooserDialog(context).apply {
                        routeSelector = selector
                    }
                    dialog.show()
                }
            } catch (e: Exception) {
                // Cast framework not available on this device (e.g. Fire TV)
            }
        },
        modifier = modifier
    )
}
