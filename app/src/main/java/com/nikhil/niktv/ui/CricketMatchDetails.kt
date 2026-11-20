package com.nikhil.niktv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected as selectedSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nikhil.niktv.sports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CricketMuted = Color(0xFFB7BEC9)
private val CricketDivider = Color.White.copy(alpha = .12f)

@Composable
internal fun CricketMatchDetails(
    match: CricketLiveMatch,
    repository: CricketLiveScoresRepository,
    updateIntervalMillis: Long,
    onBack: () -> Unit,
) {
    var commentaryOpen by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<CricketScorecard?>(null) }
    var selectedInnings by remember { mutableIntStateOf(0) }
    var lines by remember { mutableStateOf(emptyList<CricketCommentaryLine>()) }
    var older by remember { mutableStateOf<CricketCommentaryCursor?>(null) }
    var loadedCommentary by remember { mutableStateOf(false) }
    var exploredHistory by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var olderError by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val scoretab = remember { FocusRequester() }
    val commentaryTab = remember { FocusRequester() }
    val body = remember { FocusRequester() }
    val history = remember { FocusRequester() }
    val previousInnings = remember { FocusRequester() }
    val nextInnings = remember { FocusRequester() }
    var historyFocused by remember { mutableStateOf(false) }
    val cardScroll = rememberLazyListState()
    val commentaryScroll = rememberLazyListState()
    val displayedList = if (commentaryOpen) commentaryScroll else cardScroll
    val innings = card?.innings.orEmpty()
    val index = innings.indexOfFirst { it.score.id == selectedInnings }.takeIf { it >= 0 } ?: innings.lastIndex
    val displayedInnings = innings.getOrNull(index)
    val scrollStep = with(LocalDensity.current) { 104.dp.toPx() }
    var contentFocused by remember { mutableStateOf(false) }
    val contentShape = RoundedCornerShape(10.dp)

    LaunchedEffect(match.id, commentaryOpen, refresh, lifecycle) {
        val isCommentary = commentaryOpen
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                loading = if (isCommentary) !loadedCommentary else card == null
                try {
                    if (isCommentary) {
                        val page = repository.commentary(match)
                        lines = (page.lines + lines).distinctBy { it.id }.sortedByDescending { it.timestamp }
                        if (!exploredHistory) older = page.older
                        loadedCommentary = true
                        complete = complete || page.complete
                    } else {
                        val latest = repository.scorecard(match)
                        if (latest.innings.isNotEmpty() || card?.innings.isNullOrEmpty()) card = latest
                        complete = complete || latest.complete
                    }
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = if (isCommentary) "Couldn’t load commentary. Use Refresh to try again."
                        else "Couldn’t load the scorecard. Use Refresh to try again."
                } finally {
                    loading = false
                }
                if (!complete) delay(updateIntervalMillis)
            } while (!complete)
        }
    }

    Column(Modifier.fillMaxHeight().initialFocusEntry(scoretab), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NikTvSecondaryActionButton(onBack, Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to matches", Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(match.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = card?.status.orEmpty().ifBlank { match.status }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = CricketMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            NikTvSecondaryActionButton({ refresh++ }, Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Refresh, "Refresh match details", Modifier.size(19.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CricketDetailsTab("Scorecard", !commentaryOpen, { error = null; commentaryOpen = false },
                Modifier.weight(1f).focusRequester(scoretab).focusProperties { down = body })
            CricketDetailsTab("Ball-by-ball", commentaryOpen, { error = null; commentaryOpen = true },
                Modifier.weight(1f).focusRequester(commentaryTab).focusProperties { down = body })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        else Spacer(Modifier.height(2.dp))
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFCA82)) }
        val shownLabel = if (commentaryOpen) "Ball-by-ball commentary" else "Innings statistics"
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth()
                .border(1.dp, if (contentFocused) MaterialTheme.colorScheme.onSurface else CricketDivider, contentShape)
                .focusRequester(body).onFocusChanged { contentFocused = it.isFocused }
                .focusProperties {
                    up = if (commentaryOpen) commentaryTab else scoretab
                    down = when {
                        commentaryOpen && older != null -> history
                        !commentaryOpen && innings.size > 1 -> previousInnings
                        else -> FocusRequester.Cancel
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when {
                        event.key == Key.DirectionDown && displayedList.canScrollForward -> {
                            scope.launch { displayedList.scrollBy(scrollStep) }; true
                        }
                        event.key == Key.DirectionUp && displayedList.canScrollBackward -> {
                            scope.launch { displayedList.scrollBy(-scrollStep) }; true
                        }
                        else -> false
                    }
                }
                .semantics { contentDescription = shownLabel }
                .focusable(),
            state = displayedList,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (commentaryOpen) {
                cricketCommentary(lines)
                if (loadedCommentary && lines.isEmpty()) item { Text("Ball-by-ball commentary isn’t available for this match yet.", color = CricketMuted) }
            } else {
                if (displayedInnings != null) cricketInnings(displayedInnings, match.hundred)
                if (card != null && innings.isEmpty()) item { Text("The scorecard isn’t available for this match yet.", color = CricketMuted) }
            }
        }
        if (!commentaryOpen && innings.size > 1) {
            fun choose(offset: Int) {
                selectedInnings = innings[(index + offset + innings.size) % innings.size].score.id
                scope.launch { cardScroll.scrollToItem(0) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NikTvSecondaryActionButton({ choose(-1) }, Modifier.size(40.dp).focusRequester(previousInnings)
                    .focusProperties { up = body; right = nextInnings }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.ChevronLeft, "Previous innings")
                }
                Text("${displayedInnings?.score?.team.orEmpty()} · Innings ${index + 1} of ${innings.size}",
                    Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                NikTvSecondaryActionButton({ choose(1) }, Modifier.size(40.dp).focusRequester(nextInnings)
                    .focusProperties { up = body; left = previousInnings }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.ChevronRight, "Next innings")
                }
            }
        }
        if (commentaryOpen && older != null) {
            NikTvSecondaryActionButton(
                onClick = {
                    val cursor = older
                    if (cursor != null && !loadingOlder) {
                        loadingOlder = true
                        exploredHistory = true
                        olderError = null
                        scope.launch {
                            try {
                                val page = repository.commentary(match, cursor)
                                lines = (lines + page.lines).distinctBy { it.id }.sortedByDescending { it.timestamp }
                                val next = page.older?.takeIf { it.timestamp < cursor.timestamp }
                                if (next == null && historyFocused) body.requestFocus()
                                older = next
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                olderError = "Couldn’t load earlier commentary. Try again."
                            } finally {
                                loadingOlder = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(history)
                    .onFocusChanged { historyFocused = it.isFocused }.focusProperties { up = body },
            ) { Text(if (loadingOlder) "Loading earlier balls…" else "Load earlier balls", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        }
        if (commentaryOpen) olderError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFCA82)) }
    }
}

@Composable
private fun CricketDetailsTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val semantics = modifier.semantics { selectedSemantics = selected }
    NikTvActionButton(label, onClick, semantics.height(40.dp), primary = selected)
}

