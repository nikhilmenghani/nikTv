package com.nikhil.niktv.ui

import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider

/** Keep focus captions inside the display even for actions at either edge. */
internal class FocusCaptionPositionProvider(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val centered = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val x = centered.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = (anchorBounds.bottom + gap).coerceIn(0,
            (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
