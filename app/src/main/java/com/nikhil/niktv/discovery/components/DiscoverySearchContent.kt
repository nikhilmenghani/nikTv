package com.nikhil.niktv.discovery.components

import com.nikhil.niktv.ui.*


import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.model.*
import kotlinx.coroutines.launch

private val searchVisibleTypes = listOf(
    SearchContentType.ALL,
    SearchContentType.SERIES,
    SearchContentType.MOVIES,
    SearchContentType.LIVE_TV
)

private val SearchBackground = Color(0xFF090B10)
private val SearchSurface = Color(0xFF151720)
private val SearchRaised = Color(0xFF1B1E28)
private val SearchOutline = Color(0xFF2A2D36)
private val SearchMuted = Color(0xFFA7ABB5)
private val SearchAccent = Color(0xFFE50925)

private data class SearchCategoryOption(
    val id: String,
    val title: String
)

private fun searchSegmentShape(index: Int, count: Int): RoundedCornerShape = when (index) {
    0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
    count - 1 -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    else -> RoundedCornerShape(0.dp)
}

private fun Context.isSearchTvLikeDevice(configuration: Configuration): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

private fun SearchContentType.searchFavoriteKind() = when (this) {
    SearchContentType.ALL -> error("A global result must carry its media type")
    SearchContentType.LIVE_TV -> FavoriteKind.CHANNEL
    SearchContentType.MOVIES -> FavoriteKind.MOVIE
    SearchContentType.SERIES -> FavoriteKind.SERIES
    SearchContentType.EPISODES -> FavoriteKind.EPISODE
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverySearchContent(
    state: NikTvState,
    close: () -> Unit,
    setType: (SearchContentType) -> Unit,
    setCategory: (String) -> Unit,
    setQuery: (String) -> Unit,
    search: (Boolean) -> Unit,
    useRecent: (RecentSearch) -> Unit,
    deleteRecent: (RecentSearch) -> Unit,
    openResult: (MediaItem) -> Unit,
    loadMore: () -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    headerFocusRequester: FocusRequester? = null,
    queryFocusRequester: FocusRequester? = null,
    headerHasFocus: Boolean = false
) {
    var categoryPickerOpen by rememberSaveable(state.searchType) {
        mutableStateOf(false)
    }
    var restoreCategoryFocus by rememberSaveable(state.searchType) {
        mutableStateOf(false)
    }
    var searchEditing by remember { mutableStateOf(false) }
    var editSessionSawIme by remember { mutableStateOf(false) }
    var restoreQueryFocus by rememberSaveable { mutableStateOf(false) }
    var queryFocused by remember { mutableStateOf(false) }
    var showLocalSearchProgress by remember { mutableStateOf(false) }

    val fallbackSearchRequester = remember { FocusRequester() }
    val searchRequester = queryFocusRequester ?: fallbackSearchRequester
    val clearSearchRequester = remember { FocusRequester() }
    val submitSearchRequester = remember { FocusRequester() }
    val categoryRequester = remember { FocusRequester() }
    val contentRequester = remember { FocusRequester() }
    var resultsEntryRequest by remember { mutableIntStateOf(0) }
    val providerSearchRequester = remember { FocusRequester() }
    val typeRequesters = remember { searchVisibleTypes.associateWith { FocusRequester() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isSearchTvLikeDevice(configuration)
    val wideControls = !configuration.usesPhoneLayout(isTv) && configuration.screenWidthDp >=
        (if (configuration.usesShortWideLayout(isTv)) 480 else 600) * configuration.fontScale.coerceAtLeast(1f)
    val controlHeight = searchControlHeight()
    val remoteNavigationActive = context.usesRemoteNavigation(configuration) ||
        androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard

    LaunchedEffect(state.searchLocalLoading, state.searchServerLoading) {
        if (state.searchLocalLoading && !state.searchServerLoading) {
            // Avoid flashing a partial spinner for cache hits that finish within
            // a frame or two. Longer index searches still get clear feedback.
            kotlinx.coroutines.delay(180L)
            showLocalSearchProgress =
                state.searchLocalLoading && !state.searchServerLoading
        } else {
            showLocalSearchProgress = false
        }
    }

    val selectedCategoryTitle =
        state.searchCategories
            .firstOrNull { it.id == state.searchCategoryId }
            ?.title
            ?: "All categories"
    val searchingSpecificCategory = state.searchCategoryId != "*"
    val selectedTypeRequester = typeRequesters[state.searchType] ?: typeRequesters.getValue(searchVisibleTypes.first())
    val activeProfileKey = state.session?.profile?.cacheKey()
    val visibleRecentSearches = remember(
        state.recentSearches,
        activeProfileKey,
        state.searchScopeLocked,
        state.searchType
    ) {
        state.recentSearches
            .filter {
                it.type in searchVisibleTypes &&
                    it.profileKey == activeProfileKey &&
                    (!state.searchScopeLocked || it.type == state.searchType)
            }
            .take(8)
    }
    val recentListState = rememberLazyListState()
    val recentRequesters = remember(activeProfileKey) { mutableMapOf<String, FocusRequester>() }
    var recentRemovalFocus by remember(activeProfileKey, state.searchType, state.searchQuery) {
        mutableStateOf<Triple<String, Int, String?>?>(null)
    }
    fun removeRecent(recent: RecentSearch) {
        if (remoteNavigationActive) {
            val index = visibleRecentSearches.indexOfFirst { it.key == recent.key }
            if (index >= 0) {
                // Move off the disappearing X before the asynchronous store update.
                // Otherwise Compose restores focus to the screen's header.
                val neighbour = visibleRecentSearches.getOrNull(index + 1)
                    ?: visibleRecentSearches.getOrNull(index - 1)
                val prefocused = if (neighbour == null) {
                    runCatching { searchRequester.requestFocus() }
                    null
                } else neighbour.key.takeIf {
                    runCatching { recentRequesters[it]?.requestFocus() }.getOrNull() == true
                }
                recentRemovalFocus = Triple(recent.key, index, prefocused)
            }
        }
        deleteRecent(recent)
    }
    LaunchedEffect(visibleRecentSearches, recentRemovalFocus) {
        val (removedKey, index, prefocusedKey) = recentRemovalFocus ?: return@LaunchedEffect
        if (visibleRecentSearches.any { it.key == removedKey }) return@LaunchedEffect
        val targetIndex = index.coerceAtMost(visibleRecentSearches.lastIndex)
        if (targetIndex < 0) {
            runCatching { searchRequester.requestFocus() }
        } else {
            // The eight-row window can reveal an older entry after deletion.
            // Prefer that replacement, including when it needs to be composed first.
            val targetKey = visibleRecentSearches[targetIndex].key
            if (targetKey == prefocusedKey) {
                recentRemovalFocus = null
                return@LaunchedEffect
            }
            if (recentListState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                recentListState.scrollToItem(targetIndex)
            }
            repeat(6) {
                withFrameNanos { }
                if (runCatching { recentRequesters[targetKey]?.requestFocus() }.getOrNull() == true) {
                    recentRemovalFocus = null
                    return@LaunchedEffect
                }
            }
        }
        recentRemovalFocus = null
    }
    val hasContentFocusTarget =
        state.searchResults.isNotEmpty() ||
            state.searchHasMore ||
            (state.searchQuery.isBlank() && visibleRecentSearches.isNotEmpty())
    val belowFiltersRequester = if (hasContentFocusTarget) contentRequester else FocusRequester.Cancel
    val typeBelowSearch = if (state.searchScopeLocked) categoryRequester else selectedTypeRequester


    fun activateSearchField() {
        if (!searchEditing) {
            searchEditing = true
        }
    }

    // Keep the same editor focus target in both navigation and editing mode.
    // Removing/replacing a focused node lets the sidebar/header steal focus.
    LaunchedEffect(searchEditing) {
        if (searchEditing) {
            editSessionSawIme = false
            withFrameNanos { }
            runCatching { searchRequester.requestFocus() }
            keyboard?.show()
        }
    }

    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(searchEditing, imeVisible) {
        if (!searchEditing) {
            editSessionSawIme = false
        } else if (imeVisible) {
            editSessionSawIme = true
        } else if (editSessionSawIme) {
            // Fire TV consumes the first Back press while dismissing its IME,
            // so BackHandler does not always run. Leave editor mode as soon as
            // that IME closes, retaining focus on the query's D-pad target.
            searchEditing = false
            restoreQueryFocus = true
        }
    }

    LaunchedEffect(restoreQueryFocus, searchEditing, state.searchQuery) {
        if (restoreQueryFocus && !searchEditing) {
            withFrameNanos { }
            runCatching { searchRequester.requestFocus() }
            restoreQueryFocus = false
        }
    }

    BackHandler(enabled = searchEditing) {
        searchEditing = false
        keyboard?.hide()
        restoreQueryFocus = true
    }

    LaunchedEffect(remoteNavigationActive) {
        if (remoteNavigationActive) {
            withFrameNanos { }
            repeat(4) { attempt ->
                if (runCatching { searchRequester.requestFocus() }.getOrDefault(false)) {
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(40L * (attempt + 1))
            }
        }
    }

    LaunchedEffect(categoryPickerOpen) {
        if (!categoryPickerOpen && restoreCategoryFocus && remoteNavigationActive) {
            withFrameNanos { }
            runCatching { categoryRequester.requestFocus() }
            restoreCategoryFocus = false
        }
    }

    @Composable
    fun SearchFilters(modifier: Modifier = Modifier) {
        @Composable fun CategoryControl(modifier: Modifier) {
            SearchCategoryButton(selectedCategoryTitle, onClick = {
                restoreCategoryFocus = true
                categoryPickerOpen = true
            }, modifier = modifier.focusRequester(categoryRequester).focusProperties {
                up = if (wideControls || state.searchScopeLocked) searchRequester else selectedTypeRequester
                left = if (state.searchScopeLocked) searchRequester else if (wideControls) typeRequesters.getValue(searchVisibleTypes.last()) else selectedTypeRequester
                down = if (state.searchQuery.isNotBlank()) providerSearchRequester else belowFiltersRequester
                right = FocusRequester.Cancel
            })
        }
        Column(modifier) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!state.searchScopeLocked) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    searchVisibleTypes.forEachIndexed { index, type ->
                        val count = when {
                            state.searchQuery.isBlank() -> null
                            state.searchType == SearchContentType.ALL -> if (type == SearchContentType.ALL) state.searchResults.size
                                else state.searchResults.count { it.searchResultType == type }
                            state.searchType == type -> state.searchResults.size
                            else -> null
                        }
                        SearchScopeButton(type, state.searchType == type, { setType(type) }, count,
                            Modifier.focusRequester(typeRequesters.getValue(type)).focusProperties {
                                up = searchRequester
                                down = if (!wideControls) categoryRequester else if (state.searchQuery.isNotBlank()) providerSearchRequester else belowFiltersRequester
                                left = typeRequesters[searchVisibleTypes.getOrNull(index - 1)] ?: searchRequester
                                right = typeRequesters[searchVisibleTypes.getOrNull(index + 1)] ?: categoryRequester
                            })
                    }
                }
                if (wideControls || state.searchScopeLocked) CategoryControl(
                    if (state.searchScopeLocked) Modifier.weight(1f) else Modifier.widthIn(max = 180.dp))
            }
            if (!wideControls && !state.searchScopeLocked) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CategoryControl(Modifier.widthIn(max = 220.dp))
            }
            HorizontalDivider(color = SearchOutline)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SearchBackground)
            .padding(horizontal = if (configuration.screenWidthDp < 600) 14.dp else 20.dp)
    ) {

        Spacer(Modifier.height(6.dp))

        val searchBarShape = RoundedCornerShape(10.dp)
        val queryFocusBorder =
            if (queryFocused && remoteNavigationActive) {
                Color(0xFFFF3340)
            } else if (queryFocused) {
                SearchAccent
            } else {
                SearchOutline
            }
        val queryFocusBorderWidth =
            if (queryFocused && remoteNavigationActive) {
                3.dp
            } else if (queryFocused) {
                2.dp
            } else {
                1.dp
            }

        /*
         * The search actions are deliberately siblings of the query editor.
         * The outer Surface is visual-only and never becomes a focus target.
         * This prevents TextField's internal EditText from owning Clear/Search
         * and lets Compose focus routing move between three independent nodes.
         */
        Row(
            Modifier.fillMaxWidth().widthIn(max = 1120.dp).align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(controlHeight),
                    shape = searchBarShape,
                    color = if (queryFocused) SearchRaised else SearchSurface,
                    border = BorderStroke(queryFocusBorderWidth, queryFocusBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 13.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (queryFocused) SearchAccent else SearchMuted
                        )
                        Spacer(Modifier.width(8.dp))

                        BasicTextField(
                            value = state.searchQuery,
                            onValueChange = setQuery,
                            readOnly = !searchEditing,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.White
                            ),
                            cursorBrush = SolidColor(SearchAccent),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    searchEditing = false
                                    keyboard?.hide()
                                    restoreQueryFocus = true
                                    if (state.searchQuery.isNotBlank()) {
                                        // Use cached matches first, then fall back to the provider on a miss.
                                        search(false)
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .focusRequester(searchRequester)
                                .focusProperties {
                                    headerFocusRequester?.let { up = it }
                                    right =
                                        if (state.searchQuery.isNotEmpty()) {
                                            clearSearchRequester
                                        } else {
                                            submitSearchRequester
                                        }
                                    down = typeBelowSearch
                                }
                                .onFocusChanged {
                                    queryFocused = it.isFocused
                                    if (!it.isFocused && searchEditing) {
                                        searchEditing = false
                                        keyboard?.hide()
                                    }
                                }
                                .onPreInterceptKeyBeforeSoftKeyboard { event ->
                                    if (searchEditing && event.key == Key.Back) {
                                        if (event.type == KeyEventType.KeyUp) {
                                            searchEditing = false
                                            keyboard?.hide()
                                            restoreQueryFocus = true
                                        }
                                        true
                                    } else false
                                }
                                .onPreviewKeyEvent { event ->
                                    if (!searchEditing) {
                                        when (event.key) {
                                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                                // Consume the whole click; enter edit mode only
                                                // on release so the IME cannot receive its tail.
                                                if (event.type == KeyEventType.KeyUp) activateSearchField()
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                if (event.type == KeyEventType.KeyDown) {
                                                    val target = if (state.searchQuery.isNotEmpty())
                                                        clearSearchRequester else submitSearchRequester
                                                    runCatching { target.requestFocus() }
                                                }
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                if (event.type == KeyEventType.KeyDown) {
                                                    runCatching { typeBelowSearch.requestFocus() }
                                                }
                                                true
                                            }
                                            Key.DirectionUp -> {
                                                // A read-only text editor can consume Up as caret
                                                // movement before Compose follows focusProperties.
                                                val header = headerFocusRequester
                                                if (header != null) {
                                                    if (event.type == KeyEventType.KeyDown) {
                                                        runCatching { header.requestFocus() }
                                                    }
                                                    true
                                                } else false
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .pointerInput(Unit) {
                                    // Observe before the text field consumes its tap;
                                    // read-only fields still handle selection gestures.
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        activateSearchField()
                                    }
                                },
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (state.searchQuery.isEmpty()) {
                                        Text(
                                            if (state.searchScopeLocked) {
                                                "Search ${state.searchType.title.lowercase()}"
                                            } else {
                                                "Search NikTV"
                                            },
                                            color = SearchMuted,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )



                    }
                }
                if (state.searchQuery.isNotEmpty()) {
                    SearchIconControl("Clear search", Icons.Default.Close,
                        onClick = {
                            searchEditing = false
                            keyboard?.hide()
                            setQuery("")
                            restoreQueryFocus = true
                        },
                        modifier = Modifier
                            .width(controlHeight)
                            .focusRequester(clearSearchRequester)
                            .focusProperties {
                                left = searchRequester
                                right = submitSearchRequester
                                down = typeBelowSearch
                            }
                    )
                }
                SearchIconControl("Search", Icons.AutoMirrored.Filled.ArrowForward,
                    selected = state.searchQuery.isNotBlank() && !state.searchServerLoading,
                    onClick = {
                        searchEditing = false
                        keyboard?.hide()
                        search(false)
                    }, modifier = Modifier.width(controlHeight).focusRequester(submitSearchRequester).focusProperties {
                        left = if (state.searchQuery.isNotEmpty()) clearSearchRequester else searchRequester
                        right = FocusRequester.Cancel
                        down = typeBelowSearch
                        headerFocusRequester?.let { up = it }
                    })

            }
        }

        Spacer(Modifier.height(6.dp))
        SearchFilters()

        Spacer(Modifier.height(4.dp))

        // Keep one provider action mounted throughout loading and empty states.
        // Removing the focused empty-state button sends TV focus to the sidebar.
        if (state.searchQuery.isNotBlank()) {
            val resultLabel = if (state.searchResults.size == 1) "result" else "results"
            Row(
                Modifier.fillMaxWidth().heightIn(min = controlHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    when {
                        state.searchLocalLoading -> "Checking device cache…"
                        state.searchServerLoading -> "${state.searchResults.size} found so far"
                        state.searchUsedServer -> "${state.searchResults.size} $resultLabel"
                        else -> "${state.searchResults.size} cached $resultLabel"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = SearchMuted
                )
                if (wideControls && (state.searchServerLoading || showLocalSearchProgress)) {
                    Text(state.searchActivityDetail ?: state.searchActivityTitle ?: "Searching…",
                        Modifier.weight(2f), style = MaterialTheme.typography.bodySmall,
                        color = SearchMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SearchProviderAction(state, { search(true) }, Modifier.height(controlHeight)
                        .focusRequester(providerSearchRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.DirectionDown && state.searchResults.isNotEmpty()) {
                                if (event.type == KeyEventType.KeyDown) resultsEntryRequest++
                                true
                            } else false
                        }
                        .focusProperties {
                            up = categoryRequester
                            left = submitSearchRequester
                            down = if (hasContentFocusTarget) contentRequester else FocusRequester.Default
                        }
                )
            }
            if (wideControls) Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.searchServerLoading || showLocalSearchProgress) {
                    val progress = state.searchActivityProgress
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }

        state.searchProviderMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
        }

        /*
         * SEARCH_RECENTS_V4
         *
         * History is a navigation surface, not a chip cloud. A whole row is a
         * comfortable touch/D-pad target; long-press removes the entry without
         * introducing a tiny secondary focus target.
         */
        if (
            state.searchQuery.isBlank() &&
            visibleRecentSearches.isNotEmpty() &&
            state.searchResults.isEmpty()
        ) {
            Text(
                "Recent searches",
                Modifier.padding(top = 4.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Select to search again · × to remove",
                style = MaterialTheme.typography.bodySmall,
                color = SearchMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(
                state = recentListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                itemsIndexed(
                    visibleRecentSearches,
                    key = { _, recent -> "recent-${recent.key}" }
                ) { index, recent ->
                    SearchRecentRow(
                        recent = recent,
                        rowRequester = remember(recent.key) {
                            recentRequesters.getOrPut(recent.key) { FocusRequester() }
                        },
                        onClick = {
                            // The recent rows disappear as soon as a query is set.
                            // Keep remote focus on a node that survives that change.
                            if (remoteNavigationActive) searchRequester.requestFocus()
                            useRecent(recent)
                        },
                        onRemove = { removeRecent(recent) },
                        showRemoveButton = true,
                        // The first history row may be offscreen after scrolling.
                        modifier = if (index == recentListState.firstVisibleItemIndex)
                            Modifier.focusRequester(contentRequester) else Modifier
                    )
                }
            }
        }
        if (
            state.searchQuery.isNotBlank() &&
            state.searchResults.isEmpty() &&
            !state.searchHasMore &&
            !state.searchLocalLoading &&
            !state.searchServerLoading
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = Color(0xFF7C818A)
                )
                Text(
                    when {
                        state.searchUsedServer && searchingSpecificCategory ->
                            "No provider matches in $selectedCategoryTitle"
                        state.searchUsedServer ->
                            "No provider matches"
                        else ->
                            "No matches in the device cache"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when {
                        !state.searchUsedServer ->
                            "Search the provider for titles not yet cached on this device."
                        else ->
                            "Try another title or category."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.searchUsedServer && searchingSpecificCategory) {
                    NikTvSecondaryActionButton(
                        onClick = {
                            // This broaden action disappears when its category
                            // changes. Move focus to the persistent action first.
                            if (remoteNavigationActive) providerSearchRequester.requestFocus()
                            setCategory("*")
                            search(true)
                        },
                        enabled = !state.searchServerLoading
                    ) {
                        Icon(Icons.Default.SelectAll, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Search all categories")
                    }
                }
            }
        }

        if (state.searchResults.isNotEmpty() || state.searchHasMore) {
            /*
             * SEARCH_RESULTS_ADAPTIVE_V4
             *
             * Live TV remains row-based because logos, channel titles and
             * programme context scan better vertically. Wide displays use compact
             * two-column rows; narrow touch displays retain poster cards.
             */
            SearchResultsContent(
                state = state,
                isTv = isTv,
                screenWidthDp = configuration.screenWidthDp,
                openResult = openResult,
                loadMore = loadMore,
                toggleFavorite = toggleFavorite,
                firstItemRequester = contentRequester,
                entryRequest = resultsEntryRequest,
                topRequester = if (state.searchScopeLocked) categoryRequester else selectedTypeRequester,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (categoryPickerOpen) {
        SearchCategoryPicker(
            categories = state.searchCategories,
            selectedCategoryId = state.searchCategoryId,
            contentType = state.searchType,
            onSelect = { categoryId ->
                setCategory(categoryId)
                categoryPickerOpen = false
            },
            close = { categoryPickerOpen = false }
        )
    }
}

@Composable
private fun searchControlHeight(): androidx.compose.ui.unit.Dp {
    val configuration = LocalConfiguration.current
    val tv = LocalContext.current.isSearchTvLikeDevice(configuration)
    return if (tv) 36.dp else if (configuration.usesShortWideLayout(tv)) 40.dp else 48.dp
}

@Composable
private fun SearchQuietControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val isTv = LocalContext.current.isSearchTvLikeDevice(LocalConfiguration.current)
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Surface(onClick = onClick, color = if (focused) SearchRaised else Color.Transparent,
        shape = shape, border = if (focused) BorderStroke(2.dp, LocalRemoteFocusColor.current) else null, modifier = modifier.height(searchControlHeight())
            .onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false)) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = content)
    }
    }
}

@Composable
private fun SearchControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val isTv = LocalContext.current.isSearchTvLikeDevice(LocalConfiguration.current)
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (isTv) 0.dp else searchControlHeight()) {
        Surface(onClick = onClick,
            modifier = modifier.height(searchControlHeight()).onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false),
            shape = shape, color = if (selected) SearchAccent else SearchSurface,
            border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) LocalRemoteFocusColor.current else if (selected) SearchAccent else SearchOutline)) {
            Row(Modifier.fillMaxHeight().padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center, content = content)
        }
    }
}

