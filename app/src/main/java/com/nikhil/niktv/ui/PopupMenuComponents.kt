package com.nikhil.niktv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared popup row whose focus surface follows the rounded popup geometry.
 * The inset prevents the first and last focused rows from covering the menu's
 * rounded corners on TV while remaining a restrained touch/keyboard highlight.
 */
@Composable
internal fun NikDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    val background by animateColorAsState(
        if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent,
        label = "popupMenuItemBackground"
    )
    val outline by animateColorAsState(
        if (focused) Color.White.copy(alpha = 0.38f) else Color.Transparent,
        label = "popupMenuItemOutline"
    )

    DropdownMenuItem(
        text = text,
        onClick = onClick,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(shape)
            .onFocusChanged { focused = it.hasFocus }
            .background(background, shape)
            .border(BorderStroke(1.dp, outline), shape)
    )
}
