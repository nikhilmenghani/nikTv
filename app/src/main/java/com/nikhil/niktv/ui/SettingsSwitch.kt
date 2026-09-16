package com.nikhil.niktv.ui

import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Draw around the visible Material switch track, preserving its larger touch target. */
@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.drawWithContent {
            drawContent()
            if (focused) {
                val width = 58.dp.toPx()
                val height = 38.dp.toPx()
                drawRoundRect(
                    color = Color(0xFFFFB3B8),
                    topLeft = Offset((size.width - width) / 2, (size.height - height) / 2),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2),
                    style = Stroke(2.dp.toPx())
                )
            }
        }
    )
}