@Composable
private fun SearchIconControl(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    SearchControl(onClick, modifier, selected, PaddingValues(0.dp)) {
        Icon(icon, label, Modifier.size(20.dp), tint = Color.White)
    }
}

@Composable
private fun SearchProviderAction(state: NikTvState, search: () -> Unit, modifier: Modifier = Modifier) {
    SearchQuietControl({ if (!state.searchServerLoading && !state.searchLocalLoading) search() }, modifier) {
        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = SearchMuted)
        Spacer(Modifier.width(6.dp))
        Text(when {
            state.searchServerLoading -> "Searching provider…"
            state.searchLocalLoading -> "Checking cache…"
            state.searchProviderMessage != null -> "Retry provider"
            state.searchUsedServer -> "Search provider again"
            else -> "Search provider"
        }, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun SearchActivityCard(
    title: String,
    detail: String,
    progress: Float?,
    providerSearch: Boolean
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (providerSearch) Icons.Default.CloudDownload else Icons.Default.Storage,
                contentDescription = null, modifier = Modifier.size(16.dp), tint = SearchAccent)
            Text("$title · $detail", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = SearchMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}

@Composable
private fun SyncProgressCard(
    message: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = SearchSurface,
        border = BorderStroke(1.dp, SearchAccent.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, null, tint = SearchAccent)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            progress < 0.82f -> "Scanning IPTV catalogue"
                            progress < 1f -> "Synchronizing shared index"
                            else -> "Synchronization complete"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = SearchMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.titleMedium,
                    color = SearchAccent,
                    fontWeight = FontWeight.Bold
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Provider scan", style = MaterialTheme.typography.labelSmall, color = SearchMuted)
                Text("Local merge", style = MaterialTheme.typography.labelSmall, color = SearchMuted)
                Text("GitHub upload", style = MaterialTheme.typography.labelSmall, color = SearchMuted)
            }
        }
    }
}

