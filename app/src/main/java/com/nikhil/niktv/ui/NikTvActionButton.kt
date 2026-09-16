package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    /*
     * NIKTV_ACTION_BUTTON_REFERENCE_V2
     *
     * Shared actions intentionally match the original Picture Mode action
     * language: one rounded-rectangle border, slate focus fill, and no
     * secondary scale/elevation focus decoration.
     */
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = shape,
        color = when {
            !enabled -> Color.White.copy(alpha = 0.035f)
            emphasis == NikTvActionEmphasis.PRIMARY ->
                MaterialTheme.colorScheme.primary
            focused -> Color(0xFF303A49)
            else -> Color.White.copy(alpha = 0.055f)
        },
        contentColor =
            if (enabled) Color.White
            else Color.White.copy(alpha = 0.48f),
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) {
                Color(0xFFE7E9EF)
            } else {
                Color.White.copy(alpha = 0.14f)
            }
        )
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
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    // Exact shared form of the original Picture Mode Settings / Skip / Apply.
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = shape,
        color = when {
            !enabled -> Color.White.copy(alpha = 0.035f)
            primary -> MaterialTheme.colorScheme.primary
            focused -> Color(0xFF303A49)
            else -> Color.White.copy(alpha = 0.055f)
        },
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) {
                Color(0xFFE7E9EF)
            } else {
                Color.White.copy(alpha = 0.14f)
            }
        ),
        contentColor =
            if (enabled) Color.White
            else Color.White.copy(alpha = 0.48f)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight =
                    if (focused || primary) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Medium
                    },
                maxLines = 1,
                softWrap = false
            )
        }
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
