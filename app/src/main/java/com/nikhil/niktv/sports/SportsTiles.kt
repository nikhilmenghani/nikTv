package com.nikhil.niktv.sports

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.niktv.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun SportsLogo(url: String, label: String, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(url.isBlank()) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) Icon(Icons.Default.LiveTv, null, Modifier.fillMaxSize(.65f), tint = SportsMuted)
        if (url.isNotBlank()) AsyncImage(url, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onError = { failed = true }, onSuccess = { failed = false })
    }
}

internal val SportsWarning = Color(0xFFF3BE62)

@Composable private fun SportsTile(modifier: Modifier, label: String, click: () -> Unit, menu: () -> Unit, flagged: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(modifier.onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }
        .remoteCombinedClickable(click, menu, showIndication = false), shape = SportsShape,
        color = if (focused) Color(0xFF292F3B) else SportsSurface, contentColor = Color.White,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else if (flagged) SportsWarning else Color(0xFF353C49))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable internal fun SportsChannelTile(channel: SportsChannel, favourite: Boolean, modifier: Modifier,
    click: () -> Unit, menu: () -> Unit, flag: SportsChannelFlag? = null) {
    SportsTile(modifier, channel.title, click, menu, flagged = flag != null) {
        Box(Modifier.fillMaxWidth().height(78.dp).clip(SportsShape).background(Color(0xFFF1F3F6)), contentAlignment = Alignment.Center) {
            SportsLogo(channel.image, "", Modifier.fillMaxSize().padding(8.dp))
            if (favourite) Icon(Icons.Default.Favorite, "Favourite", Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(channel.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (flag != null) SportsFlagLabel(flag) else Text(channel.group.ifBlank { channel.category }.ifBlank { "Channel" }, color = SportsMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun SportsCategoryTile(category: SportsCategory, modifier: Modifier, click: () -> Unit, menu: () -> Unit) {
    SportsTile(modifier, category.title, click, menu) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp).clip(SportsShape).background(Color(0xFFF1F3F6))) { SportsLogo(category.image, "", Modifier.fillMaxSize().padding(5.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(category.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (category.custom) "Your playlist" else if (category.isPlaylist) "Playlist" else "Channels", color = SportsMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable internal fun SportsEventTile(event: SportsEvent, now: Instant, favourite: Boolean, modifier: Modifier,
    click: () -> Unit, menu: () -> Unit, flag: SportsChannelFlag? = null) {
    SportsTile(modifier, event.title, click, menu, flagged = flag != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SportsLogo(event.image, "", Modifier.size(24.dp))
            Text(listOf(event.sport, event.competition).filter(String::isNotBlank).joinToString(" · "), Modifier.weight(1f),
                color = SportsMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (event.isHot) Icon(Icons.Default.LocalFireDepartment, "Featured", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            if (favourite) Icon(Icons.Default.Favorite, "Favourite", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamBadge(event.teamAFlag, event.teamA.ifBlank { event.title }, Modifier.weight(1f))
            Text("vs", color = SportsMuted, style = MaterialTheme.typography.labelSmall)
            TeamBadge(event.teamBFlag, event.teamB, Modifier.weight(1f))
        }
        Text(event.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(sportsEventTime(event), color = SportsMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sportsEventClock(event, now), color = if (event.status(now) == SportsStatus.LIVE) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (flag != null) SportsFlagLabel(flag)
    }
}

@Composable private fun SportsFlagLabel(flag: SportsChannelFlag) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.Flag, null, Modifier.size(14.dp), tint = SportsWarning)
        Text(flag.label, color = SportsWarning, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun sportsFlagDescription(flag: SportsChannelFlag): String {
    val time = DateTimeFormatter.ofPattern("d MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(flag.updatedAt))
    return if (flag.reason == SportsFlagReason.MANUAL) "Flagged by you · $time. You can still try this channel."
        else "Playback failed · $time. Sources may recover; you can retry anytime."
}

@Composable internal fun SportsAvailabilityNotice(flag: SportsChannelFlag, clear: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = SportsSurface, shape = SportsShape, border = BorderStroke(1.dp, SportsWarning)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Flag, null, tint = SportsWarning)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(flag.label, color = SportsWarning, fontWeight = FontWeight.SemiBold)
                Text(sportsFlagDescription(flag), color = SportsMuted, style = MaterialTheme.typography.bodySmall)
            }
            NikTvSecondaryActionButton(clear) { Text("Remove flag") }
        }
    }
}

@Composable private fun TeamBadge(url: String, name: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SportsLogo(url, "", Modifier.size(40.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun SportsSourceTile(stream: SportsStream, index: Int, resolving: Boolean, modifier: Modifier, enabled: Boolean, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, modifier = modifier.remoteFocusFrame(SportsShape), shape = SportsShape,
        color = SportsSurface, contentColor = Color.White, border = BorderStroke(1.dp, if (resolving) MaterialTheme.colorScheme.primary else Color(0xFF353C49))) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (resolving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stream.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (resolving) "Preparing stream…" else "Source ${index + 1}", color = SportsMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable internal fun SportsDetailHeader(channel: SportsChannel, event: SportsEvent?, detail: SportsChannelDetail, now: Instant, adjacent: (Int) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = SportsSurface, contentColor = Color.White, shape = SportsShape) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SportsLogo(channel.image, "", Modifier.size(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(channel.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(event?.let { listOf(it.sport, it.competition).filter(String::isNotBlank).joinToString(" · ") } ?: channel.group.ifBlank { channel.category }, color = SportsMuted, style = MaterialTheme.typography.labelMedium)
                if (event != null) Text(sportsEventClock(event, now), color = SportsMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (detail.previous != null) NikTvSecondaryActionButton({ adjacent(-1) }) { Icon(Icons.Default.SkipPrevious, "Previous channel", Modifier.size(20.dp)) }
            if (detail.next != null) NikTvSecondaryActionButton({ adjacent(1) }) { Icon(Icons.Default.SkipNext, "Next channel", Modifier.size(20.dp)) }
        }
    }
}

private fun sportsEventTime(event: SportsEvent): String = event.start?.let {
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z").withZone(ZoneId.systemDefault()).format(it)
} ?: "Schedule unavailable"
