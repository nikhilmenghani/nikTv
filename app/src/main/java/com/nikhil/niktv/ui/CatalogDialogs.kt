package com.nikhil.niktv.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.R
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.data.cast4kLegacyDeviceIdentity
import com.nikhil.niktv.model.*
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.DownloadedApkCleanup
import com.nikhil.niktv.update.formatDownloadBytes
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.OfflineDownloadStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun TmdbHomeSectionsDialog(
    selected: List<TmdbHomeSection>,
    surface: DashboardSurface,
    close: () -> Unit,
    save: (List<TmdbHomeSection>) -> Unit
) {
    var choices by remember(selected) { mutableStateOf(selected.toSet()) }
    val available = remember(surface) { tmdbSectionsForSurface(surface) }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF181818),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 760.dp)
        ) {
            Column {
                Column(Modifier.weight(1f).padding(24.dp)) {
                    Text("${surface.displayTitle()} · TMDB sections", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Only this screen is affected.", color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(available, key = { it.name }) { section ->
                            val checked = section in choices
                            Surface(
                                onClick = { choices = if (checked) choices - section else choices + section },
                                modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                color = if (checked) Color(0xFF351416) else Color(0xFF181818)
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(section.title, Modifier.weight(1f))
                                    Text(if (section.series) "Series" else "Movies", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { choices = emptySet() }, modifier = Modifier.remoteFocusFrame()) { Text("Clear selections") }
                    TextButton(onClick = close, modifier = Modifier.remoteFocusFrame()) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { save(available.filter { it in choices }) }, modifier = Modifier.remoteFocusFrame()) {
                        Text("Apply & Close")
                    }
                }
            }
        }
    }
}

internal fun tmdbSectionsForSurface(surface: DashboardSurface): List<TmdbHomeSection> =
    when (surface) {
        DashboardSurface.HOME -> TmdbHomeSection.entries
        DashboardSurface.MOVIES -> TmdbHomeSection.entries.filterNot { it.series }
        DashboardSurface.SERIES -> TmdbHomeSection.entries.filter { it.series }
        DashboardSurface.LIVE_TV -> emptyList()
    }

internal fun DashboardSurface.displayTitle() = when (this) {
    DashboardSurface.HOME -> "Home"
    DashboardSurface.LIVE_TV -> "Live TV"
    DashboardSurface.MOVIES -> "Movies"
    DashboardSurface.SERIES -> "Series"
}

