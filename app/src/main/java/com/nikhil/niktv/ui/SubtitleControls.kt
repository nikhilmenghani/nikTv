package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.data.OnlineSubtitle
import com.nikhil.niktv.data.OpenSubtitlesClient
import com.nikhil.niktv.data.SubtitleSearchRequest
import com.nikhil.niktv.model.PlayingMedia
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class SubtitleTrackOption(
    val id: String,
    val label: String,
    val selected: Boolean
)

internal fun PlayingMedia.suggestedSubtitleSearchTitle(): String {
    var title = com.nikhil.niktv.data.providerDisplayTitle(series?.title ?: media.title,
        if (series != null || media.seasonNumber != null) com.nikhil.niktv.model.CatalogType.SERIES else com.nikhil.niktv.model.CatalogType.MOVIES)
    val trailingLanguageOrEdition = Regex(
        "\\s*[\\[(](?:english|hindi|french|spanish|german|italian|portuguese|arabic|turkish|urdu|tamil|telugu|korean|japanese|chinese|multi(?:[ -]?audio)?|dubbed|original|en|hi|fr|es|de|it|pt|ar)[\\])]\\s*$",
        RegexOption.IGNORE_CASE
    )
    while (trailingLanguageOrEdition.containsMatchIn(title)) {
        title = title.replace(trailingLanguageOrEdition, "").trim()
    }
    return title.ifBlank { series?.title ?: media.title }
}

private enum class SubtitleSearchEditor { QUERY, LANGUAGE }

