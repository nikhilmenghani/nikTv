package com.nikhil.niktv.ui

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.PlaybackEngine

internal fun playerPickerOptions(sdk: Int) = PLAYER_ENGINE_OPTIONS.filter {
    it != PlaybackEngine.MPV || mpvSupportedSdk(sdk)
}

internal fun PlaybackEngine.pickerChoice() = if (this == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else this

/** Route automatic dialog focus before the first item can receive or paint focus. */
internal fun Modifier.playerChoiceFocusEntry(initial: FocusRequester): Modifier =
    focusProperties { onEnter = { initial.requestFocus() } }.focusGroup()

/** Choosing is explicit: moving focus or dismissing never changes the playing engine. */
@Composable
internal fun PlayerEngineQuickOverlay(
    selectedEngine: PlaybackEngine,
    onSelect: (PlaybackEngine) -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val compactPhone = !context.isTvLikeDevice(configuration) && configuration.smallestScreenWidthDp < 600
    val columns = if (compactPhone) 2 else 3
    val options = remember { playerPickerOptions(Build.VERSION.SDK_INT) }
    val selected = selectedEngine.pickerChoice()
    val requesters = remember(options) { options.map { FocusRequester() } }
    val closeRequester = remember { FocusRequester() }
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color(0xF21A1A1A),
        title = {
            val view = LocalView.current
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.apply {
                    setGravity(Gravity.END)
                    setDimAmount(0f)
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }
            Column {
                Text("Choose player", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("Current: ${selected.playerChoiceLabel()}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(
                Modifier.playerChoiceFocusEntry(requesters[selectedIndex])
                    .then(if (compactPhone) Modifier.widthIn(min = 260.dp, max = 330.dp) else Modifier.fillMaxWidth()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                options.chunked(columns).forEachIndexed { row, engines ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        engines.forEachIndexed { column, engine ->
                            val index = row * columns + column
                            val active = engine == selected
                            val shape = RoundedCornerShape(10.dp)
                            Surface(
                                onClick = { onSelect(engine) },
                                modifier = Modifier.weight(1f).height(if (compactPhone) 48.dp else 56.dp)
                                    .focusRequester(requesters[index])
                                    .focusProperties {
                                        left = if (column > 0) requesters[index - 1] else FocusRequester.Cancel
                                        right = if (column < engines.lastIndex) requesters[index + 1] else FocusRequester.Cancel
                                        up = if (row > 0) requesters[index - columns] else FocusRequester.Cancel
                                        down = requesters.getOrNull(index + columns) ?: closeRequester
                                    }
                                    .playerControlFocus(shape) {}
                                    .semantics { this.selected = active },
                                shape = shape,
                                color = if (active) Color(0xFF303A49) else Color.White.copy(alpha = .055f),
                                border = BorderStroke(if (active) 1.5.dp else 1.dp,
                                    if (active) Color(0xFF9AA9BD) else Color.White.copy(alpha = .10f)),
                                contentColor = Color.White
                            ) {
                                Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(engine.playerChoiceLabel(), style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        repeat(columns - engines.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            NikTvActionButton("Close", modifier = Modifier.width(96.dp).height(44.dp)
                .focusRequester(closeRequester).focusProperties { up = requesters[selectedIndex] }, onClick = onDismiss)
        }
    )
}
