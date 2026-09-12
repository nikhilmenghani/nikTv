package com.nikhil.niktv.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.model.*
import kotlinx.coroutines.launch

private val searchVisibleTypes = listOf(
    SearchContentType.LIVE_TV,
    SearchContentType.SERIES,
    SearchContentType.MOVIES
)

private val SearchBackground = Color(0xFF0B0B0F)
private val SearchSurface = Color(0xFF151720)
private val SearchRaised = Color(0xFF1B1E28)
private val SearchOutline = Color(0xFF2A2D36)
private val SearchMuted = Color(0xFFA7ABB5)
private val SearchAccent = Color(0xFF7C8CFF)

private fun SearchContentType.searchAccent(): Color = when (this) {
    SearchContentType.LIVE_TV -> Color(0xFFE65D68)
    SearchContentType.MOVIES -> Color(0xFF55B8FF)
    SearchContentType.SERIES -> Color(0xFF9A80FF)
    SearchContentType.EPISODES -> SearchAccent
}

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
    SearchContentType.LIVE_TV -> FavoriteKind.CHANNEL
    SearchContentType.MOVIES -> FavoriteKind.MOVIE
    SearchContentType.SERIES -> FavoriteKind.SERIES
    SearchContentType.EPISODES -> FavoriteKind.EPISODE
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModernSearchScreen(
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
    toggleFavorite: (FavoriteItem) -> Unit
) {
    var categoryPickerOpen by rememberSaveable(state.searchType) {
        mutableStateOf(false)
    }
    var restoreCategoryFocus by rememberSaveable(state.searchType) {
        mutableStateOf(false)
    }
    var searchEditing by rememberSaveable { mutableStateOf(false) }

    val searchRequester = remember { FocusRequester() }
    val categoryRequester = remember { FocusRequester() }
    val contentRequester = remember { FocusRequester() }
    val typeRequesters = remember { searchVisibleTypes.associateWith { FocusRequester() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = context.isSearchTvLikeDevice(configuration)
    val remoteNavigationActive = context.usesRemoteNavigation(configuration)

    val selectedCategoryTitle =
        state.searchCategories
            .firstOrNull { it.id == state.searchCategoryId }
            ?.title
            ?: "All categories"
    val searchingSpecificCategory = state.searchCategoryId != "*"
    val selectedTypeRequester = typeRequesters[state.searchType] ?: typeRequesters.getValue(searchVisibleTypes.first())
    val typeBelowSearch = if (state.searchScopeLocked) categoryRequester else selectedTypeRequester
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
    val hasContentFocusTarget =
        state.searchResults.isNotEmpty() ||
            (state.searchQuery.isBlank() && visibleRecentSearches.isNotEmpty()) ||
            (state.searchQuery.isNotBlank() && !state.searchLocalLoading && !state.searchServerLoading)

    fun activateSearchField() {
        searchEditing = true
        searchRequester.requestFocus()
        keyboard?.show()
    }

    BackHandler(enabled = searchEditing) {
        searchEditing = false
        keyboard?.hide()
        searchRequester.requestFocus()
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

    LaunchedEffect(
        state.searchResults.firstOrNull()?.id,
        state.searchLocalLoading,
        state.searchServerLoading,
        state.searchUsedServer,
        searchEditing
    ) {
        if (remoteNavigationActive && (!searchEditing || state.searchUsedServer) &&
            !state.searchLocalLoading && !state.searchServerLoading &&
            state.searchResults.isNotEmpty()
        ) {
            searchEditing = false
            keyboard?.hide()
            repeat(6) { attempt ->
                withFrameNanos { }
                if (runCatching { contentRequester.requestFocus() }.getOrDefault(false)) {
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(40L * (attempt + 1))
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SearchBackground)
            .padding(horizontal = if (configuration.screenWidthDp < 600) 14.dp else 20.dp)
    ) {
        SearchScreenHeader(
            title = if (state.searchScopeLocked) {
                "Search ${state.searchType.title}"
            } else {
                "Search"
            },
            subtitle = listOfNotNull(
                state.session?.profile?.name?.takeIf { it.isNotBlank() },
                selectedCategoryTitle.takeUnless { it == "All categories" }
            ).joinToString(" · ").takeIf { it.isNotBlank() }
                ?: if (state.searchScopeLocked) {
                    "Find ${state.searchType.title.lowercase()} in your library"
                } else {
                    "Find channels, series and movies"
                },
            close = close
        )

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1120.dp)
                .align(Alignment.CenterHorizontally)
                .height(if (isTv) 62.dp else 56.dp)
                .focusRequester(searchRequester)
                .focusProperties { down = typeBelowSearch }
                .onFocusChanged {
                    if (!it.isFocused && searchEditing) {
                        searchEditing = false
                        keyboard?.hide()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (!searchEditing && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        typeBelowSearch.requestFocus()
                        true
                    } else if (
                        !searchEditing &&
                        event.type == KeyEventType.KeyUp &&
                        event.key in listOf(
                            Key.DirectionCenter,
                            Key.Enter,
                            Key.NumPadEnter
                        )
                    ) {
                        activateSearchField()
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(searchEditing) {
                    if (!searchEditing) {
                        detectTapGestures { activateSearchField() }
                    }
                }
                .remoteFocusFrame(RoundedCornerShape(18.dp)),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = {
                Text(if (state.searchScopeLocked) "Search ${state.searchType.title.lowercase()}" else "Search NikTV")
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { setQuery("") }, modifier = Modifier.focusProperties { canFocus = false }) {
                            Icon(Icons.Default.Close, "Clear search")
                        }
                    }
                    FilledIconButton(
                        onClick = {
                            searchEditing = false
                            keyboard?.hide()
                            search(true)
                        },
                        modifier = Modifier.focusProperties { canFocus = false },
                        enabled =
                            state.searchQuery.isNotBlank() &&
                                !state.searchServerLoading,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SearchAccent,
                            contentColor = Color.White,
                            disabledContainerColor = SearchOutline,
                            disabledContentColor = SearchMuted
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            "Search"
                        )
                    }
                }
            },
            readOnly = !searchEditing,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    searchEditing = false
                    keyboard?.hide()
                    if (state.searchQuery.isNotBlank()) search(true)
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SearchRaised,
                unfocusedContainerColor = SearchSurface,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = SearchAccent,
                unfocusedBorderColor = SearchOutline,
                focusedLeadingIconColor = SearchAccent,
                unfocusedLeadingIconColor = SearchMuted,
                cursorColor = SearchAccent
            )
        )

        Spacer(Modifier.height(12.dp))

        if (!state.searchScopeLocked) {
            if (configuration.screenWidthDp < 600) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    searchVisibleTypes.forEachIndexed { index, type ->
                        SearchScopeButton(
                            type = type,
                            selected = state.searchType == type,
                            onClick = { setType(type) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(typeRequesters.getValue(type))
                                .focusProperties {
                                    up = searchRequester
                                    down = categoryRequester
                                    left = typeRequesters[searchVisibleTypes.getOrNull(index - 1)] ?: FocusRequester.Cancel
                                    right = typeRequesters[searchVisibleTypes.getOrNull(index + 1)] ?: FocusRequester.Cancel
                                }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SearchCategoryButton(
                    title = selectedCategoryTitle,
                    availableCount = state.searchCategories.count { it.id != "*" },
                    contentType = state.searchType,
                    onClick = {
                        restoreCategoryFocus = true
                        categoryPickerOpen = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(categoryRequester)
                        .focusProperties {
                            up = selectedTypeRequester
                            down = if (hasContentFocusTarget) contentRequester else FocusRequester.Default
                        }
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 1120.dp)
                        .align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    searchVisibleTypes.forEachIndexed { index, type ->
                        SearchScopeButton(
                            type = type,
                            selected = state.searchType == type,
                            onClick = { setType(type) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(typeRequesters.getValue(type))
                                .focusProperties {
                                    up = searchRequester
                                    down = if (hasContentFocusTarget) contentRequester else FocusRequester.Default
                                    left = typeRequesters[searchVisibleTypes.getOrNull(index - 1)] ?: FocusRequester.Cancel
                                    right = typeRequesters[searchVisibleTypes.getOrNull(index + 1)] ?: categoryRequester
                                }
                        )
                    }
                    SearchCategoryButton(
                        title = selectedCategoryTitle,
                        availableCount = state.searchCategories.count { it.id != "*" },
                        contentType = state.searchType,
                        onClick = {
                            restoreCategoryFocus = true
                            categoryPickerOpen = true
                        },
                        modifier = Modifier
                            .weight(1.35f)
                            .focusRequester(categoryRequester)
                            .focusProperties {
                                up = searchRequester
                                left = typeRequesters.getValue(searchVisibleTypes.last())
                                right = FocusRequester.Cancel
                                down = if (hasContentFocusTarget) contentRequester else FocusRequester.Default
                            }
                    )
                }
            }
        } else {
            SearchCategoryButton(
                title = selectedCategoryTitle,
                availableCount = state.searchCategories.count { it.id != "*" },
                contentType = state.searchType,
                onClick = {
                    restoreCategoryFocus = true
                    categoryPickerOpen = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1120.dp)
                    .align(Alignment.CenterHorizontally)
                    .focusRequester(categoryRequester)
                    .focusProperties {
                        up = searchRequester
                        down = if (hasContentFocusTarget) contentRequester else FocusRequester.Default
                    }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (state.searchServerLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }

        if (state.searchLocalLoading && !state.searchServerLoading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    "Checking available items…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                if (isTv) "OK to search again · hold OK to remove" else "Select to search again",
                style = MaterialTheme.typography.bodySmall,
                color = SearchMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(
                    visibleRecentSearches,
                    key = { "recent-${it.key}" }
                ) { recent ->
                    SearchRecentRow(
                        recent = recent,
                        onClick = { useRecent(recent) },
                        onRemove = { deleteRecent(recent) },
                        showRemoveButton = !isTv,
                        modifier = if (recent == visibleRecentSearches.first()) Modifier.focusRequester(contentRequester) else Modifier
                    )
                }
            }
        }
        if (
            state.searchQuery.isNotBlank() &&
            state.searchResults.isEmpty() &&
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
                            "No matches available now"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when {
                        !state.searchUsedServer ->
                            "Search your provider or choose another category."
                        else ->
                            "Try another title or category."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when {
                    !state.searchUsedServer -> {
                        OutlinedButton(
                            onClick = { search(true) },
                            modifier = Modifier.focusRequester(contentRequester).remoteFocusFrame(),
                            enabled = !state.searchServerLoading,
                            border = BorderStroke(1.dp, SearchOutline)
                        ) {
                            Icon(Icons.Default.CloudDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (searchingSpecificCategory) {
                                    "Search provider in $selectedCategoryTitle"
                                } else {
                                    "Search provider"
                                }
                            )
                        }
                    }

                    searchingSpecificCategory -> {
                        OutlinedButton(
                            onClick = {
                                /*
                                 * SEARCH_BROADEN_EXPLICIT_V3
                                 *
                                 * setCategory updates StateFlow synchronously;
                                 * search(true) then owns the explicit wildcard
                                 * provider request and cancels the scheduled
                                 * local preview from setCategory.
                                 */
                                setCategory("*")
                                search(true)
                            },
                            modifier = Modifier.focusRequester(contentRequester).remoteFocusFrame(),
                            enabled = !state.searchServerLoading,
                            border = BorderStroke(1.dp, SearchOutline)
                        ) {
                            Icon(Icons.Default.SelectAll, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Search all categories")
                        }
                    }
                }
            }
        }

        if (state.searchResults.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.searchUsedServer) {
                        "${state.searchResults.size} results"
                    } else {
                        "${state.searchResults.size} available now"
                    },
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!state.searchUsedServer) {
                    OutlinedButton(
                        onClick = { search(true) },
                        modifier = Modifier.remoteFocusFrame(),
                        enabled = !state.searchServerLoading,
                        border = BorderStroke(1.dp, SearchOutline)
                    ) {
                        Text(
                            if (searchingSpecificCategory) {
                                "Search provider in category"
                            } else {
                                "Search provider"
                            }
                        )
                    }
                }
            }

            /*
             * SEARCH_RESULTS_ADAPTIVE_V4
             *
             * Live TV remains row-based because logos, channel titles and
             * programme context scan better vertically. Movies/Series switch
             * to poster cards when the display has enough width.
             */
            SearchResultsContent(
                state = state,
                isTv = isTv,
                screenWidthDp = configuration.screenWidthDp,
                openResult = openResult,
                loadMore = loadMore,
            toggleFavorite = toggleFavorite,
            firstItemRequester = contentRequester,
            topRequester = categoryRequester,
            autoFocusFirst = !searchEditing || state.searchUsedServer,
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
private fun SearchScreenHeader(
    title: String,
    subtitle: String?,
    close: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = close,
            modifier = Modifier.remoteFocusFrame(CircleShape)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Back",
                tint = Color.White
            )
        }

        Spacer(Modifier.width(4.dp))

        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            subtitle
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = SearchMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
        }
    }
}

@Composable
private fun SearchScopeButton(
    type: SearchContentType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = type.searchAccent()
    val shape = RoundedCornerShape(14.dp)
    val icon = when (type) {
        SearchContentType.LIVE_TV -> Icons.Default.LiveTv
        SearchContentType.SERIES -> Icons.Default.VideoLibrary
        SearchContentType.MOVIES -> Icons.Default.Movie
        SearchContentType.EPISODES -> Icons.Default.PlaylistPlay
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp).remoteFocusFrame(shape),
        shape = shape,
        color = if (selected) accent.copy(alpha = 0.18f) else SearchSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent.copy(alpha = 0.88f) else SearchOutline)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (selected) accent else SearchMuted)
            Spacer(Modifier.width(7.dp))
            Text(
                type.title,
                color = if (selected) Color.White else SearchMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SearchCategoryButton(
    title: String,
    availableCount: Int,
    contentType: SearchContentType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val accent = contentType.searchAccent()
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp).remoteFocusFrame(shape),
        shape = shape,
        color = SearchSurface,
        border = BorderStroke(1.dp, SearchOutline)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                Modifier.size(30.dp),
                shape = RoundedCornerShape(9.dp),
                color = accent.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.34f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.FilterAlt, null, Modifier.size(17.dp), tint = accent)
                }
            }
            Spacer(Modifier.width(9.dp))
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (availableCount > 0) {
                Text(availableCount.toString(), style = MaterialTheme.typography.labelSmall, color = SearchMuted)
                Spacer(Modifier.width(5.dp))
            }
            Icon(Icons.Default.ChevronRight, "Choose category", Modifier.size(20.dp), tint = SearchMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
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
    val accent = contentType.searchAccent()
    val configuration = LocalConfiguration.current
    val columns = if (isTv || configuration.screenWidthDp >= 700) 2 else 1
    val keyboard = LocalSoftwareKeyboardController.current
    val searchRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val optionRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val filteredOptions = remember(options, categoryQuery) {
        val query = categoryQuery.trim()
        if (query.isBlank()) options else options.filter { it.title.contains(query, ignoreCase = true) }
    }
    filteredOptions.forEach { optionRequesters.getOrPut(it.id) { FocusRequester() } }
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
                .remoteFocusFrame(RoundedCornerShape(14.dp)),
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
    Surface(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth().heightIn(min = if (isTv) 58.dp else 54.dp).remoteFocusFrame(shape),
        shape = shape,
        color = if (selected) accent.copy(alpha = 0.18f) else SearchSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent.copy(alpha = 0.9f) else SearchOutline)
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
private fun SearchRecentRow(
    recent: RecentSearch,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    showRemoveButton: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.fillMaxWidth().remoteFocusFrame(shape).remoteCombinedClickable(onClick = onClick, onLongClick = onRemove),
        shape = shape,
        color = SearchSurface,
        border = BorderStroke(1.dp, SearchOutline)
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
                IconButton(onClick = onRemove, modifier = Modifier.focusProperties { canFocus = false }) {
                    Icon(Icons.Default.Close, "Remove ${recent.query}", tint = SearchMuted)
                }
            }
        }
    }
}

