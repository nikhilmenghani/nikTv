package com.nikhil.niktv.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Shared device-level controls so every playback engine behaves identically. */
@Composable
internal fun PlayerLevelControls(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onFocused: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audio) {
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var brightness by remember(activity) {
        val configured = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (configured >= 0f) configured else 0.5f)
    }
    var volume by remember(audio) {
        mutableFloatStateOf(
            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
    ) {
        Icon(Icons.Default.Brightness6, "Brightness", tint = Color.White)
        Slider(
            value = brightness,
            onValueChange = { value ->
                brightness = value
                activity?.window?.attributes = activity?.window?.attributes?.apply {
                    screenBrightness = value.coerceAtLeast(0.01f)
                }
            },
            modifier = Modifier.weight(1f).playerControlFocus { onFocused(it) }
        )
        Spacer(Modifier.width(if (compact) 2.dp else 8.dp))
        Icon(Icons.Default.VolumeUp, "Volume", tint = Color.White)
        Slider(
            value = volume,
            onValueChange = { value ->
                volume = value
                audio.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (value * maxVolume).toInt(),
                    0
                )
            },
            modifier = Modifier.weight(1f).playerControlFocus { onFocused(it) }
        )
    }
}