private fun LazyListScope.cricketInnings(innings: CricketInningsCard, hundred: Boolean) {
    val score = innings.score
    val distance = if (hundred && innings.balls != null) "${innings.balls} balls" else "${score.overs} ov"
    item {
        Text("${score.team}  ${score.runs}/${score.wickets}${if (innings.declared) " dec" else ""} ($distance)",
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
    val (notBatted, batting) = innings.batters.partition {
        it.dismissal.equals("did not bat", true) || it.dismissal.equals("yet to bat", true)
    }
    if (batting.isNotEmpty()) {
        item { CricketStatsRow("Batting", listOf("R", "B", "4s", "6s", "SR"), header = true) }
        items(batting) {
            CricketStatsRow(it.name, listOf(it.runs.toString(), it.balls.toString(), it.fours.toString(),
                it.sixes.toString(), it.strikeRate.ifBlank { "–" }), it.dismissal)
        }
    }
    innings.extras?.let {
        item { CricketStatSection("Extras", "${it.total}  ·  B ${it.byes}, LB ${it.legByes}, W ${it.wides}, NB ${it.noBalls}, P ${it.penalty}") }
    }
    if (notBatted.isNotEmpty()) item { CricketStatSection("Still to bat / did not bat", notBatted.joinToString(", ") { it.name }) }
    if (innings.bowlers.isNotEmpty()) {
        item { CricketStatsRow("Bowling", listOf(if (hundred) "B" else "O", "M", "R", "W", "Econ"), header = true) }
        items(innings.bowlers) {
            val distanceBowled = if (hundred) it.balls?.toString() else it.overs
            CricketStatsRow(it.name, listOf(distanceBowled.orEmpty().ifBlank { "–" }, it.maidens.toString(),
                it.runs.toString(), it.wickets.toString(), it.economy.ifBlank { "–" }))
        }
    }
    if (innings.wickets.isNotEmpty()) item {
        CricketStatSection("Fall of wickets", innings.wickets.mapIndexed { index, wicket ->
            val delivery = if (hundred && wicket.ball != null) "ball ${wicket.ball}" else "${wicket.over} ov"
            "${wicket.runs}/${index + 1} (${wicket.batter}, $delivery)"
        }.joinToString(" · "))
    }
}

@Composable
private fun CricketStatsRow(name: String, stats: List<String>, detail: String = "", header: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val statWidth = if (maxWidth < 340.dp) 25.dp else 42.dp
        val rateWidth = if (maxWidth < 340.dp) 41.dp else 54.dp
        Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(name, fontWeight = if (header) FontWeight.Bold else FontWeight.Medium,
                        color = if (header) CricketMuted else Color.White, style = MaterialTheme.typography.bodySmall)
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall, color = CricketMuted)
                }
                stats.forEachIndexed { index, value ->
                    Text(value, Modifier.width(if (index == stats.lastIndex) rateWidth else statWidth),
                        textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall,
                        color = if (header) CricketMuted else Color.White)
                }
            }
            HorizontalDivider(color = CricketDivider)
        }
    }
}

@Composable
private fun CricketStatSection(title: String, text: String) {
    Column(Modifier.padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        Text(text, color = CricketMuted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun LazyListScope.cricketCommentary(lines: List<CricketCommentaryLine>) {
    items(lines, key = { it.id }) { line ->
        val label = listOfNotNull(line.delivery?.let { if (it.startsWith("Ball")) it else "Over $it" }, line.event).joinToString(" · ")
        Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .055f), RoundedCornerShape(8.dp)).padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (line.event != null) Color(0xFFFFD78A) else CricketMuted)
            Text(line.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
