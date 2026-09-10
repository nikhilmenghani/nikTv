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

internal fun liveChannelSupportingText(
    item: MediaItem
): String? {
    item.liveProgramme?.let { programme ->
        val cleanProgramme =
            if (
                programme.title.trim().equals(
                    item.title.trim(),
                    ignoreCase = true
                )
            ) {
                programme.copy(title = "")
            } else {
                programme
            }

        return liveProgrammeSummary(cleanProgramme)
            .takeIf { it.isNotBlank() }
    }

    val description = item.description
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return description?.takeIf {
        !it.equals(item.title.trim(), ignoreCase = true)
    }
}

internal fun liveProgrammeSummary(
    programme: LiveProgramme
): String {
    val formatter = java.text.SimpleDateFormat(
        "h:mm a",
        java.util.Locale.getDefault()
    )

    val schedule = when {
        programme.startTimeMillis != null &&
                programme.endTimeMillis != null -> {

            "${formatter.format(java.util.Date(programme.startTimeMillis))}" +
                    "–${formatter.format(java.util.Date(programme.endTimeMillis))}"
        }

        programme.startTimeMillis != null -> {
            "From ${
                formatter.format(
                    java.util.Date(programme.startTimeMillis)
                )
            }"
        }

        else -> null
    }

    return listOfNotNull(
        programme.title.takeIf { it.isNotBlank() },
        schedule
    ).joinToString("  •  ")
}

@Composable
internal fun LiveProgrammeFooter(programme: LiveProgramme) {
    val formatter = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }
    val schedule = remember(programme.startTimeMillis, programme.endTimeMillis) {
        when {
            programme.startTimeMillis != null && programme.endTimeMillis != null ->
                "${formatter.format(java.util.Date(programme.startTimeMillis))}–${formatter.format(java.util.Date(programme.endTimeMillis))}"
            programme.startTimeMillis != null -> "From ${formatter.format(java.util.Date(programme.startTimeMillis))}"
            else -> null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            programme.title,
            color = Color(0xFFE6E6E6),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        schedule?.let { Text(it, color = Color(0xFFB3B3B3), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
internal fun ModernGrid(
    columns: Int,
    state: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(20.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(20.dp),
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
        content = content
    )
}

@Composable
internal fun LiveTvChannelCard(
    item: MediaItem,
    index: Int,
    columnCount: Int,
    firstChannelFocusRequester: FocusRequester,
    itemFocusRequester: FocusRequester? = null,
    columnSelectorFocusRequester: FocusRequester,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val isFirstChannel = index == 0

    // If there are 4 columns, indexes 0,1,2,3 are the first row.
    // If there are 2 columns, indexes 0,1 are the first row.
    val isFirstRow = index < columnCount

    val cardModifier = Modifier
        .then(
            when {
                itemFocusRequester != null ->
                    Modifier.focusRequester(
                        itemFocusRequester
                    )

                isFirstChannel ->
                    Modifier.focusRequester(
                        firstChannelFocusRequester
                    )

                else ->
                    Modifier
            }
        )
        .focusProperties {
            if (isFirstRow) {
                up = columnSelectorFocusRequester
            }
        }

    ModernMediaListCard(
        item = item,
        modifier = cardModifier,
        onClick = onPlay,
        isFavorite = isFavorite,
        toggleFavorite = onToggleFavorite,
        supportingText = liveChannelSupportingText(item),
        compact = true,
        channelStyle = true
    )
}

@Composable
internal fun LiveTvColumnSelector(
    selectedColumns: Int,
    maxColumns: Int,
    onColumnsChanged: (Int) -> Unit,
    selectorFocusRequester: FocusRequester,
    firstChannelFocusRequester: FocusRequester
) {
    SingleChoiceSegmentedButtonRow {
        (1..maxColumns).forEachIndexed { index, count ->

            val shape = uniformSegmentShape(
                index = index,
                count = maxColumns
            )

            SegmentedButton(
                selected = selectedColumns == count,
                onClick = {
                    onColumnsChanged(count)
                },
                shape = shape,
                modifier = Modifier
                    .then(
                        if (selectedColumns == count) {
                            Modifier.focusRequester(
                                selectorFocusRequester
                            )
                        } else {
                            Modifier
                        }
                    )
                    .focusProperties {
                        // Down from the column selector always enters
                        // the channel grid at the first channel.
                        down = firstChannelFocusRequester

                        // Let Compose find the category row above.
                        up = FocusRequester.Default
                    }
                    .remoteFocusFrame(shape)
            ) {
                Text("$count")
            }
        }
    }
}

@Composable
internal fun ModernSectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        if (compact) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                subtitle?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                action?.let { actions ->
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { actions() }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    subtitle?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.labelMedium) }
                }
                action?.invoke()
            }
        }
    }
}

@Composable
internal fun ModernSideRail(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    openHome: () -> Unit,
    openFavorites: () -> Unit,
    openOfflineDownloads: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(modifier, color = Color(0xFF070707), shadowElevation = 12.dp) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = if (expanded) 10.dp else 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClick = openProfileSwitcher)
                    .remoteFocusFrame(RoundedCornerShape(10.dp))
                    .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
            ) {
                Text(
                    state.savedProfile?.name.orEmpty().ifBlank { "Profile" },
                    color = Color.White,
                    style = if (expanded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            ModernRailButton(Icons.Default.Home, "Home", state.homeOpen && !state.favoritesOpen && !state.searchOpen && !state.settingsOpen, expanded, openHome)
            visibleCatalogTypes.forEach { type ->
                ModernRailButton(type.icon(), type.title, !state.homeOpen && !state.favoritesOpen && !state.searchOpen && !state.settingsOpen && state.selectedType == type, expanded) { selectType(type) }
            }
            ModernRailButton(Icons.Default.Favorite, "My List", state.favoritesOpen && !state.searchOpen && !state.settingsOpen, expanded, openFavorites)
            ModernRailButton(Icons.Default.DownloadDone, "Offline", state.offlineDownloadsOpen, expanded, openOfflineDownloads)
            Spacer(Modifier.height(12.dp))
            ModernRailButton(Icons.Default.Search, "Search", state.searchOpen, expanded, openSearch)
            ModernRailButton(Icons.Default.Settings, "Settings", state.settingsOpen, expanded, openSettings)
        }
    }
}

