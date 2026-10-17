package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

internal object VideoResizePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("player_video_sizing", Context.MODE_PRIVATE)
    fun mode(context: Context): VideoResizeMode = runCatching {
        VideoResizeMode.valueOf(prefs(context).getString("mode", VideoResizeMode.FIT.name)!!)
    }.getOrDefault(VideoResizeMode.FIT)
    fun save(context: Context, mode: VideoResizeMode) { prefs(context).edit().putString("mode", mode.name).apply() }
    fun preferences(context: Context): SharedPreferences = prefs(context)
}

@Composable
internal fun rememberVideoResizeMode(): VideoResizeMode {
    val context = LocalContext.current
    var mode by remember(context) { mutableStateOf(VideoResizePreferences.mode(context)) }
    DisposableEffect(context) {
        val prefs = VideoResizePreferences.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mode = VideoResizePreferences.mode(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return mode
}

@Composable
internal fun VideoResizeQuickOverlay(
    preview: VideoResizeMode,
    onPreview: (VideoResizeMode) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val compactPhone = !LocalContext.current.isTvLikeDevice(configuration) && configuration.smallestScreenWidthDp < 600
    val columns = if (compactPhone) 2 else 4
    val requesters = remember { VideoResizeMode.entries.map { FocusRequester() } }
    val skipRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onSkip,
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
                Text("Video resize", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("Preview: ${preview.label}  •  Save to keep", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(
                Modifier.playerChoiceFocusEntry(requesters[preview.ordinal])
                    .then(if (compactPhone) Modifier.widthIn(min = 260.dp, max = 330.dp) else Modifier.fillMaxWidth()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                VideoResizeMode.entries.chunked(columns).forEachIndexed { row, modes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        modes.forEachIndexed { column, mode ->
                            val index = mode.ordinal
                            PlayerVisualChoiceButton(
                                label = mode.label,
                                selected = preview == mode,
                                modifier = Modifier.weight(1f).focusRequester(requesters[index]).focusProperties {
                                    left = if (column > 0) requesters[index - 1] else FocusRequester.Cancel
                                    right = if (column < modes.lastIndex) requesters[index + 1] else FocusRequester.Cancel
                                    up = if (row > 0) requesters[index - columns] else FocusRequester.Cancel
                                    down = requesters.getOrNull(index + columns) ?: saveRequester
                                },
                                onClick = { onPreview(mode) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                NikTvActionButton("Skip", onClick = onSkip,
                    modifier = Modifier.width(96.dp).height(44.dp).focusRequester(skipRequester)
                        .focusProperties { right = saveRequester; up = requesters[preview.ordinal] })
                NikTvActionButton("Save", onClick = onSave, primary = true,
                    modifier = Modifier.width(96.dp).height(44.dp).focusRequester(saveRequester)
                        .focusProperties { left = skipRequester; up = requesters[preview.ordinal] })
            }
        }
    )
}
