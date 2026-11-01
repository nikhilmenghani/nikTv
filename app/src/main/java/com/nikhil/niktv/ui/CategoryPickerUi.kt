@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nikhil.niktv.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun CategoryManagerDialog(state: NikTvState, close: () -> Unit,
    applyFilters: (Map<CatalogType, List<String>>) -> Unit) {
    val type = state.categoryManagerType
    val profile = state.savedProfile?.cacheKey()
    val raw = state.rawCategoriesByType[type].orEmpty().ifEmpty {
        state.browseCachesByType[type]?.categories.orEmpty().ifEmpty { if (state.selectedType == type) state.categories else emptyList() }
    }
    val saved = state.categoryFilters["$profile|${type.name}"]
    var draft by rememberSaveable(profile, type) { mutableStateOf<List<String>?>(null) }
    val defaults = raw.map { it.id }
    val selected = categoryPickerSelection(draft, saved, defaults)
    var query by rememberSaveable(profile, type) { mutableStateOf("") }
    val filtered = remember(raw, query) {
        if (query.isBlank()) raw else raw.filter { it.title.matchesTitleKeywords(query.trim()) }
            .sortedByDescending { it.title.titleKeywordScore(query.trim()) }
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val television = LocalContext.current.isTvLikeDevice(configuration)
    val compact = !television && configuration.screenWidthDp < 600
    val panelWidth = if (compact) configuration.screenWidthDp else minOf((configuration.screenWidthDp * .97f).toInt(), 1280)
    val columns = categoryPickerColumns(panelWidth - 24, density.fontScale)
    val inlineTools = (panelWidth - 24) / density.fontScale.coerceAtLeast(1f) >= 720
    val accent = MaterialTheme.colorScheme.primary
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = filtered.indexOfFirst { it.id in selected }.coerceAtLeast(0))
    val search = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val bulk = remember { FocusRequester() }
    val clear = remember { FocusRequester() }
    val requesters = remember(profile, type) { mutableMapOf<String, FocusRequester>() }
    raw.forEach { requesters.getOrPut(it.id) { FocusRequester() } }
    var lastFocused by rememberSaveable(profile, type) { mutableStateOf<String?>(null) }
    var focusJob by remember { mutableStateOf<Job?>(null) }
    var entered by remember(profile, type) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var initialFocusHandled by remember(profile, type) { mutableStateOf(false) }
    fun focusCategory(index: Int) {
        val item = filtered.getOrNull(index) ?: return
        focusJob?.cancel()
        focusJob = scope.launch {
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
            repeat(12) {
                withFrameNanos { }
                if (runCatching { requesters[item.id]?.requestFocus() == true }.getOrDefault(false)) return@launch
            }
        }
    }
    fun enterGrid() {
        if (filtered.isEmpty()) save.requestFocus()
        else focusCategory(filtered.indexOfFirst { it.id == lastFocused }.takeIf { it >= 0 } ?: 0)
    }
    LaunchedEffect(raw.isNotEmpty(), type) {
        withFrameNanos { }
        if (raw.isNotEmpty() && !entered) {
            entered = true
            focusCategory(filtered.indexOfFirst { it.id in selected }.coerceAtLeast(0))
        } else if (raw.isEmpty()) cancel.requestFocus()
    }
    LaunchedEffect(query) { focusJob?.cancel(); if (filtered.isNotEmpty()) grid.scrollToItem(0) }
    DisposableEffect(Unit) { onDispose { focusJob?.cancel() } }
    BackHandler(editing) { editing = false; keyboard?.hide(); search.requestFocus() }

    Dialog(onDismissRequest = { if (editing) { editing = false; keyboard?.hide() } else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) }
        }
        Surface(modifier = if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 1280.dp).fillMaxWidth(.97f).fillMaxHeight(.96f),
            color = Color(0xFF101217), shape = RoundedCornerShape(if (compact) 0.dp else 16.dp),
            border = if (compact) null else BorderStroke(1.dp, Color.White.copy(alpha = .16f))) {
            Column(Modifier.fillMaxSize().focusProperties {
                onEnter = {
                    if (!initialFocusHandled) {
                        initialFocusHandled = true
                        val first = filtered.firstOrNull { it.id in selected } ?: filtered.firstOrNull()
                        val target = first?.let { requesters[it.id] } ?: cancel
                        if (!runCatching { target.requestFocus() }.getOrDefault(false)) {
                            initialFocusHandled = false
                            cancelFocusChange()
                        }
                    }
                }
            }.focusGroup().then(if (!television) Modifier.safeDrawingPadding() else Modifier).imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("IPTV ${type.title} categories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${raw.count { it.id in selected }} selected · " +
                            if (query.isBlank()) "${raw.size} available" else "${filtered.size} results",
                            style = MaterialTheme.typography.labelMedium, color = Color(0xFFB6BAC5))
                    }
                }
                // Focusing search with a remote does not summon the keyboard until Select.
                val searchField: @Composable () -> Unit = {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1B1E26),
                        border = BorderStroke(if (searchFocused) 2.dp else 1.dp, if (searchFocused) Color.White else Color(0xFF353944))) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Color.LightGray)
                            BasicTextField(query, { query = it }, singleLine = true, readOnly = !editing,
                                modifier = Modifier.weight(1f).focusRequester(search)
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            editing = true; search.requestFocus()
                                            scope.launch { withFrameNanos { }; keyboard?.show() }
                                        }
                                    }
                                    .onFocusChanged { searchFocused = it.isFocused; if (!it.isFocused) { editing = false; keyboard?.hide() } }
                                    .focusProperties { up = cancel; right = bulk }
                                    .onPreviewKeyEvent { event ->
                                        when {
                                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && !editing -> {
                                                if (inlineTools) enterGrid() else bulk.requestFocus(); true
                                            }
                                            event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) && !editing -> {
                                                if (event.type == KeyEventType.KeyUp) { editing = true; keyboard?.show() }; true
                                            }
                                            else -> false
                                        }
                                    },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White), cursorBrush = SolidColor(accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { editing = false; keyboard?.hide(); enterGrid() }),
                                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search categories", color = Color(0xFFA7ABB5)); inner() } })
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Clear search") }
                        }
                    }
                }
                val bulkActions: @Composable () -> Unit = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NikTvSecondaryActionButton(onClick = { draft = (selected + filtered.map { it.id }).toList() }, enabled = filtered.isNotEmpty(),
                            modifier = Modifier.weight(1f).focusRequester(bulk).focusProperties { up = if (inlineTools) cancel else search; left = search; right = clear }
                                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { enterGrid(); true } else false }) {
                            Icon(Icons.Default.DoneAll, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (query.isBlank()) "Select all" else "Select results", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        }
                        NikTvSecondaryActionButton(onClick = { draft = (selected - filtered.map { it.id }.toSet()).toList() }, enabled = filtered.isNotEmpty(),
                            modifier = Modifier.weight(1f).focusRequester(clear).focusProperties { up = search; left = bulk }
                                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { enterGrid(); true } else false }) {
                            Icon(Icons.Default.RemoveDone, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (query.isBlank()) "Clear all" else "Clear results", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                // Keep toolbar membership/layout independent of transient IME insets.
                // Only the grid resizes when the keyboard appears or disappears.
                if (inlineTools) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { searchField() }
                        Box(Modifier.weight(1f)) { bulkActions() }
                    }
                } else {
                    searchField()
                    bulkActions()
                }
                HorizontalDivider(color = Color.White.copy(alpha = .10f))
                if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (raw.isEmpty()) "Categories are not available yet. Reopen after the profile has loaded." else "No matching categories",
                        color = Color.LightGray)
                } else CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (television) 40.dp else 48.dp) {
                StableTileFocusViewport {
                    LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(filtered, key = { _, item -> item.id }) { index, category ->
                            var focused by remember { mutableStateOf(false) }
                            val checked = category.id in selected
                            Surface(onClick = { draft = (if (checked) selected - category.id else selected + category.id).toList() },
                                shape = RoundedCornerShape(8.dp),
                                color = when { focused -> Color(0xFF303A49); checked -> accent.copy(alpha = .13f); else -> Color(0xFF1B1E26) },
                                border = BorderStroke(if (focused) 2.dp else 1.dp,
                                    when { focused -> Color.White; checked -> accent.copy(alpha = .6f); else -> Color.White.copy(alpha = .12f) }),
                                modifier = Modifier.fillMaxWidth().heightIn(min = if (television) 40.dp else 48.dp).focusRequester(requesters.getValue(category.id))
                                    .semantics { this.selected = checked; role = Role.Checkbox }
                                    .onFocusChanged { focused = it.isFocused; if (focused) lastFocused = category.id }
                                    .onPreviewKeyEvent { event ->
                                        val direction = when (event.key) { Key.DirectionUp -> CategoryPickerDirection.UP; Key.DirectionDown -> CategoryPickerDirection.DOWN
                                            Key.DirectionLeft -> CategoryPickerDirection.LEFT; Key.DirectionRight -> CategoryPickerDirection.RIGHT; else -> null }
                                        if (event.type != KeyEventType.KeyDown || direction == null) false else {
                                            val destination = categoryPickerDestination(index, filtered.size, columns, direction)
                                            if (destination != null) focusCategory(destination)
                                            else if (direction == CategoryPickerDirection.UP) search.requestFocus() else save.requestFocus()
                                            true
                                        }
                                    }) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                                        Modifier.size(20.dp), tint = if (checked) accent else Color(0xFF8D93A1))
                                    Text(category.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f).then(if (focused) Modifier.basicMarquee() else Modifier))
                                }
                            }
                        }
                    }
                }
                }
                HorizontalDivider(color = Color.White.copy(alpha = .10f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NikTvSecondaryActionButton(onClick = close, modifier = Modifier.weight(1f).focusRequester(cancel).focusProperties { right = save }
                        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { enterGrid(); true } else false }) { Text("Cancel") }
                    NikTvPrimaryActionButton(onClick = { applyFilters(mapOf(type to selected.toList())); close() },
                        modifier = Modifier.weight(1f).focusRequester(save).focusProperties { left = cancel }
                            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { enterGrid(); true } else false }) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save")
                    }
                }
            }
        }
    }
}
