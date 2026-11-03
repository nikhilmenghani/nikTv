package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Native tiles publish their window bounds only when opening a menu. Other menus keep their normal anchor. */
internal val LocalTileMenuAnchor = staticCompositionLocalOf<IntRect?> { null }

internal fun tileMenuPosition(anchor: IntRect, window: IntSize, menu: IntSize, gap: Int, margin: Int): IntOffset {
    val maxX = (window.width - menu.width - margin).coerceAtLeast(margin)
    val x = when {
        anchor.right + gap + menu.width <= window.width - margin -> anchor.right + gap
        anchor.left - gap - menu.width >= margin -> anchor.left - gap - menu.width
        else -> anchor.left
    }.coerceIn(margin, maxX)
    return IntOffset(x, anchor.top.coerceIn(margin, (window.height - menu.height - margin).coerceAtLeast(margin)))
}

@Composable
internal fun TileActionsDropdown(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val anchor = LocalTileMenuAnchor.current
    if (anchor == null) {
        DropdownMenu(expanded, onDismissRequest, containerColor = Color(0xFF202020), shape = RoundedCornerShape(12.dp), content = content)
    } else if (expanded) {
        val density = LocalDensity.current
        val position = remember(anchor, density) { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                tileMenuPosition(anchor, windowSize, popupContentSize, with(density) { 8.dp.roundToPx() }, with(density) { 8.dp.roundToPx() })
        } }
        Popup(position, onDismissRequest, PopupProperties(focusable = true)) {
            BoxWithConstraints {
                Surface(color = Color(0xFF202020), shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp) {
                    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 180.dp, max = 320.dp)
                        .heightIn(max = maxHeight - 16.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
                }
            }
        }
    }
}
