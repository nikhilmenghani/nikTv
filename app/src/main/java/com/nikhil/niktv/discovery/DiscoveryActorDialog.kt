package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil3.compose.AsyncImage
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.ui.StableTileFocusViewport
import com.nikhil.niktv.ui.initialFocusEntry
import com.nikhil.niktv.ui.AlternateSourceOnlyMenu
import com.nikhil.niktv.ui.isTvLikeDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Public metadata only. Closing/replacing this route cancels its credit lookup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryActorDialog(person: DiscoveryPerson,
    load: suspend (DiscoveryPerson, (List<DiscoveryCard>) -> Unit) -> List<DiscoveryCard>,
    close: () -> Unit, reduceMotion: Boolean = false,
    loadInfo: suspend (DiscoveryPerson) -> DiscoveryActorInfo = { DiscoveryActorInfo(it.name, it.portrait) }, open: (DiscoveryCard) -> Unit) {
    var cards by remember(person.id) { mutableStateOf(emptyList<DiscoveryCard>()) }
    var loading by remember(person.id) { mutableStateOf(true) }
    var failed by remember(person.id) { mutableStateOf(false) }
    var retry by remember(person.id) { mutableIntStateOf(0) }
    var selected by rememberSaveable(person.id) { mutableStateOf<String?>(null) }
    var sort by rememberSaveable(person.id) { mutableStateOf("Year: newest") }
    var sorting by remember { mutableStateOf(false) }
    var sortRevision by remember(person.id) { mutableIntStateOf(0) }
    var information by remember(person.id) { mutableStateOf(false) }
    val infoRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var actorView by remember { mutableStateOf<android.view.View?>(null) }
    fun dismissInfo() { information = false; scope.launch {
        repeat(30) { withFrameNanos { }; if (actorView?.hasWindowFocus() == true && runCatching { infoRequester.requestFocus() }.getOrDefault(false)) return@launch }
    } }
    val shown = remember(cards, sort) { sortActorCards(cards, sort) }
    val requesters = remember(person.id) { mutableMapOf<String, FocusRequester>() }
    val closeRequester = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val phone = usesDiscoveryPhoneLayout(configuration, context.isTvLikeDevice(configuration))
    val posterWidth = if (phone) phoneDiscoveryPosterWidth(configuration.screenWidthDp).dp else 132.dp
    LaunchedEffect(person.id, retry) {
        loading = true; failed = false
        val requestContext = coroutineContext
        try {
            val result = load(person) { cached ->
                requestContext.ensureActive()
                cards = cached.filter { it.released() }
            }
            requestContext.ensureActive()
            cards = result.filter { it.released() }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { failed = true }
        finally { if (requestContext[kotlinx.coroutines.Job]?.isActive == true) loading = false }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val currentWindow = androidx.compose.ui.platform.LocalView.current
        SideEffect { actorView = currentWindow }
        StableTileFocusViewport {
            Surface(Modifier.fillMaxSize().semantics { contentDescription = "Actor credits"; paneTitle = "Actor credits" }, color = Color(0xFF090B10), shape = RectangleShape) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActorControl("Back to cast", Icons.AutoMirrored.Filled.ArrowBack,
                            Modifier.focusRequester(closeRequester), iconOnly = true, click = close)
                        Column(Modifier.weight(1f)) {
                            Text("Featuring ${person.name}", color = Color.White, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Movies and series", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                        ActorControl("About ${person.name}", Icons.Default.Info, modifier = Modifier.focusRequester(infoRequester), iconOnly = true) { information = true }
                    }
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        com.nikhil.niktv.ui.NikTvSecondaryActionButton(onClick = { sorting = true },
                            modifier = Modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Sort: $sort", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        DropdownMenu(sorting, { sorting = false }) {
                            listOf("TMDB order", "Year: newest", "Year: oldest", "Rating: highest", "Rating: lowest").forEach { option ->
                                DropdownMenuItem(text = { Text(option, style = MaterialTheme.typography.labelMedium) }, onClick = { sort = option; sorting = false; sortRevision++ })
                            }
                        }
                    }
                    ActorControl("TMDB order", Icons.Default.FormatListNumbered, active = sort == "TMDB order") {
                        sort = "TMDB order"; sortRevision++
                    }
                    ActorControl("Year", if (sort == "Year: oldest") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        active = sort.startsWith("Year:"), direction = if (sort == "Year: oldest") "Ascending" else "Descending") {
                        sort = toggleActorSort(sort, true); sortRevision++
                    }
                    ActorControl("Rating", if (sort == "Rating: lowest") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        active = sort.startsWith("Rating:"), direction = if (sort == "Rating: lowest") "Ascending" else "Descending") {
                        sort = toggleActorSort(sort, false); sortRevision++
                    }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (failed) Button(onClick = { retry++ }) { Text("Could not update titles. Retry") }
                    if (!loading && cards.isEmpty() && !failed) Text("No released titles available", color = Color.LightGray)
                    LazyVerticalGrid(GridCells.Adaptive(posterWidth), Modifier.weight(1f).fillMaxWidth(), state = grid,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(shown, key = { it.key }) { card ->
                            val requester = remember(card.key) { requesters.getOrPut(card.key) { FocusRequester() } }
                            var alternateOpen by remember(card.key) { mutableStateOf(false) }
                            BoxWithConstraints {
                                DiscoveryPosterTile(card.title, card.poster,
                                    listOfNotNull(if (card.series) "Series" else "Movie", card.date?.take(4),
                                        card.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "),
                                    source = DiscoveryTileSource.TMDB,
                                    width = maxWidth, reduceMotion = reduceMotion, focusVerticalInset = discoveryPosterFocusInset(maxWidth.value).dp, modifier = Modifier.focusRequester(requester),
                                    onFocus = { selected = card.key }, onClick = { open(card) }, onLongClick = { alternateOpen = true })
                                AlternateSourceOnlyMenu(alternateOpen, card.media,
                                    if (card.series) com.nikhil.niktv.model.CatalogType.SERIES else com.nikhil.niktv.model.CatalogType.MOVIES,
                                    dismiss = { alternateOpen = false; runCatching { requester.requestFocus() } })
                            }
                        }
                    }
                }
            }
        }
        // The effect must use one immutable presentation snapshot. A fast cached
        // response can update cards before this composition's shown list changes.
        LaunchedEffect(shown.isNotEmpty(), sortRevision) {
            if (shown.isEmpty()) {
                withFrameNanos { }; if (actorView?.hasWindowFocus() == true) runCatching { closeRequester.requestFocus() }
            } else {
                val index = if (sortRevision > 0) 0 else shown.indexOfFirst { it.key == selected }.coerceAtLeast(0)
                val target = shown.getOrNull(index)?.key ?: return@LaunchedEffect
                selected = target
                grid.scrollToItem(index)
                if (sortRevision > 0) kotlinx.coroutines.delay(100L)
                repeat(24) {
                    withFrameNanos { }
                    if (actorView?.hasWindowFocus() == true && runCatching { requesters[target]?.requestFocus() == true }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }
    }
    if (information) ActorInformationDialog(person, loadInfo, ::dismissInfo)

}

internal fun sortActorCards(cards: List<DiscoveryCard>, sort: String): List<DiscoveryCard> = when (sort) {
    "Year: newest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.date.isNullOrBlank() }.thenByDescending { it.date })
    "Year: oldest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.date.isNullOrBlank() }.thenBy { it.date })
    "Rating: highest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.rating == null }.thenByDescending { it.rating })
    "Rating: lowest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.rating == null }.thenBy { it.rating })
    else -> cards
}

@Composable
private fun ActorControl(label: String, icon: ImageVector, modifier: Modifier = Modifier,
    active: Boolean = false, direction: String = "", iconOnly: Boolean = false, click: () -> Unit) {
    com.nikhil.niktv.ui.NikTvSecondaryActionButton(onClick = click,
        modifier = modifier.height(44.dp).semantics { contentDescription = label; selected = active; stateDescription = direction },
        contentPadding = PaddingValues(horizontal = if (iconOnly) 12.dp else 10.dp, vertical = 6.dp)) {
        if (!iconOnly) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1); Spacer(Modifier.width(6.dp)) }
        Icon(icon, null, Modifier.size(18.dp), tint = if (active) Color(0xFFFF6778) else Color.White)
    }
}

@Composable
internal fun ActorInformationDialog(person: DiscoveryPerson, load: suspend (DiscoveryPerson) -> DiscoveryActorInfo, close: () -> Unit) {
    var info by remember(person.id) { mutableStateOf<DiscoveryActorInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val closeFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 160.dp.toPx() }
    var bodyFocused by remember { mutableStateOf(false) }
    LaunchedEffect(person.id, retry) {
        loading = true; failed = false
        try { info = load(person) } catch (c: CancellationException) { throw c } catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 650.dp
            val maximumHeight = maxHeight * .92f
            Surface(Modifier.widthIn(max = 920.dp).fillMaxWidth(.96f).heightIn(max = maximumHeight)
                .semantics { paneTitle = "Actor information"; contentDescription = "Actor details popup" }
                .initialFocusEntry(closeFocus), shape = RoundedCornerShape(16.dp), color = Color(0xFF10131A)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(info?.name ?: person.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Biography and details", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        }
                        com.nikhil.niktv.ui.NikTvSecondaryActionButton(onClick = close,
                            modifier = Modifier.height(44.dp).focusRequester(closeFocus).focusProperties {
                                down = if (failed) retryFocus else bodyFocus
                                up = if (failed) retryFocus else bodyFocus
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Close", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().focusRequester(bodyFocus).onFocusChanged { bodyFocused = it.isFocused }
                        .border(1.dp, if (bodyFocused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionDown -> {
                                    if (scroll.canScrollForward) scope.launch { scroll.scrollBy(step) }
                                    else runCatching { closeFocus.requestFocus() }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (scroll.canScrollBackward) scope.launch { scroll.scrollBy(-step) }
                                    else runCatching { closeFocus.requestFocus() }
                                    true
                                }
                                else -> false
                            }
                        }.semantics { contentDescription = "Actor information" }.focusable(enabled = !failed)
                        .verticalScroll(scroll).padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (failed) com.nikhil.niktv.ui.NikTvSecondaryActionButton(
                            onClick = { retry++; runCatching { closeFocus.requestFocus() } },
                            modifier = Modifier.focusRequester(retryFocus).focusProperties { up = closeFocus }) { Text("Retry information") }
                        val portrait = info?.portrait ?: person.portrait
                        val values = info?.let { data -> listOfNotNull(
                            data.department?.let { "Known for" to it }, data.birthday?.let { "Born" to it },
                            actorAge(data)?.let { (if (data.deathday == null) "Age" else "Age at death") to it.toString() },
                            data.birthplace?.let { "Birthplace" to it }, data.deathday?.let { "Died" to it },
                            person.role.takeIf(String::isNotBlank)?.let { "Role in this title" to it },
                            data.aliases.takeIf { it.isNotEmpty() }?.let { "Also known as" to it.joinToString(", ") }) }.orEmpty()
                        if (portrait != null || values.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            portrait?.let {
                                AsyncImage(it, "${person.name} portrait", Modifier.width(if (wide) 120.dp else 88.dp)
                                    .height(if (wide) 180.dp else 132.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                values.chunked(if (wide) 2 else 1).forEach { fields ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        fields.forEach { (label, value) ->
                                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
                                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9EA5B2))
                                                Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                            }
                                        }
                                        if (wide && fields.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        info?.let { data ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Biography", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text(data.biography?.takeIf(String::isNotBlank) ?: "Biography not available.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text("Information from TMDB", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
