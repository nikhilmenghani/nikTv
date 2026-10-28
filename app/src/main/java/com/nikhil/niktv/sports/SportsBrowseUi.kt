package com.nikhil.niktv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.niktv.R
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SportsSurface = Color(0xFF181D26)
private val SportsMuted = Color(0xFFB5BBC7)
private val SportsShape = RoundedCornerShape(10.dp)

@Composable
private fun SportsFilterAction(label: String, selected: Boolean, isDefault: Boolean,
    select: () -> Unit, saveDefault: () -> Unit, resetDefault: () -> Unit) {
    val origin = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    LaunchedEffect(menu) {
        if (!menu && restore) { restore = false; withFrameNanos { }; runCatching { origin.requestFocus() } }
    }
    Surface(modifier = Modifier.focusRequester(origin).onFocusChanged { focused = it.isFocused }
        .remoteCombinedClickable(onClick = select, onLongClick = { restore = true; menu = true })
        .heightIn(min = 40.dp), shape = SportsShape,
        color = if (selected) MaterialTheme.colorScheme.primary else SportsSurface,
        contentColor = Color.White,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454B58))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (isDefault) Icon(Icons.Default.Star, "Default filter", Modifier.size(13.dp))
        }
    }
    if (menu) AlertDialog(onDismissRequest = { menu = false }, shape = SportsShape,
        containerColor = SportsSurface, titleContentColor = Color.White, textContentColor = SportsMuted,
        modifier = Modifier.initialFocusEntry(saveFocus),
        title = { Text(label) },
        text = { Text(if (isDefault) "This filter opens by default." else "Use this filter whenever Sports opens.") },
        confirmButton = {
            NikTvPrimaryActionButton(onClick = { saveDefault(); menu = false }, modifier = Modifier.focusRequester(saveFocus)) {
                Text("Set as default")
            }
        }, dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isDefault) NikTvSecondaryActionButton(onClick = { resetDefault(); menu = false }) { Text("Reset default") }
                NikTvSecondaryActionButton(onClick = { menu = false }) { Text("Close") }
            }
        })
}

