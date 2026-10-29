package com.nikhil.niktv.sports

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal data class SportsEventSection(val title: String, val events: List<SportsEvent>)

internal fun sportsEventSections(events: List<SportsEvent>, filters: List<SportsEventFilter>): List<SportsEventSection> {
    val titles = (listOf("All sports") + filters.map { it.title } + events.map { it.sport })
        .filter { it.isNotBlank() && !it.equals("All", true) }.distinctBy { it.lowercase() }
    return titles.map { title -> SportsEventSection(title, if (title == "All sports") events else events.filter {
        it.sport.equals(title, true) || it.eventName.equals(title, true) || it.teamA.equals(title, true) || it.teamB.equals(title, true)
    }) }.filter { it.events.isNotEmpty() }
}

/** Category rails use independent saved scroll positions and explicit remote movement like IPTV collections. */
@Composable
internal fun SportsEventRows(sections: List<SportsEventSection>, modifier: Modifier, phone: Boolean,
    above: FocusRequester, entry: FocusRequester, requester: (String) -> FocusRequester,
    tile: @Composable (SportsEvent, String, Modifier) -> Unit) {
    val vertical = rememberLazyListState()
    val horizontalStates = remember { mutableMapOf<String, LazyListState>() }
    val scope = rememberCoroutineScope()
    var movement by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun focus(row: Int, index: Int) {
        movement?.cancel()
        movement = scope.launch {
            if (row < 0) { runCatching { above.requestFocus() }; return@launch }
            val section = sections.getOrNull(row) ?: return@launch
            val target = index.coerceIn(0, section.events.lastIndex)
            vertical.scrollToItem(row)
            horizontalStates.getOrPut(section.title) { LazyListState() }.scrollToItem(target)
            withFrameNanos { }
            runCatching { requester("${section.title}:${section.events[target].slug}").requestFocus() }
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = vertical, verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 18.dp)) {
        itemsIndexed(sections, key = { _, section -> section.title }) { row, section ->
            val savedState = rememberLazyListState()
            val horizontal = horizontalStates.getOrPut(section.title) { savedState }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(section.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LazyRow(Modifier.fillMaxWidth().focusGroup(), state = horizontal,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
                    itemsIndexed(section.events, key = { _, event -> event.slug }) { index, event ->
                        val tileModifier = Modifier.width(if (phone) 268.dp else 300.dp)
                            .then(if (row == 0 && index == 0) Modifier.focusRequester(entry) else Modifier)
                            .focusRequester(requester("${section.title}:${event.slug}"))
                            .onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                    Key.DirectionUp -> { focus(row - 1, index); true }
                                    Key.DirectionDown -> { focus(row + 1, index); true }
                                    Key.DirectionLeft -> { if (index > 0) focus(row, index - 1); true }
                                    Key.DirectionRight -> { if (index < section.events.lastIndex) focus(row, index + 1); true }
                                    else -> false
                                }
                            }
                        tile(event, "${section.title}:${event.slug}", tileModifier)
                    }
                }
            }
        }
    }
}