@Composable
internal fun SubtitleSelectionDialog(
    tracks: List<SubtitleTrackOption>,
    delayMs: Long,
    onSelect: (String?) -> Unit,
    onDelayChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    timingRequiresVlc: Boolean = false,
    appearance: SubtitleAppearancePreset = SubtitleAppearancePreset.STANDARD,
    onAppearanceChange: (SubtitleAppearancePreset) -> Unit = {},
    internetSearch: SubtitleSearchRequest? = null,
    onExternalSubtitle: ((File) -> Unit)? = null,
    downloadedSubtitleName: String? = null,
    onDeleteDownloadedSubtitle: (() -> Unit)? = null,
    delaySupported: Boolean = true,
    appearanceSupported: Boolean = true,
    limitationMessage: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    var searchMode by remember { mutableStateOf(false) }
    var query by remember(internetSearch?.query) { mutableStateOf(internetSearch?.query.orEmpty()) }
    var language by remember { mutableStateOf(internetSearch?.languages ?: "en") }
    var editing by remember { mutableStateOf<SubtitleSearchEditor?>(null) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val hasActiveSubtitle = tracks.isNotEmpty() || !downloadedSubtitleName.isNullOrBlank()
    val hasDelayControls = hasActiveSubtitle && delaySupported
    val hasAppearanceControl = hasActiveSubtitle && appearanceSupported
    val canSearch = internetSearch != null && onExternalSubtitle != null
    val isTv = context.isTvLikeDevice(configuration)
    val compact = configuration.usesPhoneLayout(isTv) || configuration.usesShortWideLayout(isTv)
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val panelWidthFraction = when {
        isTv -> .36f
        compact && landscape -> .54f
        compact -> .94f
        else -> .48f
    }
    val panelMaxWidth = when {
        isTv -> 620.dp
        compact -> 520.dp
        else -> 540.dp
    }
    val listHeight = when {
        compact && searchMode -> 132.dp
        compact -> 86.dp
        searchMode -> 230.dp
        else -> 190.dp
    }
    val modeFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val queryFocusRequester = remember { FocusRequester() }
    val languageFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val earlierFocusRequester = remember { FocusRequester() }
    val laterFocusRequester = remember { FocusRequester() }
    val resetFocusRequester = remember { FocusRequester() }
    val appearanceFocusRequester = remember { FocusRequester() }
    val deleteFocusRequester = remember { FocusRequester() }
    val offFocusRequester = remember { FocusRequester() }
    // Keep the focus owner for each track even when selection or the list changes.
    val retainedTrackRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val trackFocusRequesters = listOf(offFocusRequester) + tracks.map {
        retainedTrackRequesters.getOrPut(it.id) { FocusRequester() }
    }
    val retainedResultRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val resultFocusRequesters = results.map {
        retainedResultRequesters.getOrPut(it.id) { FocusRequester() }
    }
    val trackHeaderFocusRequester = if (canSearch) modeFocusRequester else closeFocusRequester
    val afterTracksFocusRequester = when {
        hasDelayControls -> earlierFocusRequester
        hasAppearanceControl -> appearanceFocusRequester
        else -> trackHeaderFocusRequester
    }
    val initialTrackIndex = remember { tracks.indexOfFirst { it.selected } + 1 }
    val initialTrackRequester = remember { trackFocusRequesters[initialTrackIndex] }
    val trackList = rememberLazyListState(initialFirstVisibleItemIndex = initialTrackIndex)
    val resultList = rememberLazyListState()
    var focusedTrackId by remember { mutableStateOf<String?>(null) }
    val focusedTrackIndex = tracks.indexOfFirst { it.id == focusedTrackId } + 1
    var focusedResultId by remember { mutableStateOf<String?>(null) }
    val focusedResultIndex = results.indexOfFirst { it.id == focusedResultId }
    var trackListHasFocus by remember { mutableStateOf(false) }
    var resultListHasFocus by remember { mutableStateOf(false) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }

    fun focusControl(destination: FocusRequester): Boolean {
        navigationJob?.cancel()
        runCatching { destination.requestFocus() }
        return true
    }

    fun focusTrack(index: Int): Boolean {
        navigationJob?.cancel()
        val destination = trackFocusRequesters.getOrNull(index) ?: return true
        if (trackList.layoutInfo.visibleItemsInfo.any { it.index == index }) {
            runCatching { destination.requestFocus() }
        } else {
            // Focused lazy items stay pinned while the destination is brought into
            // composition. Transfer once after layout; never retry after navigation.
            navigationJob = scope.launch {
                trackList.scrollToItem(index)
                withFrameNanos { }
                runCatching { destination.requestFocus() }
            }
        }
        return true
    }

    fun focusResult(index: Int): Boolean {
        navigationJob?.cancel()
        val destination = resultFocusRequesters.getOrNull(index) ?: return true
        if (resultList.layoutInfo.visibleItemsInfo.any { it.index == index }) {
            runCatching { destination.requestFocus() }
        } else {
            navigationJob = scope.launch {
                resultList.scrollToItem(index)
                withFrameNanos { }
                runCatching { destination.requestFocus() }
            }
        }
        return true
    }

    fun stopEditing() {
        val destination = when (editing) {
            SubtitleSearchEditor.QUERY -> queryFocusRequester
            SubtitleSearchEditor.LANGUAGE -> languageFocusRequester
            null -> null
        }
        destination?.let { focusControl(it) }
        editing = null
    }

    fun showSearch(show: Boolean) {
        // This toolbar target survives both layouts. Focus it before removing a
        // focused field/result/track so no unrelated control receives interim focus.
        focusControl(modeFocusRequester)
        editing = null
        searchMode = show
    }

    fun dismissPopup() {
        navigationJob?.cancel()
        onDismiss()
    }

    fun back() {
        when {
            editing != null -> stopEditing()
            searchMode -> showSearch(false)
            else -> dismissPopup()
        }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The dialog has its own text-input owner and keyboard controller.
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(editing) {
            val destination = when (editing) {
                SubtitleSearchEditor.QUERY -> queryFocusRequester
                SubtitleSearchEditor.LANGUAGE -> languageFocusRequester
                null -> { keyboard?.hide(); return@LaunchedEffect }
            }
            withFrameNanos { }
            if (runCatching { destination.requestFocus() }.getOrDefault(false)) keyboard?.show()
        }
        // Observe the dialog's IME insets, including Back consumed by the keyboard.
        SeriesSearchKeyboardDismiss(editing != null, ::stopEditing)
        BackHandler(enabled = editing != null || searchMode, onBack = ::back)
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        navigationJob?.cancel()
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) navigationJob?.cancel()
                    if (event.type != KeyEventType.KeyDown) false
                    else if (!searchMode && trackListHasFocus) when (event.key) {
                        Key.DirectionUp -> if (focusedTrackIndex == 0) focusControl(trackHeaderFocusRequester)
                            else focusTrack(focusedTrackIndex - 1)
                        Key.DirectionDown -> if (focusedTrackIndex < tracks.size) focusTrack(focusedTrackIndex + 1)
                            else focusControl(afterTracksFocusRequester)
                        else -> { navigationJob?.cancel(); false }
                    } else if (searchMode && resultListHasFocus) when (event.key) {
                        Key.DirectionUp -> if (focusedResultIndex <= 0) focusControl(searchFocusRequester)
                            else focusResult(focusedResultIndex - 1)
                        Key.DirectionDown -> focusResult((focusedResultIndex + 1).coerceAtMost(results.lastIndex))
                        else -> { navigationJob?.cancel(); false }
                    } else false
                }
                .padding(end = if (compact) 8.dp else 24.dp, bottom = if (compact) 8.dp else 20.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(panelWidthFraction).widthIn(max = panelMaxWidth)
                    .heightIn(max = (configuration.screenHeightDp - if (compact) 12 else 32).coerceAtLeast(220).dp),
                color = Color(0xF2111317),
                contentColor = Color.White,
                shape = RoundedCornerShape(if (compact) 16.dp else 22.dp),
                border = BorderStroke(1.dp, Color(0xFF30343B)),
                shadowElevation = if (compact) 8.dp else 14.dp
            ) {
                Column(
                    Modifier.initialFocusEntry(initialTrackRequester)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 8.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searchMode) "Find subtitles" else "Subtitles",
                            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                        if (canSearch) NikTvTextActionButton(
                            onClick = { showSearch(!searchMode) },
                            modifier = Modifier.heightIn(min = 44.dp).focusRequester(modeFocusRequester)
                                .focusProperties {
                                    canFocus = true
                                    left = modeFocusRequester
                                    right = if (!searchMode && onDeleteDownloadedSubtitle != null) deleteFocusRequester else closeFocusRequester
                                    down = if (searchMode) queryFocusRequester else offFocusRequester
                                }.onPreviewKeyEvent {
                                    if (!searchMode && it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) focusTrack(0) else false
                                }
                        ) {
                            Icon(if (searchMode) Icons.Default.Subtitles else Icons.Default.Search,
                                if (searchMode) "Show subtitle tracks" else "Find subtitles online")
                            if (!compact) Text(if (searchMode) " Tracks" else " Online")
                        }
                        if (!searchMode && onDeleteDownloadedSubtitle != null) IconButton(
                            onClick = {
                                focusControl(trackHeaderFocusRequester)
                                onDeleteDownloadedSubtitle()
                            },
                            modifier = Modifier.focusRequester(deleteFocusRequester).focusProperties {
                                canFocus = true
                                left = if (canSearch) modeFocusRequester else deleteFocusRequester
                                right = closeFocusRequester
                                down = offFocusRequester
                            }.onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) focusTrack(0) else false
                            }.playerControlFocus(CircleShape, scaleOnFocus = false)
                        ) {
                            Icon(Icons.Default.Delete, downloadedSubtitleName?.let { "Delete downloaded subtitle $it" }
                                ?: "Delete downloaded subtitle")
                        }
                        NikTvTextActionButton(
                            onClick = ::dismissPopup,
                            modifier = Modifier.heightIn(min = 44.dp).focusRequester(closeFocusRequester).focusProperties {
                                canFocus = true
                                left = when {
                                    !searchMode && onDeleteDownloadedSubtitle != null -> deleteFocusRequester
                                    canSearch -> modeFocusRequester
                                    else -> closeFocusRequester
                                }
                                right = closeFocusRequester
                                down = if (searchMode) queryFocusRequester else offFocusRequester
                            }.onPreviewKeyEvent {
                                if (!searchMode && it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) focusTrack(0) else false
                            }
                        ) { Text("Close") }
                    }
                    if (canSearch && !searchMode && !compact) Text("Embedded and downloaded tracks", style = MaterialTheme.typography.labelSmall)
                    if (searchMode) {
                        SubtitleSearchInput(
                            value = query, onValueChange = { query = it },
                            label = if (internetSearch?.seasonNumber != null || internetSearch?.episodeNumber != null) "Series title" else "Movie title",
                            editing = editing == SubtitleSearchEditor.QUERY,
                            activate = { navigationJob?.cancel(); editing = SubtitleSearchEditor.QUERY },
                            stopEditing = ::stopEditing,
                            onFocusLost = { if (editing == SubtitleSearchEditor.QUERY) { editing = null; keyboard?.hide() } },
                            modifier = Modifier.fillMaxWidth().focusRequester(queryFocusRequester).focusProperties {
                                canFocus = true
                                up = modeFocusRequester
                                down = languageFocusRequester
                                left = queryFocusRequester
                                right = queryFocusRequester
                            },
                            textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
                        )
                        internetSearch?.episodeTitle?.takeIf { it.isNotBlank() }?.let { episodeTitle ->
                            Text(buildString {
                                internetSearch.seasonNumber?.let { append("S").append(it.toString().padStart(2, '0')) }
                                internetSearch.episodeNumber?.let { append("E").append(it.toString().padStart(2, '0')) }
                                if (isNotEmpty()) append(" \u00b7 ")
                                append(episodeTitle)
                            }, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8BBC3), maxLines = 1)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SubtitleSearchInput(
                                value = language, onValueChange = { language = it.take(12) }, label = "Languages",
                                editing = editing == SubtitleSearchEditor.LANGUAGE,
                                activate = { navigationJob?.cancel(); editing = SubtitleSearchEditor.LANGUAGE }, stopEditing = ::stopEditing,
                                onFocusLost = { if (editing == SubtitleSearchEditor.LANGUAGE) { editing = null; keyboard?.hide() } },
                                modifier = Modifier.weight(1f).focusRequester(languageFocusRequester).focusProperties {
                                    canFocus = true
                                    up = queryFocusRequester
                                    left = languageFocusRequester
                                    right = searchFocusRequester
                                    down = resultFocusRequesters.firstOrNull() ?: languageFocusRequester
                                }.onPreviewKeyEvent {
                                    if (editing == null && it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && results.isNotEmpty()) focusResult(0) else false
                                }
                            )
                            IconButton(
                                onClick = {
                                    if (!searching && query.isNotBlank()) {
                                        focusControl(searchFocusRequester)
                                        editing = null
                                        keyboard?.hide()
                                        val request = internetSearch?.copy(query = query, languages = language)
                                            ?: SubtitleSearchRequest(query = query, languages = language)
                                        searching = true
                                        searchError = null
                                        scope.launch {
                                            results = runCatching { OpenSubtitlesClient.search(request) }
                                                .onFailure { searchError = it.message ?: "Subtitle search failed." }
                                                .getOrDefault(emptyList())
                                            searching = false
                                        }
                                    }
                                },
                                modifier = Modifier.focusRequester(searchFocusRequester).focusProperties {
                                    canFocus = true
                                    up = queryFocusRequester
                                    left = languageFocusRequester
                                    right = searchFocusRequester
                                    down = resultFocusRequesters.firstOrNull() ?: searchFocusRequester
                                }.onPreviewKeyEvent {
                                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                        Key.DirectionUp -> focusControl(queryFocusRequester)
                                        Key.DirectionDown -> if (results.isNotEmpty()) focusResult(0) else focusControl(searchFocusRequester)
                                        Key.DirectionLeft -> focusControl(languageFocusRequester)
                                        Key.DirectionRight -> focusControl(searchFocusRequester)
                                        else -> false
                                    }
                                }.playerControlFocus(CircleShape, scaleOnFocus = false)
                            ) { Icon(Icons.Default.Search, "Search") }
                        }
                        if (searching) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        searchError?.let { Text(it) }
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = listHeight)
                                .onFocusChanged { resultListHasFocus = it.hasFocus }.focusGroup(),
                            state = resultList
                        ) {
                            items(results.size, key = { results[it].id }) { index ->
                                val subtitle = results[index]
                                NikTvTextActionButton(
                                    onClick = {
                                        if (downloadingId == null) scope.launch {
                                            downloadingId = subtitle.id
                                            searchError = null
                                            runCatching { OpenSubtitlesClient.download(context, subtitle) }
                                                .onSuccess { file -> onExternalSubtitle?.invoke(file); dismissPopup() }
                                                .onFailure { searchError = it.message ?: "Subtitle download failed." }
                                            downloadingId = null
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                        .focusRequester(resultFocusRequesters[index])
                                        .onFocusChanged { if (it.isFocused) focusedResultId = subtitle.id }
                                        .focusProperties { canFocus = true; left = resultFocusRequesters[index]; right = resultFocusRequesters[index] }
                                ) {
                                    Text(subtitle.displayName, Modifier.weight(1f))
                                    if (downloadingId == subtitle.id) CircularProgressIndicator(Modifier.padding(4.dp))
                                }
                            }
                            if (!searching && results.isEmpty() && searchError == null) item {
                                Text("Search OpenSubtitles using the suggested title or enter your own.", Modifier.padding(12.dp))
                            }
                        }
                    } else LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = listHeight)
                            .onFocusChanged { trackListHasFocus = it.hasFocus }.focusGroup(),
                        state = trackList
                    ) {
                        item(key = "subtitle-off") {
                            SubtitleTrackRow("Off", tracks.none { it.selected },
                                Modifier.focusRequester(offFocusRequester)
                                    .onFocusChanged { if (it.isFocused) focusedTrackId = null }
                                    .focusProperties { left = offFocusRequester; right = offFocusRequester }) { onSelect(null) }
                        }
                        items(tracks.size, key = { "subtitle-track:${tracks[it].id}" }) { index ->
                            val track = tracks[index]
                            val requester = trackFocusRequesters[index + 1]
                            SubtitleTrackRow(track.label, track.selected,
                                Modifier.focusRequester(requester)
                                    .onFocusChanged { if (it.isFocused) focusedTrackId = track.id }
                                    .focusProperties { left = requester; right = requester }) { onSelect(track.id) }
                        }
                        if (tracks.isEmpty()) item(key = "subtitle-empty") {
                            Text("No subtitle tracks are available in this stream.", Modifier.padding(12.dp))
                        }
                    }
                    if (!searchMode && hasDelayControls && !compact) Text("Subtitle timing")
                    if (!searchMode && hasDelayControls) Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onDelayChange((delayMs - 250L).coerceAtLeast(-10_000L)) },
                            modifier = Modifier.focusRequester(earlierFocusRequester).focusProperties {
                                canFocus = true
                                left = earlierFocusRequester; right = laterFocusRequester
                                down = when {
                                    delayMs != 0L -> resetFocusRequester
                                    hasAppearanceControl -> appearanceFocusRequester
                                    else -> earlierFocusRequester
                                }
                            }.onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                    Key.DirectionUp -> focusTrack(tracks.size)
                                    Key.DirectionDown -> focusControl(when {
                                        delayMs != 0L -> resetFocusRequester
                                        hasAppearanceControl -> appearanceFocusRequester
                                        else -> earlierFocusRequester
                                    })
                                    Key.DirectionLeft -> focusControl(earlierFocusRequester)
                                    Key.DirectionRight -> focusControl(laterFocusRequester)
                                    else -> false
                                }
                            }.playerControlFocus(CircleShape, scaleOnFocus = false)
                        ) { Icon(Icons.Default.Remove, "Show subtitles earlier") }
                        Text(if (delayMs == 0L) "0 ms" else "%+d ms".format(delayMs))
                        IconButton(
                            onClick = { onDelayChange((delayMs + 250L).coerceAtMost(10_000L)) },
                            modifier = Modifier.focusRequester(laterFocusRequester).focusProperties {
                                canFocus = true
                                left = earlierFocusRequester; right = laterFocusRequester
                                down = when {
                                    delayMs != 0L -> resetFocusRequester
                                    hasAppearanceControl -> appearanceFocusRequester
                                    else -> laterFocusRequester
                                }
                            }.onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                    Key.DirectionUp -> focusTrack(tracks.size)
                                    Key.DirectionDown -> focusControl(when {
                                        delayMs != 0L -> resetFocusRequester
                                        hasAppearanceControl -> appearanceFocusRequester
                                        else -> laterFocusRequester
                                    })
                                    Key.DirectionLeft -> focusControl(earlierFocusRequester)
                                    Key.DirectionRight -> focusControl(laterFocusRequester)
                                    else -> false
                                }
                            }.playerControlFocus(CircleShape, scaleOnFocus = false)
                        ) { Icon(Icons.Default.Add, "Show subtitles later") }
                    }
                    if (!searchMode && hasDelayControls && delayMs != 0L) NikTvTextActionButton(
                        onClick = { focusControl(earlierFocusRequester); onDelayChange(0L) },
                        modifier = Modifier.heightIn(min = 44.dp).focusRequester(resetFocusRequester).focusProperties {
                            canFocus = true
                            up = earlierFocusRequester; down = if (hasAppearanceControl) appearanceFocusRequester else resetFocusRequester
                            left = resetFocusRequester; right = resetFocusRequester
                        }.onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                Key.DirectionUp -> focusControl(earlierFocusRequester)
                                Key.DirectionDown -> focusControl(if (hasAppearanceControl) appearanceFocusRequester else resetFocusRequester)
                                else -> false
                            }
                        }
                    ) { Text("Reset timing") }
                    if (!searchMode && hasAppearanceControl) NikTvTextActionButton(
                        onClick = { onAppearanceChange(appearance.next()) },
                        modifier = Modifier.heightIn(min = 44.dp).focusRequester(appearanceFocusRequester).focusProperties {
                            canFocus = true
                            up = if (hasDelayControls) {
                                if (delayMs != 0L) resetFocusRequester else earlierFocusRequester
                            } else trackFocusRequesters.last()
                            left = appearanceFocusRequester; right = appearanceFocusRequester; down = appearanceFocusRequester
                        }.onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                Key.DirectionUp -> if (hasDelayControls) focusControl(
                                    if (delayMs != 0L) resetFocusRequester else earlierFocusRequester
                                ) else focusTrack(tracks.size)
                                Key.DirectionDown -> focusControl(appearanceFocusRequester)
                                else -> false
                            }
                        }
                    ) { Text("Appearance: ${appearance.label}") }
                    if (!searchMode && hasDelayControls && timingRequiresVlc && !compact) {
                        Text("Changing timing switches this playback session to VLC while preserving your position.")
                    }
                    if (!searchMode && limitationMessage != null) {
                        Text(limitationMessage, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8BBC3))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleSearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    editing: Boolean,
    activate: () -> Unit,
    stopEditing: () -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val currentActivate by rememberUpdatedState(activate)
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        readOnly = !editing, singleLine = true, textStyle = textStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(onDone = { stopEditing() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) onFocusLost() }
            .onPreviewKeyEvent {
                if (!editing && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (it.type == KeyEventType.KeyUp) activate()
                    true
                } else if (!editing && it.type == KeyEventType.KeyDown) {
                    val direction = when (it.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        else -> null
                    }
                    if (direction != null) { focusManager.moveFocus(direction); true } else false
                } else false
            }.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) currentActivate()
                }
            }
    )
}

@Composable
private fun SubtitleTrackRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // NikTvTextActionButton owns the single matching rounded focus outline.
    NikTvTextActionButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)
        .focusProperties { canFocus = true }) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