private fun Modifier.searchResultGridDpadNavigation(
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
            if (target < itemCount) { moveFocus(target); true } else false
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
private fun SearchResultsContent(
    state: NikTvState,
    isTv: Boolean,
    screenWidthDp: Int,
    openResult: (MediaItem) -> Unit,
    loadMore: () -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    firstItemRequester: FocusRequester,
    topRequester: FocusRequester,
    autoFocusFirst: Boolean,
    modifier: Modifier = Modifier
) {
    val posterGrid = state.searchType in setOf(SearchContentType.MOVIES, SearchContentType.SERIES) && (isTv || screenWidthDp >= 600)
    val liveGrid = state.searchType == SearchContentType.LIVE_TV && isTv
    if (posterGrid || liveGrid) {
        val columns = when {
            liveGrid -> 2
            isTv -> 6
            screenWidthDp >= 1200 -> 5
            screenWidthDp >= 840 -> 4
            else -> 3
        }
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        val requesters = remember(state.searchType, state.searchQuery, state.searchCategoryId) { mutableMapOf<String, FocusRequester>() }
        state.searchResults.forEachIndexed { index, item -> if (index > 0) requesters.getOrPut(item.id) { FocusRequester() } }
        fun requesterAt(index: Int): FocusRequester = if (index == 0) firstItemRequester else requesters.getOrPut(state.searchResults[index].id) { FocusRequester() }
        fun requestResultFocus(targetIndex: Int) {
            if (targetIndex !in state.searchResults.indices) return
            val requester = requesterAt(targetIndex)
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
            scope.launch {
                if (gridState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) gridState.scrollToItem(targetIndex)
                repeat(5) { attempt ->
                    withFrameNanos { }
                    if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
                    kotlinx.coroutines.delay(30L * (attempt + 1))
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 14.dp)
        ) {
            gridItemsIndexed(state.searchResults, key = { _, item -> "search-${state.searchType}-${item.id}" }) { index, item ->
                if (index == 0) SearchFirstResultFocusEffect(firstItemRequester, autoFocusFirst, item.id)
                val category = state.searchCategoryTitle(item)
                val favorite = state.isSearchFavorite(item)
                val toggle = { toggleFavorite(state.searchFavoriteItem(item, category)) }
                val itemModifier = Modifier
                    .focusRequester(requesterAt(index))
                    .searchResultGridDpadNavigation(index, columns, state.searchResults.size, topRequester, ::requestResultFocus)
                if (liveGrid) {
                    ModernSearchLiveResultRow(item, category, favorite, toggle, { openResult(item) }, true, itemModifier)
                } else {
                    ModernSearchPosterResultCard(
                        item = item,
                        type = state.searchType,
                        categoryTitle = category,
                        isFavorite = favorite,
                        toggleFavorite = toggle,
                        onClick = { openResult(item) },
                        isTv = isTv,
                        modifier = itemModifier
                    )
                }
            }
            if (state.searchHasMore) {
                item(key = "search-load-more", span = { GridItemSpan(maxLineSpan) }) {
                    SearchLoadMoreButton(state.searchServerLoading, loadMore)
                }
            } else if (state.searchUsedServer) {
                item(key = "search-provider-complete", span = { GridItemSpan(maxLineSpan) }) { SearchProviderCompleteMessage() }
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.searchResults, key = { "search-${state.searchType}-${it.id}" }) { item ->
            if (item == state.searchResults.first()) SearchFirstResultFocusEffect(firstItemRequester, autoFocusFirst, item.id)
            val category = state.searchCategoryTitle(item)
            val favorite = state.isSearchFavorite(item)
            val toggle = { toggleFavorite(state.searchFavoriteItem(item, category)) }
            val firstModifier = if (item == state.searchResults.first()) Modifier.focusRequester(firstItemRequester) else Modifier
            if (state.searchType == SearchContentType.LIVE_TV) {
                ModernSearchLiveResultRow(item, category, favorite, toggle, { openResult(item) }, isTv, firstModifier)
            } else {
                ModernSearchMediaResultRow(item, state.searchType, category, favorite, toggle, { openResult(item) }, isTv, firstModifier)
            }
        }
        if (state.searchHasMore) {
            item("search-load-more") { SearchLoadMoreButton(state.searchServerLoading, loadMore) }
        } else if (state.searchUsedServer) {
            item("search-provider-complete") { SearchProviderCompleteMessage() }
        }
    }
}

@Composable
private fun SearchFirstResultFocusEffect(
    requester: FocusRequester,
    enabled: Boolean,
    resultId: String
) {
    LaunchedEffect(enabled, resultId) {
        if (!enabled) return@LaunchedEffect
        repeat(6) { attempt ->
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            kotlinx.coroutines.delay(35L * (attempt + 1))
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
            favorite.kind == searchType.searchFavoriteKind()
    }

private fun NikTvState.searchFavoriteItem(
    item: MediaItem,
    categoryTitle: String?
): FavoriteItem =
    FavoriteItem(
        kind = searchType.searchFavoriteKind(),
        media = item,
        categoryTitle = categoryTitle
    )

@Composable
private fun SearchLoadMoreButton(
    loading: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .remoteFocusFrame()
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(Icons.Default.ExpandMore, null)
        }
        Spacer(Modifier.width(8.dp))
        Text("Load more results")
    }
}

@Composable
private fun SearchProviderCompleteMessage() {
    Text(
        "No more provider results",
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
    val context = LocalContext.current
    val artworkModel = remember(item.id, item.title, item.logo) {
        artworkRequest(context, item)
    }
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val shape = RoundedCornerShape(14.dp)
    val programme =
        item.liveProgramme?.title?.takeIf { it.isNotBlank() }

    Box {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .remoteFocusFrame(shape)
                .remoteCombinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                ),
            shape = shape,
            color = SearchSurface,
            border = BorderStroke(1.dp, SearchOutline)
        ) {
            Row(
                Modifier.padding(if (isTv) 7.dp else 10.dp),
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
                        .width(if (isTv) 104.dp else if (compact) 104.dp else 142.dp)
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
                                when (painter.state.value) {
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
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
    val context = LocalContext.current
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
                .remoteFocusFrame(shape)
                .remoteCombinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                ),
            shape = shape,
            color = SearchSurface,
            border = BorderStroke(1.dp, SearchOutline)
        ) {
            Row(
                Modifier.padding(if (isTv) 7.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Box(
                    Modifier
                        .width(if (isTv) 54.dp else 78.dp)
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
                            when (painter.state.value) {
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
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    categoryTitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            SearchMetadataBadge(
                                it,
                                Color(0xFF34383F)
                            )
                        }

                    description?.let {
                        Text(
                            it,
                            color = Color(0xFFB8BCC4),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (isTv) 1 else 2,
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
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val artworkModel = remember(item.id, item.title, item.logo) {
        artworkRequest(context, item)
    }
    val shape = RoundedCornerShape(14.dp)
    val fallbackIcon = if (type == SearchContentType.SERIES) Icons.Default.VideoLibrary else Icons.Default.Movie

    Box {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .remoteFocusFrame(shape)
                .remoteCombinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                ),
            shape = shape,
            color = SearchSurface,
            border = BorderStroke(1.dp, SearchOutline)
        ) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(Color(0xFF292D34)),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.logo.isNullOrBlank()) {
                        Icon(
                            fallbackIcon,
                            null,
                            Modifier.size(if (isTv) 40.dp else 44.dp),
                            tint = Color.LightGray
                        )
                    } else {
                        SubcomposeAsyncImage(
                            artworkModel,
                            item.title,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        ) {
                            when (painter.state.value) {
                                is coil3.compose.AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                                else -> Icon(
                                    fallbackIcon,
                                    null,
                                    Modifier.size(if (isTv) 40.dp else 44.dp),
                                    tint = Color.LightGray
                                )
                            }
                        }
                    }
                }

                Column(
                    Modifier.padding(
                        horizontal = if (isTv) 9.dp else 11.dp,
                        vertical = if (isTv) 8.dp else 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = if (isTv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    categoryTitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = SearchMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        SearchFavoriteMenu(
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
private fun SearchFavoriteMenu(
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
        DropdownMenuItem(
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
