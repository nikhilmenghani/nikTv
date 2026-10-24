package com.nikhil.niktv.discovery

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import com.nikhil.niktv.ui.TrailerDialog

@Composable
internal fun DiscoveryTrailerAction(card: DiscoveryCard?, iconOnly: Boolean = false, onFocus: () -> Unit = {}) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    fun restore() { scope.launch {
        repeat(24) {
            withFrameNanos { }
            if (view.hasWindowFocus() && runCatching { focus.requestFocus() }.getOrDefault(false)) return@launch
        }
    } }
    var opened by remember { mutableStateOf<DiscoveryCard?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        restore()
    }
    DiscoveryAction("Watch trailer", Icons.Default.SmartDisplay, iconOnly = iconOnly,
        modifier = Modifier.focusRequester(focus), enabled = card != null && !card.adult,
        onFocus = onFocus) { opened = card }
    opened?.let { selected ->
        TrailerDialog(selected.media, selected.series, launch = { key ->
            launcher.launch(Intent(context, com.nikhil.niktv.TrailerPlayerActivity::class.java)
                .putExtra("video_key", key).putExtra("title", selected.title))
        }, dismiss = { opened = null; restore() })
    }
}
