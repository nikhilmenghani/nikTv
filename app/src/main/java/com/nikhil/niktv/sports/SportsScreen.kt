package com.nikhil.niktv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SportsScreen(onClose: () -> Unit, vm: SportsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back = { if (!vm.back()) onClose() }
    BackHandler(onBack = back)
    if (state.playback.active) {
        SportsPlayer(state.playback, state.event?.title.orEmpty(), onBack = { vm.back() }, onFailure = vm::playbackFailed, onRetry = vm::retryPlayback)
        return
    }
    var sport by rememberSaveable { mutableStateOf("All sports") }
    var status by rememberSaveable { mutableStateOf("Now & next") }
    var search by rememberSaveable { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycle, state.event?.slug) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = Instant.now(); vm.refreshIfStale(); delay(30_000) } }
    }
    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        TopAppBar(title = { Text(state.event?.title ?: "Sports", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { state.event?.let(vm::open) ?: vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh sports") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val event = state.event
            if (event != null) {
                Text(listOf(event.sport, event.competition, event.status(now).label).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                Text(sportsEventTime(event), style = MaterialTheme.typography.bodyMedium)
                if (state.loadingStreams || state.resolving != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.streamError?.let { SportsError(it) { vm.open(event) } }
                if (!state.loadingStreams && state.streams.isEmpty() && state.streamError == null) Text("No streams are available for this event yet.")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.streams.size) { index ->
                        FilledTonalButton(onClick = { vm.play(index) }, enabled = state.resolving == null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(if (state.resolving == index) "Resolving ${state.streams[index].title}…" else state.streams[index].title)
                        }
                    }
                }
            } else {
                OutlinedTextField(search, { search = it }, label = { Text("Find a team or event") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Now & next", "Live", "Upcoming", "Finished", "All events")) { label -> FilterChip(status == label, { status = label }, label = { Text(label) }) }
                }
                val sports = remember(state.events) { listOf("All sports") + state.events.map { it.sport }.filter(String::isNotBlank).distinct().sorted() }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sports) { label -> FilterChip(sport == label, { sport = label }, label = { Text(label) }) }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { SportsError(it) { vm.refresh() } }
                val visible = remember(state.events, sport, status, search, now) {
                    state.events.filter { event ->
                        (sport == "All sports" || event.sport == sport) &&
                            (search.isBlank() || listOf(event.title, event.teamA, event.teamB, event.competition).any { it.contains(search.trim(), true) }) &&
                            when (status) {
                                "Now & next" -> event.status(now) != SportsStatus.FINISHED
                                "All events" -> true
                                else -> event.status(now).label == status
                            }
                    }.sortedWith(compareBy<SportsEvent> { if (it.status(now) == SportsStatus.LIVE) 0 else 1 }.thenBy { it.start })
                }
                Text("${visible.size} events", style = MaterialTheme.typography.labelLarge)
                if (visible.isEmpty() && !state.loading) Text("No events match these filters. Try All events or refresh.")
                LazyVerticalGrid(GridCells.Adaptive(260.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(visible, key = { it.slug }) { event ->
                        OutlinedCard(onClick = { vm.open(event) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AsyncImage(event.image, null, Modifier.size(44.dp), contentScale = ContentScale.Fit)
                                    Column { Text(event.status(now).label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                        Text(event.sport, style = MaterialTheme.typography.labelMedium) }
                                }
                                Text(event.title, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(event.competition, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(sportsEventTime(event), style = MaterialTheme.typography.bodySmall)
                                Text("${event.choices.size} stream choices", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SportsError(message: String, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text("Retry") }
    }
}

private fun sportsEventTime(event: SportsEvent): String = event.start?.let {
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z").withZone(ZoneId.systemDefault()).format(it)
} ?: "Schedule unavailable"
