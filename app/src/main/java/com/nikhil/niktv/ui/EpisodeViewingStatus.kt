package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One quiet, non-interactive status below episode metadata on every layout. */
@Composable
internal fun EpisodeViewingStatus(remaining: String?, lastWatched: Boolean, compact: Boolean = false, singleLine: Boolean = false) {
    if (!lastWatched && remaining == null) return
    val watched = remaining == "Watched"
    val color = if (lastWatched && !watched) Color(0xFFFFA0AA) else Color(0xFFB9BDC7)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = if (watched) Icons.Default.Check else if (lastWatched) Icons.Default.History else null
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp), tint = color) }
        Text(if (compact) remaining ?: "Last watched"
            else listOfNotNull("Last watched".takeIf { lastWatched }, remaining).joinToString(" · "),
            color = color, style = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp) else MaterialTheme.typography.labelSmall,
            maxLines = if (compact || singleLine) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
