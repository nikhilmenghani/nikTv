package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints

/** Reserve room for the link within the same two lines, including at larger font sizes. */
@Composable
internal fun InlineEpisodeDescription(description: String, expanded: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodySmall, open: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val linkColor = Color(0xFFE2E4EA)
    val currentOpen by rememberUpdatedState(open)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val text = remember(description, width, style, linkColor, measurer, expanded) {
            val bounds = Constraints(maxWidth = width)
            val full = measurer.measure(description, style = style, maxLines = 2, constraints = bounds)
            if (expanded) buildAnnotatedString {
                append(description)
                append(" ")
                withLink(LinkAnnotation.Clickable("collapse-description",
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)),
                    linkInteractionListener = { currentOpen() })) { append("Read less") }
            }
            else if (!full.hasVisualOverflow) buildAnnotatedString { append(description) }
            else {
                fun preview(length: Int) = buildAnnotatedString {
                    val prefix = description.take(length).trimEnd().let {
                        if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
                    }
                    append(prefix)
                    append("… ")
                    withLink(LinkAnnotation.Clickable("episode-description",
                        styles = TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)),
                        linkInteractionListener = { currentOpen() })) { append("Read more") }
                }
                var low = 0
                var high = full.getLineEnd(1, visibleEnd = true)
                while (low < high) {
                    val middle = (low + high + 1) / 2
                    if (measurer.measure(preview(middle), style = style, maxLines = 2,
                            constraints = bounds).hasVisualOverflow) high = middle - 1
                    else low = middle
                }
                preview(low)
            }
        }
        Text(text, style = style, color = Color(0xFFCDD0D8), maxLines = if (expanded) Int.MAX_VALUE else 2)
    }
}
