package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One toolbar for both provider and TMDB episodes. Search stays local to the loaded season. */
@Composable
internal fun SeriesEpisodeToolbar(
    season: @Composable () -> Unit,
    count: String,
    compact: Boolean,
    newestFirst: Boolean,
    onNewestFirst: (Boolean) -> Unit,
    listView: Boolean,
    onListView: (Boolean) -> Unit,
    searchOpen: Boolean,
    onSearch: () -> Unit,
    searchField: @Composable () -> Unit,
    searchRequester: FocusRequester,
    sortRequester: FocusRequester,
    layoutRequester: FocusRequester,
    onSortApplied: (() -> Unit)? = null,
    beforeAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var restoreLayoutFocus by remember { mutableStateOf(false) }
    val selectedSort = remember { FocusRequester() }
    val host = LocalView.current
    var hadSortPopup by remember { mutableStateOf(false) }
    var sortSelected by remember { mutableStateOf(false) }
    val currentSortApplied by rememberUpdatedState(onSortApplied)
    fun dismissSort() { sortOpen = false }
    LaunchedEffect(sortOpen) {
        if (sortOpen) {
            hadSortPopup = true
            sortSelected = false
            repeat(12) {
                withFrameNanos { }
                if (runCatching { selectedSort.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        } else if (hadSortPopup) {
            hadSortPopup = false
            repeat(30) {
                withFrameNanos { }
                if (host.hasWindowFocus()) {
                    if (sortSelected && currentSortApplied != null) {
                        sortSelected = false
                        currentSortApplied?.invoke()
                        return@LaunchedEffect
                    }
                    if (runCatching { sortRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }
    }
    LaunchedEffect(listView) {
        if (restoreLayoutFocus) {
            withFrameNanos { }
            runCatching { (if (compact) sortRequester else layoutRequester).requestFocus() }
            restoreLayoutFocus = false
        }
    }
    fun selectLayout(value: Boolean) {
        if (value != listView) { beforeAction(); restoreLayoutFocus = true; onListView(value) }
    }
    val searchWidth by animateDpAsState(if (searchOpen && !compact) 220.dp else 48.dp,
        tween(220, easing = FastOutSlowInEasing), label = "episodeSearchWidth")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(48.dp).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary) {}
                }
                Box(Modifier.padding(start = 10.dp).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) { season() }
            }
            Box(Modifier.width(searchWidth).height(48.dp).clip(RoundedCornerShape(12.dp))) {
                if (searchOpen && !compact) Box(Modifier.fillMaxSize().focusRequester(searchRequester)) { searchField() }
                else EpisodeToolbarIcon(Icons.Default.Search, "Search episodes", searchOpen,
                    Modifier.focusRequester(searchRequester), onSearch)
            }
            Box {
                EpisodeToolbarIcon(if (compact) Icons.Default.MoreHoriz else Icons.AutoMirrored.Filled.Sort,
                    if (compact) "Episode options" else "Sort episodes", sortOpen,
                    Modifier.focusRequester(sortRequester)) { beforeAction(); sortOpen = true }
                DropdownMenu(sortOpen, ::dismissSort) {
                    listOf(true to "Latest first", false to "Earliest first").forEach { (descending, label) ->
                        NikDropdownMenuItem(text = { Text(label) },
                            modifier = if (newestFirst == descending) Modifier.focusRequester(selectedSort) else Modifier,
                            leadingIcon = if (newestFirst == descending) {{ Icon(Icons.Default.Check, null) }} else null,
                            onClick = { sortSelected = true; onNewestFirst(descending); dismissSort() })
                    }
                    if (compact) {
                        HorizontalDivider()
                        listOf(true to "List view", false to "Grid view").forEach { (list, label) ->
                            NikDropdownMenuItem(text = { Text(label) },
                                leadingIcon = { Icon(if (listView == list) Icons.Default.Check else if (list) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, null) },
                                onClick = { selectLayout(list); dismissSort() })
                        }
                    }
                }
            }
            if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                EpisodeToolbarIcon(Icons.AutoMirrored.Filled.ViewList, "Episode list view", listView,
                    if (!listView) Modifier.focusRequester(layoutRequester) else Modifier) { selectLayout(true) }
                EpisodeToolbarIcon(Icons.Default.GridView, "Episode grid view", !listView,
                    if (listView) Modifier.focusRequester(layoutRequester) else Modifier) { selectLayout(false) }
            }
        }
        Text(count, Modifier.padding(start = 13.dp), color = Color(0xFF9CA3AF), style = MaterialTheme.typography.labelSmall)
        AnimatedVisibility(searchOpen && compact,
            enter = expandVertically(tween(220)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(120))) {
            Box(Modifier.fillMaxWidth().height(48.dp)) { searchField() }
        }
    }
}

@Composable
private fun EpisodeToolbarIcon(icon: ImageVector, label: String, active: Boolean,
    modifier: Modifier = Modifier, click: () -> Unit) {
    NikTvSecondaryActionButton(click,
        modifier.size(48.dp).semantics { contentDescription = label; selected = active },
        contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxSize().then(if (active) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)) else Modifier), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (active) MaterialTheme.colorScheme.primary else Color.White)
        }
    }
}