@Composable
private fun SearchScopeButton(type: SearchContentType, selected: Boolean, onClick: () -> Unit,
    count: Int?, modifier: Modifier = Modifier) {
    val isTv = LocalContext.current.isSearchTvLikeDevice(LocalConfiguration.current)
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    Surface(onClick = onClick, color = if (focused) SearchRaised else Color.Transparent, shape = shape,
        border = if (focused) BorderStroke(2.dp, LocalRemoteFocusColor.current) else null,
        modifier = modifier.widthIn(min = 48.dp).width(IntrinsicSize.Max).height(searchControlHeight()).onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false)
            .semantics { this.selected = selected; contentDescription = "Search type: ${type.title}" }) {
        Column(Modifier.fillMaxWidth().padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.weight(1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(type.title, color = if (selected) Color.White else SearchMuted,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                count?.let { Text(it.toString(), color = SearchMuted, style = MaterialTheme.typography.labelSmall) }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) SearchAccent else Color.Transparent))
        }
    }
    }
}

@Composable
private fun SearchCategoryButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SearchQuietControl(onClick, modifier) {
        Icon(Icons.Default.Tune, null, Modifier.size(16.dp), tint = SearchMuted)
        Spacer(Modifier.width(6.dp))
        Text(title, Modifier.weight(1f), color = Color.White,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.ExpandMore, "Choose category", Modifier.size(20.dp), tint = SearchMuted)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchCategoryPicker(
    categories: List<Category>,
    selectedCategoryId: String,
    contentType: SearchContentType,
    onSelect: (String) -> Unit,
    close: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isSearchTvLikeDevice(configuration)
    val remoteNavigationActive = context.usesRemoteNavigation(configuration)
    val options = remember(categories) {
        buildList {
            add(SearchCategoryOption("*", "All categories"))
            categories.filter { it.id != "*" }.distinctBy { it.id }.forEach {
                add(SearchCategoryOption(it.id, it.title))
            }
        }
    }
    if (isTv) {
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.84f).widthIn(max = 760.dp),
                shape = RoundedCornerShape(24.dp),
                color = SearchBackground,
                tonalElevation = 8.dp,
                border = BorderStroke(1.dp, SearchOutline)
            ) {
                SearchCategoryPickerContent(options, selectedCategoryId, contentType, true, true, onSelect, close)
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = close, containerColor = SearchBackground, dragHandle = { BottomSheetDefaults.DragHandle() }) {
            Box(Modifier.fillMaxWidth().heightIn(max = 680.dp).navigationBarsPadding()) {
                SearchCategoryPickerContent(options, selectedCategoryId, contentType, false, remoteNavigationActive, onSelect, close)
            }
        }
    }
}

