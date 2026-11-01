package com.nikhil.niktv.discovery.components

import com.nikhil.niktv.ui.*

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
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.InputMode
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
internal fun DiscoveryFavoritesContent(
    state: NikTvState,
    openFavorite: (FavoriteItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    refreshLiveProgramme: (MediaItem) -> Unit = {},
    heroPresentation: (com.nikhil.niktv.discovery.DiscoveryCard?) -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isModernTileTv(configuration)
    val profileKey = state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey().orEmpty()
    val availability = rememberIptvAvailability(profileKey)
    val availabilityFocus = remember(profileKey) { FocusRequester() }
    val availabilityScope = rememberCoroutineScope()
    val entryFocusRequester = remember(profileKey) { FocusRequester() }
    val gridState = rememberLazyGridState()
    val posterColumns = modernPosterColumns(configuration, isTv)
    val phoneLibrary = com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, isTv)
    val discoveryCards = !phoneLibrary
    val groups = FavoriteKind.entries.mapNotNull { kind ->
        state.favorites.filter { it.kind == kind && (kind != FavoriteKind.CHANNEL ||
            availability.filter.includes(availability.flags[it.media.id])) }.takeIf { it.isNotEmpty() }?.let { kind to it }
    }
    val firstFavoriteKey = groups.firstOrNull()?.second?.firstOrNull()?.key
    LazyVerticalGrid(
        // Match the dashboard's Continue Watching geometry. The old adaptive
        // 180dp rule collapsed compact phones to one nearly full-width poster.
        columns = if (discoveryCards) GridCells.FixedSize((if (configuration.smallestScreenWidthDp < 600 && !isTv)
            com.nikhil.niktv.discovery.phoneDiscoveryPosterWidth(configuration.screenWidthDp)
            else com.nikhil.niktv.discovery.discoveryPosterWidth(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())).dp)
            else GridCells.Fixed(posterColumns),
        state = gridState,
        modifier = Modifier.fillMaxSize().background(Color(0xFF090909)),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (phoneLibrary) item("favorites-hero", span = { GridItemSpan(maxLineSpan) }) {
            com.nikhil.niktv.discovery.DiscoveryFavoritesHero(state, gridState.isScrollInProgress || gridState.firstVisibleItemIndex > 0,
                openFavorite, toggleFavorite, heroPresentation)
        }
        if (!phoneLibrary) item("favorites-top", span = { GridItemSpan(maxLineSpan) }) {
            ModernSectionHeader("My List", "Saved for this profile")
        }
        if (state.favorites.any { it.kind == FavoriteKind.CHANNEL }) item("channel-availability", span = { GridItemSpan(maxLineSpan) }) {
            IptvAvailabilityControl(availability, Modifier.padding(horizontal = 12.dp).focusRequester(availabilityFocus))
        }
        if (groups.isEmpty()) item("favorites-empty", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().height(360.dp)
                .focusRequester(entryFocusRequester).focusable(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.FavoriteBorder, null, Modifier.size(48.dp), tint = Color.Gray)
                    Text(if (state.favorites.isNotEmpty()) "No favorites match this availability filter" else "No favorites yet", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("Press and hold any media item to add it here", color = Color.Gray)
                }
            }
        } else {
            groups.forEach { (kind, favorites) ->
                item("header-${kind.name}", span = { GridItemSpan(maxLineSpan) }) {
                    if (phoneLibrary) Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(kind.sectionTitle(), color = Color.White, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("${favorites.size} saved", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    } else ModernSectionHeader(kind.sectionTitle(), "${favorites.size} saved")
                }
                items(
                    items = favorites,
                    key = { it.key },
                    span = { if (discoveryCards && it.kind in setOf(FavoriteKind.CHANNEL, FavoriteKind.EPISODE)) GridItemSpan(minOf(2, maxLineSpan)) else GridItemSpan(1) }
                ) { favorite ->
                    ModernFavoriteCard(
                        favorite = favorite,
                        aspectRatio = 16f / 9f,
                        open = { openFavorite(favorite) },
                        remove = { toggleFavorite(favorite) },
                        modifier = if (favorite.key == firstFavoriteKey) Modifier.focusRequester(entryFocusRequester) else Modifier,
                        discoveryState = state.takeIf { discoveryCards || kind == FavoriteKind.CHANNEL }, refreshLiveProgramme = refreshLiveProgramme,
                        onChannelAvailabilityChanged = { if (availability.filter != IptvAvailabilityFilter.ALL) availabilityScope.launch {
                            gridState.scrollToItem(0); withFrameNanos { }; runCatching { availabilityFocus.requestFocus() }
                        } }
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModernFavoriteCard(
    favorite: FavoriteItem,
    aspectRatio: Float,
    open: () -> Unit,
    remove: () -> Unit,
    modifier: Modifier = Modifier,
    discoveryState: NikTvState? = null,
    refreshLiveProgramme: (MediaItem) -> Unit = {},
    onChannelAvailabilityChanged: () -> Unit = {}
) {
    var removalConfirmationOpen by rememberSaveable(favorite.key) {
        mutableStateOf(false)
    }
    val cancelRemovalRequester = remember(favorite.key) { FocusRequester() }

    LaunchedEffect(removalConfirmationOpen) {
        if (removalConfirmationOpen) {
            withFrameNanos { }
            runCatching { cancelRemovalRequester.requestFocus() }
        }
    }

    if (discoveryState != null && favorite.kind in setOf(FavoriteKind.MOVIE, FavoriteKind.SERIES)) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val context = LocalContext.current
            val locked = (favorite.media.isAdult || com.nikhil.niktv.data.adultContentLabel(favorite.media.title)) &&
                com.nikhil.niktv.data.ParentalControls.enabled(context) && !com.nikhil.niktv.data.ParentalControls.unlocked(context)
            val progress = com.nikhil.niktv.discovery.rememberDiscoveryProgressLookup(favorite.profileKey, discoveryState.recentlyPlayed, discoveryState.playbackProgress)
            val type = if (favorite.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES
            com.nikhil.niktv.discovery.LibrarySavedPoster(com.nikhil.niktv.discovery.LibraryEntry(favorite.media, favorite.kind, favorite = favorite),
                discoveryState, maxWidth, locked, progress?.provider(type, favorite.media.id), open,
                { removalConfirmationOpen = true }, {}, com.nikhil.niktv.discovery.discoveryPosterFocusInset(maxWidth.value).dp)
        }
    } else if (favorite.kind == FavoriteKind.MOVIE || favorite.kind == FavoriteKind.SERIES) {
        ModernCompactMediaCard(
            item = favorite.media,
            subtitle = listOfNotNull(
                favorite.kind.mediaTypeLabel(),
                favorite.categoryTitle?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            onClick = open,
            isFavorite = true,
            onFavorite = { removalConfirmationOpen = true },
            sourceLabel = favorite.source.takeIf { it == FavoriteSource.TMDB }?.name,
            modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
        )
    } else if (discoveryState != null && favorite.kind == FavoriteKind.CHANNEL) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val profile = discoveryState.session?.profile?.cacheKey() ?: discoveryState.savedProfile?.cacheKey().orEmpty()
        val item = discoveryState.withCachedLiveGuide(profile, favorite.media)
        val now by produceState(System.currentTimeMillis(), item.id) {
            while (true) { delay(30_000L); value = System.currentTimeMillis() }
        }
        ModernLiveChannelTile(item, { refreshLiveProgramme(item) },
            availabilityProfile = profile,
            onAvailabilityChanged = onChannelAvailabilityChanged,
            xtream = discoveryState.savedProfile?.portalType == PortalType.XTREAM,
            categoryTitle = favorite.categoryTitle.orEmpty(), guideNow = now,
            programmeLoading = item.id in discoveryState.liveGuideLoadingIds,
            programmeWaitingUntil = discoveryState.liveGuideWaitingUntilById[item.id], programmeStatusNow = now,
            showQualityBadge = true, isFavorite = true, onFavorite = { removalConfirmationOpen = true },
            isPinned = false, onTogglePin = null, onClick = open, modifier = modifier.padding(4.dp),
            isTv = context.isModernTileTv(configuration), compact = true)
    } else ModernPosterCard(
        item = favorite.media,
        aspectRatio = aspectRatio,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        onClick = open,
        onLongClick = { removalConfirmationOpen = true },
        titleMaxLines = Int.MAX_VALUE,
        focusedScale = 1f
    ) {
        Text(
            listOfNotNull(
                favorite.kind.mediaTypeLabel(),
                favorite.categoryTitle?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            color = Color(0xFFB3B3B3),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (removalConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { removalConfirmationOpen = false },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = Color(0xFFE50914)
                )
            },
            title = { Text("Remove from My List?") },
            text = {
                Text(
                    "Remove “${favorite.media.title}” from My List? " +
                        "You can add it again later."
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NikTvSecondaryActionButton(
                        onClick = { removalConfirmationOpen = false },
                        modifier = Modifier.weight(1f).focusRequester(cancelRemovalRequester)
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelLarge)
                    }
                    NikTvPrimaryActionButton(
                        onClick = { removalConfirmationOpen = false; remove() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Remove", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFF181818),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}

@Composable
internal fun ModernScreenTopBar(title: String, close: () -> Unit, openSearch: (() -> Unit)? = null, openSettings: (() -> Unit)? = null,
    backModifier: Modifier = Modifier) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF090909)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = close, modifier = backModifier.remoteFocusFrame(CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        openSearch?.let { IconButton(onClick = it, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.Search, "Search", tint = Color.White) } }
        openSettings?.let { IconButton(onClick = it, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) } }
    }
}