@Composable
internal fun ModernRailButton(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val railContext = LocalContext.current
    val railConfiguration = LocalConfiguration.current
    val isTv = railContext.isTvLikeDevice(railConfiguration)
    val focusHighlight = focused && isTv
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp).padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Tab; this.selected = selected }
            .then(
                if (focusHighlight) {
                    Modifier.shadow(
                        12.dp,
                        shape,
                        ambientColor = Color(0xFFE50914),
                        spotColor = Color(0xFFE50914)
                    )
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = when {
            focusHighlight -> Color(0xFF3A0A0D)
            selected -> Color(0xFF241012)
            else -> Color.Transparent
        },
        border = when {
            focusHighlight -> BorderStroke(3.dp, Color(0xFFFF3340))
            selected -> BorderStroke(1.dp, Color(0xFFE50914))
            else -> null
        }
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (expanded) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
        ) {
            Icon(icon, label, Modifier.size(24.dp), tint = if (focusHighlight || selected) Color.White else Color.Gray)
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(
                    label,
                    color = if (focusHighlight || selected) Color.White else Color.LightGray,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (focusHighlight || selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun YouTubeStyleTopBar(
    state: NikTvState,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF090909)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.size(34.dp))
        Spacer(Modifier.width(9.dp))
        Text("NikTV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        IconButton(onClick = openSearch) { Icon(Icons.Default.Search, "Search") }
        IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
        IconButton(onClick = openProfileSwitcher) {
            Icon(Icons.Default.AccountCircle, state.savedProfile?.name ?: "Profile")
        }
    }
}

@Composable
internal fun YouTubeStyleBottomBar(
    currentPage: MobileMainPage,
    selectPage: (MobileMainPage) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color(0xFF101216), tonalElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MobileMainPage.entries.forEach { page ->
                ExpressiveBottomNavigationItem(
                    icon = page.icon,
                    label = page.title,
                    selected = currentPage == page,
                    onClick = { selectPage(page) },
                    inactiveWidth = 48.dp
                )
            }
        }
    }
}

@Composable
internal fun ModernTopBar(
    state: NikTvState,
    home: Boolean,
    openHome: () -> Unit,
    selectType: (CatalogType) -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit
) {
    /*
     * MOBILE_NAVIGATION_TITLES_V12
     *
     * Every destination has visible text. Horizontal scrolling keeps the
     * labels readable instead of squeezing them on narrow phones.
     */
    Surface(color = Color(0xFF090909)) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item("mobile-profile") {
                TextButton(
                    onClick = openProfileSwitcher,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.AccountCircle,
                        null,
                        Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        state.savedProfile?.name.orEmpty()
                            .ifBlank { "Profile" },
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }

            item("mobile-home") {
                TextButton(
                    onClick = openHome,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.Home,
                        null,
                        Modifier.size(18.dp),
                        tint = if (home && !state.favoritesOpen && !state.searchOpen && !state.settingsOpen) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Home",
                        color = if (home && !state.favoritesOpen && !state.searchOpen && !state.settingsOpen) Color.White else Color.Gray
                    )
                }
            }

            visibleCatalogTypes.forEach { type ->
                item("mobile-${type.name}") {
                    val selected =
                        !home &&
                            !state.favoritesOpen &&
                            !state.searchOpen &&
                            !state.settingsOpen &&
                            state.selectedType == type

                    TextButton(
                        onClick = { selectType(type) },
                        modifier = Modifier.remoteFocusFrame(
                            RoundedCornerShape(10.dp)
                        )
                    ) {
                        Icon(
                            type.icon(),
                            null,
                            Modifier.size(18.dp),
                            tint = if (selected) Color.White else Color.Gray
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            type.title,
                            color = if (selected) Color.White else Color.Gray,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            item("mobile-my-list") {
                val selected = state.favoritesOpen && !state.searchOpen && !state.settingsOpen
                TextButton(
                    onClick = openFavorites,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.FavoriteBorder,
                        null,
                        Modifier.size(18.dp),
                        tint = if (selected) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "My List",
                        color = if (selected) Color.White else Color.Gray
                    )
                }
            }

            item("mobile-search") {
                val selected = state.searchOpen
                TextButton(
                    onClick = openSearch,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.Search,
                        null,
                        Modifier.size(18.dp),
                        tint = if (selected) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Search",
                        color = if (selected) Color.White else Color.Gray
                    )
                }
            }

            item("mobile-settings") {
                val selected = state.settingsOpen
                TextButton(
                    onClick = openSettings,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.Settings,
                        null,
                        Modifier.size(18.dp),
                        tint = if (selected) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Settings",
                        color = if (selected) Color.White else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
internal fun LiveTvPreviewPlaceholder(categoryTitle: String?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top
                )
            )
            .padding(
                horizontal = 20.dp,
                vertical = 8.dp
            ),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111111)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 12.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.LiveTv,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color(0xFFE50914)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Choose a channel to start watching",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    categoryTitle ?: "Live TV",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
