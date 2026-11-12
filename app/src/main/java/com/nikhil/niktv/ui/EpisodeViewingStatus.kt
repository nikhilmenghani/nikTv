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

/** One quiet, non-interactive status below episode metadata on every layout. */
@Composable
internal fun EpisodeViewingStatus(remaining: String?, lastWatched: Boolean) {
    if (!lastWatched && remaining == null) return
    val watched = remaining == "Watched"
    val color = if (lastWatched && !watched) Color(0xFFFFA0AA) else Color(0xFFB9BDC7)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = if (watched) Icons.Default.Check else if (lastWatched) Icons.Default.History else null
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp), tint = color) }
        Text(listOfNotNull("Last watched".takeIf { lastWatched }, remaining).joinToString(" · "),
            color = color, style = MaterialTheme.typography.labelSmall)
    }
}
