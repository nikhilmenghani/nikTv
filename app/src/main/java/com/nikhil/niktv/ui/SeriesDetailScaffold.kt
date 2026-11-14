package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Fixed hero and toolbar geometry; only the supplied episode viewport scrolls. */
@Composable
internal fun SeriesDetailScaffold(header: @Composable () -> Unit, episodes: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        header()
        Box(Modifier.weight(1f).fillMaxWidth()) { episodes() }
    }
}

@Composable
internal fun SeriesEpisodePagingError(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, Modifier.weight(1f), color = Color(0xFFD0D3DC), style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        NikTvTextActionButton(onClick = retry) { Text("Retry") }
    }
}

/** The toolbar stays fixed while the episode viewport scrolls inside one surface. */
@Composable
internal fun SeriesEpisodePanel(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    toolbar: @Composable () -> Unit,
    episodes: @Composable () -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color(0x44111720),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                toolbar()
            }
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) { episodes() }
        }
    }
}

/** IME consumes the first Back; collapse its search field on that same dismissal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesSearchKeyboardDismiss(editing: Boolean, close: () -> Unit) {
    val visible = WindowInsets.isImeVisible
    var shown by remember { mutableStateOf(false) }
    val currentClose by rememberUpdatedState(close)
    LaunchedEffect(editing, visible) {
        if (!editing) shown = false
        else if (visible) shown = true
        else if (shown) { shown = false; currentClose() }
    }
}
