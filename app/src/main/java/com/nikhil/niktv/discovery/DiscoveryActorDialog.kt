package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.ui.StableTileFocusViewport
import com.nikhil.niktv.ui.AlternateSourceOnlyMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Public metadata only. Closing/replacing this route cancels its credit lookup. */
@Composable
internal fun DiscoveryActorDialog(person: DiscoveryPerson,
    load: suspend (DiscoveryPerson, (List<DiscoveryCard>) -> Unit) -> List<DiscoveryCard>,
    close: () -> Unit, reduceMotion: Boolean = false, open: (DiscoveryCard) -> Unit) {
    var cards by remember(person.id) { mutableStateOf(emptyList<DiscoveryCard>()) }
    var loading by remember(person.id) { mutableStateOf(true) }
    var failed by remember(person.id) { mutableStateOf(false) }
    var retry by remember(person.id) { mutableIntStateOf(0) }
    var selected by rememberSaveable(person.id) { mutableStateOf<String?>(null) }
    var sort by rememberSaveable(person.id) { mutableStateOf("Default") }
    var sorting by remember { mutableStateOf(false) }
    var sortRevision by remember(person.id) { mutableIntStateOf(0) }
    var sortFocused by remember { mutableStateOf(false) }
    val shown = remember(cards, sort) { sortActorCards(cards, sort) }
    val requesters = remember(person.id) { mutableMapOf<String, FocusRequester>() }
    val closeRequester = remember { FocusRequester() }
    val grid = rememberLazyGridState()
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
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        StableTileFocusViewport {
            Surface(Modifier.fillMaxSize().padding(16.dp), color = Color(0xFF10131A), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalIconButton(close, Modifier.focusRequester(closeRequester)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to cast")
                        }
                        Column {
                            Text("Featuring ${person.name}", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text("Movies and series", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Box {
                        com.nikhil.niktv.ui.NikTvSecondaryActionButton(onClick = { sorting = true },
                            modifier = Modifier.onFocusChanged { sortFocused = it.isFocused }.drawWithContent {
                                drawContent()
                                if (sortFocused) drawRect(Color(0xFFE50925), Offset(12.dp.toPx(), size.height - 5.dp.toPx()), Size((size.width - 24.dp.toPx()).coerceAtLeast(0f), 3.dp.toPx()))
                            }) { Text("Sort: $sort") }
                        DropdownMenu(sorting, { sorting = false }) {
                            listOf("Default", "Year: newest", "Year: oldest", "Rating: highest", "Rating: lowest").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { sort = option; sorting = false; sortRevision++ })
                            }
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (failed) Button(onClick = { retry++ }) { Text("Could not update titles. Retry") }
                    if (!loading && cards.isEmpty() && !failed) Text("No released titles available", color = Color.LightGray)
                    LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f).fillMaxWidth(), state = grid,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(shown, key = { it.key }) { card ->
                            val requester = remember(card.key) { requesters.getOrPut(card.key) { FocusRequester() } }
                            var alternateOpen by remember(card.key) { mutableStateOf(false) }
                            BoxWithConstraints {
                                DiscoveryPosterTile(card.title, card.poster,
                                    listOfNotNull(if (card.series) "Series" else "Movie", card.date?.take(4)).joinToString(" · "),
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
                withFrameNanos { }; runCatching { closeRequester.requestFocus() }
            } else {
                val index = if (sortRevision > 0) 0 else shown.indexOfFirst { it.key == selected }.coerceAtLeast(0)
                val target = shown.getOrNull(index)?.key ?: return@LaunchedEffect
                selected = target
                grid.scrollToItem(index)
                if (sortRevision > 0) kotlinx.coroutines.delay(100L)
                repeat(24) {
                    withFrameNanos { }
                    if (runCatching { requesters[target]?.requestFocus() == true }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }
    }
}

internal fun sortActorCards(cards: List<DiscoveryCard>, sort: String): List<DiscoveryCard> = when (sort) {
    "Year: newest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.date.isNullOrBlank() }.thenByDescending { it.date })
    "Year: oldest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.date.isNullOrBlank() }.thenBy { it.date })
    "Rating: highest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.rating == null }.thenByDescending { it.rating })
    "Rating: lowest" -> cards.sortedWith(compareBy<DiscoveryCard> { it.rating == null }.thenBy { it.rating })
    else -> cards
}