@Composable
internal fun ProjectCardConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    close: () -> Unit,
    confirm: () -> Unit
) {
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF181818), modifier = Modifier.widthIn(max = 620.dp)) {
            Column {
                Column(Modifier.padding(24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = Color.LightGray)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close, modifier = Modifier.remoteFocusFrame()) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = confirm, modifier = Modifier.remoteFocusFrame(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryManagerDialog(
    state: NikTvState,
    close: () -> Unit,
    applyFilters: (Map<CatalogType, List<String>>) -> Unit
) {
    val type = state.categoryManagerType
    val profile = state.savedProfile
    val profileKey = profile?.cacheKey()
    val raw = state.rawCategoriesByType[type].orEmpty().ifEmpty { if (state.selectedType == type) state.categories else emptyList() }
    val filterKey = "$profileKey|${type.name}"
    val enabledIds = state.categoryFilters[filterKey]

    /*
     * TAB_SCOPED_CATEGORY_FILTERS_V35
     *
     * This editor owns exactly one CatalogType for its lifetime. A Movie
     * dialog cannot mutate Series or Live TV selections, and vice versa.
     */
    var currentEnabledSet by remember(profileKey, type, enabledIds, raw) {
        mutableStateOf(
            enabledIds?.take(10)?.toSet()
                ?: raw.take(10).map { it.id }.toSet()
        )
    }
    val updateCurrentSelection: (Set<String>) -> Unit = { selection ->
        currentEnabledSet = selection.take(10).toSet()
    }
    var searchQuery by rememberSaveable(type) { mutableStateOf("") }
    val filteredRaw = remember(raw, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) raw else raw.filter { it.title.matchesTitleKeywords(query) }
            .sortedByDescending { it.title.titleKeywordScore(query) }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val categoryConfiguration = LocalConfiguration.current
    val categoryContext = LocalContext.current
    val categoryIsTv = categoryContext.isTvLikeDevice(categoryConfiguration)
    val categoryIsCompact = !categoryIsTv && categoryConfiguration.screenWidthDp < 600
    val categoryColumns = when {
        categoryIsTv || categoryConfiguration.screenWidthDp >= 840 -> 4
        categoryConfiguration.screenWidthDp >= 600 -> 2
        else -> 1
    }
    val closeRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val selectAllRequester = remember { FocusRequester() }
    val deselectAllRequester = remember { FocusRequester() }
    val selectMatchingRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val categoryRequesters = remember { mutableMapOf<String, FocusRequester>() }
    filteredRaw.forEach { categoryRequesters.getOrPut(it.id) { FocusRequester() } }
    val firstCategoryRequester = filteredRaw.firstOrNull()?.let { categoryRequesters[it.id] }
    val lastCategoryRequester = filteredRaw.lastOrNull()?.let { categoryRequesters[it.id] }
    var searchEditing by remember(type) { mutableStateOf(false) }
    var searchFocused by remember(type) { mutableStateOf(false) }
    var closeFocused by remember { mutableStateOf(false) }
    var applyFocused by remember { mutableStateOf(false) }
    var focusedCategoryId by remember { mutableStateOf<String?>(null) }
    val activateSearch: () -> Unit = {
        if (!searchEditing) {
            searchEditing = true
            scope.launch {
                withFrameNanos { }
                searchRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    LaunchedEffect(type) {
        withFrameNanos { }
        // Never make the text editor the dialog's implicit entry target.
        // Empty category lists start on Apply instead.
        (firstCategoryRequester ?: applyRequester).requestFocus()
    }
    LaunchedEffect(type, searchQuery) {
        if (filteredRaw.isNotEmpty()) gridState.scrollToItem(0)
    }
    BackHandler(searchEditing) {
        searchEditing = false
        keyboardController?.hide()
        searchRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = {
            if (searchEditing) {
                searchEditing = false
                keyboardController?.hide()
            } else {
                close()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val categoryPanelModifier = if (categoryIsTv) {
            Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.96f).widthIn(max = 1280.dp)
        } else {
            Modifier.fillMaxSize()
        }
        Surface(
            modifier = categoryPanelModifier,
            shape = if (categoryIsTv) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp),
            color = Color(0xFF090B10),
            tonalElevation = if (categoryIsTv) 6.dp else 0.dp
        ) {
            Column(
                Modifier.fillMaxSize()
                    .then(if (!categoryIsTv) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (categoryIsCompact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF2B1014),
                            border = BorderStroke(1.dp, Color(0x66E50914))
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                null,
                                Modifier.padding(10.dp).size(22.dp),
                                tint = Color(0xFFFF3340)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${type.title} Categories",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Choose up to 10 categories",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = close,
                            modifier = Modifier
                                .size(44.dp)
                                .focusRequester(closeRequester)
                                .onFocusChanged { closeFocused = it.isFocused }
                                .background(
                                    if (closeFocused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    CircleShape
                                )
                        ) { Icon(Icons.Default.Close, "Close") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            applyFilters(mapOf(type to currentEnabledSet.toList()))
                            close()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .focusRequester(applyRequester)
                            .onFocusChanged { applyFocused = it.isFocused },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE50914),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.DoneAll, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Apply & Close", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF2B1014),
                        border = BorderStroke(1.dp, Color(0x66E50914))
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            null,
                            Modifier.padding(12.dp).size(24.dp),
                            tint = Color(0xFFFF3340)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${type.title} Categories", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Choose up to 10 · press → at the end of any row to save",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            applyFilters(mapOf(type to currentEnabledSet.toList()))
                            close()
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .focusRequester(applyRequester)
                            .focusProperties {
                                left = focusedCategoryId
                                    ?.let(categoryRequesters::get)
                                    ?: firstCategoryRequester
                                    ?: searchRequester
                                right = closeRequester
                                down = firstCategoryRequester ?: searchRequester
                            }
                            .onFocusChanged { applyFocused = it.isFocused }
                            .border(
                                if (applyFocused) 3.dp else 0.dp,
                                if (applyFocused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.DoneAll, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Apply & Close", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = close,
                        modifier = Modifier
                            .size(48.dp)
                            .focusRequester(closeRequester)
                            .focusProperties {
                                left = applyRequester
                                down = firstCategoryRequester ?: searchRequester
                            }
                            .onFocusChanged { closeFocused = it.isFocused }
                            .background(
                                if (closeFocused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                CircleShape
                            )
                    ) { Icon(Icons.Default.Close, "Close") }
                }

                Spacer(Modifier.height(8.dp))

                val enabledCount = currentEnabledSet.size
                val totalCount = raw.size
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "${filteredRaw.size} matching · $enabledCount of 10 selected ($totalCount available)"
                        } else {
                            "$enabledCount of 10 ${type.title} categories selected · $totalCount available"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) {
                        TextButton(onClick = {
                            updateCurrentSelection((currentEnabledSet + filteredRaw.map { it.id }).take(10).toSet())
                        }, modifier = Modifier.heightIn(min = 64.dp).focusRequester(selectMatchingRequester)) { Text("Select matching") }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 3.dp))

                if (raw.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Loading categories…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (filteredRaw.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No categories match “${searchQuery.trim()}”", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        modifier = Modifier.weight(1f),
                        columns = GridCells.Fixed(categoryColumns),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        itemsIndexed(filteredRaw, key = { _, category -> category.id }) { index, category ->
                            val selected = category.id in currentEnabledSet
                            val selectionLimitReached = !selected && currentEnabledSet.size >= 10
                            val rowRequester = categoryRequesters.getValue(category.id)
                            val rowFocused = focusedCategoryId == category.id
                            Surface(
                                onClick = {
                                    if (!selectionLimitReached) {
                                        updateCurrentSelection(
                                            if (selected) currentEnabledSet - category.id
                                            else currentEnabledSet + category.id
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    rowFocused -> Color(0xFF292929)
                                    selected -> Color(0xFF351416)
                                    selectionLimitReached -> Color(0xFF111111)
                                    else -> Color(0xFF171717)
                                },
                                border = when {
                                    rowFocused -> BorderStroke(4.dp, Color(0xFFFF3340))
                                    selected -> BorderStroke(1.dp, Color(0xFFE50914).copy(alpha = 0.75f))
                                    else -> BorderStroke(1.dp, Color(0xFF303030))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .focusRequester(rowRequester)
                                    .focusProperties {
                                        if (index < categoryColumns) up = applyRequester
                                        if (index >= filteredRaw.size - categoryColumns) down = searchRequester
                                        if ((index + 1) % categoryColumns == 0 || index == filteredRaw.lastIndex) {
                                            right = applyRequester
                                        }
                                    }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            focusedCategoryId = category.id
                                            scope.launch {
                                                if (gridState.layoutInfo.visibleItemsInfo.none { item -> item.index == index }) {
                                                    gridState.animateScrollToItem(index)
                                                }
                                            }
                                        } else if (focusedCategoryId == category.id) {
                                            focusedCategoryId = null
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(20.dp).clip(CircleShape)
                                            .background(if (selected) Color(0xFFE50914) else Color.Transparent)
                                            .border(1.dp, if (selected) Color(0xFFE50914) else Color(0xFF777777), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color.White)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = category.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth().then(
                                                if (rowFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                                            )
                                        )
                                        Text(
                                            when {
                                                selected -> "Included · ${currentEnabledSet.indexOf(category.id) + 1} of ${currentEnabledSet.size}"
                                                selectionLimitReached -> "Selection limit reached"
                                                else -> "Not included"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) Color(0xFFFF8A91) else Color(0xFF8D929B),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1.55f)) {
                        Surface(
                            Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black,
                            border = BorderStroke(
                                if (searchEditing || searchFocused) 4.dp else 1.dp,
                                if (searchEditing || searchFocused) Color(0xFFFF3340) else Color(0xFF666666)
                            )
                        ) {
                            Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Color.LightGray)
                                Spacer(Modifier.width(6.dp))
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.weight(1f)
                                        .focusRequester(searchRequester)
                                        .focusProperties {
                                            up = lastCategoryRequester ?: applyRequester
                                            right = selectAllRequester
                                            down = applyRequester
                                        }
                                        .onFocusChanged {
                                            if (it.isFocused && !categoryIsTv) searchEditing = true
                                            searchFocused = it.isFocused
                                            if (!it.isFocused && searchEditing) {
                                                searchEditing = false
                                                keyboardController?.hide()
                                            }
                                        }
                                        .onPreviewKeyEvent { event ->
                                            if (!searchEditing && event.type == KeyEventType.KeyUp &&
                                                event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                                            ) {
                                                activateSearch(); true
                                            } else false
                                        },
                                    singleLine = true,
                                    readOnly = categoryIsTv && !searchEditing,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                    cursorBrush = SolidColor(Color(0xFFE50914)),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        searchEditing = false
                                        keyboardController?.hide()
                                    }),
                                    decorationBox = { inner ->
                                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                            if (searchQuery.isEmpty()) Text("Search categories", color = Color.Gray, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                            inner()
                                        }
                                    }
                                )
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.focusProperties { canFocus = false }) {
                                        Icon(Icons.Default.Close, "Clear search", tint = Color.LightGray)
                                    }
                                }
                            }
                        }
                        if (categoryIsTv && !searchEditing) {
                            Box(Modifier.matchParentSize().pointerInput(type) { detectTapGestures { activateSearch() } })
                        }
                    }
                    CategoryDialogActionButton(
                        text = "Select first 10",
                        onClick = { updateCurrentSelection(raw.take(10).map { it.id }.toSet()) },
                        modifier = Modifier.weight(1f).focusRequester(selectAllRequester).focusProperties {
                            left = searchRequester
                            right = deselectAllRequester
                            up = lastCategoryRequester ?: applyRequester
                            down = applyRequester
                        }
                    )
                    CategoryDialogActionButton(
                        text = "Deselect All",
                        onClick = { updateCurrentSelection(emptySet()) },
                        modifier = Modifier.weight(1f).focusRequester(deselectAllRequester).focusProperties {
                            left = selectAllRequester
                            right = FocusRequester.Cancel
                            up = lastCategoryRequester ?: applyRequester
                            down = applyRequester
                        }
                    )
                }

            }

        }
    }
}

@Composable
internal fun CategoryDialogActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .onFocusChanged { focused = it.isFocused },
        border = BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (focused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun SeriesHeroActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val showFocusHint = context.usesRemoteNavigation(configuration) && focused

    Box(
        modifier = Modifier
            .wrapContentSize()
            .zIndex(if (showFocusHint) 1f else 0f)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .remoteFocusFrame(CircleShape)
        ) {
            Icon(icon, description, tint = tint)
        }

        if (showFocusHint) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .wrapContentWidth(unbounded = true)
                    .offset(y = 52.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xF2111111),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            ) {
                Text(
                    text = description,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}
