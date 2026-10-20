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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.ui.StableTileFocusViewport
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
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (failed) Button(onClick = { retry++ }) { Text("Could not update titles. Retry") }
                    if (!loading && cards.isEmpty() && !failed) Text("No released titles available", color = Color.LightGray)
                    LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f).fillMaxWidth(), state = grid,
                        contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(cards, key = { it.key }) { card ->
                            val requester = remember(card.key) { requesters.getOrPut(card.key) { FocusRequester() } }
                            BoxWithConstraints {
                                DiscoveryPosterTile(card.title, card.poster,
                                    listOfNotNull(if (card.series) "Series" else "Movie", card.date?.take(4)).joinToString(" · "),
                                    width = maxWidth, reduceMotion = reduceMotion, modifier = Modifier.focusRequester(requester),
                                    onFocus = { selected = card.key }, onClick = { open(card) })
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(cards.isNotEmpty()) {
            if (cards.isEmpty()) {
                withFrameNanos { }; runCatching { closeRequester.requestFocus() }
            } else {
                val index = cards.indexOfFirst { it.key == selected }.coerceAtLeast(0)
                val target = cards[index].key
                grid.scrollToItem(index)
                repeat(12) {
                    withFrameNanos { }
                    if (runCatching { requesters[target]?.requestFocus() == true }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }
    }
}