private fun Modifier.searchPickerDpadNavigation(
    index: Int,
    columns: Int,
    itemCount: Int,
    topRequester: FocusRequester,
    moveFocus: (Int) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionUp -> {
            val target = index - columns
            if (target < 0) topRequester.requestFocus() else moveFocus(target)
            true
        }
        Key.DirectionDown -> {
            val target = index + columns
            if (target < itemCount) moveFocus(target)
            true
        }
        Key.DirectionLeft -> {
            if (index % columns > 0) moveFocus(index - 1)
            true
        }
        Key.DirectionRight -> {
            val target = index + 1
            if (index % columns < columns - 1 && target < itemCount) moveFocus(target)
            true
        }
        else -> false
    }
}

@Composable
private fun SearchCategoryPickerContent(
    options: List<SearchCategoryOption>,
    selectedCategoryId: String,
    contentType: SearchContentType,
    isTv: Boolean,
    remoteNavigationActive: Boolean,
    onSelect: (String) -> Unit,
    close: () -> Unit
) {
    var categoryQuery by rememberSaveable(contentType) { mutableStateOf("") }
    var categorySearchEditing by rememberSaveable(contentType) { mutableStateOf(false) }
    val accent = SearchAccent
    val configuration = LocalConfiguration.current
    val columns = if (isTv || configuration.screenWidthDp >= 700) 2 else 1
    val keyboard = LocalSoftwareKeyboardController.current
    val searchRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val optionRequesters = remember(options) { options.associate { it.id to FocusRequester() } }
    val filteredOptions = remember(options, categoryQuery) {
        val query = categoryQuery.trim()
        if (query.isBlank()) options else options.filter { it.title.contains(query, ignoreCase = true) }
    }
    val entryIndex = remember(filteredOptions, selectedCategoryId) {
        filteredOptions.indexOfFirst { it.id == selectedCategoryId }.takeIf { it >= 0 } ?: 0
    }
    fun requestOptionFocus(targetIndex: Int) {
        val option = filteredOptions.getOrNull(targetIndex) ?: return
        val requester = optionRequesters.getValue(option.id)
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
        scope.launch {
            if (columns > 1) gridState.scrollToItem(targetIndex) else listState.scrollToItem(targetIndex)
            repeat(5) { attempt ->
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
                kotlinx.coroutines.delay(30L * (attempt + 1))
            }
        }
    }
    fun activateCategorySearch() {
        categorySearchEditing = true
        searchRequester.requestFocus()
        keyboard?.show()
    }
    BackHandler(enabled = categorySearchEditing) {
        categorySearchEditing = false
        keyboard?.hide()
        searchRequester.requestFocus()
    }
    LaunchedEffect(remoteNavigationActive, selectedCategoryId, categoryQuery, categorySearchEditing, columns) {
        if (!remoteNavigationActive || filteredOptions.isEmpty() || categorySearchEditing) return@LaunchedEffect
        if (columns > 1) gridState.scrollToItem(entryIndex) else listState.scrollToItem(entryIndex)
        withFrameNanos { }
        val requester = optionRequesters.getValue(filteredOptions[entryIndex].id)
        repeat(4) { attempt ->
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            kotlinx.coroutines.delay(40L * (attempt + 1))
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 22.dp else 18.dp, vertical = if (isTv) 18.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Choose category", style = if (isTv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${contentType.title} · ${options.size - 1} available", style = MaterialTheme.typography.bodySmall, color = SearchMuted)
            }
            IconButton(onClick = close, modifier = Modifier.focusProperties { canFocus = false }) {
                Icon(Icons.Default.Close, "Close category picker")
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = categoryQuery,
            onValueChange = { categoryQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchRequester)
                .focusProperties {
                    down = filteredOptions.getOrNull(entryIndex)?.let { optionRequesters.getValue(it.id) } ?: FocusRequester.Default
                }
                .onFocusChanged {
                    if (it.isFocused && !remoteNavigationActive) categorySearchEditing = true
                    if (!it.isFocused && categorySearchEditing) { categorySearchEditing = false; keyboard?.hide() }
                }
                .onPreviewKeyEvent { event ->
                    if (!categorySearchEditing && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        if (filteredOptions.isNotEmpty()) requestOptionFocus(entryIndex)
                        true
                    } else if (!categorySearchEditing && event.type == KeyEventType.KeyUp && event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                        activateCategorySearch(); true
                    } else false
                }
                .pointerInput(categorySearchEditing, remoteNavigationActive) {
                    if (remoteNavigationActive && !categorySearchEditing) detectTapGestures { activateCategorySearch() }
                }
                .remoteFocusFrame(RoundedCornerShape(14.dp), drawBorder = false),
            singleLine = true,
            readOnly = remoteNavigationActive && !categorySearchEditing,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("Find a category") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (categoryQuery.isNotEmpty()) {{
                IconButton(onClick = { categoryQuery = "" }, modifier = Modifier.focusProperties { canFocus = false }) {
                    Icon(Icons.Default.Close, "Clear category search")
                }
            }} else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { categorySearchEditing = false; keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SearchRaised,
                unfocusedContainerColor = SearchSurface,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = accent,
                unfocusedBorderColor = SearchOutline,
                focusedLeadingIconColor = accent,
                unfocusedLeadingIconColor = SearchMuted,
                cursorColor = accent
            )
        )
        Spacer(Modifier.height(10.dp))
        if (filteredOptions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.FilterListOff, null, Modifier.size(36.dp), tint = SearchMuted)
                    Text("No categories match “${categoryQuery.trim()}”", color = SearchMuted)
                }
            }
        } else if (columns > 1) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = gridState,
                contentPadding = PaddingValues(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItemsIndexed(filteredOptions, key = { _, option -> option.id }) { index, option ->
                    SearchCategoryOptionTile(
                        option = option,
                        selected = option.id == selectedCategoryId,
                        accent = accent,
                        isTv = isTv,
                        onSelect = { onSelect(option.id) },
                        modifier = Modifier
                            .focusRequester(optionRequesters.getValue(option.id))
                            .searchPickerDpadNavigation(index, columns, filteredOptions.size, searchRequester, ::requestOptionFocus)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(filteredOptions, key = { it.id }) { option ->
                    val index = filteredOptions.indexOf(option)
                    SearchCategoryOptionTile(
                        option = option,
                        selected = option.id == selectedCategoryId,
                        accent = accent,
                        isTv = isTv,
                        onSelect = { onSelect(option.id) },
                        modifier = Modifier
                            .focusRequester(optionRequesters.getValue(option.id))
                            .searchPickerDpadNavigation(index, 1, filteredOptions.size, searchRequester, ::requestOptionFocus)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCategoryOptionTile(
    option: SearchCategoryOption,
    selected: Boolean,
    accent: Color,
    isTv: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth().heightIn(min = if (isTv) 58.dp else 54.dp).onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false),
        shape = shape,
        color = if (selected) accent.copy(alpha = 0.18f) else SearchSurface,
        border = BorderStroke(if (focused || selected) 2.dp else 1.dp, if (focused) LocalRemoteFocusColor.current else if (selected) accent.copy(alpha = 0.9f) else SearchOutline)
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (option.id == "*") Icons.Default.SelectAll else Icons.Default.FolderOpen, null, Modifier.size(20.dp), tint = if (selected) accent else SearchMuted)
            Spacer(Modifier.width(10.dp))
            Text(option.title, Modifier.weight(1f), color = Color.White, style = if (isTv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(Icons.Default.CheckCircle, "Selected", Modifier.size(20.dp), tint = accent)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SearchRecentRow(
    recent: RecentSearch,
    rowRequester: FocusRequester,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    showRemoveButton: Boolean,
    modifier: Modifier = Modifier
) {
    var rowFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val removeRequester = remember { FocusRequester() }
    val remoteNavigationActive = LocalContext.current.usesRemoteNavigation(LocalConfiguration.current)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(rowRequester)
            .onFocusChanged { rowFocused = it.isFocused }
            .semantics(mergeDescendants = true) { focused = rowFocused }
            .focusProperties { right = removeRequester }
            .remoteFocusFrame(shape, drawBorder = false)
            // Do not install the app-wide preview-key click handler on this
            // parent. Preview events reach the row before its focused X child,
            // so DPAD_CENTER used to invoke the recent search instead of the X.
            .combinedClickable(
                indication = if (remoteNavigationActive) null else LocalIndication.current,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
                onLongClick = onRemove
            ),
        shape = shape,
        color = SearchSurface,
        border = BorderStroke(if (rowFocused) 2.dp else 1.dp, if (rowFocused) LocalRemoteFocusColor.current else SearchOutline)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = CircleShape, color = SearchRaised) {
                Icon(Icons.Default.History, null, Modifier.padding(8.dp).size(19.dp), tint = SearchAccent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(recent.query, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${recent.type.title} · ${recent.categoryTitle}", style = MaterialTheme.typography.bodySmall, color = SearchMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showRemoveButton) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .focusRequester(removeRequester)
                        .focusProperties { left = rowRequester }
                        .remoteFocusFrame(CircleShape)
                ) {
                    Icon(Icons.Default.Close, "Remove ${recent.query}", tint = SearchMuted)
                }
            }
        }
    }
}

private data class SearchResultEntry(val key: String, val media: MediaItem? = null, val heading: String? = null)

@Composable
private fun SearchResultsContent(
    state: NikTvState,
    isTv: Boolean,
    screenWidthDp: Int,
    openResult: (MediaItem) -> Unit,
    loadMore: () -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    firstItemRequester: FocusRequester,
    entryRequest: Int,
    topRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val all = state.searchType == SearchContentType.ALL
    val configuration = LocalConfiguration.current
    val phoneLayout = com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout(configuration,
        LocalContext.current.isTvLikeDevice(configuration))
    val resultRequester = firstItemRequester
    val denseResults = !phoneLayout
    val columns = if (phoneLayout) 1 else 2
    val entries = remember(state.searchResults, state.searchType) {
        state.searchResults.map { SearchResultEntry(it.searchIdentity(state.searchType), media = it) }
    }
    val results = entries.mapNotNull { it.media }
    val keys = results.map { it.searchIdentity(state.searchType) }
    val resultIndices = keys.withIndex().associate { it.value to it.index }
    val lazyIndices = entries.withIndex().associate { it.value.key to it.index }
    val footer = state.searchHasMore || state.searchUsedServer || (all && results.isEmpty())
    val footerKey = "search-pagination"
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.searchType, state.searchQuery, state.searchCategoryId) { gridState.scrollToItem(0) }
    PrefetchCatalogPage(
        scope = "search:${state.session?.profile?.cacheKey()}:${state.searchQuery}:${state.searchType}:${state.searchCategoryId}",
        ids = keys,
        enabled = state.searchHasMore && !state.searchLocalLoading,
        loading = state.searchServerLoading,
        grid = gridState,
        // Automatic appends leave the currently focused result in place. Only
        // explicit footer activation moves focus to the first new result.
        loadMore = loadMore
    )
    val scope = rememberCoroutineScope()
    val requesters = remember(state.searchType, state.searchQuery, state.searchCategoryId) { mutableMapOf<String, FocusRequester>() }
    val footerRequester = remember { FocusRequester() }
    var footerFocused by remember(state.searchType) { mutableStateOf(false) }
    var navigationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(state.searchType) { navigationJob?.cancel() }
    var pendingPage by remember(state.searchType, state.searchQuery, state.searchCategoryId) {
        mutableStateOf<Pair<Int, Set<String>>?>(null)
    }
    fun requesterAt(index: Int): FocusRequester = when {
        index == results.size -> if (results.isEmpty()) resultRequester else footerRequester
        index == 0 -> resultRequester
        else -> requesters.getOrPut(keys[index]) { FocusRequester() }
    }
    fun moveTo(index: Int) {
        if (index < 0) { runCatching { topRequester.requestFocus() }; return }
        if (index > results.size || (index == results.size && !footer)) return
        val requester = requesterAt(index)
        val lazyIndex = if (index == results.size) entries.size else lazyIndices.getValue(keys[index])
        navigationJob?.cancel()
        navigationJob = scope.launch {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == lazyIndex }) gridState.scrollToItem(lazyIndex)
            repeat(6) {
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
            }
        }
    }
    LaunchedEffect(entryRequest) {
        if (entryRequest > 0 && results.isNotEmpty()) moveTo(0)
    }
    fun navigation(index: Int): Modifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                val target = searchVerticalTarget(index, columns, results.size, event.key == Key.DirectionDown, footer)
                // The phone dock is below the results. At the last row, leave
                // ordinary focus search free to reach Home and the other tabs.
                if (phoneLayout && event.key == Key.DirectionDown && target == index) false
                else { moveTo(target); true }
            }
            Key.DirectionLeft -> {
                when {
                    index < results.size && index % columns > 0 -> { moveTo(index - 1); true }
                    all -> { moveTo(-1); true }
                    // Allow focus to leave the first column for the sidebar.
                    else -> false
                }
            }
            Key.DirectionRight -> {
                if (index < results.size && index % columns < columns - 1 && index + 1 < results.size) {
                    moveTo(index + 1)
                }
                true
            }
            else -> false
        }
    }
    LaunchedEffect(state.searchPaginationGeneration, state.searchServerLoading) {
        val pending = pendingPage ?: return@LaunchedEffect
        if (state.searchServerLoading || state.searchPaginationGeneration == pending.first) return@LaunchedEffect
        pendingPage = null
        // Never steal focus if the user navigated away while the request was running.
        if (footerFocused) {
            val firstNew = keys.indexOfFirst { it !in pending.second }
            if (firstNew >= 0) moveTo(firstNew)
        }
    }
    Column(modifier.fillMaxWidth()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (denseResults) 6.dp else 10.dp)
    ) {
        gridItems(entries, key = { it.key }, span = { if (it.media == null) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { entry ->
            val item = entry.media
            if (item == null) {
                Text(entry.heading.orEmpty(), color = SearchAccent, style = MaterialTheme.typography.titleSmall)
            } else {
                val index = resultIndices.getValue(entry.key)
                val type = item.searchResultType ?: state.searchType
                val category = if (!all) state.searchCategoryTitle(item) else {
                    val catalog = when (type) {
                        SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
                        SearchContentType.MOVIES -> CatalogType.MOVIES
                        else -> CatalogType.SERIES
                    }
                    state.browseCachesByType[catalog]?.categories?.firstOrNull { it.id == item.portalCategoryId }?.title
                }
                val label = if (all) listOfNotNull(type.title, category).joinToString(" · ") else category
                val toggle = { toggleFavorite(state.searchFavoriteItem(item, category)) }
                val itemModifier = Modifier.focusRequester(requesterAt(index))
                    .then(navigation(index))
                if (type == SearchContentType.LIVE_TV) {
                    ModernSearchLiveResultRow(item, label, state.isSearchFavorite(item), toggle,
                        { openResult(item) }, isTv, itemModifier)
                } else {
                    ModernSearchMediaResultRow(item, type, label, state.isSearchFavorite(item), toggle,
                        { openResult(item) }, isTv, itemModifier)
                }
            }
        }
        if (footer) item(key = footerKey, span = { GridItemSpan(maxLineSpan) }) {
            SearchLoadMoreButton(
                loading = state.searchServerLoading,
                hasMore = state.searchHasMore,
                idleLabel = when {
                    all && state.searchLocalLoading -> "Checking local indexes…"
                    all && !state.searchUsedServer && results.isEmpty() -> "No cached matches · Search provider for more"
                    all && state.searchHasMore -> "Load more results"
                    else -> null
                },
                onClick = {
                    pendingPage = state.searchPaginationGeneration to keys.toSet()
                    loadMore()
                },
                modifier = Modifier.focusRequester(requesterAt(results.size))
                    .onFocusChanged { footerFocused = it.hasFocus }
                    .then(navigation(results.size))
            )
        }
    }
    }
}

