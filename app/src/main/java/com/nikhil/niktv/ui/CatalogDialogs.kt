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
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.window.DialogWindowProvider
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

private val CategoryPickerBackground = Color(0xFF0B0B0F)
private val CategoryPickerSurface = Color(0xFF151720)
private val CategoryPickerRaised = Color(0xFF1B1E28)
private val CategoryPickerOutline = Color(0xFF2A2D36)
private val CategoryPickerMuted = Color(0xFFA7ABB5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryManagerDialog(
    state: NikTvState,
    close: () -> Unit,
    applyFilters: (Map<CatalogType, List<String>>) -> Unit
) {
    val type = state.categoryManagerType
    val categoryAccent = when (type) {
        CatalogType.LIVE_TV -> Color(0xFFE65D68)
        CatalogType.MOVIES -> Color(0xFF55B8FF)
        CatalogType.SERIES -> Color(0xFF9A80FF)
        CatalogType.RADIO -> Color(0xFF7C8CFF)
    }
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
    fun requestCategoryFocus(targetIndex: Int) {
        val category = filteredRaw.getOrNull(targetIndex) ?: return
        val requester = categoryRequesters.getValue(category.id)
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
        scope.launch {
            gridState.scrollToItem(targetIndex)
            repeat(5) { attempt ->
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
                delay(30L * (attempt + 1))
            }
        }
    }
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
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            onDispose { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) }
        }
        val categoryPanelModifier = if (categoryIsTv) {
            Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.96f).widthIn(max = 1280.dp)
        } else {
            Modifier.fillMaxSize()
        }
        Surface(
            modifier = categoryPanelModifier,
            shape = if (categoryIsTv) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp),
            color = CategoryPickerBackground,
            tonalElevation = if (categoryIsTv) 6.dp else 0.dp
        ) {
            Column(
                Modifier.fillMaxSize()
                    .then(if (!categoryIsTv) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                    .then(if (!categoryIsTv) Modifier.imePadding() else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (categoryIsCompact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = categoryAccent.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, categoryAccent.copy(alpha = 0.42f))
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                null,
                                Modifier.padding(10.dp).size(22.dp),
                                tint = categoryAccent
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
                                "Choose the categories shown in NikTV",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = close,
                            modifier = Modifier
                                .size(44.dp)
                                .focusRequester(closeRequester)
                                .focusProperties {
                                    left = applyRequester
                                    down = searchRequester
                                }
                                .onFocusChanged { closeFocused = it.isFocused }
                                .background(
                                    if (closeFocused) categoryAccent.copy(alpha = 0.18f) else Color.Transparent,
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
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    searchRequester.requestFocus()
                                    true
                                } else false
                            }
                            .focusProperties {
                                right = closeRequester
                                down = searchRequester
                            }
                            .onFocusChanged { applyFocused = it.isFocused },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = categoryAccent,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.DoneAll, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = categoryAccent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, categoryAccent.copy(alpha = 0.42f))
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            null,
                            Modifier.padding(12.dp).size(24.dp),
                            tint = categoryAccent
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${type.title} Categories", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Choose the categories shown in NikTV",
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
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    (firstCategoryRequester ?: searchRequester).requestFocus()
                                    true
                                } else false
                            }
                            .focusProperties {
                                left = focusedCategoryId
                                    ?.let(categoryRequesters::get)
                                    ?: deselectAllRequester
                                right = closeRequester
                                down = searchRequester
                            }
                            .onFocusChanged { applyFocused = it.isFocused }
                            .border(
                                if (applyFocused) 3.dp else 0.dp,
                                if (applyFocused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = categoryAccent, contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.DoneAll, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = close,
                        modifier = Modifier
                            .size(48.dp)
                            .focusRequester(closeRequester)
                            .focusProperties {
                                left = applyRequester
                                down = searchRequester
                            }
                            .onFocusChanged { closeFocused = it.isFocused }
                            .background(
                                if (closeFocused) categoryAccent.copy(alpha = 0.18f) else Color.Transparent,
                                CircleShape
                            )
                    ) { Icon(Icons.Default.Close, "Close") }
                }

                Spacer(Modifier.height(8.dp))

                val enabledCount = currentEnabledSet.size
                val totalCount = raw.size
                val selectionLimitReached = enabledCount >= 10

                if (categoryIsCompact) {
                    CategoryManagerSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        isTv = categoryIsTv,
                        editing = searchEditing,
                        onEditingChange = { searchEditing = it },
                        focused = searchFocused,
                        onFocusedChange = { searchFocused = it },
                        accent = categoryAccent,
                        searchRequester = searchRequester,
                        upRequester = applyRequester,
                        downRequester = firstCategoryRequester ?: applyRequester,
                        rightRequester = if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) selectMatchingRequester else selectAllRequester,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) {
                            CategoryDialogActionButton(
                                text = "Matching",
                                accent = categoryAccent,
                                onClick = { updateCurrentSelection((currentEnabledSet + filteredRaw.map { it.id }).take(10).toSet()) },
                                modifier = Modifier.weight(1f).focusRequester(selectMatchingRequester).focusProperties {
                                    left = searchRequester; right = selectAllRequester; up = applyRequester; down = firstCategoryRequester ?: applyRequester
                                }
                            )
                        }
                        CategoryDialogActionButton(
                            text = "First 10",
                            accent = categoryAccent,
                            onClick = { updateCurrentSelection(raw.take(10).map { it.id }.toSet()) },
                            modifier = Modifier.weight(1f).focusRequester(selectAllRequester).focusProperties {
                                left = if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) selectMatchingRequester else searchRequester
                                right = deselectAllRequester; up = applyRequester; down = firstCategoryRequester ?: applyRequester
                            }
                        )
                        CategoryDialogActionButton(
                            text = "Clear",
                            accent = categoryAccent,
                            onClick = { updateCurrentSelection(emptySet()) },
                            modifier = Modifier.weight(1f).focusRequester(deselectAllRequester).focusProperties {
                                left = selectAllRequester; right = applyRequester; up = applyRequester; down = firstCategoryRequester ?: applyRequester
                            }
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryManagerSearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            isTv = categoryIsTv,
                            editing = searchEditing,
                            onEditingChange = { searchEditing = it },
                            focused = searchFocused,
                            onFocusedChange = { searchFocused = it },
                            accent = categoryAccent,
                            searchRequester = searchRequester,
                            upRequester = applyRequester,
                            downRequester = firstCategoryRequester ?: applyRequester,
                            rightRequester = if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) selectMatchingRequester else selectAllRequester,
                            modifier = Modifier.weight(1.7f)
                        )
                        if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) {
                            CategoryDialogActionButton(
                                text = "Matching",
                                accent = categoryAccent,
                                onClick = { updateCurrentSelection((currentEnabledSet + filteredRaw.map { it.id }).take(10).toSet()) },
                                modifier = Modifier.weight(0.8f).focusRequester(selectMatchingRequester).focusProperties {
                                    left = searchRequester; right = selectAllRequester; up = applyRequester; down = firstCategoryRequester ?: applyRequester
                                }
                            )
                        }
                        CategoryDialogActionButton(
                            text = "First 10",
                            accent = categoryAccent,
                            onClick = { updateCurrentSelection(raw.take(10).map { it.id }.toSet()) },
                            modifier = Modifier.weight(0.75f).focusRequester(selectAllRequester).focusProperties {
                                left = if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) selectMatchingRequester else searchRequester
                                right = deselectAllRequester; up = applyRequester; down = firstCategoryRequester ?: applyRequester
                            }
                        )
                        CategoryDialogActionButton(
                            text = "Clear",
                            accent = categoryAccent,
                            onClick = { updateCurrentSelection(emptySet()) },
                            modifier = Modifier.weight(0.65f).focusRequester(deselectAllRequester).focusProperties {
                                left = selectAllRequester; right = applyRequester; up = applyRequester; down = firstCategoryRequester ?: applyRequester
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (selectionLimitReached) categoryAccent.copy(alpha = 0.18f) else CategoryPickerRaised,
                        border = BorderStroke(1.dp, if (selectionLimitReached) categoryAccent.copy(alpha = 0.72f) else CategoryPickerOutline)
                    ) {
                        Text(
                            "$enabledCount / 10 selected",
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selectionLimitReached) Color.White else CategoryPickerMuted
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            selectionLimitReached -> "Deselect one category to choose another"
                            searchQuery.isNotBlank() -> "${filteredRaw.size} matching · $totalCount available"
                            else -> "$totalCount categories available"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = CategoryPickerMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = CategoryPickerOutline
                )

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
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
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
                                shape = RoundedCornerShape(14.dp),
                                color = when {
                                    rowFocused && selected -> categoryAccent.copy(alpha = 0.26f)
                                    rowFocused -> CategoryPickerRaised
                                    selected -> categoryAccent.copy(alpha = 0.16f)
                                    selectionLimitReached -> CategoryPickerSurface.copy(alpha = 0.55f)
                                    else -> CategoryPickerSurface
                                },
                                border = when {
                                    rowFocused -> BorderStroke(3.dp, Color.White)
                                    selected -> BorderStroke(1.dp, categoryAccent.copy(alpha = 0.88f))
                                    else -> BorderStroke(1.dp, CategoryPickerOutline)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .focusRequester(rowRequester)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                val targetIndex = index - categoryColumns
                                                if (targetIndex < 0) {
                                                    searchRequester.requestFocus()
                                                } else {
                                                    requestCategoryFocus(targetIndex)
                                                }
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                val targetIndex = index + categoryColumns
                                                if (targetIndex >= filteredRaw.size) {
                                                    applyRequester.requestFocus()
                                                } else {
                                                    requestCategoryFocus(targetIndex)
                                                }
                                                true
                                            }
                                            Key.DirectionLeft -> {
                                                if (index % categoryColumns > 0) requestCategoryFocus(index - 1)
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                val targetIndex = index + 1
                                                if (index % categoryColumns < categoryColumns - 1 && targetIndex < filteredRaw.size) {
                                                    requestCategoryFocus(targetIndex)
                                                } else {
                                                    applyRequester.requestFocus()
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .focusProperties {
                                        if (index < categoryColumns) up = searchRequester
                                        if (index >= filteredRaw.size - categoryColumns) down = applyRequester
                                        if ((index + 1) % categoryColumns == 0 || index == filteredRaw.lastIndex) right = applyRequester
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
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = category.title,
                                        style = if (categoryIsTv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = when {
                                            selected -> Color.White
                                            selectionLimitReached -> Color(0xFF777D88)
                                            else -> Color(0xFFE7E9EF)
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).then(
                                            if (rowFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                                        )
                                    )
                                    if (selected) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(Modifier.size(26.dp), shape = CircleShape, color = categoryAccent) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp), tint = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }



            }

        }
    }
}
@Composable
private fun CategoryManagerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    isTv: Boolean,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    focused: Boolean,
    onFocusedChange: (Boolean) -> Unit,
    accent: Color,
    searchRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    rightRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    fun activate() {
        if (editing) return
        onEditingChange(true)
        scope.launch {
            withFrameNanos { }
            searchRequester.requestFocus()
            keyboard?.show()
        }
    }
    Box(modifier) {
        Surface(
            Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            color = CategoryPickerSurface,
            border = BorderStroke(if (editing || focused) 3.dp else 1.dp, if (editing || focused) accent else CategoryPickerOutline)
        ) {
            Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = if (editing || focused) accent else CategoryPickerMuted)
                Spacer(Modifier.width(7.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchRequester)
                        .focusProperties { up = upRequester; down = downRequester; right = rightRequester }
                        .onFocusChanged {
                            onFocusedChange(it.isFocused)
                            if (!it.isFocused && editing) { onEditingChange(false); keyboard?.hide() }
                        }
                        .onPreviewKeyEvent { event ->
                            if (!editing && event.type == KeyEventType.KeyUp && event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) { activate(); true } else false
                        },
                    singleLine = true,
                    readOnly = !editing,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onEditingChange(false); keyboard?.hide() }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search categories", color = CategoryPickerMuted, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            inner()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.focusProperties { canFocus = false }) {
                        Icon(Icons.Default.Close, "Clear search", tint = CategoryPickerMuted)
                    }
                }
            }
        }
        if (!editing) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(editing) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed && !it.previousPressed }) {
                                    activate()
                                }
                            }
                        }
                    }
            )
        }
    }
}

@Composable
internal fun CategoryDialogActionButton(
    text: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(50.dp)
            .onFocusChanged { focused = it.isFocused },
        border = BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) accent else CategoryPickerOutline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor =
                if (focused) accent.copy(alpha = 0.18f)
                else CategoryPickerSurface,
            contentColor = if (focused) Color.White else CategoryPickerMuted
        ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
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
