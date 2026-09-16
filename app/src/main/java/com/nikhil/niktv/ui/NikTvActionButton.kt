package com.nikhil.niktv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * NIKTV_ACTION_BUTTON_V1
 *
 * Shared action-button language extracted from PlayerPictureModeActionButton.
 *
 * - D-pad / keyboard focus: bright white outline + slate highlight.
 * - Touch: normal Material ripple/press feedback without a persistent TV ring.
 * - Primary actions: NikTV red.
 * - Secondary/text actions: restrained translucent surface.
 *
 * Existing Button/OutlinedButton/FilledTonalButton/TextButton call sites are
 * migrated to wrappers below so callbacks, enabled state, focusProperties,
 * FocusRequesters, widths/heights and content remain owned by each screen.
 */

private enum class NikTvActionEmphasis {
    PRIMARY,
    SECONDARY,
    TEXT
}

@Composable
private fun NikTvActionSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    emphasis: NikTvActionEmphasis,
    contentPadding: PaddingValues,
    minHeight: androidx.compose.ui.unit.Dp,
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isTvLikeDevice(configuration)
    val inputModeManager = LocalInputModeManager.current
    var focused by remember { mutableStateOf(false) }

    val keyboardFocused =
        focused && inputModeManager.inputMode == InputMode.Keyboard

    val containerColor by animateColorAsState(
        targetValue =
            when {
                !enabled ->
                    Color.White.copy(alpha = 0.035f)
                emphasis == NikTvActionEmphasis.PRIMARY && keyboardFocused ->
                    Color(0xFFF12A34)
                emphasis == NikTvActionEmphasis.PRIMARY ->
                    Color(0xFFE50914)
                keyboardFocused ->
                    Color(0xFF303A49)
                emphasis == NikTvActionEmphasis.TEXT ->
                    Color.Transparent
                else ->
                    Color.White.copy(alpha = 0.055f)
            },
        label = "nikTvActionContainer"
    )

    val targetScale =
        if (keyboardFocused && isTv) 1.035f else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        label = "nikTvActionScale"
    )

    val shape = RoundedCornerShape(12.dp)
    val focusBorder =
        when {
            keyboardFocused ->
                BorderStroke(2.5.dp, Color.White)
            emphasis == NikTvActionEmphasis.PRIMARY ->
                BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            emphasis == NikTvActionEmphasis.TEXT ->
                BorderStroke(1.dp, Color.Transparent)
            else ->
                BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                focused = it.isFocused
            },
        shape = shape,
        color = containerColor,
        contentColor =
            if (enabled) {
                Color.White
            } else {
                Color.White.copy(alpha = 0.48f)
            },
        border = focusBorder,
        shadowElevation =
            if (keyboardFocused && isTv) 8.dp
            else if (emphasis == NikTvActionEmphasis.PRIMARY) 2.dp
            else 0.dp
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = minHeight)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement.Center,
            content = content
        )
    }
}

@Composable
internal fun NikTvActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    NikTvActionSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        emphasis =
            if (primary) {
                NikTvActionEmphasis.PRIMARY
            } else {
                NikTvActionEmphasis.SECONDARY
            },
        contentPadding =
            PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        minHeight = 46.dp
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 7.dp)
                    .defaultMinSize(
                        minWidth = 18.dp,
                        minHeight = 18.dp
                    )
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun NikTvPrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues =
        PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    NikTvActionSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        emphasis = NikTvActionEmphasis.PRIMARY,
        contentPadding = contentPadding,
        minHeight = 48.dp,
        content = content
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun NikTvSecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues =
        PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    NikTvActionSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        emphasis = NikTvActionEmphasis.SECONDARY,
        contentPadding = contentPadding,
        minHeight = 48.dp,
        content = content
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun NikTvTextActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues =
        PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    NikTvActionSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        emphasis = NikTvActionEmphasis.TEXT,
        contentPadding = contentPadding,
        minHeight = 40.dp,
        content = content
    )
}
