package com.nikhil.niktv.ui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

/** Apply the same readable presets to Media3's native cues and downloaded text. */
@UnstableApi
internal fun SubtitleView.applySubtitleAppearance(appearance: SubtitleAppearancePreset, bottomInsetDp: Int = 0) {
    // Standard respects authored ASS/SSA colors and sizes. Choosing a readability
    // preset explicitly overrides them; returning to Standard restores them.
    val preserveAuthoredStyle = appearance == SubtitleAppearancePreset.STANDARD
    setApplyEmbeddedStyles(preserveAuthoredStyle)
    setApplyEmbeddedFontSizes(preserveAuthoredStyle)
    setStyle(
        CaptionStyleCompat(
            appearance.textColor.toArgb(),
            appearance.backgroundColor.toArgb(),
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            Color.TRANSPARENT,
            Typeface.DEFAULT
        )
    )
    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.textSizeSp.toFloat())
    val density = resources.displayMetrics.density
    val horizontal = (24 * density).roundToInt()
    val bottomPadding = appearance.bottomPaddingDp + bottomInsetDp.coerceAtLeast(0)
    setPadding(horizontal, 0, horizontal, (bottomPadding * density).roundToInt())
    setBottomPaddingFraction(0f)
}