/** Changing focus never fetches a season; selection uses the existing provider/TMDB callback. */
@Composable
internal fun SeriesSeasonSidebar(seasons: List<Int>, selected: Int?, counts: Map<Int, String>,
    requester: FocusRequester, select: (Int) -> Unit, right: () -> Unit, up: () -> Unit,
    modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val ordered = remember(seasons, selected) { (seasons + listOfNotNull(selected)).distinct().sorted() }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    var rowHeight by remember(density.fontScale) { mutableIntStateOf(0) }
    val tile: @Composable (Int, Int) -> Unit = { index, season ->
            NikTvSecondaryActionButton({ select(season) },
                (if (horizontal) Modifier.width(156.dp) else Modifier.fillMaxWidth())
                    .onSizeChanged { rowHeight = maxOf(rowHeight, it.height) }
                    .then(if (season == selected || selected == null && index == 0) Modifier.focusRequester(requester) else Modifier)
                    .semantics { this.selected = season == selected }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else if (horizontal) when (event.key) {
                            Key.DirectionUp -> { up(); true }
                            Key.DirectionDown -> { right(); true }
                            else -> false
                        } else when (event.key) {
                            Key.DirectionRight -> { right(); true }
                            Key.DirectionUp -> if (index == 0) { up(); true } else false
                            Key.DirectionLeft -> true
                            else -> false
                        }
                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Season $season", color = if (season == selected) MaterialTheme.colorScheme.primary else Color.White,
                        style = MaterialTheme.typography.labelLarge)
                    Text(counts[season] ?: "Not loaded", color = Color(0xFF9CA3AF), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
    }
    if (horizontal) {
        LaunchedEffect(selected, ordered) {
            val index = ordered.indexOf(selected)
            if (index >= 0) list.scrollToItem(index)
        }
        LazyRow(modifier, state = list, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(ordered, key = { _, season -> season }) { index, season -> tile(index, season) }
        }
    } else {
        var visibleHeight by remember { mutableIntStateOf(0) }
        var sidebarFocused by remember { mutableStateOf(false) }
        var alignedSelection by remember { mutableStateOf<Int?>(null) }
        val gap = with(density) { 8.dp.roundToPx() }
        val visibleSlots by remember(gap) { derivedStateOf {
            if (rowHeight <= 0) 1 else ((visibleHeight + gap) / (rowHeight + gap)).coerceAtLeast(1)
        } }
        val ready by remember { derivedStateOf { visibleHeight > 0 && rowHeight > 0 } }
        BoxWithConstraints(modifier.onFocusChanged { sidebarFocused = it.hasFocus }.focusGroup()
            .onGloballyPositioned { visibleHeight = it.boundsInWindow().height.roundToInt() }) {
        // Show the selected season with preceding seasons, rather than putting
        // the last season alone at the top. Account for clipping by the outer page.
        LaunchedEffect(selected, ordered, rowHeight, visibleSlots, ready) {
            if (!ready || (sidebarFocused && alignedSelection == selected) || list.isScrollInProgress) return@LaunchedEffect
            val index = ordered.indexOf(selected)
            if (index >= 0) {
                list.scrollToItem((index - visibleSlots + 1).coerceAtLeast(0))
                alignedSelection = selected
            }
        }
        val rowsHeight = rowHeight * visibleSlots + gap * (visibleSlots - 1)
        val tail = (maxHeight - with(density) { rowsHeight.toDp() }).coerceAtLeast(0.dp)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = tail),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ordered, key = { _, season -> season }) { index, season -> tile(index, season) }
        }
        }
    }
}

/** A real 44dp input, rather than shrinking Material's 56dp text-field decoration. */
@Composable
internal fun SeriesEpisodeSearchField(value: String, change: (String) -> Unit, close: () -> Unit,
    modifier: Modifier = Modifier, readOnly: Boolean = false,
    options: KeyboardOptions = KeyboardOptions.Default, actions: KeyboardActions = KeyboardActions.Default) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFF171B23),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454B58))) {
        BasicTextField(value, change, modifier.fillMaxSize().onFocusChanged { focused = it.hasFocus },
            readOnly = readOnly, singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White), keyboardOptions = options, keyboardActions = actions,
            decorationBox = { input ->
                Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = Color(0xFFB9BDC7))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text("Search episodes…", color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        input()
                    }
                    IconButton(close, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close episode search", Modifier.size(18.dp)) }
                }
            })
        }
}
