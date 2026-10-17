package com.nikhil.niktv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.currentLiveProgramme
import com.nikhil.niktv.model.isMissingLiveProgrammeTitle

@Composable
internal fun CompactPlayerQueueContent(
    item: MediaItem, current: Boolean, live: Boolean, category: String, xtream: Boolean,
    now: Long, favorite: Boolean, pinned: Boolean
) {
    val title = remember(item.title, category, xtream) { liveTvTilePresentation(item.title, category, xtream) }
    val programme = if (live) item.currentLiveProgramme(now)?.takeUnless { isMissingLiveProgrammeTitle(it.title) } else null
    val start = programme?.startTimeMillis
    val end = programme?.endTimeMillis
    val progress = if (start != null && end != null && end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else null
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (current) Icon(Icons.Default.PlayArrow, "Now playing", Modifier.size(16.dp), tint = Color(0xFFFF4D57))
            Text(if (live) title.title else item.title, Modifier.weight(1f),
                style = if (programme == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = if (programme == null) Color.White else Color(0xFFB9BDCA),
                fontWeight = FontWeight.SemiBold, maxLines = if (programme == null) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (pinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(14.dp), tint = Color.LightGray)
            if (favorite) Icon(Icons.Default.Favorite, "Favorite", Modifier.size(14.dp), tint = Color(0xFFFF727A))
            title.quality?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.LightGray) }
        }
        programme?.let {
            Spacer(Modifier.height(3.dp))
            Text(liveProgrammeDisplayTitle(it.title), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            liveTileScheduleText(start, end)?.let { schedule ->
                Text(schedule, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp), color = Color.LightGray, maxLines = 1)
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF363B47))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFF8A86E8)))
            }
        }
    }
}
