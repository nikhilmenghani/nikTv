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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer

/** Allows the shared artwork host to anchor its fade to the actual episode panel. */
internal val LocalSeriesEpisodePanelTop = staticCompositionLocalOf<((Float) -> Unit)?> { null }

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
    loading: Boolean = false,
    loadingColumns: Int = 2,
    posterRow: Boolean = false,
    toolbar: (@Composable () -> Unit)?,
    episodes: @Composable () -> Unit,
) {
    val reportTop = LocalSeriesEpisodePanelTop.current
    val television = LocalContext.current.isTvLikeDevice(LocalConfiguration.current)
    Surface(modifier.then(if (reportTop != null) Modifier.onGloballyPositioned {
        reportTop(it.positionInWindow().y)
    } else Modifier), shape = RoundedCornerShape(14.dp), color = Color(0x44111720),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))) {
        Column(Modifier.fillMaxSize()) {
            if (toolbar != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) { toolbar() }
                HorizontalDivider(color = Color.White.copy(alpha = .08f))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp,
                top = if (television) 4.dp else 8.dp, bottom = 8.dp)) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (loading) 0f else 1f }) { episodes() }
                if (loading) Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(if (posterRow) 1 else 3) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            repeat(loadingColumns.coerceIn(1, 6)) {
                                Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(10.dp),
                                    color = Color(0xBB161B23), border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
                                        Surface(Modifier.fillMaxWidth(.7f).height(10.dp), color = Color.White.copy(alpha = .10f), shape = RoundedCornerShape(4.dp)) {}
                                        Spacer(Modifier.height(8.dp))
                                        Surface(Modifier.fillMaxWidth(.45f).height(7.dp), color = Color.White.copy(alpha = .06f), shape = RoundedCornerShape(4.dp)) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