/** Presentation only: filtering is local; focus and drawing never resolve a stream. */
@Composable
internal fun SportsBrowseContent(state: SportsState, now: Instant, sport: String, setSport: (String) -> Unit,
    status: String, setStatus: (String) -> Unit, query: String, setQuery: (String) -> Unit,
    back: () -> Unit, refresh: () -> Unit, open: (SportsEvent) -> Unit, play: (Int) -> Unit,
    eventsScroll: LazyGridState = rememberLazyGridState(), streamsScroll: LazyGridState = rememberLazyGridState(),
    lastSource: Int? = null, defaults: SportsBrowseDefaults = SportsBrowseDefaults(),
    saveDefaultSport: (String) -> Unit = {}, saveDefaultStatus: (String) -> Unit = {}) {
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 600
    val side = if (compact) 14.dp else 24.dp
    val backFocus = remember(state.event?.slug) { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val sourceFocus = remember(state.streams) { state.streams.map { FocusRequester() } }
    val initial = remember(state.event?.slug) { lastSource?.let { sourceFocus.getOrNull(it) } ?: backFocus }
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() { editing = false; searchOpen = false; keyboard?.hide() }
    BackHandler(searchOpen && state.event == null) { closeSearch() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) { withFrameNanos { }; runCatching { searchFocus.requestFocus() }; if (editing) keyboard?.show() }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).windowInsetsPadding(WindowInsets.safeDrawing)
        .initialFocusEntry(initial).padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = SportsShape, color = SportsSurface, contentColor = Color.White) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.size(44.dp).graphicsLayer { scaleX = 1.6f; scaleY = 1.6f })
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, modifier = Modifier.focusRequester(backFocus), onClick = back)
                Text(state.event?.title ?: "Sports", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.event == null) DiscoveryAction("Search sports", Icons.Default.Search, iconOnly = true, selected = searchOpen) {
                    if (searchOpen) closeSearch() else { editing = true; searchOpen = true }
                }
                DiscoveryAction("Refresh sports", Icons.Default.Refresh, iconOnly = true, onClick = refresh)
            }
        }
        val event = state.event
        if (event == null) {
            AnimatedVisibility(searchOpen) {
                Box(Modifier.fillMaxWidth()) {
                    SeriesEpisodeSearchField(query, setQuery, ::closeSearch, readOnly = !editing,
                        modifier = Modifier.focusRequester(searchFocus).onPreviewKeyEvent {
                            if (!editing && it.type == KeyEventType.KeyUp && it.key in listOf(Key.DirectionCenter, Key.Enter)) {
                                editing = true; keyboard?.show(); true
                            } else false
                        }, options = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        actions = KeyboardActions(onSearch = { editing = false; keyboard?.hide() }),
                        placeholder = "Find a team or event", closeLabel = "Close sports search")
                    if (!editing) Box(Modifier.matchParentSize().padding(end = 40.dp)
                        .focusProperties { canFocus = false }.clickable { editing = true; keyboard?.show() })
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                items(SPORTS_STATUS_FILTERS) { label ->
                    SportsFilterAction(label, status == label, defaults.status == label, { setStatus(label) }, { saveDefaultStatus(label) }, { saveDefaultStatus("Now & next") })
                }
            }
            val sports = remember(state.events, defaults.sport) { (listOf("All sports", defaults.sport) + state.events.map { it.sport }.filter(String::isNotBlank).sorted()).distinct() }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                items(sports) { label -> SportsFilterAction(label, sport == label, defaults.sport == label, { setSport(label) }, { saveDefaultSport(label) }, { saveDefaultSport("All sports") }) }
            }
            val activeQuery = query.takeIf { searchOpen }.orEmpty()
            LaunchedEffect(sport, status, activeQuery) { eventsScroll.scrollToItem(0) }
            val visible = remember(state.events, sport, status, activeQuery, now) {
                state.events.filter { item ->
                    (sport == "All sports" || item.sport == sport) &&
                    (activeQuery.isBlank() || listOf(item.title, item.teamA, item.teamB, item.competition).any { it.contains(activeQuery.trim(), true) }) &&
                    when (status) { "Now & next" -> item.status(now) != SportsStatus.FINISHED; "All events" -> true; else -> item.status(now).label == status }
                }.sortedWith(compareBy<SportsEvent> { if (it.status(now) == SportsStatus.LIVE) 0 else 1 }.thenBy { it.start })
            }
            Text("${visible.size} ${if (visible.size == 1) "event" else "events"}", color = SportsMuted, style = MaterialTheme.typography.labelLarge)
            if (state.loading) SportsLoading("Loading events")
            state.error?.let { SportsFailure(it, refresh) }
            if (!state.loading && visible.isEmpty() && state.error == null) SportsEmpty("No events match these filters", "Try All events, another sport or a different team name.")
            LazyVerticalGrid(if (compact) GridCells.Fixed(2) else GridCells.Adaptive(190.dp), state = eventsScroll,
                modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(6.dp, 4.dp, 6.dp, 20.dp)) {
                items(visible, key = { it.slug }) { item -> SportsEventCard(item, now) { open(item) } }
            }
        } else {
            Surface(shape = SportsShape, color = SportsSurface, contentColor = Color.White) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(event.image, null, Modifier.width(if (compact) 72.dp else 132.dp).aspectRatio(16f / 9f), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(listOf(event.sport, event.competition).filter(String::isNotBlank).joinToString(" · "), color = SportsMuted, style = MaterialTheme.typography.bodySmall)
                        Text(sportsEventTime(event), color = SportsMuted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Text("Choose a stream", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            if (state.loadingStreams) SportsLoading("Loading stream choices")
            state.streamError?.let { SportsFailure(it, refresh) }
            if (!state.loadingStreams && state.streams.isEmpty() && state.streamError == null) SportsEmpty("Streams are not available yet", "Refresh closer to the event's start time.")
            LazyVerticalGrid(if (compact) GridCells.Fixed(2) else GridCells.Adaptive(190.dp), state = streamsScroll,
                modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(6.dp, 6.dp, 6.dp, 20.dp)) {
                items(state.streams.size, key = { it }) { index ->
                    val resolving = state.resolving == index
                    Surface(onClick = { if (state.resolving == null) play(index) }, enabled = state.resolving == null || resolving,
                        modifier = Modifier.fillMaxWidth().focusRequester(sourceFocus[index]).remoteFocusFrame(SportsShape),
                        shape = SportsShape, color = SportsSurface, contentColor = Color.White,
                        border = BorderStroke(1.dp, if (resolving) MaterialTheme.colorScheme.primary else Color(0xFF353C49))) {
                        Row(Modifier.heightIn(min = 62.dp).padding(9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (resolving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(state.streams[index].title, style = MaterialTheme.typography.labelLarge, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (resolving) "Preparing stream…" else "Source ${index + 1}", color = SportsMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SportsEventCard(event: SportsEvent, now: Instant, click: () -> Unit) {
    Surface(onClick = click, modifier = Modifier.fillMaxWidth().remoteFocusFrame(SportsShape), shape = SportsShape,
        color = SportsSurface, contentColor = Color.White, border = BorderStroke(1.dp, Color(0xFF353C49))) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2.3f).background(Color(0xFF222936)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SportsCricket, null, Modifier.size(36.dp), tint = SportsMuted)
                AsyncImage(event.image, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(5.dp),
                    color = if (event.status(now) == SportsStatus.LIVE) MaterialTheme.colorScheme.primary else Color(0xDD171B24), contentColor = Color.White) {
                    Text(event.status(now).label, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(event.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(event.sport, event.competition).filter(String::isNotBlank).joinToString(" · "), color = SportsMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sportsEventTime(event), color = SportsMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = Color.White.copy(alpha = .08f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("${event.choices.size} ${if (event.choices.size == 1) "stream choice" else "stream choices"}", color = SportsMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun SportsLoading(label: String) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, color = SportsMuted)
    }
}
@Composable private fun SportsEmpty(title: String, description: String) {
    Surface(Modifier.fillMaxWidth(), shape = SportsShape, color = SportsSurface, contentColor = Color.White) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold); Text(description, color = SportsMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun SportsFailure(message: String, retry: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = SportsShape, color = SportsSurface, contentColor = Color.White,
        border = BorderStroke(1.dp, Color(0xFF63313A))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = SportsMuted)
            NikTvSecondaryActionButton(retry) { Text("Retry") }
        }
    }
}
private fun sportsEventTime(event: SportsEvent): String = event.start?.let {
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z").withZone(ZoneId.systemDefault()).format(it)
} ?: "Schedule unavailable"