private fun NikTvState.searchCategoryTitle(item: MediaItem): String? =
    searchCategories
        .firstOrNull { it.id == item.portalCategoryId }
        ?.title
        ?: searchCategories
            .firstOrNull { it.id == searchCategoryId }
            ?.title

private fun NikTvState.isSearchFavorite(item: MediaItem): Boolean =
    favorites.any { favorite ->
        favorite.media.id == item.id &&
            favorite.kind == (item.searchResultType ?: searchType).searchFavoriteKind()
    }

private fun NikTvState.searchFavoriteItem(
    item: MediaItem,
    categoryTitle: String?
): FavoriteItem =
    FavoriteItem(
        kind = (item.searchResultType ?: searchType).searchFavoriteKind(),
        media = item,
        categoryTitle = categoryTitle
    )

@Composable
private fun SearchLoadMoreButton(
    loading: Boolean,
    hasMore: Boolean,
    onClick: () -> Unit,
    idleLabel: String? = null,
    modifier: Modifier = Modifier
) {
    SearchQuietControl(onClick = { if (!loading && hasMore) onClick() }, modifier = modifier) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(Icons.Default.ExpandMore, null)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (loading) "Loading more…" else idleLabel ?: if (hasMore) "Load more results" else "All results loaded")
    }
}

@Composable
private fun ModernSearchLiveResultRow(
    item: MediaItem,
    categoryTitle: String?,
    isFavorite: Boolean,
    toggleFavorite: () -> Unit,
    onClick: () -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val artworkModel = remember(item.id, item.title, item.logo) {
        artworkRequest(context, item)
    }
    val dense = !LocalConfiguration.current.usesPhoneLayout(isTv)
    val compact = LocalConfiguration.current.usesPhoneLayout(isTv)
    val shape = RoundedCornerShape(14.dp)
    val programme = rememberCurrentLiveProgramme(item).value?.title

    Box {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .drawBehind { drawLine(SearchOutline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false)
                .remoteCombinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                ),
            shape = shape,
            border = if (focused) BorderStroke(2.dp, LocalRemoteFocusColor.current) else null,
            color = Color.Transparent
        ) {
            Row(
                Modifier.padding(if (dense) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                /*
                 * SEARCH_LIVE_FIT_V4
                 *
                 * Channel artwork is a logo, not a poster. Fit it inside a
                 * bounded neutral canvas instead of cropping it to 16:9.
                 */
                Surface(
                    modifier = Modifier
                        .width(if (dense) 72.dp else if (compact) 104.dp else 142.dp)
                        .aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF0F1F3)
                ) {
                    Box(
                        Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.logo.isNullOrBlank()) {
                            Icon(
                                Icons.Default.LiveTv,
                                null,
                                Modifier.size(if (isTv) 28.dp else 34.dp),
                                tint = Color(0xFF454A52)
                            )
                        } else {
                            SubcomposeAsyncImage(
                                artworkModel,
                                item.title,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            ) {
                                val artworkState by painter.state.collectAsState()
                                when (artworkState) {
                                    is coil3.compose.AsyncImagePainter.State.Success ->
                                        SubcomposeAsyncImageContent()

                                    else ->
                                        Icon(
                                            Icons.Default.LiveTv,
                                            null,
                                            Modifier.size(34.dp),
                                            tint = Color(0xFF454A52)
                                        )
                                }
                            }
                        }
                    }
                }

                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = if (!LocalConfiguration.current.usesPhoneLayout(isTv)) 12.sp else 11.sp,
                            lineHeight = if (!LocalConfiguration.current.usesPhoneLayout(isTv)) 14.sp else 13.sp
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (dense) 2 else 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    categoryTitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(
                                it,
                                color = Color(0xFF9DA2AB),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                    programme?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                modifier = Modifier.size(9.dp),
                                tint = Color(0xFFE50914)
                            )
                            Text(
                                "Now playing: $it",
                                color = Color(0xFFCACDD2),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Icon(
                    Icons.Default.PlayArrow,
                    "Open ${item.title}",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        SearchFavoriteMenu(
            item = item.copy(searchResultType = SearchContentType.LIVE_TV),
            expanded = menuOpen,
            isFavorite = isFavorite,
            onDismiss = { menuOpen = false },
            onToggle = {
                menuOpen = false
                toggleFavorite()
            }
        )
    }
}

@Composable
private fun ModernSearchMediaResultRow(
    item: MediaItem,
    type: SearchContentType,
    categoryTitle: String?,
    isFavorite: Boolean,
    toggleFavorite: () -> Unit,
    onClick: () -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val dense = !LocalConfiguration.current.usesPhoneLayout(isTv)
    val artworkModel = remember(item.id, item.title, item.logo) {
        artworkRequest(context, item)
    }
    val shape = RoundedCornerShape(14.dp)
    val description = item.description?.takeIf { it.isNotBlank() }
    val fallbackIcon =
        if (type == SearchContentType.SERIES) {
            Icons.Default.VideoLibrary
        } else {
            Icons.Default.Movie
        }

    Box {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .drawBehind { drawLine(SearchOutline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false)
                .remoteCombinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                ),
            shape = shape,
            border = if (focused) BorderStroke(2.dp, LocalRemoteFocusColor.current) else null,
            color = Color.Transparent
        ) {
            Row(
                Modifier.padding(if (dense) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Box(
                    Modifier
                        .width(if (dense) 32.dp else 48.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF292D34)),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.logo.isNullOrBlank()) {
                        Icon(
                            fallbackIcon,
                            null,
                            Modifier.size(34.dp),
                            tint = Color.LightGray
                        )
                    } else {
                        SubcomposeAsyncImage(
                            artworkModel,
                            item.title,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        ) {
                            val artworkState by painter.state.collectAsState()
                            when (artworkState) {
                                is coil3.compose.AsyncImagePainter.State.Success ->
                                    SubcomposeAsyncImageContent()

                                else ->
                                    Icon(
                                        fallbackIcon,
                                        null,
                                        Modifier.size(34.dp),
                                        tint = Color.LightGray
                                    )
                            }
                        }
                    }
                }

                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 5.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = if (!LocalConfiguration.current.usesPhoneLayout(isTv)) 12.sp else 11.sp,
                            lineHeight = if (!LocalConfiguration.current.usesPhoneLayout(isTv)) 14.sp else 13.sp
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (dense) 2 else 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    categoryTitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(it, color = SearchMuted, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                    description?.takeUnless { dense }?.let {
                        Text(
                            it,
                            color = Color(0xFFB8BCC4),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    "Open ${item.title}",
                    tint = Color(0xFFB8BCC4)
                )
            }
        }

        SearchFavoriteMenu(
            item = item.copy(searchResultType = type),
            expanded = menuOpen,
            isFavorite = isFavorite,
            onDismiss = { menuOpen = false },
            onToggle = {
                menuOpen = false
                toggleFavorite()
            }
        )
    }
}

@Composable
private fun ModernSearchPosterResultCard(
    item: MediaItem,
    type: SearchContentType,
    categoryTitle: String?,
    isFavorite: Boolean,
    toggleFavorite: () -> Unit,
    onClick: () -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    ModernCompactMediaCard(
        item = item.copy(searchResultType = type),
        subtitle = categoryTitle.orEmpty(),
        onClick = onClick,
        isFavorite = isFavorite,
        onFavorite = toggleFavorite,
        // Match Continue Watching's smaller TV typography without shrinking
        // the result grid's posters. Give long provider titles more lines.
        titleScale = if (isTv) 0.82f else 1f,
        titleMaxLines = 5,
        subtitleMaxLines = 2,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun SearchFavoriteMenu(
    item: MediaItem? = null,
    expanded: Boolean,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = SearchRaised,
        shape = RoundedCornerShape(12.dp)
    ) {
        item?.let {
            AlternateSourceMenuItem(it, dismiss = onDismiss)
            ShowRecommendationsMenuItem(it, dismiss = onDismiss)
            ProviderMetadataLookupMenuItem(it, dismiss = onDismiss)
        }
        NikDropdownMenuItem(
            text = {
                Text(
                    if (isFavorite) {
                        "Remove from My List"
                    } else {
                        "Add to My List"
                    }
                )
            },
            leadingIcon = {
                Icon(
                    if (isFavorite) {
                        Icons.Default.HeartBroken
                    } else {
                        Icons.Default.FavoriteBorder
                    },
                    null
                )
            },
            onClick = onToggle
        )
    }
}

@Composable
private fun SearchMetadataBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
